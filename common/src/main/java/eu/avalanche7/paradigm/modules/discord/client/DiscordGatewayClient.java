package eu.avalanche7.paradigm.modules.discord.client;

import java.net.URI;
import java.net.http.WebSocket;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Supplier;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonNull;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import eu.avalanche7.paradigm.modules.discord.DiscordConnectionState;
import eu.avalanche7.paradigm.modules.discord.DiscordInboundCapability;
import eu.avalanche7.paradigm.modules.discord.DiscordSecrets;
import eu.avalanche7.paradigm.utils.DebugLogger;

public final class DiscordGatewayClient {
    private static final int OP_DISPATCH = 0;
    private static final int OP_HEARTBEAT = 1;
    private static final int OP_IDENTIFY = 2;
    private static final int OP_RESUME = 6;
    private static final int OP_RECONNECT = 7;
    private static final int OP_INVALID_SESSION = 9;
    private static final int OP_HELLO = 10;
    private static final int OP_PRESENCE_UPDATE = 3;
    private static final int OP_HEARTBEAT_ACK = 11;
    private static final int ACTIVITY_CUSTOM = 4;

    static final int INTENT_GUILDS = 1;

    static final int INTENT_GUILD_MESSAGES = 1 << 9;

    static final int INTENT_MESSAGE_CONTENT = 1 << 15;

    private static final Set<Integer> TERMINAL_CLOSE_CODES = Set.of(4004, 4010, 4011, 4012);

    private static final Set<Integer> NON_RESUMABLE_CLOSE_CODES = Set.of(1000, 1001, 4007, 4009);

    private static final long MAX_BACKOFF_MILLIS = 60_000L;
    private static final int EMPTY_CONTENT_SUSPICION_THRESHOLD = 3;

    public interface Listener {
        void onReady(String botUserId, String botUsername, String applicationId);

        void onMessage(DiscordInboundMessage message);

        default void onMessageUpdated(DiscordInboundMessage message) {
        }

        default void onMessageDeleted(String messageId, String channelId, String guildId) {
        }

        default void onInteraction(DiscordInteraction interaction) {
        }

        void onStateChanged(DiscordConnectionState state, String detail);

        void onInboundCapability(DiscordInboundCapability capability);
    }

    private final Supplier<String> tokenSupplier;
    private final DiscordRestClient rest;
    private final DebugLogger debugLogger;
    private final Listener listener;
    private final ScheduledExecutorService scheduler;

    private final AtomicBoolean running = new AtomicBoolean();
    private final AtomicBoolean reconnectPending = new AtomicBoolean();
    private final AtomicInteger reconnectAttempts = new AtomicInteger();
    private final AtomicInteger connectionGeneration = new AtomicInteger();

    private volatile WebSocket webSocket;
    private volatile CompletableFuture<WebSocket> connecting;
    private volatile ScheduledFuture<?> heartbeatTask;
    private volatile boolean awaitingHeartbeatAck;
    private volatile long lastHeartbeatAckMillis;
    private volatile long lastHeartbeatSentMillis;

    private volatile String sessionId;
    private volatile String resumeGatewayUrl;
    private volatile long lastSequence;

    private volatile int intents = INTENT_GUILDS | INTENT_GUILD_MESSAGES | INTENT_MESSAGE_CONTENT;
    private volatile boolean messageContentRequested = true;
    private volatile DiscordInboundCapability inboundCapability = DiscordInboundCapability.UNKNOWN;
    private volatile int emptyContentStreak;

    private volatile DiscordConnectionState state = DiscordConnectionState.DISCONNECTED;
    private volatile String lastDetail;
    private volatile String botUserId;
    private volatile String applicationId;
    private volatile String lastPresencePayload;

    private final Object sendLock = new Object();
    private CompletableFuture<?> sendChain = CompletableFuture.completedFuture(null);
    private final DiscordRoleColors roleColors = new DiscordRoleColors();

    public DiscordGatewayClient(Supplier<String> tokenSupplier, DiscordRestClient rest,
                                DebugLogger debugLogger, Listener listener, ScheduledExecutorService scheduler) {
        this.tokenSupplier = tokenSupplier;
        this.rest = rest;
        this.debugLogger = debugLogger;
        this.listener = listener;
        this.scheduler = scheduler;
    }

    public void start() {
        if (!running.compareAndSet(false, true)) {
            return;
        }
        resetSession();
        intents = INTENT_GUILDS | INTENT_GUILD_MESSAGES | INTENT_MESSAGE_CONTENT;
        messageContentRequested = true;
        setCapability(DiscordInboundCapability.UNKNOWN);
        reconnectAttempts.set(0);
        scheduler.execute(() -> openConnection(false));
    }

    public void stop() {
        if (!running.compareAndSet(true, false)) {
            return;
        }
        invalidateConnectionAttempt();
        stopHeartbeat();
        WebSocket socket = webSocket;
        webSocket = null;
        if (socket != null) {
            try {
                socket.sendClose(WebSocket.NORMAL_CLOSURE, "Paradigm shutting down");
            } catch (RuntimeException ignored) {
                socket.abort();
            }
        }
        resetSession();
        notifyState(DiscordConnectionState.DISCONNECTED, null);
    }

    public void reconnectNow() {
        if (!running.get()) {
            start();
            return;
        }
        invalidateConnectionAttempt();
        reconnectAttempts.set(0);
        resetSession();
        abortSocket();
        scheduleReconnect(false, "Manual reconnect requested.", 0L);
    }

    public DiscordConnectionState state() {
        return state;
    }

    public String lastDetail() {
        return lastDetail;
    }

    public DiscordInboundCapability inboundCapability() {
        return inboundCapability;
    }

    public String botUserId() {
        return botUserId;
    }

    public long lastHeartbeatAckMillis() {
        return lastHeartbeatAckMillis;
    }

    public boolean isHeartbeatOutstanding() {
        return awaitingHeartbeatAck;
    }

    public long millisSinceLastHeartbeatAck() {
        long ack = lastHeartbeatAckMillis;
        return ack == 0L ? -1L : System.currentTimeMillis() - ack;
    }

    private void openConnection(boolean resume) {
        if (!running.get()) {
            return;
        }
        int generation = beginConnectionAttempt();
        notifyState(DiscordConnectionState.CONNECTING, null);
        String url;
        try {
            String cached = resumeGatewayUrl;
            url = resume && cached != null && !cached.isBlank() ? cached : rest.fetchGatewayUrl();
        } catch (DiscordRestClient.DiscordApiException failure) {
            if (!isCurrentConnection(generation)) {
                return;
            }
            if (!failure.isRetryable()) {
                fail(failure.getMessage());
            } else {
                scheduleReconnect(false, failure.getMessage(), backoffMillis(reconnectAttempts.incrementAndGet()));
            }
            return;
        }

        String token = tokenSupplier.get();
        if (!isCurrentConnection(generation)) {
            return;
        }
        if (!DiscordSecrets.isPresent(token)) {
            fail("No Discord bot token configured.");
            return;
        }

        URI target = URI.create(url + (url.contains("?") ? "&" : "?") + "v=10&encoding=json");
        try {
            CompletableFuture<WebSocket> future = rest.httpClient().newWebSocketBuilder()
                    .connectTimeout(Duration.ofSeconds(20))
                    .buildAsync(target, new SocketListener(resume, generation));
            connecting = future;
            if (!isCurrentConnection(generation)) {
                if (connecting == future) {
                    connecting = null;
                }
                future.cancel(true);
                return;
            }
            future.whenComplete((socket, error) -> {
                if (connecting == future) {
                    connecting = null;
                }
                if (!isCurrentConnection(generation)) {
                    if (socket != null) {
                        socket.abort();
                    }
                    return;
                }
                if (error != null) {
                    String message = DiscordSecrets.redact(rootMessage(error), token);
                    debug("Gateway connect failed: " + message);
                    scheduleReconnect(resume, "Could not open the Discord gateway: " + message,
                            backoffMillis(reconnectAttempts.incrementAndGet()));
                    return;
                }
                webSocket = socket;
            });
        } catch (RuntimeException failure) {
            if (!isCurrentConnection(generation)) {
                return;
            }
            String message = DiscordSecrets.redact(rootMessage(failure), token);
            scheduleReconnect(resume, "Could not open the Discord gateway: " + message,
                    backoffMillis(reconnectAttempts.incrementAndGet()));
        }
    }

    private void scheduleReconnect(boolean resume, String reason, long delayMillis) {
        if (!running.get()) {
            return;
        }
        if (!reconnectPending.compareAndSet(false, true)) {
            return;
        }
        stopHeartbeat();
        notifyState(DiscordConnectionState.RECONNECTING, reason);
        long delay = Math.max(0L, delayMillis);
        debug("Reconnecting in " + delay + "ms (resume=" + resume + "): " + reason);
        try {
            scheduler.schedule(() -> {
                reconnectPending.set(false);
                openConnection(resume);
            }, delay, TimeUnit.MILLISECONDS);
        } catch (RuntimeException rejected) {
            reconnectPending.set(false);
        }
    }

    static long backoffMillis(int attempt) {
        int safeAttempt = Math.max(1, Math.min(attempt, 16));
        long base = Math.min(MAX_BACKOFF_MILLIS, 1000L << (safeAttempt - 1));
        double jitter = 0.8d + ThreadLocalRandom.current().nextDouble() * 0.4d;
        return Math.min(MAX_BACKOFF_MILLIS, (long) (base * jitter));
    }

    private void abortSocket() {
        WebSocket socket = webSocket;
        webSocket = null;
        if (socket != null) {
            socket.abort();
        }
    }

    private int beginConnectionAttempt() {
        invalidateConnectionAttempt();
        return connectionGeneration.get();
    }

    private void invalidateConnectionAttempt() {
        connectionGeneration.incrementAndGet();
        CompletableFuture<WebSocket> future = connecting;
        connecting = null;
        if (future != null) {
            future.cancel(true);
        }
    }

    private boolean isCurrentConnection(int generation) {
        return running.get() && connectionGeneration.get() == generation;
    }

    private void fail(String reason) {
        running.set(false);
        invalidateConnectionAttempt();
        stopHeartbeat();
        abortSocket();
        notifyState(DiscordConnectionState.FAILED, reason);
    }

    private void resetSession() {
        sessionId = null;
        resumeGatewayUrl = null;
        lastSequence = 0L;
    }

    private void startHeartbeat(WebSocket socket, long intervalMillis) {
        stopHeartbeat();
        awaitingHeartbeatAck = false;
        long interval = Math.max(1000L, intervalMillis);
        long initialDelay = (long) (interval * ThreadLocalRandom.current().nextDouble());
        try {
            heartbeatTask = scheduler.scheduleAtFixedRate(() -> heartbeat(socket), initialDelay, interval, TimeUnit.MILLISECONDS);
        } catch (RuntimeException rejected) {
            heartbeatTask = null;
        }
    }

    private void stopHeartbeat() {
        ScheduledFuture<?> task = heartbeatTask;
        heartbeatTask = null;
        if (task != null) {
            task.cancel(false);
        }
        awaitingHeartbeatAck = false;
    }

    private void heartbeat(WebSocket socket) {
        if (!running.get() || socket != webSocket) {
            return;
        }
        if (awaitingHeartbeatAck) {
            debug("No heartbeat ACK since " + lastHeartbeatSentMillis + "; treating the connection as a zombie.");
            abortSocket();
            scheduleReconnect(sessionId != null,
                    "Discord stopped acknowledging heartbeats; reconnecting.",
                    backoffMillis(reconnectAttempts.incrementAndGet()));
            return;
        }
        awaitingHeartbeatAck = true;
        lastHeartbeatSentMillis = System.currentTimeMillis();
        sendHeartbeat(socket);
    }

    private void sendHeartbeat(WebSocket socket) {
        JsonObject payload = new JsonObject();
        payload.addProperty("op", OP_HEARTBEAT);
        long sequence = lastSequence;
        if (sequence > 0) {
            payload.addProperty("d", sequence);
        } else {
            payload.add("d", JsonNull.INSTANCE);
        }
        send(socket, payload);
    }

    public String applicationId() {
        return applicationId;
    }

    public boolean sendPresence(String text, String activityType, String status) {
        WebSocket socket = webSocket;
        if (socket == null || state != DiscordConnectionState.CONNECTED) {
            return false;
        }
        String payload = buildPresencePayload(text, activityType, status);
        if (payload.equals(lastPresencePayload)) {
            return false;
        }
        lastPresencePayload = payload;
        sendRaw(socket, payload);
        return true;
    }

    public static String buildPresencePayload(String text, String activityType, String status) {
        int type = activityTypeCode(activityType);
        JsonObject activity = new JsonObject();
        activity.addProperty("type", type);
        if (type == ACTIVITY_CUSTOM) {
            activity.addProperty("name", "Custom Status");
            activity.addProperty("state", text);
        } else {
            activity.addProperty("name", text);
        }

        JsonArray activities = new JsonArray();
        activities.add(activity);

        JsonObject data = new JsonObject();
        data.add("since", JsonNull.INSTANCE);
        data.add("activities", activities);
        data.addProperty("status", status != null && !status.isBlank() ? status : "online");
        data.addProperty("afk", false);

        JsonObject payload = new JsonObject();
        payload.addProperty("op", OP_PRESENCE_UPDATE);
        payload.add("d", data);
        return payload.toString();
    }

    static int activityTypeCode(String activityType) {
        String normalized = activityType != null ? activityType.trim().toLowerCase(Locale.ROOT) : "";
        return switch (normalized) {
            case "playing" -> 0;
            case "listening" -> 2;
            case "watching" -> 3;
            case "competing" -> 5;
            default -> ACTIVITY_CUSTOM;
        };
    }

    private void handlePayload(WebSocket socket, String raw, SocketListener owner) {
        JsonObject payload;
        try {
            JsonElement parsed = JsonParser.parseString(raw);
            if (!parsed.isJsonObject()) {
                return;
            }
            payload = parsed.getAsJsonObject();
        } catch (RuntimeException malformed) {
            debug("Ignoring malformed gateway payload.");
            return;
        }

        Integer op = DiscordJson.integer(payload, "op");
        if (op == null) {
            return;
        }
        long sequence = DiscordJson.longValue(payload, "s", -1L);
        if (sequence > 0) {
            lastSequence = sequence;
        }

        switch (op) {
            case OP_HELLO -> {
                JsonObject data = DiscordJson.object(payload, "d");
                long interval = DiscordJson.longValue(data, "heartbeat_interval", 41_250L);
                startHeartbeat(socket, interval);
                if (owner.resume && sessionId != null && lastSequence > 0) {
                    sendResume(socket);
                } else {
                    sendIdentify(socket);
                }
            }
            case OP_HEARTBEAT -> {
                awaitingHeartbeatAck = true;
                lastHeartbeatSentMillis = System.currentTimeMillis();
                sendHeartbeat(socket);
            }
            case OP_HEARTBEAT_ACK -> {
                awaitingHeartbeatAck = false;
                lastHeartbeatAckMillis = System.currentTimeMillis();
            }
            case OP_RECONNECT -> {
                abortSocket();
                scheduleReconnect(sessionId != null, "Discord asked us to reconnect.", 500L);
            }
            case OP_INVALID_SESSION -> {
                boolean resumable = payload.has("d") && DiscordJson.bool(payload, "d", false);
                if (!resumable) {
                    resetSession();
                }
                abortSocket();
                scheduleReconnect(resumable, "Discord invalidated the gateway session.", 2000L);
            }
            case OP_DISPATCH -> handleDispatch(payload);
            default -> {
            }
        }
    }

    private void handleDispatch(JsonObject payload) {
        String type = DiscordJson.string(payload, "t");
        if (type == null) {
            return;
        }
        JsonObject data = DiscordJson.object(payload, "d");
        switch (type) {
            case "READY" -> {
                sessionId = DiscordJson.string(data, "session_id");
                resumeGatewayUrl = DiscordJson.string(data, "resume_gateway_url");
                JsonObject user = DiscordJson.object(data, "user");
                botUserId = DiscordJson.string(user, "id");
                String username = DiscordJson.string(user, "username", "unknown");
                applicationId = DiscordJson.string(DiscordJson.object(data, "application"), "id");
                reconnectAttempts.set(0);
                emptyContentStreak = 0;
                lastPresencePayload = null;
                setCapability(messageContentRequested
                        ? DiscordInboundCapability.UNKNOWN
                        : DiscordInboundCapability.CONTENT_INTENT_MISSING);
                notifyState(DiscordConnectionState.CONNECTED, null);
                if (listener != null) {
                    listener.onReady(botUserId, username, applicationId);
                }
            }
            case "RESUMED" -> {
                reconnectAttempts.set(0);
                lastPresencePayload = null;
                notifyState(DiscordConnectionState.CONNECTED, null);
            }
            case "MESSAGE_CREATE" -> {
                DiscordInboundMessage message = parseMessage(data);
                if (message == null) {
                    return;
                }
                trackCapability(message);
                if (listener != null) {
                    listener.onMessage(message);
                }
            }
            case "MESSAGE_UPDATE" -> {
                if (data == null || !data.has("content")) {
                    return;
                }
                DiscordInboundMessage message = parseMessage(data);
                if (message != null && listener != null) {
                    listener.onMessageUpdated(message);
                }
            }
            case "MESSAGE_DELETE" -> {
                String messageId = DiscordJson.string(data, "id");
                String channelId = DiscordJson.string(data, "channel_id");
                if (messageId != null && channelId != null && listener != null) {
                    listener.onMessageDeleted(messageId, channelId, DiscordJson.string(data, "guild_id"));
                }
            }
            case "GUILD_CREATE" -> handleGuildCreate(data);
            case "GUILD_ROLE_CREATE", "GUILD_ROLE_UPDATE" -> handleGuildRole(data);
            case "GUILD_ROLE_DELETE" -> handleGuildRoleDelete(data);
            case "INTERACTION_CREATE" -> {
                DiscordInteraction interaction = parseInteraction(data);
                if (interaction != null && listener != null) {
                    listener.onInteraction(interaction);
                }
            }
            default -> {
            }
        }
    }

    DiscordInteraction parseInteraction(JsonObject data) {
        if (data == null) {
            return null;
        }
        String id = DiscordJson.string(data, "id");
        String token = DiscordJson.string(data, "token");
        Integer type = DiscordJson.integer(data, "type");
        if (id == null || token == null || type == null) {
            return null;
        }

        JsonObject member = DiscordJson.object(data, "member");
        JsonObject user = member != null ? DiscordJson.object(member, "user") : DiscordJson.object(data, "user");
        String authorId = DiscordJson.string(user, "id");
        String authorDisplayName = displayNameOf(user, member);
        boolean bot = DiscordJson.bool(user, "bot", false);

        JsonObject commandData = DiscordJson.object(data, "data");
        String commandName = DiscordJson.string(commandData, "name");
        String optionValue = null;
        JsonArray options = DiscordJson.array(commandData, "options");
        if (options != null) {
            for (JsonElement element : options) {
                if (!element.isJsonObject()) {
                    continue;
                }
                JsonObject option = element.getAsJsonObject();
                if (DiscordJson.bool(option, "focused", false)) {
                    optionValue = DiscordJson.string(option, "value", "");
                    break;
                }
            }
            if (optionValue == null && !options.isEmpty() && options.get(0).isJsonObject()) {
                optionValue = DiscordJson.string(options.get(0).getAsJsonObject(), "value", "");
            }
        }

        return new DiscordInteraction(id, token, type, DiscordJson.string(data, "guild_id"),
                DiscordJson.string(data, "channel_id"), commandName, optionValue, authorId,
                authorDisplayName, bot);
    }

    DiscordInboundMessage parseMessage(JsonObject data) {
        if (data == null) {
            return null;
        }
        String messageId = DiscordJson.string(data, "id");
        String channelId = DiscordJson.string(data, "channel_id");
        if (messageId == null || channelId == null) {
            return null;
        }
        JsonObject author = DiscordJson.object(data, "author");
        String authorId = DiscordJson.string(author, "id");
        boolean bot = DiscordJson.bool(author, "bot", false);
        boolean webhook = DiscordJson.string(data, "webhook_id") != null;

        Integer messageType = DiscordJson.integer(data, "type");
        boolean system = messageType != null && messageType != 0 && messageType != 19;

        JsonObject member = DiscordJson.object(data, "member");
        String display = displayNameOf(author, member);

        java.util.List<String> attachmentUrls = new java.util.ArrayList<>();
        java.util.List<DiscordInboundMessage.Attachment> attachments = new java.util.ArrayList<>();
        JsonArray attachmentArray = DiscordJson.array(data, "attachments");
        if (attachmentArray != null) {
            for (JsonElement element : attachmentArray) {
                if (!element.isJsonObject()) {
                    continue;
                }
                JsonObject attachment = element.getAsJsonObject();
                String url = DiscordJson.string(attachment, "url");
                if (url != null && !url.isBlank()) {
                    attachmentUrls.add(url);
                    attachments.add(new DiscordInboundMessage.Attachment(url,
                            DiscordJson.string(attachment, "filename", "attachment")));
                }
            }
        }

        java.util.List<DiscordInboundMessage.EmbedSummary> embeds = new java.util.ArrayList<>();
        JsonArray embedArray = DiscordJson.array(data, "embeds");
        if (embedArray != null) {
            for (JsonElement element : embedArray) {
                if (!element.isJsonObject()) {
                    continue;
                }
                JsonObject embed = element.getAsJsonObject();
                embeds.add(new DiscordInboundMessage.EmbedSummary(
                        DiscordJson.string(DiscordJson.object(embed, "author"), "name"),
                        DiscordJson.string(embed, "title"),
                        DiscordJson.string(embed, "description"),
                        DiscordJson.string(DiscordJson.object(embed, "image"), "url")));
            }
        }

        java.util.List<String> stickerNames = new java.util.ArrayList<>();
        JsonArray stickerArray = DiscordJson.array(data, "sticker_items");
        if (stickerArray != null) {
            for (JsonElement element : stickerArray) {
                if (element.isJsonObject()) {
                    String name = DiscordJson.string(element.getAsJsonObject(), "name");
                    if (name != null && !name.isBlank()) {
                        stickerNames.add(name);
                    }
                }
            }
        }

        Integer authorColorRgb = null;
        JsonArray memberRoles = DiscordJson.array(member, "roles");
        if (memberRoles != null && !memberRoles.isEmpty()) {
            java.util.List<String> roleIds = new java.util.ArrayList<>(memberRoles.size());
            for (JsonElement element : memberRoles) {
                if (element.isJsonPrimitive()) {
                    roleIds.add(element.getAsString());
                }
            }
            authorColorRgb = roleColors.highestColor(roleIds);
        }

        Map<String, String> mentionNames = new LinkedHashMap<>();
        JsonArray mentions = DiscordJson.array(data, "mentions");
        if (mentions != null) {
            for (JsonElement element : mentions) {
                if (!element.isJsonObject()) {
                    continue;
                }
                JsonObject mentioned = element.getAsJsonObject();
                String id = DiscordJson.string(mentioned, "id");
                if (id == null) {
                    continue;
                }
                mentionNames.put(id, displayNameOf(mentioned, DiscordJson.object(mentioned, "member")));
            }
        }

        String replyAuthorName = null;
        String replyContent = null;
        JsonObject referenced = DiscordJson.object(data, "referenced_message");
        if (referenced != null) {
            replyAuthorName = displayNameOf(DiscordJson.object(referenced, "author"),
                    DiscordJson.object(referenced, "member"));
            replyContent = DiscordJson.string(referenced, "content", "");
        }

        return new DiscordInboundMessage(
                messageId,
                channelId,
                DiscordJson.string(data, "guild_id"),
                authorId,
                display,
                bot,
                webhook,
                system,
                DiscordJson.string(data, "content", ""),
                attachmentUrls,
                mentionNames,
                replyAuthorName,
                replyContent,
                attachments,
                embeds,
                stickerNames,
                authorColorRgb);
    }

    void handleGuildCreate(JsonObject data) {
        JsonArray guildRoles = DiscordJson.array(data, "roles");
        if (guildRoles == null) {
            return;
        }
        for (JsonElement element : guildRoles) {
            if (element.isJsonObject()) {
                putRole(element.getAsJsonObject());
            }
        }
    }

    void handleGuildRole(JsonObject data) {
        JsonObject role = DiscordJson.object(data, "role");
        if (role != null) {
            putRole(role);
        }
    }

    void handleGuildRoleDelete(JsonObject data) {
        roleColors.remove(DiscordJson.string(data, "role_id"));
    }

    private void putRole(JsonObject role) {
        String id = DiscordJson.string(role, "id");
        if (id == null) {
            return;
        }
        Integer colorValue = DiscordJson.integer(role, "color");
        int color = colorValue != null ? colorValue : 0;
        int position = (int) DiscordJson.longValue(role, "position", 0L);
        roleColors.put(id, color, position);
    }

    private static String displayNameOf(JsonObject user, JsonObject member) {
        String nick = DiscordJson.string(member, "nick");
        if (nick != null && !nick.isBlank()) {
            return nick;
        }
        String globalName = DiscordJson.string(user, "global_name");
        if (globalName != null && !globalName.isBlank()) {
            return globalName;
        }
        return DiscordJson.string(user, "username", "unknown");
    }

    private void trackCapability(DiscordInboundMessage message) {
        if (!messageContentRequested) {
            return;
        }
        if (message.hasContent()) {
            emptyContentStreak = 0;
            setCapability(DiscordInboundCapability.AVAILABLE);
            return;
        }
        if (message.hasAttachments() || message.system()) {
            return;
        }
        if (inboundCapability == DiscordInboundCapability.AVAILABLE) {
            return;
        }
        if (++emptyContentStreak >= EMPTY_CONTENT_SUSPICION_THRESHOLD) {
            setCapability(DiscordInboundCapability.CONTENT_INTENT_MISSING);
        }
    }

    private void setCapability(DiscordInboundCapability capability) {
        if (inboundCapability == capability) {
            return;
        }
        inboundCapability = capability;
        if (listener != null) {
            listener.onInboundCapability(capability);
        }
    }

    private void sendIdentify(WebSocket socket) {
        String token = tokenSupplier.get();
        if (!DiscordSecrets.isPresent(token)) {
            fail("No Discord bot token configured.");
            return;
        }
        JsonObject properties = new JsonObject();
        properties.addProperty("os", System.getProperty("os.name", "unknown"));
        properties.addProperty("browser", "Paradigm");
        properties.addProperty("device", "Paradigm");

        JsonObject data = new JsonObject();
        data.addProperty("token", token);
        data.addProperty("intents", intents);
        data.add("properties", properties);

        JsonObject payload = new JsonObject();
        payload.addProperty("op", OP_IDENTIFY);
        payload.add("d", data);
        send(socket, payload);
    }

    private void sendResume(WebSocket socket) {
        String token = tokenSupplier.get();
        if (!DiscordSecrets.isPresent(token)) {
            fail("No Discord bot token configured.");
            return;
        }
        JsonObject data = new JsonObject();
        data.addProperty("token", token);
        data.addProperty("session_id", sessionId);
        data.addProperty("seq", lastSequence);

        JsonObject payload = new JsonObject();
        payload.addProperty("op", OP_RESUME);
        payload.add("d", data);
        send(socket, payload);
    }

    private void send(WebSocket socket, JsonObject payload) {
        sendRaw(socket, payload.toString());
    }

    private void sendRaw(WebSocket socket, String text) {
        if (socket == null) {
            return;
        }
        synchronized (sendLock) {
            sendChain = sendChain
                    .handle((ignoredResult, ignoredError) -> null)
                    .thenCompose(ignored -> socket.sendText(text, true))
                    .handle((ignoredResult, error) -> {
                        if (error != null) {
                            debug("Gateway send failed: "
                                    + DiscordSecrets.redact(rootMessage(error), tokenSupplier.get()));
                        }
                        return null;
                    });
        }
    }

    private void handleClose(WebSocket socket, int generation, int statusCode, String reason) {
        if (!isCurrentConnection(generation) || socket != webSocket) {
            return;
        }
        stopHeartbeat();
        webSocket = null;
        if (!running.get()) {
            return;
        }
        String detail = "Discord closed the gateway (" + statusCode
                + (reason == null || reason.isBlank() ? "" : ": " + reason) + ").";

        if ((statusCode == 4014 || statusCode == 4013) && messageContentRequested) {
            messageContentRequested = false;
            intents = INTENT_GUILDS | INTENT_GUILD_MESSAGES;
            setCapability(DiscordInboundCapability.CONTENT_INTENT_MISSING);
            resetSession();
            scheduleReconnect(false,
                    "Discord refused the MESSAGE_CONTENT intent; reconnecting without Discord to Minecraft chat.",
                    1000L);
            return;
        }

        if (TERMINAL_CLOSE_CODES.contains(statusCode) || statusCode == 4014 || statusCode == 4013) {
            fail(detail + " This will not resolve until the bot token or intents are corrected.");
            return;
        }

        boolean resumable = sessionId != null && !NON_RESUMABLE_CLOSE_CODES.contains(statusCode);
        if (!resumable) {
            resetSession();
        }
        scheduleReconnect(resumable, detail, backoffMillis(reconnectAttempts.incrementAndGet()));
    }

    private void notifyState(DiscordConnectionState next, String detail) {
        state = next;
        lastDetail = detail;
        if (listener != null) {
            listener.onStateChanged(next, detail);
        }
    }

    private void debug(String message) {
        if (debugLogger != null) {
            debugLogger.debugLog("[Discord] " + message);
        }
    }

    private static String rootMessage(Throwable error) {
        Throwable cursor = error;
        while (cursor.getCause() != null && cursor.getCause() != cursor) {
            cursor = cursor.getCause();
        }
        String message = cursor.getMessage();
        return message != null && !message.isBlank() ? message : cursor.getClass().getSimpleName();
    }

    private final class SocketListener implements WebSocket.Listener {
        private final boolean resume;
        private final int generation;
        private final StringBuilder buffer = new StringBuilder();

        private SocketListener(boolean resume, int generation) {
            this.resume = resume;
            this.generation = generation;
        }

        @Override
        public void onOpen(WebSocket socket) {
            if (!isCurrentConnection(generation)) {
                socket.abort();
                return;
            }
            webSocket = socket;
            socket.request(1);
        }

        @Override
        public CompletionStage<?> onText(WebSocket socket, CharSequence data, boolean last) {
            if (!isCurrentConnection(generation) || socket != webSocket) {
                socket.abort();
                return null;
            }
            buffer.append(data);
            if (last) {
                String raw = buffer.toString();
                buffer.setLength(0);
                try {
                    handlePayload(socket, raw, this);
                } catch (RuntimeException failure) {
                    debug("Gateway payload handling failed: " + rootMessage(failure));
                }
            }
            socket.request(1);
            return null;
        }

        @Override
        public CompletionStage<?> onClose(WebSocket socket, int statusCode, String reason) {
            try {
                handleClose(socket, generation, statusCode, reason);
            } catch (RuntimeException failure) {
                debug("Gateway close handling failed: " + rootMessage(failure));
            }
            return null;
        }

        @Override
        public void onError(WebSocket socket, Throwable error) {
            if (!isCurrentConnection(generation) || socket != webSocket) {
                return;
            }
            String message = DiscordSecrets.redact(rootMessage(error), tokenSupplier.get());
            debug("Gateway error: " + message);
            stopHeartbeat();
            webSocket = null;
            if (running.get()) {
                scheduleReconnect(sessionId != null, "Discord gateway error: " + message,
                        backoffMillis(reconnectAttempts.incrementAndGet()));
            }
        }
    }
}
