package eu.avalanche7.paradigm.configs;

import java.util.List;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import eu.avalanche7.paradigm.ParadigmConstants;
import eu.avalanche7.paradigm.platform.Interfaces.IConfig;
import eu.avalanche7.paradigm.utils.DebugLogger;

public class DiscordConfigHandler extends BaseConfigHandler<DiscordConfigHandler.Config> {
    private static final Logger LOGGER = LoggerFactory.getLogger(ParadigmConstants.MOD_ID);
    private static DiscordConfigHandler INSTANCE;
    private Config config;

    private DiscordConfigHandler(IConfig platformConfig) {
        super(LOGGER, platformConfig, "discord.json");
    }

    public static void init(IConfig platformConfig, DebugLogger debugLogger) {
        if (INSTANCE == null) {
            synchronized (DiscordConfigHandler.class) {
                if (INSTANCE == null) {
                    INSTANCE = new DiscordConfigHandler(platformConfig);
                    INSTANCE.setJsonValidator(debugLogger);
                    INSTANCE.config = INSTANCE.load();
                }
            }
        }
    }

    public static Config getConfig() {
        if (INSTANCE == null) {
            throw new IllegalStateException("DiscordConfigHandler not initialized! Call init() first.");
        }
        return INSTANCE.config;
    }

    public static boolean isInitialized() {
        return INSTANCE != null;
    }

    public static void reload() {
        if (INSTANCE != null) {
            INSTANCE.config = INSTANCE.load();
        }
    }

    public static void persistConfig() {
        if (INSTANCE != null && INSTANCE.config != null) {
            INSTANCE.save(INSTANCE.config);
        }
    }

    @Override
    protected Config createDefaultConfig() {
        return new Config();
    }

    @Override
    protected Class<Config> getConfigClass() {
        return Config.class;
    }

    public static class Config {
        public ConfigEntry<Boolean> enabled = new ConfigEntry<>(
                false,
                "Enable or disable the Discord integration. Requires a bot token and at least one channel ID."
        );

        public ConfigEntry<String> botToken = new ConfigEntry<>(
                "",
                "Discord bot token. Keep this secret; it is never shown in the dashboard or written to logs."
        );

        public ConfigEntry<String> guildId = new ConfigEntry<>(
                "",
                "Discord guild (server) ID. Messages from other guilds are ignored."
        );

        public ConfigEntry<String> chatChannelId = new ConfigEntry<>(
                "",
                "Channel ID used for the Minecraft chat relay in both directions."
        );

        public ConfigEntry<String> moderationChannelId = new ConfigEntry<>(
                "",
                "Optional channel ID for moderation notifications. Empty falls back to the chat channel."
        );

        public ConfigEntry<String> notificationChannelId = new ConfigEntry<>(
                "",
                "Optional channel ID for server/player/restart notifications. Empty falls back to the chat channel."
        );

        public ConfigEntry<String> serverChannelId = new ConfigEntry<>(
                "",
                "Optional channel ID for server start/stop and restart messages. Empty falls back to the notification channel."
        );

        public ConfigEntry<String> deathsChannelId = new ConfigEntry<>(
                "",
                "Optional channel ID for death messages. Empty falls back to the notification channel."
        );

        public ConfigEntry<String> advancementsChannelId = new ConfigEntry<>(
                "",
                "Optional channel ID for advancement messages. Empty falls back to the notification channel."
        );

        public ConfigEntry<String> commandLogChannelId = new ConfigEntry<>(
                "",
                "Channel ID for the command log. Empty disables the command log entirely; unlike the other channels "
                        + "this never falls back, so an unset ID can never leak executed commands into public chat."
        );

        public ConfigEntry<Boolean> minecraftToDiscordEnabled = new ConfigEntry<>(
                true,
                "Relay public Minecraft chat into the Discord chat channel. Staff and group chat are never relayed."
        );

        public ConfigEntry<Boolean> discordToMinecraftEnabled = new ConfigEntry<>(
                true,
                "Relay messages from the Discord chat channel into Minecraft chat."
        );

        public ConfigEntry<Boolean> allowOtherBots = new ConfigEntry<>(
                false,
                "Relay messages authored by other Discord bots. Paradigm's own messages are always ignored."
        );

        public ConfigEntry<Boolean> allowDiscordMentions = new ConfigEntry<>(
                false,
                "Allow Minecraft players to ping Discord users and roles. Disabled means mentions are rendered as text only."
        );

        public ConfigEntry<Boolean> showAttachments = new ConfigEntry<>(
                true,
                "Render attachments, embeds and stickers from Discord messages as clickable links and extra lines "
                        + "under the relayed Minecraft message."
        );

        public ConfigEntry<Boolean> discordRoleColorIngame = new ConfigEntry<>(
                true,
                "Color the author's name in-game using their highest colored Discord role."
        );

        public ConfigEntry<String> discordChatFormat = new ConfigEntry<>(
                "**{player}**: {message}",
                "Discord-side chat format. Discord markdown in this template is preserved; relayed player text is escaped. "
                        + "Feature tokens: {player}, {message}. Paradigm player placeholders such as {prefix}, {suffix} and {group} also work."
        );

        public ConfigEntry<String> minecraftChatFormat = new ConfigEntry<>(
                "<color:dark_gray>[</color><color:blue>Discord</color><color:dark_gray>]</color> "
                        + "<color:aqua>{name}</color> <color:dark_gray>»</color> <color:white>{message}</color>",
                "Minecraft-side chat format for relayed Discord messages. Supports the same TAG formatting as "
                        + "chat.customChatFormat, for example <color:aqua>text</color> and <bold>text</bold>. "
                        + "Tokens: {name}, {message}."
        );

        public ConfigEntry<Boolean> webhookEnabled = new ConfigEntry<>(
                false,
                "Relay player chat through a channel webhook so each message shows the player's own name and skin "
                        + "instead of the bot identity. Requires the Manage Webhooks permission in the chat channel. "
                        + "Note that the avatar URL below sends player names or UUIDs to a third-party skin service."
        );

        public ConfigEntry<String> webhookName = new ConfigEntry<>(
                "Paradigm",
                "Name of the webhook Paradigm creates and reuses. Paradigm only adopts a webhook that it owns, "
                        + "so a webhook created by someone else with this name is never touched."
        );

        public ConfigEntry<String> webhookPlayerAvatarUrl = new ConfigEntry<>(
                "https://minotar.net/avatar/%uuid%?randomuuid=%randomUUID%",
                "Avatar image URL used for relayed player chat in webhook mode. "
                        + "Tokens: %name%, %uuid%, %uuid_dashless%, %randomUUID%."
        );

        public ConfigEntry<Boolean> allowWebhookMessages = new ConfigEntry<>(
                false,
                "Relay messages posted by other webhooks into Minecraft. Paradigm's own webhook is always ignored."
        );

        public ConfigEntry<Boolean> presenceEnabled = new ConfigEntry<>(
                true,
                "Show a bot status such as the online player count in Discord."
        );

        public ConfigEntry<String> presenceType = new ConfigEntry<>(
                "custom",
                "Bot status type: custom, playing, watching, listening or competing."
        );

        public ConfigEntry<Integer> presenceUpdateSeconds = new ConfigEntry<>(
                60,
                "How often the bot status is refreshed, in seconds. Discord only allows a few presence updates per "
                        + "minute, and unchanged statuses are never re-sent."
        );

        public ConfigEntry<String> presenceFormat = new ConfigEntry<>(
                "{online}/{max} players online",
                "Bot status text. Feature tokens: {online}, {max}."
        );

        public ConfigEntry<String> presenceFormatSingular = new ConfigEntry<>(
                "{online}/{max} player online",
                "Bot status text used when exactly one player is online. Empty uses presenceFormat."
        );

        public ConfigEntry<String> presenceFormatEmpty = new ConfigEntry<>(
                "Nobody is online",
                "Bot status text used when nobody is online. Empty uses presenceFormat."
        );

        public ConfigEntry<Boolean> notifyPlayerDeath = new ConfigEntry<>(
                false,
                "Send a Discord message when a player dies."
        );

        public ConfigEntry<String> deathFormat = new ConfigEntry<>(
                "{message}",
                "Discord message for player deaths. Feature tokens: {player}, {message}."
        );

        public ConfigEntry<Boolean> notifyAdvancement = new ConfigEntry<>(
                false,
                "Send a Discord message when a player earns an advancement that Minecraft would announce to chat."
        );

        public ConfigEntry<String> advancementFormat = new ConfigEntry<>(
                "**{player}** has made the advancement **{advancement}**\n_{description}_",
                "Discord message for advancements. Feature tokens: {player}, {advancement}, {description}."
        );

        public ConfigEntry<String> commandLogFormat = new ConfigEntry<>(
                "`{sender}` ran `/{command}`",
                "Command log message. Feature tokens: {sender}, {command}, {command_root}."
        );

        public ConfigEntry<List<String>> commandLogIgnoredCommands = new ConfigEntry<>(
                List.of("list", "help", "?"),
                "Command roots that are not logged. Namespaces are ignored, so 'tell' also matches 'minecraft:tell'. "
                        + "Commands that can carry passwords or private messages are always excluded regardless of "
                        + "this list and of commandLogWhitelist."
        );

        public ConfigEntry<Boolean> commandLogWhitelist = new ConfigEntry<>(
                false,
                "Invert commandLogIgnoredCommands so that it becomes the only list of commands that are logged."
        );

        public ConfigEntry<String> minecraftReplyFormat = new ConfigEntry<>(
                "<color:dark_gray>[</color><color:blue>Discord</color><color:dark_gray>]</color> "
                        + "<color:aqua>{name}</color> <color:dark_gray>replying to</color> "
                        + "<color:aqua>{reply_name}</color> <color:dark_gray>»</color> <color:white>{message}</color>",
                "Minecraft-side format for a Discord message that replies to another message. Supports the same TAG "
                        + "formatting as minecraftChatFormat. Tokens: {name}, {message}, {reply_name}, {reply_message}."
        );

        public ConfigEntry<Boolean> notifyDiscordEdits = new ConfigEntry<>(
                true,
                "Relay it to Minecraft as a new line when a relayed Discord message is edited. "
                        + "Minecraft cannot edit a line that was already sent."
        );

        public ConfigEntry<String> minecraftEditFormat = new ConfigEntry<>(
                "<color:dark_gray>[</color><color:blue>Discord</color><color:dark_gray>]</color> "
                        + "<color:aqua>{name}</color> <color:dark_gray><italic>edited:</italic></color> "
                        + "<color:white>{message}</color>",
                "Minecraft-side format for an edited Discord message. Supports the same TAG formatting as "
                        + "minecraftChatFormat. Tokens: {name}, {message}."
        );

        public ConfigEntry<Boolean> notifyDiscordDeletes = new ConfigEntry<>(
                true,
                "Relay it to Minecraft when a relayed Discord message is deleted. The original content is never "
                        + "shown again, only that it was deleted."
        );

        public ConfigEntry<String> minecraftDeleteFormat = new ConfigEntry<>(
                "<color:dark_gray>[</color><color:blue>Discord</color><color:dark_gray>]</color> "
                        + "<color:gray><italic>{name} deleted their message</italic></color>",
                "Minecraft-side format shown when a relayed Discord message is deleted. Tokens: {name}."
        );

        public ConfigEntry<String> serverStartedFormat = new ConfigEntry<>(
                "**Server started**",
                "Discord message sent when the server has finished starting."
        );

        public ConfigEntry<String> serverStoppingFormat = new ConfigEntry<>(
                "**Server stopping**",
                "Discord message sent when the server begins shutting down."
        );

        public ConfigEntry<String> playerJoinFormat = new ConfigEntry<>(
                "**{player}** joined the server ({online}/{max})",
                "Discord message for player joins. Feature tokens: {player}, {online}, {max}."
        );

        public ConfigEntry<String> playerLeaveFormat = new ConfigEntry<>(
                "**{player}** left the server ({online}/{max})",
                "Discord message for player leaves. Feature tokens: {player}, {online}, {max}."
        );

        public ConfigEntry<String> moderationFormat = new ConfigEntry<>(
                "**{action}** — **{target}** by **{actor}**{duration_suffix}\nReason: {reason}",
                "Discord message for moderation actions. Feature tokens: {icon}, {action}, {target}, {actor}, "
                        + "{reason}, {duration}, {duration_suffix}, {punishment_id}, {expiry}."
        );

        public ConfigEntry<String> restartScheduledFormat = new ConfigEntry<>(
                "**Restart scheduled** in {time}",
                "Discord message when a restart sequence starts. Feature tokens: {time}, {seconds}."
        );

        public ConfigEntry<String> restartCancelledFormat = new ConfigEntry<>(
                "**Restart cancelled**",
                "Discord message when a scheduled restart is cancelled."
        );

        public ConfigEntry<String> restartCountdownFormat = new ConfigEntry<>(
                "Restarting in **{time}**",
                "Discord message for restart countdown warnings. Feature tokens: {time}, {seconds}."
        );

        public ConfigEntry<String> restartImminentFormat = new ConfigEntry<>(
                "**Server is restarting now**",
                "Discord message sent immediately before the server restarts."
        );

        public ConfigEntry<Boolean> notifyServerStarted = new ConfigEntry<>(
                true,
                "Send a Discord notification when the server has started."
        );

        public ConfigEntry<Boolean> notifyServerStopping = new ConfigEntry<>(
                true,
                "Send a Discord notification when the server is stopping."
        );

        public ConfigEntry<Boolean> notifyPlayerJoin = new ConfigEntry<>(
                true,
                "Send a Discord notification when a player joins."
        );

        public ConfigEntry<Boolean> notifyPlayerLeave = new ConfigEntry<>(
                true,
                "Send a Discord notification when a player leaves."
        );

        public ConfigEntry<Boolean> notifyBan = new ConfigEntry<>(
                true,
                "Send a Discord notification for permanent bans (including IP bans)."
        );

        public ConfigEntry<Boolean> notifyTempban = new ConfigEntry<>(
                true,
                "Send a Discord notification for temporary bans."
        );

        public ConfigEntry<Boolean> notifyMute = new ConfigEntry<>(
                true,
                "Send a Discord notification for permanent mutes."
        );

        public ConfigEntry<Boolean> notifyTempmute = new ConfigEntry<>(
                true,
                "Send a Discord notification for temporary mutes."
        );

        public ConfigEntry<Boolean> notifyWarn = new ConfigEntry<>(
                true,
                "Send a Discord notification for warnings."
        );

        public ConfigEntry<Boolean> notifyJail = new ConfigEntry<>(
                true,
                "Send a Discord notification for jail punishments."
        );

        public ConfigEntry<Boolean> notifyPunishmentRevoked = new ConfigEntry<>(
                true,
                "Send a Discord notification when a punishment is revoked (unban, unmute, unjail)."
        );

        public ConfigEntry<Boolean> notifyRestartScheduled = new ConfigEntry<>(
                true,
                "Send a Discord notification when a restart sequence is scheduled."
        );

        public ConfigEntry<Boolean> notifyRestartCancelled = new ConfigEntry<>(
                true,
                "Send a Discord notification when a scheduled restart is cancelled."
        );

        public ConfigEntry<Boolean> notifyRestartCountdown = new ConfigEntry<>(
                false,
                "Send a Discord notification for each restart countdown warning. Can be noisy."
        );

        public ConfigEntry<Boolean> notifyRestartImminent = new ConfigEntry<>(
                true,
                "Send a Discord notification immediately before the server restarts."
        );

        public ConfigEntry<Integer> outboundQueueSize = new ConfigEntry<>(
                500,
                "Maximum number of queued outbound Discord messages. Further messages are dropped rather than blocking the server."
        );

        public ConfigEntry<Boolean> useEmbeds = new ConfigEntry<>(
                false,
                "Render notification messages as simple Discord embeds instead of plain messages. Chat relay always uses plain messages."
        );

        public ConfigEntry<Boolean> useAnsiColors = new ConfigEntry<>(
                true,
                "Render notifications and relayed player chat as colorized text in a Discord code block, matching "
                        + "the console channel's look. Discord does not parse @mentions inside code blocks, so "
                        + "turning this on means allowDiscordMentions stops actually pinging anyone in chat."
        );

        public ConfigEntry<Integer> shutdownFlushMillis = new ConfigEntry<>(
                3000,
                "How long shutdown waits for queued Discord messages to flush, in milliseconds. Kept well below the 30s shutdown watchdog."
        );

        public ConfigEntry<Integer> countdownAnnounceSeconds = new ConfigEntry<>(
                300,
                "Only restart countdown warnings at or below this many seconds are sent to Discord."
        );

        public ConfigEntry<String> consoleChannelId = new ConfigEntry<>(
                "",
                "Channel ID for the console channel. Empty disables it entirely; unlike the other channels this "
                        + "never falls back, so an unset ID can never leak console output or accept commands."
        );

        public ConfigEntry<Boolean> notifyConsoleLog = new ConfigEntry<>(
                false,
                "Relay server console/log output into the console channel."
        );

        public ConfigEntry<String> consoleLogMinimumLevel = new ConfigEntry<>(
                "INFO",
                "Lowest log level relayed to the console channel: TRACE, DEBUG, INFO, WARN or ERROR. INFO matches "
                        + "what the server console shows by default; raise it only to cut down on noise."
        );

        public ConfigEntry<Integer> consoleLogFlushSeconds = new ConfigEntry<>(
                3,
                "How often buffered console lines are flushed to Discord, in seconds."
        );

        public ConfigEntry<List<String>> consoleLogIgnoredPatterns = new ConfigEntry<>(
                List.of(),
                "Regular expressions. Log lines matching any of these are never relayed to the console channel."
        );

        public ConfigEntry<Boolean> allowConsoleCommands = new ConfigEntry<>(
                false,
                "Let messages posted in the console channel run as server console commands. Anyone who can post in "
                        + "that channel can run commands; use Discord's own channel permissions to restrict who that is."
        );
    }
}
