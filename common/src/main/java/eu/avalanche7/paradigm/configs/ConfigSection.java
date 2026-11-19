package eu.avalanche7.paradigm.configs;

import java.util.Locale;
import java.util.function.Supplier;

import eu.avalanche7.paradigm.configs.schema.ConfigReloadBehavior;

public enum ConfigSection {
    MAIN(MainConfigHandler::handler),
    CHAT(ChatConfigHandler::handler),
    MENTIONS(MentionConfigHandler::handler),
    ANNOUNCEMENTS(AnnouncementsConfigHandler::handler),
    RESTART(RestartConfigHandler::handler),
    MOTD(MOTDConfigHandler::handler),
    MODERATION(ModerationConfigHandler::handler),
    AFK(AfkConfigHandler::handler),
    TABLIST(TablistConfigHandler::handler),
    DISCORD(DiscordConfigHandler::handler),
    TICKETS(TicketsConfigHandler::handler),
    EMOJI(EmojiConfigHandler::handler),
    COOLDOWNS(CooldownConfigHandler::handler),
    COMMANDS(null),
    CUSTOMCOMMANDS(null),
    MENUS(null),
    HOLOGRAMS(null),
    DASHBOARD(null);

    private final Supplier<BaseConfigHandler<?>> handler;

    ConfigSection(Supplier<BaseConfigHandler<?>> handler) {
        this.handler = handler;
    }

    public BaseConfigHandler<?> handler() {
        return handler == null ? null : handler.get();
    }

    public String key() {
        return name().toLowerCase(Locale.ROOT);
    }

    public static ConfigSection forName(String name) {
        return valueOf(switch (name) {
            case "mention" -> "MENTIONS";
            case "emojis" -> "EMOJI";
            case "custom_commands" -> "CUSTOMCOMMANDS";
            default -> name.toUpperCase(Locale.ROOT);
        });
    }

    public static ConfigReloadBehavior behavior(String key) {
        return switch (key) {
            case "main.registerForgePermissionHandler", "main.telemetryEnable",
                    "main.telemetryIntervalSeconds", "main.telemetryServerId" -> ConfigReloadBehavior.RESTART_REQUIRED;
            default -> key.startsWith("dashboard.") ? ConfigReloadBehavior.RELOAD_REQUIRED : ConfigReloadBehavior.LIVE;
        };
    }
}
