package eu.avalanche7.paradigm.modules.discord;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.ScheduledThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import org.apache.logging.log4j.Level;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.core.LoggerContext;

import eu.avalanche7.paradigm.configs.DiscordConfigHandler;
import eu.avalanche7.paradigm.core.Services;
import eu.avalanche7.paradigm.modules.audit.AuditActionType;
import eu.avalanche7.paradigm.modules.audit.AuditResult;
import eu.avalanche7.paradigm.modules.discord.client.DiscordGatewayClient;
import eu.avalanche7.paradigm.modules.discord.client.DiscordInboundMessage;
import eu.avalanche7.paradigm.modules.discord.client.DiscordInteraction;
import eu.avalanche7.paradigm.modules.discord.client.DiscordJson;
import eu.avalanche7.paradigm.modules.discord.client.DiscordRateLimiter;
import eu.avalanche7.paradigm.modules.discord.client.DiscordRestClient;
import eu.avalanche7.paradigm.modules.discord.console.ConsoleRelayAppender;
import eu.avalanche7.paradigm.platform.Interfaces.IPlatformAdapter;
import eu.avalanche7.paradigm.utils.CommandSuggestions;
import eu.avalanche7.paradigm.utils.DebugLogger;

public final class DiscordService implements DiscordOutbox {
    private static final long WEBHOOK_RETRYABLE_BACKOFF_MILLIS = 30_000L;
    private static final long WEBHOOK_PERMISSION_BACKOFF_MILLIS = 300_000L;
    private final Services services;
    private final DiscordRelay relay;
    private final DiscordRateLimiter rateLimiter = new DiscordRateLimiter();
    private final AtomicBoolean startedNotificationSent = new AtomicBoolean();
    private final AtomicBoolean eventsSubscribed = new AtomicBoolean();

    private volatile ScheduledExecutorService executor;
    private volatile ExecutorService httpExecutor;
    private volatile ExecutorService interactionExecutor;
    private volatile DiscordRestClient rest;
    private volatile DiscordGatewayClient gateway;
    private volatile DiscordDispatcher dispatcher;

    private volatile DiscordConnectionState state = DiscordConnectionState.DISABLED;
    private volatile DiscordInboundCapability inboundCapability = DiscordInboundCapability.UNKNOWN;
    private volatile String lastError;
    private volatile String botUsername;
    private volatile String botUserId;
    private volatile String applicationId;
    private volatile long connectedSinceMs;
    private volatile ScheduledFuture<?> presenceTask;
    private volatile String webhookUnavailable;
    private volatile ConsoleRelayAppender consoleAppender;
    private volatile ScheduledFuture<?> consoleFlushTask;
    private volatile String lastReconciledGuildId;
    private volatile boolean consoleCommandRegisteredByUs;
    private volatile AutocompleteCoordinator autocompleteCoordinator;
    private final AtomicBoolean criticalFlushPending = new AtomicBoolean();
    private final Object consoleRelayLock = new Object();

    private static final String CONSOLE_COMMAND_NAME = "console";

    private final Map<String, DiscordRestClient.Webhook> webhooks = new ConcurrentHashMap<>();
    private final Map<String, Long> webhookRetryAfterMs = new ConcurrentHashMap<>();
    private final Set<String> webhookIds = ConcurrentHashMap.newKeySet();
    private final AtomicBoolean webhookPermissionWarned = new AtomicBoolean();

    public DiscordService(Services services) {
        this.services = services;
        this.relay = new DiscordRelay(services, this);
    }

    @Override
    public DiscordConfigHandler.Config config() {
        try {
            return DiscordConfigHandler.getConfig();
        } catch (IllegalStateException notInitialized) {
            return null;
        }
    }

    public DiscordRelay relay() {
        return relay;
    }

    private String token() {
        DiscordConfigHandler.Config config = config();
        String token = config != null ? config.botToken.get() : null;
        return token != null ? token.trim() : "";
    }

    @Override
    public boolean isEnabled() {
        DiscordConfigHandler.Config config = config();
        if (config == null || !Boolean.TRUE.equals(config.enabled.get())) {
            return false;
        }
        if (!DiscordSecrets.isPresent(token())) {
            return false;
        }
        return anyChannelConfigured(config);
    }

    static boolean anyChannelConfigured(DiscordConfigHandler.Config config) {
        for (DiscordDestination destination : DiscordDestination.values()) {
            if (!destination.channelId(config).isBlank()) {
                return true;
            }
        }
        return false;
    }

    @Override
    public String botUserId() {
        return botUserId;
    }

    public void subscribeToParadigmEvents() {
        if (services == null || !eventsSubscribed.compareAndSet(false, true)) {
            return;
        }
        services.getParadigmEvents().register(relay);
    }

    public synchronized void start() {
        DiscordConfigHandler.Config config = config();
        if (config == null || !isEnabled()) {
            state = DiscordConnectionState.DISABLED;
            lastError = describeWhyDisabled(config);
            return;
        }
        if (dispatcher != null && dispatcher.isRunning()) {
            return;
        }

        startedNotificationSent.set(false);
        rateLimiter.reset();
        relay.reset();
        webhooks.clear();
        webhookRetryAfterMs.clear();
        webhookPermissionWarned.set(false);
        webhookUnavailable = null;

        ScheduledThreadPoolExecutor pool = new ScheduledThreadPoolExecutor(1, runnable -> {
            Thread thread = new Thread(runnable, "paradigm-discord-gateway");
            thread.setDaemon(true);
            return thread;
        });
        pool.setRemoveOnCancelPolicy(true);
        executor = pool;

        ExecutorService http = Executors.newFixedThreadPool(2, runnable -> {
            Thread thread = new Thread(runnable, "paradigm-discord-http");
            thread.setDaemon(true);
            return thread;
        });
        httpExecutor = http;

        ExecutorService interaction = Executors.newCachedThreadPool(runnable -> {
            Thread thread = new Thread(runnable, "paradigm-discord-interaction");
            thread.setDaemon(true);
            return thread;
        });
        interactionExecutor = interaction;

        DebugLogger debugLogger = services.getDebugLogger();
        autocompleteCoordinator = new AutocompleteCoordinator(debugLogger, pool, interaction, this::respondAutocomplete);
        DiscordRestClient restClient = new DiscordRestClient(this::token, rateLimiter, debugLogger, http);
        rest = restClient;

        DiscordDispatcher queue = new DiscordDispatcher(
                (channelId, message) -> {
                    DiscordRestClient.Result result = dispatch(restClient, channelId, message);
                    return new DiscordDispatcher.Sender.Result(result.success(), result.errorMessage(), result.retryable());
                },
                destination -> {
                    DiscordConfigHandler.Config current = config();
                    return current != null ? destination.channelId(current) : "";
                },
                debugLogger,
                clampQueueSize(config));
        dispatcher = queue;
        queue.start();

        DiscordGatewayClient client = new DiscordGatewayClient(this::token, restClient, debugLogger,
                new GatewayListener(), pool);
        gateway = client;
        state = DiscordConnectionState.CONNECTING;
        lastError = null;
        client.start();

        startConsoleRelay(config, pool);
    }

    private void startConsoleRelay(DiscordConfigHandler.Config config, ScheduledExecutorService pool) {
        if (DiscordDestination.CONSOLE.channelId(config).isBlank() || !Boolean.TRUE.equals(config.notifyConsoleLog.get())) {
            return;
        }
        Level minimumLevel = Level.toLevel(config.consoleLogMinimumLevel.get(), Level.INFO);
        ConsoleRelayAppender appender = new ConsoleRelayAppender(
                "ParadigmConsoleRelay", minimumLevel, config.consoleLogIgnoredPatterns.get(),
                this::onCriticalConsoleEvent);
        appender.start();
        LoggerContext context = (LoggerContext) LogManager.getContext(false);
        context.getRootLogger().addAppender(appender);
        consoleAppender = appender;

        Integer configured = config.consoleLogFlushSeconds.get();
        long period = Math.max(1L, Math.min(configured != null ? configured : 3, 30L));
        try {
            consoleFlushTask = pool.scheduleAtFixedRate(() -> flushConsoleRelay(appender), period, period, TimeUnit.SECONDS);
        } catch (RuntimeException unavailable) {
            consoleFlushTask = null;
        }
    }

    private void flushConsoleRelay(ConsoleRelayAppender appender) {
        synchronized (consoleRelayLock) {
            try {
                for (String chunk : appender.drainChunks()) {
                    send(DiscordMessage.plain(DiscordDestination.CONSOLE, chunk, false));
                }
            } catch (RuntimeException failure) {
                services.getDebugLogger().debugLog("[Discord] Console relay flush failed: "
                        + failure.getClass().getSimpleName());
            }
        }
    }

    private void onCriticalConsoleEvent() {
        ScheduledExecutorService pool = executor;
        ConsoleRelayAppender appender = consoleAppender;
        if (pool == null || appender == null || !criticalFlushPending.compareAndSet(false, true)) {
            return;
        }
        try {
            pool.execute(() -> {
                try {
                    flushConsoleRelay(appender);
                } finally {
                    criticalFlushPending.set(false);
                }
            });
        } catch (RuntimeException rejected) {
            criticalFlushPending.set(false);
        }
    }

    private void reconcileConsoleSlashCommand(DiscordConfigHandler.Config config, DiscordRestClient restClient) {
        String guildId = nullToEmpty(config.guildId.get());
        String application = applicationId;
        boolean applicationKnown = application != null && !application.isBlank();
        boolean wantCommand = Boolean.TRUE.equals(config.allowConsoleCommands.get()) && !guildId.isBlank() && applicationKnown;

        String previousGuildId = lastReconciledGuildId;
        if (previousGuildId != null && !previousGuildId.isBlank() && !previousGuildId.equals(guildId)
                && applicationKnown && consoleCommandRegisteredByUs) {
            deleteConsoleCommandIfPresent(restClient, previousGuildId, application);
            consoleCommandRegisteredByUs = false;
        }

        if (guildId.isBlank() || !applicationKnown) {
            return;
        }

        switch (consoleCommandAction(wantCommand, consoleCommandRegisteredByUs)) {
            case UPSERT -> {
                try {
                    restClient.upsertGuildCommand(guildId, application, buildConsoleCommandPayload());
                    consoleCommandRegisteredByUs = true;
                } catch (DiscordRestClient.DiscordApiException failure) {
                    services.getDebugLogger().debugLog("[Discord] Failed to register the /console command: "
                            + failure.getClass().getSimpleName());
                }
            }
            case DELETE -> {
                deleteConsoleCommandIfPresent(restClient, guildId, application);
                consoleCommandRegisteredByUs = false;
            }
            case LEAVE -> services.getDebugLogger().debugLog("[Discord] Console commands are disabled here, but this "
                    + "instance never registered /console, so any existing registration is left alone; another "
                    + "instance sharing this bot token may own it.");
        }
        lastReconciledGuildId = guildId;
    }

    enum ConsoleCommandAction {
        UPSERT, DELETE, LEAVE
    }

    static ConsoleCommandAction consoleCommandAction(boolean wantCommand, boolean registeredByUs) {
        if (wantCommand) {
            return ConsoleCommandAction.UPSERT;
        }
        return registeredByUs ? ConsoleCommandAction.DELETE : ConsoleCommandAction.LEAVE;
    }

    private void deleteConsoleCommandIfPresent(DiscordRestClient restClient, String guildId, String application) {
        try {
            JsonArray commands = restClient.listGuildCommands(guildId, application);
            for (JsonElement element : commands) {
                if (!element.isJsonObject()) {
                    continue;
                }
                JsonObject command = element.getAsJsonObject();
                if (CONSOLE_COMMAND_NAME.equals(DiscordJson.string(command, "name"))) {
                    String id = DiscordJson.string(command, "id");
                    if (id != null && !id.isBlank()) {
                        restClient.deleteGuildCommand(guildId, application, id);
                    }
                }
            }
        } catch (DiscordRestClient.DiscordApiException failure) {
            services.getDebugLogger().debugLog("[Discord] Failed to reconcile the /console command: "
                    + failure.getClass().getSimpleName());
        }
    }

    private static String buildConsoleCommandPayload() {
        JsonObject option = new JsonObject();
        option.addProperty("type", 3);
        option.addProperty("name", "command");
        option.addProperty("description", "Minecraft command to run as console");
        option.addProperty("required", true);
        option.addProperty("autocomplete", true);

        JsonArray options = new JsonArray();
        options.add(option);

        JsonObject command = new JsonObject();
        command.addProperty("name", CONSOLE_COMMAND_NAME);
        command.addProperty("description", "Run a Minecraft server console command");
        command.add("options", options);
        return command.toString();
    }

    private void handleInteraction(DiscordInteraction interaction) {
        if (!CONSOLE_COMMAND_NAME.equals(interaction.commandName())) {
            return;
        }
        if (!relay.ownsConsoleChannel(interaction.channelId())) {
            services.getDebugLogger().debugLog("[Discord] Ignoring a /console interaction from channel "
                    + interaction.channelId() + "; this instance does not own that channel, and responding "
                    + "would consume the interaction token the owning instance needs.");
            return;
        }
        boolean authorized = relay.authorizeConsoleCommand(interaction.channelId(), interaction.bot(), false, false);
        services.getDebugLogger().debugLog("[Discord] Interaction type=" + interaction.type()
                + " channel=" + interaction.channelId() + " authorized=" + authorized
                + " inputLen=" + interaction.optionValue().length());

        if (interaction.isAutocomplete()) {
            IPlatformAdapter platform = services.getPlatformAdapter();
            AutocompleteCoordinator coordinator = autocompleteCoordinator;
            if (!authorized || platform == null || coordinator == null) {
                runOnInteractionExecutor(() -> respondAutocomplete(interaction, List.of()));
                return;
            }
            coordinator.handle(interaction, platform::executeOnServerThread,
                    () -> CommandSuggestions.suggestAsync(platform.getCommandDispatcher(), platform.getConsoleCommandSource(),
                            interaction.optionValue(), 25, services.getDebugLogger()));
            return;
        }

        if (interaction.isSubmit()) {
            if (!authorized) {
                runOnInteractionExecutor(() -> respondEphemeral(interaction, "This command isn't available here."));
                return;
            }
            runOnInteractionExecutor(() -> respondEphemeral(interaction, "Command received."));
            relay.dispatchConsoleCommand(interaction.authorId(), interaction.authorDisplayName(), interaction.optionValue());
        }
    }

    private void runOnInteractionExecutor(Runnable task) {
        ExecutorService interaction = interactionExecutor;
        if (interaction != null) {
            interaction.execute(task);
        } else {
            task.run();
        }
    }

    private void runOnHttpExecutor(Runnable task) {
        ExecutorService http = httpExecutor;
        if (http != null) {
            http.execute(task);
        } else {
            task.run();
        }
    }

    private void respondAutocomplete(DiscordInteraction interaction, List<String> choices) {
        JsonArray choiceArray = new JsonArray();
        for (String choice : choices) {
            JsonObject entry = new JsonObject();
            entry.addProperty("name", choice);
            entry.addProperty("value", choice);
            choiceArray.add(entry);
        }
        JsonObject data = new JsonObject();
        data.add("choices", choiceArray);
        JsonObject payload = new JsonObject();
        payload.addProperty("type", 8);
        payload.add("data", data);

        sendInteractionResponse(interaction, payload.toString());
    }

    private void respondEphemeral(DiscordInteraction interaction, String content) {
        JsonObject data = new JsonObject();
        data.addProperty("content", content);
        data.addProperty("flags", 64);
        JsonObject payload = new JsonObject();
        payload.addProperty("type", 4);
        payload.add("data", data);

        sendInteractionResponse(interaction, payload.toString());
    }

    private void sendInteractionResponse(DiscordInteraction interaction, String payloadJson) {
        DiscordRestClient restClient = rest;
        if (restClient == null) {
            services.getDebugLogger().debugLog("[Discord] Cannot respond to interaction " + interaction.id()
                    + ": no REST client available.");
            return;
        }
        long startNanos = System.nanoTime();
        DiscordRestClient.Result result = restClient.respondToInteraction(interaction.id(), interaction.token(),
                payloadJson);
        long restMs = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - startNanos);
        services.getDebugLogger().debugLog("[Discord] Interaction response for " + interaction.id()
                + " success=" + result.success() + " status=" + result.status() + " restMs=" + restMs
                + (result.success() ? "" : " error=" + result.errorMessage()));
    }

    private void drainAndStopConsoleRelay() {
        ScheduledFuture<?> flushTask = consoleFlushTask;
        consoleFlushTask = null;
        if (flushTask != null) {
            flushTask.cancel(false);
        }
        ConsoleRelayAppender appender = consoleAppender;
        consoleAppender = null;
        if (appender != null) {
            LoggerContext context = (LoggerContext) LogManager.getContext(false);
            context.getRootLogger().removeAppender(appender);
            flushConsoleRelay(appender);
            appender.stop();
        }
    }

    public synchronized void shutdown() {
        stop(true);
    }

    private void stop(boolean notifyServerStopping) {
        DiscordDispatcher queue = dispatcher;
        DiscordGatewayClient client = gateway;
        ScheduledExecutorService pool = executor;
        ExecutorService http = httpExecutor;
        ExecutorService interaction = interactionExecutor;

        if (notifyServerStopping && queue != null && queue.isAccepting()) {
            relay.notifyServerStopping();
        }
        drainAndStopConsoleRelay();
        if (queue != null && queue.isAccepting()) {
            queue.closeAcceptance();
            queue.flush(flushBudgetMillis());
        }

        cancelPresence();
        if (client != null) {
            client.stop();
        }
        if (queue != null) {
            queue.stop();
        }
        if (pool != null) {
            pool.shutdownNow();
            try {
                pool.awaitTermination(1, TimeUnit.SECONDS);
            } catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt();
            }
        }
        if (http != null) {
            http.shutdownNow();
            try {
                http.awaitTermination(1, TimeUnit.SECONDS);
            } catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt();
            }
        }
        if (interaction != null) {
            interaction.shutdownNow();
            try {
                interaction.awaitTermination(1, TimeUnit.SECONDS);
            } catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt();
            }
        }

        AutocompleteCoordinator coordinator = autocompleteCoordinator;
        if (coordinator != null) {
            coordinator.reset();
        }
        autocompleteCoordinator = null;

        gateway = null;
        dispatcher = null;
        rest = null;
        executor = null;
        httpExecutor = null;
        interactionExecutor = null;
        connectedSinceMs = 0L;
        botUserId = null;
        botUsername = null;
        applicationId = null;
        webhooks.clear();
        webhookRetryAfterMs.clear();
        webhookIds.clear();
        state = DiscordConnectionState.DISABLED;
    }

    public synchronized void reload() {
        stop(false);
        start();
    }

    public synchronized void reconnect() {
        DiscordGatewayClient client = gateway;
        if (client == null) {
            start();
            return;
        }
        client.reconnectNow();
    }

    public void markServerReady() {
        if (!startedNotificationSent.compareAndSet(false, true)) {
            return;
        }
        relay.notifyServerStarted();
    }

    private long flushBudgetMillis() {
        DiscordConfigHandler.Config config = config();
        Integer configured = config != null ? config.shutdownFlushMillis.get() : null;
        long value = configured != null ? configured : 3000L;
        return Math.max(0L, Math.min(value, 10_000L));
    }

    static int clampQueueSize(DiscordConfigHandler.Config config) {
        Integer configured = config != null ? config.outboundQueueSize.get() : null;
        int value = configured != null ? configured : 500;
        return Math.max(16, Math.min(value, 10_000));
    }

    @Override
    public boolean send(DiscordMessage message) {
        DiscordDispatcher queue = dispatcher;
        if (queue == null || message == null || message.isEmpty()) {
            return false;
        }
        return queue.enqueue(message);
    }

    @Override
    public boolean sendNotification(String content, int colorRgb, String title) {
        return sendDecorated(DiscordDestination.NOTIFICATIONS, content, colorRgb, title, null);
    }

    @Override
    public boolean sendServer(String content, int colorRgb, String title) {
        return sendDecorated(DiscordDestination.SERVER, content, colorRgb, title, null);
    }

    @Override
    public boolean sendModeration(String content, int colorRgb, String title, String dedupeKey) {
        return sendDecorated(DiscordDestination.MODERATION, content, colorRgb, title, dedupeKey);
    }

    private boolean sendDecorated(DiscordDestination destination, String content, int colorRgb,
                                  String title, String dedupeKey) {
        if (content == null || content.isBlank()) {
            return false;
        }
        DiscordConfigHandler.Config config = config();
        boolean embeds = config != null && Boolean.TRUE.equals(config.useEmbeds.get());
        if (embeds) {
            return send(new DiscordMessage(destination, "",
                    new DiscordEmbed(title, content, colorRgb, null), false, dedupeKey));
        }
        return send(new DiscordMessage(destination, content, null, false, dedupeKey));
    }

    private DiscordRestClient.Result dispatch(DiscordRestClient restClient, String channelId, DiscordMessage message) {
        String payload = DiscordDispatcher.buildPayload(message);
        if (!message.hasIdentity()) {
            return restClient.postMessage(channelId, payload);
        }

        DiscordRestClient.Webhook webhook = resolveWebhook(restClient, channelId);
        if (webhook == null) {
            return restClient.postMessage(channelId, DiscordDispatcher.buildPayload(withoutIdentity(message)));
        }

        DiscordRestClient.Result result = restClient.executeWebhook(webhook.id(), webhook.token(), payload);
        if (result.status() == 404) {
            webhooks.remove(channelId);
            webhookRetryAfterMs.remove(channelId);
            DiscordRestClient.Webhook replacement = resolveWebhook(restClient, channelId);
            if (replacement != null) {
                return restClient.executeWebhook(replacement.id(), replacement.token(), payload);
            }
            return restClient.postMessage(channelId, DiscordDispatcher.buildPayload(withoutIdentity(message)));
        }
        return result;
    }

    private static DiscordMessage withoutIdentity(DiscordMessage message) {
        return new DiscordMessage(message.destination(), message.content(), message.embed(),
                message.allowMentions(), message.dedupeKey(), null);
    }

    private DiscordRestClient.Webhook resolveWebhook(DiscordRestClient restClient, String channelId) {
        DiscordRestClient.Webhook cached = webhooks.get(channelId);
        if (cached != null) {
            return cached;
        }
        Long retryAfter = webhookRetryAfterMs.get(channelId);
        long now = System.currentTimeMillis();
        if (retryAfter != null && retryAfter > now) {
            return null;
        }
        if (retryAfter != null) {
            webhookRetryAfterMs.remove(channelId, retryAfter);
        }
        String application = applicationId;
        if (application == null || application.isBlank()) {
            return null;
        }
        DiscordConfigHandler.Config config = config();
        String name = config != null ? nullToEmpty(config.webhookName.get()) : "";
        if (name.isBlank()) {
            name = "Paradigm";
        }

        try {
            for (DiscordRestClient.Webhook candidate : restClient.listWebhooks(channelId)) {
                if (application.equals(candidate.applicationId())
                        && name.equals(candidate.name())
                        && candidate.token() != null
                        && !candidate.token().isBlank()) {
                    webhooks.put(channelId, candidate);
                    webhookRetryAfterMs.remove(channelId);
                    webhookIds.add(candidate.id());
                    return candidate;
                }
            }
            DiscordRestClient.Webhook created = restClient.createWebhook(channelId, name);
            if (created.token() == null || created.token().isBlank()) {
                return null;
            }
            webhooks.put(channelId, created);
            webhookRetryAfterMs.remove(channelId);
            webhookIds.add(created.id());
            return created;
        } catch (DiscordRestClient.DiscordApiException failure) {
            if (failure.status() == 403 && webhookPermissionWarned.compareAndSet(false, true)) {
                services.getLogger().warn("[Paradigm] Discord: the bot cannot manage webhooks in the chat channel, "
                        + "so player chat is relayed under the bot identity. Grant the Manage Webhooks permission "
                        + "to use webhook chat mode.");
            }
            webhookUnavailable = failure.getMessage();
            long backoff = failure.status() == 403
                    ? WEBHOOK_PERMISSION_BACKOFF_MILLIS
                    : WEBHOOK_RETRYABLE_BACKOFF_MILLIS;
            webhookRetryAfterMs.put(channelId, System.currentTimeMillis() + backoff);
            return null;
        }
    }

    @Override
    public boolean isOwnWebhook(String webhookId) {
        return webhookId != null && webhookIds.contains(webhookId);
    }

    @Override
    public void auditConsoleCommand(String actorId, String actorName, String command) {
        services.getAuditService().discord(actorId, actorName, AuditActionType.DISCORD_CONSOLE_COMMAND,
                AuditResult.SUCCESS, "Discord console command executed", Map.of("command", command));
    }

    private void updatePresence() {
        DiscordConfigHandler.Config config = config();
        DiscordGatewayClient client = gateway;
        if (config == null || client == null || !Boolean.TRUE.equals(config.presenceEnabled.get())) {
            return;
        }
        IPlatformAdapter platform = services.getPlatformAdapter();
        int online;
        int max;
        try {
            online = platform != null && platform.getOnlinePlayers() != null ? platform.getOnlinePlayers().size() : 0;
            max = platform != null ? platform.getMaxPlayers() : 0;
        } catch (RuntimeException | AbstractMethodError unavailable) {
            return;
        }

        String template = nullToEmpty(config.presenceFormat.get());
        if (online == 0) {
            String empty = nullToEmpty(config.presenceFormatEmpty.get());
            if (!empty.isBlank()) {
                template = empty;
            }
        } else if (online == 1) {
            String singular = nullToEmpty(config.presenceFormatSingular.get());
            if (!singular.isBlank()) {
                template = singular;
            }
        }
        if (template.isBlank()) {
            return;
        }

        String text = DiscordTemplates.apply(template, Map.of(
                "online", Integer.toString(online),
                "max", Integer.toString(max)));
        client.sendPresence(DiscordSanitizer.truncate(text, 128), config.presenceType.get(), "online");
    }

    private void schedulePresence() {
        ScheduledExecutorService pool = executor;
        DiscordConfigHandler.Config config = config();
        if (pool == null || config == null || !Boolean.TRUE.equals(config.presenceEnabled.get())) {
            return;
        }
        cancelPresence();
        Integer configured = config.presenceUpdateSeconds.get();
        long period = Math.max(15L, Math.min(configured != null ? configured : 60, 3600L));
        try {
            presenceTask = pool.scheduleAtFixedRate(() -> {
                try {
                    updatePresence();
                } catch (RuntimeException failure) {
                    services.getDebugLogger().debugLog("[Discord] Presence update failed: "
                            + failure.getClass().getSimpleName());
                }
            }, 2L, period, TimeUnit.SECONDS);
        } catch (RuntimeException unavailable) {
            presenceTask = null;
        }
    }

    private synchronized void cancelPresence() {
        ScheduledFuture<?> task = presenceTask;
        presenceTask = null;
        if (task != null) {
            task.cancel(false);
        }
    }

    public boolean sendTest(DiscordDestination destination) {
        DiscordDestination target = destination != null ? destination : DiscordDestination.CHAT;
        String message = "Paradigm test message for the " + target.name().toLowerCase(java.util.Locale.ROOT)
                + " destination.";
        DiscordConfigHandler.Config config = config();
        boolean ansi = config != null && Boolean.TRUE.equals(config.useAnsiColors.get());
        return send(DiscordMessage.plain(target, ansi ? "```ansi\n\u001b[32m" + message + "\u001b[0m\n```" : message,
                false));
    }

    public DiscordConnectionStatus status() {
        DiscordConfigHandler.Config config = config();
        DiscordDispatcher queue = dispatcher;
        DiscordGatewayClient client = gateway;

        boolean enabled = isEnabled();
        boolean tokenConfigured = DiscordSecrets.isPresent(token());
        boolean toDiscord = config != null && Boolean.TRUE.equals(config.minecraftToDiscordEnabled.get());
        boolean toMinecraft = config != null && Boolean.TRUE.equals(config.discordToMinecraftEnabled.get());

        DiscordConnectionState currentState = enabled ? state : DiscordConnectionState.DISABLED;
        DiscordInboundCapability capability = client != null ? client.inboundCapability() : inboundCapability;

        DiscordConnectionStatus status = new DiscordConnectionStatus(
                enabled,
                currentState,
                capability,
                botUsername,
                tokenConfigured,
                config != null && !DiscordDestination.CHAT.channelId(config).isBlank(),
                config != null && !nullToEmpty(config.moderationChannelId.get()).isBlank(),
                config != null && !nullToEmpty(config.notificationChannelId.get()).isBlank(),
                toDiscord,
                toMinecraft,
                connectedSinceMs,
                client != null ? client.lastHeartbeatAckMillis() : 0L,
                client != null && client.isHeartbeatOutstanding(),
                queue != null ? queue.queueDepth() : 0,
                queue != null ? queue.sentCount() : 0L,
                queue != null ? queue.droppedCount() : 0L,
                queue != null ? queue.failedCount() : 0L,
                lastError,
                List.of());

        List<String> warnings = buildWarnings(config, status);
        String webhookProblem = webhookUnavailable;
        if (webhookProblem != null && config != null && Boolean.TRUE.equals(config.webhookEnabled.get())) {
            warnings.add("Webhook chat mode is enabled but no webhook is available, so chat is relayed under the "
                    + "bot identity: " + webhookProblem);
        }
        return status.withWarnings(warnings);
    }

    static List<String> buildWarnings(DiscordConfigHandler.Config config, DiscordConnectionStatus status) {
        List<String> warnings = new ArrayList<>();
        if (config == null) {
            return warnings;
        }
        if (Boolean.TRUE.equals(config.enabled.get()) && !status.tokenConfigured()) {
            warnings.add("No bot token is configured, so the Discord integration cannot connect.");
        }
        if (Boolean.TRUE.equals(config.enabled.get()) && !status.chatChannelConfigured()) {
            warnings.add("No chat channel ID is configured; chat relay in both directions is inactive.");
        }
        if (status.inboundRelayBroken()) {
            warnings.add("Discord to Minecraft chat is enabled but the bot cannot read message content. "
                    + "Enable the MESSAGE CONTENT intent for this bot in the Discord developer portal, "
                    + "then run /paradigm discord reconnect.");
        }
        if (status.state() == DiscordConnectionState.FAILED) {
            warnings.add("The Discord connection failed and will not retry until the configuration changes.");
        }
        if (status.heartbeatOutstanding() && status.state() == DiscordConnectionState.CONNECTED) {
            warnings.add("Discord has not acknowledged the last heartbeat; the connection may be recovering.");
        }
        if (status.droppedCount() > 0) {
            warnings.add(status.droppedCount() + " outbound Discord message(s) were dropped because the queue was full.");
        }
        return warnings;
    }

    private String describeWhyDisabled(DiscordConfigHandler.Config config) {
        if (config == null) {
            return "Discord configuration is not loaded.";
        }
        if (!Boolean.TRUE.equals(config.enabled.get())) {
            return null;
        }
        if (!DiscordSecrets.isPresent(token())) {
            return "No Discord bot token is configured.";
        }
        if (!anyChannelConfigured(config)) {
            return "No Discord channel ID is configured.";
        }
        return null;
    }

    private static String nullToEmpty(String value) {
        return value != null ? value.trim() : "";
    }

    private final class GatewayListener implements DiscordGatewayClient.Listener {
        @Override
        public void onReady(String userId, String username, String application) {
            botUserId = userId;
            botUsername = username;
            applicationId = application;
            connectedSinceMs = System.currentTimeMillis();
            schedulePresence();

            DiscordConfigHandler.Config cfg = config();
            DiscordRestClient restClient = rest;
            if (cfg != null && restClient != null) {
                runOnHttpExecutor(() -> reconcileConsoleSlashCommand(cfg, restClient));
            }
        }

        @Override
        public void onMessage(DiscordInboundMessage message) {
            try {
                DiscordConfigHandler.Config config = config();
                String consoleChannel = DiscordDestination.CONSOLE.channelId(config);
                if (!consoleChannel.isBlank() && consoleChannel.equals(message.channelId())) {
                    relay.handleConsoleCommand(message);
                    return;
                }
                relay.handleInbound(message);
            } catch (RuntimeException failure) {
                services.getDebugLogger().debugLog("[Discord] Inbound relay failed: "
                        + failure.getClass().getSimpleName());
            }
        }

        @Override
        public void onMessageUpdated(DiscordInboundMessage message) {
            try {
                relay.handleInboundEdit(message);
            } catch (RuntimeException failure) {
                services.getDebugLogger().debugLog("[Discord] Inbound edit relay failed: "
                        + failure.getClass().getSimpleName());
            }
        }

        @Override
        public void onMessageDeleted(String messageId, String channelId, String guildId) {
            try {
                relay.handleInboundDelete(messageId, channelId);
            } catch (RuntimeException failure) {
                services.getDebugLogger().debugLog("[Discord] Inbound delete relay failed: "
                        + failure.getClass().getSimpleName());
            }
        }

        @Override
        public void onInteraction(DiscordInteraction interaction) {
            try {
                handleInteraction(interaction);
            } catch (RuntimeException failure) {
                services.getDebugLogger().debugLog("[Discord] Interaction handling failed: "
                        + failure.getClass().getSimpleName());
            }
        }

        @Override
        public void onStateChanged(DiscordConnectionState next, String detail) {
            state = next;
            if (detail != null && !detail.isBlank()) {
                lastError = DiscordSecrets.redact(detail, token());
            } else if (next == DiscordConnectionState.CONNECTED) {
                lastError = null;
            }
            if (next != DiscordConnectionState.CONNECTED) {
                connectedSinceMs = 0L;
                cancelPresence();
            }
        }

        @Override
        public void onInboundCapability(DiscordInboundCapability capability) {
            inboundCapability = capability;
            if (capability.blocksInboundRelay()) {
                services.getLogger().warn("[Paradigm] Discord: the bot cannot read message content. "
                        + "Enable the MESSAGE CONTENT privileged intent in the Discord developer portal "
                        + "to use Discord to Minecraft chat.");
            }
        }
    }
}
