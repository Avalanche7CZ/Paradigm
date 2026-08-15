package eu.avalanche7.paradigm.modules.discord;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;

import eu.avalanche7.paradigm.configs.DiscordConfigHandler;
import eu.avalanche7.paradigm.core.ParadigmEvents;
import eu.avalanche7.paradigm.core.Services;
import eu.avalanche7.paradigm.modules.discord.client.DiscordInboundMessage;
import eu.avalanche7.paradigm.modules.moderation.PunishmentRecord;
import eu.avalanche7.paradigm.modules.moderation.PunishmentType;
import eu.avalanche7.paradigm.platform.Interfaces.IComponent;
import eu.avalanche7.paradigm.platform.Interfaces.IEventSystem;
import eu.avalanche7.paradigm.platform.Interfaces.IPlatformAdapter;
import eu.avalanche7.paradigm.platform.Interfaces.IPlayer;
import eu.avalanche7.paradigm.utils.MessageParser;
import eu.avalanche7.paradigm.utils.formatting.ComponentSlots;

public final class DiscordRelay implements ParadigmEvents.Listener {
    private static final int PROCESSED_MESSAGE_MEMORY = 512;
    private static final int RELAYED_AUTHOR_MEMORY = 256;
    private static final int REPLY_QUOTE_LENGTH = 64;
    private static final int EMBED_DESCRIPTION_LENGTH = 300;
    private static final String ATTACHMENT_TOKEN_PREFIX = "DISCORD_ATTACH_";

    private static final String DEFAULT_CHAT_FORMAT =
            "<color:dark_gray>[</color><color:blue>Discord</color><color:dark_gray>]</color> "
                    + "<color:aqua>{name}</color> <color:dark_gray>»</color> <color:white>{message}</color>";
    private static final String DEFAULT_REPLY_FORMAT =
            "<color:dark_gray>[</color><color:blue>Discord</color><color:dark_gray>]</color> "
                    + "<color:aqua>{name}</color> <color:dark_gray>replying to</color> "
                    + "<color:aqua>{reply_name}</color> <color:dark_gray>»</color> <color:white>{message}</color>";
    private static final String DEFAULT_EDIT_FORMAT =
            "<color:dark_gray>[</color><color:blue>Discord</color><color:dark_gray>]</color> "
                    + "<color:aqua>{name}</color> <color:dark_gray><italic>edited:</italic></color> "
                    + "<color:white>{message}</color>";
    private static final String DEFAULT_DELETE_FORMAT =
            "<color:dark_gray>[</color><color:blue>Discord</color><color:dark_gray>]</color> "
                    + "<color:gray><italic>{name} deleted their message</italic></color>";

    private static final String ANSI_ESC = "\u001b";
    private static final String ANSI_RESET = ANSI_ESC + "[0m";
    private static final Pattern MARKDOWN_BOLD = Pattern.compile("\\*\\*(.+?)\\*\\*");
    private static final Pattern MARKDOWN_ITALIC = Pattern.compile("(?<!_)_(?!_)(.+?)(?<!_)_(?!_)");

    private static final Set<String> SENSITIVE_COMMANDS = Set.of(
            "login", "log", "l", "register", "reg", "passwd", "password", "changepassword", "changepass",
            "unregister", "email", "msg", "m", "w", "t", "tell", "whisper", "pm", "r", "reply");

    private final Services services;
    private final DiscordOutbox service;

    private final Set<String> processedMessageIds = Collections.newSetFromMap(
            Collections.synchronizedMap(new LinkedHashMap<>(PROCESSED_MESSAGE_MEMORY + 1, 0.75f, true) {
                @Override
                protected boolean removeEldestEntry(Map.Entry<String, Boolean> eldest) {
                    return size() > PROCESSED_MESSAGE_MEMORY;
                }
            }));

    private final Map<String, String> relayedAuthors = Collections.synchronizedMap(
            new LinkedHashMap<>(RELAYED_AUTHOR_MEMORY + 1, 0.75f, true) {
                @Override
                protected boolean removeEldestEntry(Map.Entry<String, String> eldest) {
                    return size() > RELAYED_AUTHOR_MEMORY;
                }
            });

    private volatile boolean platformListenersRegistered;

    public DiscordRelay(Services services, DiscordOutbox service) {
        this.services = services;
        this.service = service;
    }

    public void registerPlatformListeners() {
        if (platformListenersRegistered || services == null) {
            return;
        }
        IPlatformAdapter platform = services.getPlatformAdapter();
        IEventSystem events = platform != null ? platform.getEventSystem() : null;
        if (events == null) {
            return;
        }
        platformListenersRegistered = true;

        events.onPlayerChat(event -> {
            if (event == null || event.isCancelled()) {
                return;
            }
            relayPlayerChat(event.getPlayer(), event.getMessage());
        });
        events.onPlayerJoin(event -> {
            if (event != null) {
                relayPlayerPresence(event.getPlayer(), true);
            }
        });
        events.onPlayerLeave(event -> {
            if (event != null) {
                relayPlayerPresence(event.getPlayer(), false);
            }
        });
        events.onPlayerDeath(event -> {
            if (event != null) {
                relayPlayerDeath(event.getPlayer(), event.getDeathMessage());
            }
        });
        events.onPlayerCommand(event -> {
            if (event != null && !event.isCancelled()) {
                relayCommand(event.getPlayer(), event.getCommand());
            }
        });
        events.onPlayerAdvancement(event -> {
            if (event != null) {
                relayAdvancement(event.getPlayer(), event.getAdvancementName(), event.getAdvancementDescription());
            }
        });
    }

    private static String styled(DiscordConfigHandler.Config config, String colorCode, String text) {
        return Boolean.TRUE.equals(config.useAnsiColors.get()) ? ansiBlock(colorCode, text) : text;
    }

    private static String ansiBlock(String colorCode, String text) {
        return "```ansi\n" + ANSI_ESC + "[" + colorCode + "m" + toAnsiSafe(text) + ANSI_RESET + "\n```";
    }

    private static String toAnsiSafe(String text) {
        String result = MARKDOWN_BOLD.matcher(text)
                .replaceAll(ANSI_ESC + "[1m$1" + ANSI_ESC + "[22m");
        return MARKDOWN_ITALIC.matcher(result).replaceAll("$1");
    }

    void relayAdvancement(IPlayer player, String advancementName, String advancementDescription) {
        DiscordConfigHandler.Config config = service.config();
        if (config == null || !service.isEnabled() || player == null) {
            return;
        }
        if (!Boolean.TRUE.equals(config.notifyAdvancement.get())) {
            return;
        }
        if (advancementName == null || advancementName.isBlank()) {
            return;
        }

        IPlatformAdapter platform = services.getPlatformAdapter();
        String resolved = platform != null
                ? platform.replacePlaceholders(config.advancementFormat.get(), player)
                : config.advancementFormat.get();
        resolved = DiscordSanitizer.stripMinecraftMarkup(resolved);
        String content = DiscordTemplates.apply(resolved, Map.of(
                "advancement", DiscordSanitizer.forDiscord(advancementName),
                "description", DiscordSanitizer.forDiscord(
                        advancementDescription != null ? advancementDescription : "")));
        if (content.isBlank()) {
            return;
        }
        service.send(DiscordMessage.plain(DiscordDestination.ADVANCEMENTS, styled(config, "1;33", content), false));
    }

    void relayPlayerDeath(IPlayer player, String deathMessage) {
        DiscordConfigHandler.Config config = service.config();
        if (config == null || !service.isEnabled() || player == null) {
            return;
        }
        if (!Boolean.TRUE.equals(config.notifyPlayerDeath.get())) {
            return;
        }
        if (deathMessage == null || deathMessage.isBlank()) {
            return;
        }

        IPlatformAdapter platform = services.getPlatformAdapter();
        String resolved = platform != null
                ? platform.replacePlaceholders(config.deathFormat.get(), player)
                : config.deathFormat.get();
        resolved = DiscordSanitizer.stripMinecraftMarkup(resolved);
        String content = DiscordTemplates.apply(resolved, Map.of(
                "message", DiscordSanitizer.forDiscord(deathMessage)));
        if (content.isBlank()) {
            return;
        }
        service.send(DiscordMessage.plain(DiscordDestination.DEATHS, styled(config, "31", content), false));
    }

    void relayCommand(IPlayer player, String command) {
        DiscordConfigHandler.Config config = service.config();
        if (config == null || !service.isEnabled() || command == null || command.isBlank()) {
            return;
        }
        if (DiscordDestination.COMMAND_LOG.channelId(config).isBlank()) {
            return;
        }
        String root = commandRoot(command);
        if (!isLoggable(config, root)) {
            return;
        }

        String sender = player != null ? player.getName() : "console";
        String template = config.commandLogFormat.get();
        if (template == null || template.isBlank()) {
            template = "`{sender}` ran `/{command}`";
        }
        String content = DiscordTemplates.apply(DiscordSanitizer.stripMinecraftMarkup(template), Map.of(
                "sender", DiscordSanitizer.forDiscord(sender),
                "command", DiscordSanitizer.forDiscord(stripLeadingSlash(command)),
                "command_root", DiscordSanitizer.forDiscord(root)));
        if (content.isBlank()) {
            return;
        }
        service.send(DiscordMessage.plain(DiscordDestination.COMMAND_LOG, content, false));
    }

    static String commandRoot(String command) {
        String text = stripLeadingSlash(command).trim();
        int space = text.indexOf(' ');
        if (space >= 0) {
            text = text.substring(0, space);
        }
        int colon = text.indexOf(':');
        if (colon >= 0 && colon + 1 < text.length()) {
            text = text.substring(colon + 1);
        }
        return text.toLowerCase(java.util.Locale.ROOT);
    }

    private static String stripLeadingSlash(String command) {
        String text = command != null ? command.trim() : "";
        return text.startsWith("/") ? text.substring(1) : text;
    }

    static boolean isLoggable(DiscordConfigHandler.Config config, String root) {
        if (root.isEmpty() || SENSITIVE_COMMANDS.contains(root)) {
            return false;
        }
        java.util.List<String> configured = config.commandLogIgnoredCommands.get();
        boolean listed = false;
        if (configured != null) {
            for (String entry : configured) {
                if (entry != null && commandRoot(entry).equals(root)) {
                    listed = true;
                    break;
                }
            }
        }
        return Boolean.TRUE.equals(config.commandLogWhitelist.get()) ? listed : !listed;
    }

    void relayPlayerChat(IPlayer player, String rawMessage) {
        DiscordConfigHandler.Config config = service.config();
        if (config == null || !service.isEnabled() || !Boolean.TRUE.equals(config.minecraftToDiscordEnabled.get())) {
            return;
        }
        if (player == null || rawMessage == null || rawMessage.isBlank()) {
            return;
        }
        boolean allowMentions = Boolean.TRUE.equals(config.allowDiscordMentions.get());
        if (Boolean.TRUE.equals(config.webhookEnabled.get())) {
            String body = DiscordSanitizer.forDiscord(rawMessage);
            if (body.isBlank()) {
                return;
            }
            service.send(DiscordMessage.identified(DiscordDestination.CHAT, styled(config, "37", body),
                    allowMentions, webhookIdentity(config, player)));
            return;
        }

        String content = renderChatForDiscord(config, player, rawMessage);
        if (content.isBlank()) {
            return;
        }
        service.send(DiscordMessage.plain(DiscordDestination.CHAT, styled(config, "36", content), allowMentions));
    }

    static DiscordIdentity webhookIdentity(DiscordConfigHandler.Config config, IPlayer player) {
        String name = player.getName();
        String uuid = player.getUUID() != null ? player.getUUID() : "";
        String template = config.webhookPlayerAvatarUrl.get();
        String avatar = template != null ? template
                .replace("%name%", name != null ? name : "")
                .replace("%uuid_dashless%", uuid.replace("-", ""))
                .replace("%uuid%", uuid)
                .replace("%randomUUID%", java.util.UUID.randomUUID().toString())
                : "";
        return new DiscordIdentity(DiscordSanitizer.forDiscord(name), avatar);
    }

    String renderChatForDiscord(DiscordConfigHandler.Config config, IPlayer player, String rawMessage) {
        IPlatformAdapter platform = services.getPlatformAdapter();
        String template = config.discordChatFormat.get();
        if (template == null || template.isBlank()) {
            template = "**{player}**: {message}";
        }
        String resolved = platform != null ? platform.replacePlaceholders(template, player) : template;
        resolved = DiscordSanitizer.stripMinecraftMarkup(resolved);
        return DiscordTemplates.apply(resolved, Map.of("message", DiscordSanitizer.forDiscord(rawMessage)));
    }

    private void relayPlayerPresence(IPlayer player, boolean joined) {
        DiscordConfigHandler.Config config = service.config();
        if (config == null || !service.isEnabled() || player == null) {
            return;
        }
        boolean toggled = joined
                ? Boolean.TRUE.equals(config.notifyPlayerJoin.get())
                : Boolean.TRUE.equals(config.notifyPlayerLeave.get());
        if (!toggled) {
            return;
        }

        IPlatformAdapter platform = services.getPlatformAdapter();
        String template = joined ? config.playerJoinFormat.get() : config.playerLeaveFormat.get();
        String resolved = platform != null ? platform.replacePlaceholders(template, player) : template;
        resolved = DiscordSanitizer.stripMinecraftMarkup(resolved);

        int online = onlineCount(platform);

        if (!joined && online > 0) {
            online = online - 1;
        }
        String content = DiscordTemplates.apply(resolved, Map.of(
                "online", Integer.toString(Math.max(0, online)),
                "max", Integer.toString(maxPlayers(platform))));
        service.sendNotification(styled(config, joined ? "1;32" : "1;33", content), joined ? 0x2ECC71 : 0xE67E22,
                joined ? "Player joined" : "Player left");
    }

    private int onlineCount(IPlatformAdapter platform) {
        try {
            return platform != null && platform.getOnlinePlayers() != null ? platform.getOnlinePlayers().size() : 0;
        } catch (RuntimeException unavailable) {
            return 0;
        }
    }

    private int maxPlayers(IPlatformAdapter platform) {
        try {
            return platform != null ? platform.getMaxPlayers() : 0;
        } catch (RuntimeException | AbstractMethodError unavailable) {
            return 0;
        }
    }

    void notifyServerStarted() {
        DiscordConfigHandler.Config config = service.config();
        if (config == null || !service.isEnabled() || !Boolean.TRUE.equals(config.notifyServerStarted.get())) {
            return;
        }
        service.sendServer(styled(config, "1;32", plainNotification(config.serverStartedFormat.get())), 0x2ECC71,
                "Server started");
    }

    void notifyServerStopping() {
        DiscordConfigHandler.Config config = service.config();
        if (config == null || !service.isEnabled() || !Boolean.TRUE.equals(config.notifyServerStopping.get())) {
            return;
        }
        service.sendServer(styled(config, "1;31", plainNotification(config.serverStoppingFormat.get())), 0xE74C3C,
                "Server stopping");
    }

    private String plainNotification(String template) {
        IPlatformAdapter platform = services != null ? services.getPlatformAdapter() : null;
        String resolved = platform != null ? platform.replacePlaceholders(template, null) : template;
        return DiscordSanitizer.stripMinecraftMarkup(resolved);
    }

    @Override
    public void onPunishmentCreated(PunishmentRecord record) {
        DiscordConfigHandler.Config config = service.config();
        if (config == null || !service.isEnabled() || record == null) {
            return;
        }
        if (!moderationToggleEnabled(config, record)) {
            return;
        }

        PunishmentRecord safe = record.withoutSensitiveIp();
        service.sendModeration(styled(config, moderationAnsiColor(safe.type(), false), renderModeration(config, safe, false)),
                moderationColor(safe.type()), actionLabel(safe, false), "punishment:" + safe.punishmentId() + ":created");
    }

    @Override
    public void onPunishmentRevoked(PunishmentRecord record) {
        DiscordConfigHandler.Config config = service.config();
        if (config == null || !service.isEnabled() || record == null) {
            return;
        }
        if (!Boolean.TRUE.equals(config.notifyPunishmentRevoked.get())) {
            return;
        }
        PunishmentRecord safe = record.withoutSensitiveIp();
        service.sendModeration(styled(config, moderationAnsiColor(safe.type(), true), renderModeration(config, safe, true)),
                0x95A5A6, actionLabel(safe, true), "punishment:" + safe.punishmentId() + ":revoked");
    }

    String renderModeration(DiscordConfigHandler.Config config, PunishmentRecord record, boolean revoked) {
        IPlatformAdapter platform = services != null ? services.getPlatformAdapter() : null;
        String template = config.moderationFormat.get();
        String resolved = platform != null ? platform.replacePlaceholders(template, null) : template;
        resolved = DiscordSanitizer.stripMinecraftMarkup(resolved);

        String duration = revoked ? "" : durationLabel(record);
        String reason = revoked ? record.revokeReason() : record.reason();
        String actor = revoked ? record.revokedByName() : record.actorName();

        Map<String, String> tokens = new java.util.HashMap<>();
        tokens.put("icon", revoked ? "" : moderationIcon(record.type()));
        tokens.put("action", actionLabel(record, revoked));
        tokens.put("target", DiscordSanitizer.forDiscord(nonBlank(record.subjectName(), record.subjectUuid(), "unknown")));
        tokens.put("actor", DiscordSanitizer.forDiscord(nonBlank(actor, null, "console")));
        tokens.put("reason", DiscordSanitizer.forDiscord(nonBlank(reason, null, "No reason given")));
        tokens.put("duration", duration);
        tokens.put("duration_suffix", duration.isEmpty() ? "" : " (" + duration + ")");
        tokens.put("punishment_id", DiscordSanitizer.forDiscord(nonBlank(record.punishmentId(), null, "")));
        tokens.put("expiry", expiryLabel(record));
        tokens.put("scope", record.scope() != null ? record.scope().name() : "");
        return DiscordTemplates.apply(resolved, tokens);
    }

    @Override
    public void onRestartScheduled(long restartAtEpochMs) {
        DiscordConfigHandler.Config config = service.config();
        if (config == null || !service.isEnabled() || !Boolean.TRUE.equals(config.notifyRestartScheduled.get())) {
            return;
        }
        long seconds = Math.max(0L, (restartAtEpochMs - System.currentTimeMillis()) / 1000L);
        service.sendServer(styled(config, "34", renderRestart(config.restartScheduledFormat.get(), seconds)), 0x3498DB,
                "Restart scheduled");
    }

    @Override
    public void onRestartCancelled() {
        DiscordConfigHandler.Config config = service.config();
        if (config == null || !service.isEnabled() || !Boolean.TRUE.equals(config.notifyRestartCancelled.get())) {
            return;
        }
        service.sendServer(styled(config, "2;37", renderRestart(config.restartCancelledFormat.get(), 0L)), 0x95A5A6,
                "Restart cancelled");
    }

    @Override
    public void onRestartCountdown(long secondsRemaining) {
        DiscordConfigHandler.Config config = service.config();
        if (config == null || !service.isEnabled() || !Boolean.TRUE.equals(config.notifyRestartCountdown.get())) {
            return;
        }
        Integer threshold = config.countdownAnnounceSeconds.get();
        if (threshold != null && threshold > 0 && secondsRemaining > threshold) {
            return;
        }
        service.sendServer(styled(config, "1;33", renderRestart(config.restartCountdownFormat.get(), secondsRemaining)),
                0xF1C40F, "Restart countdown");
    }

    @Override
    public void onRestartImminent() {
        DiscordConfigHandler.Config config = service.config();
        if (config == null || !service.isEnabled() || !Boolean.TRUE.equals(config.notifyRestartImminent.get())) {
            return;
        }
        service.sendServer(styled(config, "1;31", renderRestart(config.restartImminentFormat.get(), 0L)), 0xE67E22,
                "Restart imminent");
    }

    private String renderRestart(String template, long seconds) {
        IPlatformAdapter platform = services != null ? services.getPlatformAdapter() : null;
        String resolved = platform != null ? platform.replacePlaceholders(template, null) : template;
        resolved = DiscordSanitizer.stripMinecraftMarkup(resolved);
        return DiscordTemplates.apply(resolved, Map.of(
                "time", DiscordTemplates.formatDuration(seconds),
                "seconds", Long.toString(Math.max(0L, seconds))));
    }

    public void handleInbound(DiscordInboundMessage message) {
        DiscordConfigHandler.Config config = service.config();
        if (config == null || !service.isEnabled() || message == null) {
            return;
        }
        if (!Boolean.TRUE.equals(config.discordToMinecraftEnabled.get())) {
            return;
        }
        if (!isRelayableAuthor(config, message)) {
            return;
        }
        if (!matchesRelayChannel(config, message)) {
            return;
        }
        if (!processedMessageIds.add(message.messageId())) {
            return;
        }

        boolean showAttachments = Boolean.TRUE.equals(config.showAttachments.get());
        String content = DiscordSanitizer.resolveMentions(message.content(), message.mentionNames());
        content = DiscordSanitizer.describeAttachments(content, message.attachmentUrls(), showAttachments);
        boolean hasText = content != null && !content.isBlank();
        if (!hasText && !(showAttachments && message.hasRichContent())) {
            return;
        }

        String body = hasText ? DiscordSanitizer.forMinecraft(content) : "";
        String name = DiscordSanitizer.forMinecraft(message.authorDisplayName());
        String colored = colorize(name, message.authorColorRgb(), config);
        String template;
        Map<String, String> tokens;
        if (message.hasReply()) {
            template = config.minecraftReplyFormat.get();
            if (template == null || template.isBlank()) {
                template = DEFAULT_REPLY_FORMAT;
            }
            String quoted = DiscordSanitizer.resolveMentions(message.replyContent(), message.mentionNames());
            tokens = Map.of(
                    "name", colored,
                    "message", body,
                    "reply_name", DiscordSanitizer.forMinecraft(message.replyAuthorName()),
                    "reply_message", DiscordSanitizer.truncate(
                            DiscordSanitizer.forMinecraft(quoted), REPLY_QUOTE_LENGTH));
        } else {
            template = config.minecraftChatFormat.get();
            if (template == null || template.isBlank()) {
                template = DEFAULT_CHAT_FORMAT;
            }
            tokens = Map.of("name", colored, "message", body);
        }
        String formatted = DiscordTemplates.apply(template, tokens);

        ComponentSlots.Builder slotsBuilder = ComponentSlots.builder();
        String extras = showAttachments ? renderRichExtras(message, slotsBuilder) : "";
        ComponentSlots slots = slotsBuilder.build();
        String marked = slots.mark(formatted + extras);

        rememberRelayedAuthor(message);
        broadcast(marked, slots);
    }

    public void handleInboundEdit(DiscordInboundMessage message) {
        DiscordConfigHandler.Config config = service.config();
        if (config == null || !service.isEnabled() || message == null) {
            return;
        }
        if (!Boolean.TRUE.equals(config.discordToMinecraftEnabled.get())
                || !Boolean.TRUE.equals(config.notifyDiscordEdits.get())) {
            return;
        }
        if (!isRelayableAuthor(config, message) || !matchesRelayChannel(config, message)) {
            return;
        }

        String content = DiscordSanitizer.resolveMentions(message.content(), message.mentionNames());
        if (content == null || content.isBlank()) {
            return;
        }

        rememberRelayedAuthor(message);

        String template = config.minecraftEditFormat.get();
        if (template == null || template.isBlank()) {
            template = DEFAULT_EDIT_FORMAT;
        }
        String formatted = DiscordTemplates.apply(template, Map.of(
                "name", DiscordSanitizer.forMinecraft(message.authorDisplayName()),
                "message", DiscordSanitizer.forMinecraft(content)));
        broadcast(formatted);
    }

    public void handleInboundDelete(String messageId, String channelId) {
        DiscordConfigHandler.Config config = service.config();
        if (config == null || !service.isEnabled() || messageId == null) {
            return;
        }
        if (!Boolean.TRUE.equals(config.discordToMinecraftEnabled.get())
                || !Boolean.TRUE.equals(config.notifyDiscordDeletes.get())) {
            return;
        }
        String chatChannel = DiscordDestination.CHAT.channelId(config);
        if (chatChannel.isBlank() || !chatChannel.equals(channelId)) {
            return;
        }

        String author = relayedAuthors.remove(messageId);
        if (author == null) {
            return;
        }

        String template = config.minecraftDeleteFormat.get();
        if (template == null || template.isBlank()) {
            template = DEFAULT_DELETE_FORMAT;
        }
        String formatted = DiscordTemplates.apply(template,
                Map.of("name", DiscordSanitizer.forMinecraft(author)));
        broadcast(formatted);
    }

    public void handleConsoleCommand(DiscordInboundMessage message) {
        if (message == null) {
            return;
        }
        if (!authorizeConsoleCommand(message.channelId(), message.bot(), message.webhook(), message.system())) {
            return;
        }
        dispatchConsoleCommand(message.authorId(), message.authorDisplayName(), message.content());
    }

    public boolean ownsConsoleChannel(String channelId) {
        DiscordConfigHandler.Config config = service.config();
        if (config == null) {
            return false;
        }
        String consoleChannel = DiscordDestination.CONSOLE.channelId(config);
        return !consoleChannel.isBlank() && consoleChannel.equals(channelId);
    }

    public boolean authorizeConsoleCommand(String channelId, boolean bot, boolean webhook, boolean system) {
        DiscordConfigHandler.Config config = service.config();
        if (config == null || !service.isEnabled()) {
            return false;
        }
        if (!Boolean.TRUE.equals(config.allowConsoleCommands.get())) {
            return false;
        }
        if (!ownsConsoleChannel(channelId)) {
            return false;
        }
        return !bot && !webhook && !system;
    }

    public void dispatchConsoleCommand(String authorId, String authorName, String rawCommand) {
        String command = rawCommand != null ? rawCommand.trim() : "";
        if (command.startsWith("/")) {
            command = command.substring(1).trim();
        }
        if (command.isEmpty()) {
            return;
        }

        IPlatformAdapter platform = services.getPlatformAdapter();
        if (platform == null) {
            return;
        }
        String finalCommand = command;
        platform.executeOnServerThread(() -> {
            try {
                platform.executeCommandAsConsole(finalCommand);
            } catch (RuntimeException failure) {
                services.getDebugLogger().debugLog("[Discord] Console command execution failed: "
                        + failure.getClass().getSimpleName());
            }
        });
        service.auditConsoleCommand(authorId, authorName, command);
    }

    private void rememberRelayedAuthor(DiscordInboundMessage message) {
        String id = message.messageId();
        String author = message.authorDisplayName();
        if (id != null && author != null && !author.isBlank()) {
            relayedAuthors.put(id, author);
        }
    }

    private String colorize(String sanitizedName, Integer colorRgb, DiscordConfigHandler.Config config) {
        if (colorRgb == null || !Boolean.TRUE.equals(config.discordRoleColorIngame.get())) {
            return sanitizedName;
        }
        return String.format("<color:#%06X>%s</color>", colorRgb & 0xFFFFFF, sanitizedName);
    }

    private String renderRichExtras(DiscordInboundMessage message, ComponentSlots.Builder slotsBuilder) {
        StringBuilder text = new StringBuilder();

        java.util.List<DiscordInboundMessage.Attachment> attachments = message.attachments();
        for (int index = 0; index < attachments.size(); index++) {
            DiscordInboundMessage.Attachment attachment = attachments.get(index);
            if (attachment.url() == null || attachment.url().isBlank()) {
                continue;
            }
            String token = ATTACHMENT_TOKEN_PREFIX + index;
            String linkText = "📎 " + DiscordSanitizer.forMinecraft(attachment.filename());
            String url = attachment.url();
            slotsBuilder.add(token, style -> renderAttachmentLink(linkText, url, style));
            text.append(index == 0 ? "\n" : "  ").append(token);
        }

        for (DiscordInboundMessage.EmbedSummary embed : message.embeds()) {
            String line = renderEmbedLine(embed);
            if (!line.isEmpty()) {
                text.append(line);
            }
        }

        for (String sticker : message.stickerNames()) {
            text.append("\n<color:dark_gray><italic>Sticker: ")
                    .append(DiscordSanitizer.forMinecraft(sticker))
                    .append("</italic></color>");
        }

        return text.toString();
    }

    private IComponent renderAttachmentLink(String linkText, String url, Object surroundingStyle) {
        IPlatformAdapter platform = services.getPlatformAdapter();
        MessageParser parser = services.getMessageParser();
        Object style = platform.createStyleWithClickEvent(surroundingStyle, "open_url", url);
        style = platform.createStyleWithHoverEvent(style, url);
        return parser.parseNested(linkText, null, style);
    }

    private String renderEmbedLine(DiscordInboundMessage.EmbedSummary embed) {
        if (embed.isEmpty()) {
            return "";
        }
        StringBuilder line = new StringBuilder("\n<color:dark_gray>┃</color> ");
        boolean any = false;
        if (embed.authorName() != null && !embed.authorName().isBlank()) {
            line.append("<bold>").append(DiscordSanitizer.forMinecraft(embed.authorName())).append("</bold> ");
            any = true;
        }
        if (embed.title() != null && !embed.title().isBlank()) {
            line.append(DiscordSanitizer.forMinecraft(embed.title()));
            any = true;
        }
        if (embed.description() != null && !embed.description().isBlank()) {
            if (any) {
                line.append(": ");
            }
            line.append(DiscordSanitizer.truncate(
                    DiscordSanitizer.forMinecraft(embed.description()), EMBED_DESCRIPTION_LENGTH));
            any = true;
        }
        return any ? line.toString() : "";
    }

    boolean isRelayableAuthor(DiscordConfigHandler.Config config, DiscordInboundMessage message) {
        if (message.authorId() != null && message.authorId().equals(service.botUserId())) {
            return false;
        }
        if (message.system()) {
            return false;
        }
        if (message.webhook()) {
            return !service.isOwnWebhook(message.authorId())
                    && Boolean.TRUE.equals(config.allowWebhookMessages.get());
        }
        return !message.bot() || Boolean.TRUE.equals(config.allowOtherBots.get());
    }

    boolean matchesRelayChannel(DiscordConfigHandler.Config config, DiscordInboundMessage message) {
        String chatChannel = DiscordDestination.CHAT.channelId(config);
        if (chatChannel.isBlank() || !chatChannel.equals(message.channelId())) {
            return false;
        }
        String guildId = config.guildId.get();
        if (guildId != null && !guildId.isBlank() && message.guildId() != null) {
            return guildId.trim().equals(message.guildId());
        }
        return true;
    }

    private void broadcast(String formatted) {
        broadcast(formatted, ComponentSlots.none());
    }

    private void broadcast(String formatted, ComponentSlots slots) {
        IPlatformAdapter platform = services.getPlatformAdapter();
        if (platform == null) {
            return;
        }
        platform.executeOnServerThread(() -> {
            try {
                platform.broadcastSystemMessage(services.getMessageParser().parseMessage(formatted, null, slots));
            } catch (RuntimeException failure) {
                services.getDebugLogger().debugLog("[Discord] Could not broadcast a relayed message: "
                        + failure.getClass().getSimpleName());
            }
        });
    }

    public void reset() {
        processedMessageIds.clear();
        relayedAuthors.clear();
    }

    static boolean moderationToggleEnabled(DiscordConfigHandler.Config config, PunishmentRecord record) {
        boolean temporary = record.expiresAtMs() != null;
        return switch (record.type()) {
            case BAN, IP_BAN -> temporary
                    ? Boolean.TRUE.equals(config.notifyTempban.get())
                    : Boolean.TRUE.equals(config.notifyBan.get());
            case MUTE -> temporary
                    ? Boolean.TRUE.equals(config.notifyTempmute.get())
                    : Boolean.TRUE.equals(config.notifyMute.get());
            case WARN -> Boolean.TRUE.equals(config.notifyWarn.get());
            case JAIL -> Boolean.TRUE.equals(config.notifyJail.get());
        };
    }

    static String actionLabel(PunishmentRecord record, boolean revoked) {
        if (revoked) {
            return switch (record.type()) {
                case BAN -> "Unban";
                case IP_BAN -> "IP Unban";
                case MUTE -> "Unmute";
                case JAIL -> "Unjail";
                case WARN -> "Warning revoked";
            };
        }
        boolean temporary = record.expiresAtMs() != null;
        return switch (record.type()) {
            case BAN -> temporary ? "Temp Ban" : "Ban";
            case IP_BAN -> temporary ? "Temp IP Ban" : "IP Ban";
            case MUTE -> temporary ? "Temp Mute" : "Mute";
            case WARN -> "Warn";
            case JAIL -> "Jail";
        };
    }

    static String moderationIcon(PunishmentType type) {
        return "";
    }

    static int moderationColor(PunishmentType type) {
        return switch (type) {
            case BAN, IP_BAN -> 0xE74C3C;
            case MUTE -> 0xE67E22;
            case WARN -> 0xF1C40F;
            case JAIL -> 0x9B59B6;
        };
    }

    static String moderationAnsiColor(PunishmentType type, boolean revoked) {
        if (revoked) {
            return "1;32";
        }
        return switch (type) {
            case BAN, IP_BAN -> "1;31";
            case MUTE -> "1;35";
            case WARN -> "1;33";
            case JAIL -> "1;36";
        };
    }

    private static String durationLabel(PunishmentRecord record) {
        Long expires = record.expiresAtMs();
        if (expires == null) {
            return "";
        }
        long seconds = Math.max(0L, (expires - System.currentTimeMillis()) / 1000L);
        return DiscordTemplates.formatDuration(seconds);
    }

    private static String expiryLabel(PunishmentRecord record) {
        Long expires = record.expiresAtMs();
        return expires == null ? "never" : "<t:" + (expires / 1000L) + ":R>";
    }

    private static String nonBlank(String primary, String secondary, String fallback) {
        if (primary != null && !primary.isBlank()) {
            return primary;
        }
        if (secondary != null && !secondary.isBlank()) {
            return secondary;
        }
        return fallback;
    }
}
