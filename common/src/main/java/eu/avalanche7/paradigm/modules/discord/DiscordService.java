package eu.avalanche7.paradigm.modules.discord;

import java.io.IOException;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.ScheduledThreadPoolExecutor;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.BooleanSupplier;

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
import eu.avalanche7.paradigm.modules.tickets.Ticket;
import eu.avalanche7.paradigm.modules.tickets.TicketActor;
import eu.avalanche7.paradigm.platform.Interfaces.IPlatformAdapter;
import eu.avalanche7.paradigm.storage.StorageProviderType;
import eu.avalanche7.paradigm.storage.StorageService;
import eu.avalanche7.paradigm.utils.CommandSuggestions;
import eu.avalanche7.paradigm.utils.DebugLogger;
import eu.avalanche7.paradigm.utils.ServerThreadCalls;

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
    private volatile ExecutorService ticketReplyExecutor;
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
    private final AtomicBoolean presenceUpdatePending = new AtomicBoolean();
    private final Object consoleRelayLock = new Object();

    private static final String CONSOLE_COMMAND_NAME = "console";
    static final String LINK_COMMAND_NAME = "link";
    private final Object linkRegistrationLock = new Object();
    private volatile LinkGateway linkGateway;
    private volatile String linkRegistrationWarning;
    private boolean linkRegistrationVerified;

    private record LinkGateway(String token, String application) {}

    private volatile TicketRoomRun ticketRoomRun;
    private ScheduledFuture<?> ticketRoomTask;
    private final Object ticketRoomLock = new Object();
    private volatile String ticketRoomWarning;

    private record TicketRoomRun(DiscordTicketRooms.Settings settings, long since, boolean advanceFloor) {}

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
        return anyChannelConfigured(config) || linkingSettings(config).owner();
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

    record LinkingSettings(String guild, boolean owner) {
        private static final LinkingSettings DISABLED = new LinkingSettings("", false);

        boolean accepts(DiscordInteraction interaction) {
            return owner && guild.equals(interaction.guildId()) && !interaction.bot()
                    && DiscordConfigHandler.isGuildId(interaction.authorId())
                    && (interaction.isLinkModal()
                            || (interaction.isSubmit() && LINK_COMMAND_NAME.equals(interaction.commandName())));
        }
    }

    private LinkingSettings linkingSettings(DiscordConfigHandler.Config config) {
        StorageService storage = services == null ? null : services.getStorageService();
        if (storage == null) {
            return LinkingSettings.DISABLED;
        }
        return linkingSettings(config, StorageProviderType.parse(storage.config().provider), storage.context().serverId());
    }

    static LinkingSettings linkingSettings(DiscordConfigHandler.Config config, StorageProviderType provider, String serverId) {
        if (!linkingEnabled(config)) {
            return LinkingSettings.DISABLED;
        }
        String guild = nullToEmpty(config.guildId.get());
        if (!DiscordConfigHandler.isGuildId(guild)) {
            return LinkingSettings.DISABLED;
        }
        String owner = configuredLinkOwner(config, provider, serverId);
        return owner.isEmpty() ? LinkingSettings.DISABLED : new LinkingSettings(guild, owner.equals(serverId));
    }

    private static boolean linkingEnabled(DiscordConfigHandler.Config config) {
        return config != null && Boolean.TRUE.equals(config.enabled.get()) && Boolean.TRUE.equals(config.linkingEnabled.get());
    }

    private static String configuredLinkOwner(DiscordConfigHandler.Config config, StorageProviderType provider, String serverId) {
        String owner;
        try {
            owner = DiscordConfigHandler.normalizeLinkingOwner(config.linkingOwnerServerId.get());
        } catch (IllegalArgumentException invalidOwner) {
            return "";
        }
        if (provider == StorageProviderType.SQLITE && (owner.isEmpty() || owner.equals(serverId))) {
            return serverId;
        }
        if (provider == StorageProviderType.MYSQL && !"default".equals(owner)) {
            return owner;
        }
        return "";
    }

    String linkingGuild() {
        return linkingSettings(config()).guild();
    }

    private ConnectionSettings appliedConnectionSettings;

    private record ConnectionSettings(boolean enabled, String token, String guildId, boolean consoleCommands,
            int queueSize, boolean consoleRelay, String consoleChannel, String consoleLevel,
            List<String> ignoredLogs, Integer consoleFlush, Boolean presenceEnabled, Integer presenceInterval) {
    }

    private ConnectionSettings connectionSettings(DiscordConfigHandler.Config config) {
        boolean consoleRelay = Boolean.TRUE.equals(config.notifyConsoleLog.get())
                && !DiscordDestination.CONSOLE.channelId(config).isBlank();
        return new ConnectionSettings(isEnabled(), token(), config.guildId.get(), Boolean.TRUE.equals(config.allowConsoleCommands.get()),
                clampQueueSize(config), consoleRelay, consoleRelay ? DiscordDestination.CONSOLE.channelId(config) : "",
                config.consoleLogMinimumLevel.get(), config.consoleLogIgnoredPatterns.get(), config.consoleLogFlushSeconds.get(),
                config.presenceEnabled.get(), config.presenceUpdateSeconds.get());
    }

    public synchronized void applyConfiguration() {
        if (services.isStopping()) throw new IllegalStateException("Discord is stopping.");
        DiscordConfigHandler.Config config = config();
        if (config == null) throw new IllegalStateException("Discord configuration is unavailable.");
        ConnectionSettings desired = connectionSettings(config);
        if (!desired.equals(appliedConnectionSettings)) {
            stop(false);
            start();
        } else {
            if (gateway != null) {
                updatePresence();
            }
            scheduleLinkRegistration();
            configureTicketRooms();
        }
    }

    public synchronized void start() {
        DiscordConfigHandler.Config config = config();
        scheduleLinkRegistration();
        configureTicketRooms();
        if (config == null || !isEnabled()) {
            state = DiscordConnectionState.DISABLED;
            lastError = describeWhyDisabled(config);
            appliedConnectionSettings = config == null ? null : connectionSettings(config);
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
        ticketReplyExecutor = new ThreadPoolExecutor(1, 1, 0, TimeUnit.MILLISECONDS, new ArrayBlockingQueue<>(256), runnable -> {
            Thread thread = new Thread(runnable, "paradigm-discord-ticket-replies");
            thread.setDaemon(true);
            return thread;
        });

        String connectionToken = token();
        DebugLogger debugLogger = services.getDebugLogger();
        autocompleteCoordinator = new AutocompleteCoordinator(debugLogger, pool, interaction, this::respondAutocomplete);
        DiscordRestClient restClient = new DiscordRestClient(() -> connectionToken, rateLimiter, debugLogger, http);
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

        DiscordGatewayClient client = new DiscordGatewayClient(() -> connectionToken, restClient, debugLogger,
                new GatewayListener(connectionToken), pool);
        gateway = client;
        state = DiscordConnectionState.CONNECTING;
        lastError = null;
        client.start();

        startConsoleRelay(config, pool);
        appliedConnectionSettings = connectionSettings(config);
    }

    private void startConsoleRelay(DiscordConfigHandler.Config config, ScheduledExecutorService pool) {
        if (DiscordDestination.CONSOLE.channelId(config).isBlank() || !Boolean.TRUE.equals(config.notifyConsoleLog.get())) {
            return;
        }
        Level minimumLevel = Level.toLevel(config.consoleLogMinimumLevel.get(), Level.INFO);
        ConsoleRelayAppender appender = new ConsoleRelayAppender(
                "ParadigmConsoleRelay", minimumLevel, config.consoleLogIgnoredPatterns.get(),
                this::onCriticalConsoleEvent, services.getPlatformAdapter()::getLogEventTime);
        appender.start();
        LoggerContext context = (LoggerContext) LogManager.getContext(false);
        context.getLogger("").addAppender(appender);
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

    private boolean isConfiguredLinkOwner(DiscordConfigHandler.Config config, StorageService storage) {
        return config != null && storage != null && storage.context().serverId().equals(
                configuredLinkOwner(config, StorageProviderType.parse(storage.config().provider), storage.context().serverId()));
    }

    private void scheduleLinkRegistration() {
        StorageService storage = services == null ? null : services.getStorageService();
        if (!isConfiguredLinkOwner(config(), storage) || services.getTaskScheduler() == null || services.isStopping()) {
            return;
        }
        services.getTaskScheduler().scheduleRaw(() -> {
            synchronized (linkRegistrationLock) {
                reconcileLinkRegistration(storage);
            }
        }, 0, TimeUnit.MILLISECONDS);
    }

    private void reconcileLinkRegistration(StorageService storage) {
        DiscordConfigHandler.Config config = config();
        if (services.isStopping() || !isConfiguredLinkOwner(config, storage)) {
            return;
        }
        boolean remove = !linkingEnabled(config);
        LinkingSettings settings = linkingSettings(config);
        LinkGateway ready = linkGateway;
        String configuredToken = token();
        if (!remove && (!settings.owner() || ready == null || !ready.token().equals(configuredToken)
                || !DiscordConfigHandler.isGuildId(ready.application()))) {
            return;
        }
        var owner = new DiscordLinkRegistration.Owner(storage.context().networkId(), storage.context().serverId());
        var path = services.getPlatformAdapter().getConfig().resolveConfigPath("paradigm/data/discord-link-command.json");
        if (remove && !linkRegistrationVerified && Files.notExists(path)) {
            return;
        }
        var registration = new DiscordLinkRegistration(path);
        var client = new DiscordRestClient(() -> configuredToken, rateLimiter, services.getDebugLogger(), null);
        try {
            if (remove) {
                BooleanSupplier stillOwner = () -> !services.isStopping() && !linkingEnabled(config())
                        && isConfiguredLinkOwner(config(), storage) && configuredToken.equals(token());
                if (registration.remove(client, owner, stillOwner)) {
                    linkRegistrationVerified = false;
                    linkRegistrationWarning = null;
                } else if (linkRegistrationVerified && stillOwner.getAsBoolean()) {
                    reportLinkRegistrationFailure(true, "The saved /link registration receipt is missing");
                }
            } else if (registration.register(client, owner, settings.guild(), ready.application(), () -> !services.isStopping()
                    && ready == linkGateway && settings.equals(linkingSettings(config())) && configuredToken.equals(token()))) {
                linkRegistrationVerified = true;
                linkRegistrationWarning = null;
            }
        } catch (IOException | DiscordRestClient.DiscordApiException failure) {
            String detail = failure instanceof DiscordRestClient.DiscordApiException api
                    ? (api.status() > 0 ? "Discord HTTP " + api.status() : "Discord did not confirm the request")
                    : failure.getMessage();
            reportLinkRegistrationFailure(remove, detail);
        }
    }

    private void reportLinkRegistrationFailure(boolean remove, String detail) {
        String warning = "Could not confirm /link " + (remove ? "removal" : "registration")
                + ". " + detail + ". See the README for manual recovery.";
        if (!warning.equals(linkRegistrationWarning) && services.getLogger() != null) {
            services.getLogger().warn("[Discord] {}", warning);
        }
        linkRegistrationWarning = warning;
    }

    private DiscordTicketRooms.Settings ticketRoomSettings() {
        DiscordConfigHandler.Config config = config();
        LinkingSettings linking = linkingSettings(config);
        if (!linking.owner()) {
            return null;
        }
        DiscordConfigHandler.validateTicketRooms(config);
        return new DiscordTicketRooms.Settings(linking.guild(), config.ticketCategoryId.get(),
                config.ticketStaffRoleIds.get().stream().distinct().sorted().toList(), config.ticketChannelNameTemplate.get(),
                Boolean.TRUE.equals(config.ticketRoomsEnabled.get()));
    }

    private void configureTicketRooms() {
        if (services == null || services.getTaskScheduler() == null) {
            return;
        }
        DiscordTicketRooms.Settings settings;
        try {
            settings = ticketRoomSettings();
        } catch (IllegalArgumentException invalid) {
            settings = null;
            warnTicketRoom(invalid.getMessage());
        }
        if (settings == null) {
            if (ticketRoomTask != null) {
                ticketRoomTask.cancel(false);
                ticketRoomTask = null;
            }
            ticketRoomRun = null;
            return;
        }
        TicketRoomRun previous = ticketRoomRun;
        if (previous == null || !previous.settings().equals(settings)) {
            boolean sameGuild = previous != null && previous.settings().guild().equals(settings.guild());
            boolean newlyEnabled = sameGuild && !previous.settings().admits() && settings.admits();
            long since = sameGuild && !newlyEnabled ? previous.since() : System.currentTimeMillis();
            boolean advance = newlyEnabled || (sameGuild && previous.advanceFloor());
            ticketRoomRun = new TicketRoomRun(settings, since, advance);
        }
        if (ticketRoomTask == null || ticketRoomTask.isDone()) {
            ticketRoomTask = services.getTaskScheduler().scheduleAtFixedRateRaw(this::sweepTicketRooms, 0, 4, TimeUnit.SECONDS);
        }
    }

    private void sweepTicketRooms() {
        synchronized (ticketRoomLock) {
            TicketRoomRun run = ticketRoomRun;
            if (run == null || services.isStopping()) {
                return;
            }
            StorageService storage = services.getStorageService();
            if (!storage.isSqlActive()) {
                if (run.settings().admits()) {
                    warnTicketRoom("Discord ticket rooms require active SQL storage.");
                }
                return;
            }
            try {
                DiscordTicketRoomStore store = storage.discordTicketRooms();
                if (run.settings().admits()) {
                    if (run.advanceFloor()) {
                        store.advanceFloor(run.settings().guild(), run.since());
                    }
                } else if (!store.hasState(run.settings().guild())) {
                    return;
                }
                DiscordRestClient client = rest;
                String bot = botUserId;
                if (client == null || !DiscordConfigHandler.isGuildId(bot)) {
                    if (run.settings().admits()) {
                        store.discover(run.settings().guild(), run.since());
                    } else {
                        store.pause(run.settings().guild());
                    }
                    return;
                }
                BooleanSupplier current = () -> !services.isStopping() && ticketRoomRun == run && rest == client
                        && linkingSettings(config()).owner() && run.settings().equals(ticketRoomSettings());
                new DiscordTicketRooms(store, storage.discordAccountLinks(), client, ticket ->
                        storage.servers().getServer(ticket.originServerId())
                                .filter(identity -> ticket.networkId().equals(identity.networkId()))
                                .map(identity -> identity.serverName())
                                .orElse(ticket.originServerId() == null ? "unknown" : ticket.originServerId()),
                        this::warnTicketRoom).sweep(run.settings(), bot, run.since(), current);
                new DiscordTicketReplies(store, client, this::warnTicketRoom)
                        .deliver(linkingSettings(config()), run.settings().rolesKey(bot),
                                () -> current.getAsBoolean() && services.getTicketService().enabled());
            } catch (RuntimeException failure) {
                warnTicketRoom("Discord ticket-room reconciliation failed (" + failure.getClass().getSimpleName() + ").");
            }
        }
    }

    private void warnTicketRoom(String warning) {
        if (!warning.equals(ticketRoomWarning) && services.getLogger() != null) {
            services.getLogger().warn("[Discord] {}", warning);
        }
        ticketRoomWarning = warning;
    }

    private TicketActor discordTicketActor(UUID uuid, Ticket ticket) {
        String playerId = uuid.toString();
        var permissions = services.getPermissionsHandler();
        String name = services.getStorageService().players().getProfile(playerId)
                .map(profile -> profile.name()).filter(value -> !value.isBlank())
                .orElse(ticket.isCreatedBy(playerId) && ticket.creatorName() != null ? ticket.creatorName() : playerId);
        return TicketActor.administrative(playerId, name, permission -> {
            Boolean online = ServerThreadCalls.supply(services, () -> {
                var player = services.getPlatformAdapter().getPlayerByUuid(playerId);
                return player == null ? null : permissions.hasPermission(player, permission);
            }).orTimeout(4, TimeUnit.SECONDS).join();
            return online != null ? online : permissions.hasOfflinePermission(uuid, permission);
        });
    }

    static String linkModalPayload() {
        JsonObject input = new JsonObject();
        input.addProperty("type", 4);
        input.addProperty("custom_id", DiscordInteraction.LINK_CODE_FIELD);
        input.addProperty("style", 1);
        input.addProperty("required", true);
        input.addProperty("min_length", DiscordAccountLinks.CODE_LENGTH);
        input.addProperty("max_length", DiscordAccountLinks.CODE_LENGTH);
        JsonObject label = new JsonObject();
        label.addProperty("type", 18);
        label.addProperty("label", "Minecraft link code");
        label.add("component", input);
        JsonArray components = new JsonArray();
        components.add(label);
        JsonObject data = new JsonObject();
        data.addProperty("custom_id", DiscordInteraction.LINK_MODAL_ID);
        data.addProperty("title", "Link your Minecraft account");
        data.add("components", components);
        JsonObject response = new JsonObject();
        response.addProperty("type", 9);
        response.add("data", data);
        return response.toString();
    }

    private void handleLinkInteraction(DiscordInteraction interaction) {
        LinkingSettings settings = linkingSettings(config());
        DiscordRestClient client = rest;
        String application = applicationId;
        ExecutorService worker = interactionExecutor;
        if (!settings.accepts(interaction) || client == null || application == null || worker == null) {
            return;
        }
        worker.execute(() -> {
            if (!canHandleLink(settings, client, interaction)) {
                return;
            }
            if (interaction.isSubmit()) {
                sendInteractionResponse(client, interaction, linkModalPayload());
                return;
            }
            JsonObject deferred = new JsonObject();
            deferred.addProperty("type", 5);
            JsonObject ephemeral = new JsonObject();
            ephemeral.addProperty("flags", 64);
            deferred.add("data", ephemeral);
            if (!sendInteractionResponse(client, interaction, deferred.toString())) {
                return;
            }
            services.getStorageService().withDiscordAccountLinksAsync(links ->
                    canHandleLink(settings, client, interaction)
                            ? links.claim(interaction.authorId(), settings.guild(), interaction.modalCode())
                            : null)
                    .whenCompleteAsync((claim, failure) -> {
                        String message;
                        if (failure != null || claim == null) {
                            message = "Account linking is currently unavailable. Check your link status in Minecraft before retrying.";
                            if (failure != null) {
                                services.getDebugLogger().debugLog("[Discord] Link claim could not be confirmed ("
                                        + failure.getClass().getSimpleName() + ").");
                            }
                        } else {
                            message = switch (claim) {
                                case LINKED -> "Your Minecraft and Discord accounts are linked.";
                                case INVALID_CODE -> "That code is invalid or expired. Request a new one in Minecraft.";
                                case ALREADY_LINKED -> "An account is already linked. Check your link status in Minecraft.";
                                case RATE_LIMITED -> "Too many attempts. Try again in a minute.";
                            };
                        }
                        JsonObject data = new JsonObject();
                        data.addProperty("content", message);
                        DiscordRestClient.Result result = client.editInteractionResponse(application, interaction.token(), data.toString());
                        if (!result.success()) {
                            services.getDebugLogger().debugLog("[Discord] Could not update the /link response (HTTP " + result.status() + ").");
                        }
                    }, worker);
        });
    }

    private boolean canHandleLink(LinkingSettings settings, DiscordRestClient client, DiscordInteraction interaction) {
        return client == rest && !services.isStopping() && settings.equals(linkingSettings(config())) && settings.accepts(interaction);
    }

    private void handleInteraction(DiscordInteraction interaction) {
        if (LINK_COMMAND_NAME.equals(interaction.commandName()) || interaction.isLinkModal()) {
            handleLinkInteraction(interaction);
            return;
        }
        if (!CONSOLE_COMMAND_NAME.equals(interaction.commandName()) || (!interaction.isSubmit() && !interaction.isAutocomplete())) {
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
        sendInteractionResponse(restClient, interaction, payloadJson);
    }

    private boolean sendInteractionResponse(DiscordRestClient restClient, DiscordInteraction interaction, String payloadJson) {
        long startNanos = System.nanoTime();
        DiscordRestClient.Result result = restClient.respondToInteraction(interaction.id(), interaction.token(),
                payloadJson);
        long restMs = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - startNanos);
        services.getDebugLogger().debugLog("[Discord] Interaction response for " + interaction.id()
                + " success=" + result.success() + " status=" + result.status() + " restMs=" + restMs
                + (result.success() ? "" : " error=" + result.errorMessage()));
        return result.success();
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
            context.getLogger("").removeAppender(appender);
            flushConsoleRelay(appender);
            appender.stop();
        }
    }

    public synchronized void shutdown() {
        if (ticketRoomTask != null) {
            ticketRoomTask.cancel(false);
            ticketRoomTask = null;
        }
        ticketRoomRun = null;
        stop(true);
    }

    private void stop(boolean notifyServerStopping) {
        linkGateway = null;
        DiscordDispatcher queue = dispatcher;
        DiscordGatewayClient client = gateway;
        ScheduledExecutorService pool = executor;
        ExecutorService http = httpExecutor;
        ExecutorService interaction = interactionExecutor;
        ExecutorService ticketReplies = ticketReplyExecutor;
        ticketReplyExecutor = null;
        if (ticketReplies != null) {
            ticketReplies.shutdownNow();
        }

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

        appliedConnectionSettings = null;
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
        if (config == null || client == null || !Boolean.TRUE.equals(config.presenceEnabled.get())
                || !presenceUpdatePending.compareAndSet(false, true)) {
            return;
        }

        ServerThreadCalls.supply(services, () -> {
            IPlatformAdapter platform = services.getPlatformAdapter();
            int online = platform != null && platform.getOnlinePlayers() != null ? (int) platform.getOnlinePlayers().stream().filter(player -> player == null || !eu.avalanche7.paradigm.platform.VanishTracking.hidden(player.getUUID())).count() : 0;
            int max = platform != null ? platform.getMaxPlayers() : 0;
            return new PresenceSnapshot(online, max);
        }).whenComplete((snapshot, failure) -> {
            ScheduledExecutorService pool = executor;
            if (failure != null || snapshot == null || pool == null) {
                presenceUpdatePending.set(false);
                return;
            }
            try {
                pool.execute(() -> {
                    try {
                        sendPresence(snapshot.online(), snapshot.max());
                    } finally {
                        presenceUpdatePending.set(false);
                    }
                });
            } catch (RuntimeException rejected) {
                presenceUpdatePending.set(false);
            }
        });
    }

    private void sendPresence(int online, int max) {
        DiscordConfigHandler.Config config = config();
        DiscordGatewayClient client = gateway;
        if (config == null || client == null || !Boolean.TRUE.equals(config.presenceEnabled.get())) {
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
        presenceUpdatePending.set(false);
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
        String roomWarning = ticketRoomWarning;
        if (roomWarning != null) {
            warnings.add(roomWarning);
        }
        String registrationWarning = linkRegistrationWarning;
        if (registrationWarning != null) {
            warnings.add(registrationWarning);
        }
        if (config != null && Boolean.TRUE.equals(config.enabled.get()) && Boolean.TRUE.equals(config.linkingEnabled.get())) {
            StorageService storage = services == null ? null : services.getStorageService();
            if (storage == null || !storage.isSqlActive()) {
                warnings.add("Discord account linking requires active SQLite or shared MariaDB storage.");
            }
            if (linkingSettings(config).guild().isEmpty()) {
                warnings.add("Discord account linking requires a guild ID and, on shared storage, the owner's stable server ID.");
            }
        }
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
            if (!linkingSettings(config).guild().isEmpty()) {
                return null; // A code-issuing server does not need a gateway for linking.
            }
            return "No Discord channel ID is configured.";
        }
        return null;
    }

    private static String nullToEmpty(String value) {
        return value != null ? value.trim() : "";
    }

    private final class GatewayListener implements DiscordGatewayClient.Listener {
        private final String connectionToken;

        private GatewayListener(String connectionToken) {
            this.connectionToken = connectionToken;
        }

        @Override
        public void onReady(String userId, String username, String application) {
            botUserId = userId;
            botUsername = username;
            applicationId = application;
            linkGateway = new LinkGateway(connectionToken, application);
            connectedSinceMs = System.currentTimeMillis();
            schedulePresence();

            DiscordConfigHandler.Config cfg = config();
            DiscordRestClient restClient = rest;
            if (cfg != null && restClient != null) {
                runOnHttpExecutor(() -> reconcileConsoleSlashCommand(cfg, restClient));
                scheduleLinkRegistration();
            }
        }

        @Override
        public void onMessage(DiscordInboundMessage message) {
            DiscordConfigHandler.Config cfg = config();
            LinkingSettings linking = linkingSettings(cfg);
            boolean relayChannel = cfg != null && (relay.ownsConsoleChannel(message.channelId())
                    || relay.matchesRelayChannel(cfg, message));
            DiscordRestClient client = rest;
            StorageService storage = services.getStorageService();
            if (client != null && storage != null && storage.isSqlActive() && linking.guild().equals(message.guildId())
                    && (relayChannel || (linking.owner() && !message.bot() && !message.webhook() && !message.system()))) {
                ExecutorService worker = ticketReplyExecutor;
                if (worker != null) {
                    try {
                        worker.execute(() -> routeTicketRoomMessage(message, linking, client, storage, relayChannel));
                    } catch (RejectedExecutionException overloaded) {
                        if (ticketReplyExecutor == worker) {
                            warnTicketRoom("Discord ticket-room message queue is full. Message " + message.messageId()
                                    + " was not routed.");
                        }
                    }
                }
            } else {
                relayMessage(message);
            }
        }

        private void routeTicketRoomMessage(DiscordInboundMessage message, LinkingSettings linking,
                                            DiscordRestClient client, StorageService storage, boolean relayChannel) {
            if (services.isStopping() || rest != client) {
                return;
            }
            try {
                var replies = new DiscordTicketReplies(storage.discordTicketRooms(), client, DiscordService.this::warnTicketRoom);
                boolean handled = replies.handle(linking, message, storage.discordAccountLinks(),
                        services.getTicketService(), DiscordService.this::discordTicketActor,
                        !inboundCapability.blocksInboundRelay(),
                        () -> !services.isStopping() && rest == client && linking.equals(linkingSettings(config())));
                if (!handled && relayChannel && rest == client) {
                    relayMessage(message);
                }
            } catch (RuntimeException failure) {
                warnTicketRoom("Could not verify the ticket room for Discord message " + message.messageId()
                        + " (" + failure.getClass().getSimpleName() + ").");
            }
        }

        private void relayMessage(DiscordInboundMessage message) {
            try {
                if (relay.ownsConsoleChannel(message.channelId())) {
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
                        + "to use Discord to Minecraft chat and ticket replies.");
            }
        }
    }

    private record PresenceSnapshot(int online, int max) {
    }
}
