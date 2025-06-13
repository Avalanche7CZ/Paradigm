package eu.avalanche7.paradigm.configs;

import net.minecraftforge.common.config.Configuration;

public class MainConfigHandler {
    private static Configuration mainConfig;

    public static boolean ANNOUNCEMENTS_ENABLE;
    public static boolean MOTD_ENABLE;
    public static boolean MENTIONS_ENABLE;
    public static boolean RESTART_ENABLE;
    public static boolean DEBUG_ENABLE;

    public static void init(Configuration config) {
        mainConfig = config;
        loadConfig();
    }

    private static void loadConfig() {
        ANNOUNCEMENTS_ENABLE = mainConfig.getBoolean("announcementsEnable", "main", true, "Enable or disable announcements feature");
        MOTD_ENABLE = mainConfig.getBoolean("motdEnable", "main", true, "Enable or disable MOTD feature");
        MENTIONS_ENABLE = mainConfig.getBoolean("mentionsEnable", "main", true, "Enable or disable mentions feature");
        RESTART_ENABLE = mainConfig.getBoolean("restartEnable", "main", true, "Enable or disable restart feature");
        DEBUG_ENABLE = mainConfig.getBoolean("debugEnable", "main", false, "Enable or disable debug mode");

        if (mainConfig.hasChanged()) {
            mainConfig.save();
        }
    }
}
