package eu.avalanche7.forgeannouncements.configs;

import eu.avalanche7.forgeannouncements.ForgeAnnouncements;
import net.minecraftforge.common.config.Configuration;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import java.util.List;
import java.util.Arrays;

public class AnnouncementsConfigHandler {

    private static final Logger LOGGER = LogManager.getLogger(ForgeAnnouncements.MODID);
    public static Configuration config;
    public static boolean globalEnable;
    public static boolean headerAndFooter;
    public static int globalInterval;
    public static String prefix;
    public static String header;
    public static String footer;
    public static String sound;
    public static List<String> globalMessages;

    public static boolean actionbarEnable;
    public static int actionbarInterval;
    public static List<String> actionbarMessages;

    public static boolean titleEnable;
    public static int titleInterval;
    public static List<String> titleMessages;

    public static boolean bossbarEnable;
    public static int bossbarInterval;
    public static String bossbarColor;
    public static int bossbarTime;
    public static List<String> bossbarMessages;

    public static void init(Configuration config) {
        AnnouncementsConfigHandler.config = config;
        config.load();

        globalEnable = config.getBoolean("GlobalEnable", "Auto_Broadcast", true, "Enable global messages");
        headerAndFooter = config.getBoolean("HeaderAndFooter", "Auto_Broadcast", true, "Enable header and footer");
        globalInterval = config.getInt("GlobalInterval", "Auto_Broadcast", 1800, 1, Integer.MAX_VALUE, "Interval in seconds for global messages");
        prefix = config.getString("Prefix", "Auto_Broadcast", "§9§l[§b§lPREFIX§9§l]", "Prefix for messages");
        header = config.getString("Header", "Auto_Broadcast", "§7*§7§m---------------------------------------------------§7*", "Header for messages");
        footer = config.getString("Footer", "Auto_Broadcast", "§7*§7§m---------------------------------------------------§7*", "Footer for messages");
        sound = config.getString("Sound", "Auto_Broadcast", "", "Sound to play");
        globalMessages = Arrays.asList(config.getStringList("GlobalMessages", "Auto_Broadcast", new String[]{"{Prefix} §7This is global message with link: https://link/."}, "Global messages to broadcast"));

        actionbarEnable = config.getBoolean("ActionbarEnable", "Auto_Broadcast", true, "Enable actionbar messages");
        actionbarInterval = config.getInt("ActionbarInterval", "Auto_Broadcast", 1800, 1, Integer.MAX_VALUE, "Interval in seconds for actionbar messages");
        actionbarMessages = Arrays.asList(config.getStringList("ActionbarMessages", "Auto_Broadcast", new String[]{"{Prefix} §7This is an actionbar message."}, "Actionbar messages to broadcast"));

        titleEnable = config.getBoolean("TitleEnable", "Auto_Broadcast", true, "Enable title messages");
        titleInterval = config.getInt("TitleInterval", "Auto_Broadcast", 1800, 1, Integer.MAX_VALUE, "Interval in seconds for title messages");
        titleMessages = Arrays.asList(config.getStringList("TitleMessages", "Auto_Broadcast", new String[]{"{Prefix} §7This is a title message."}, "Title messages to broadcast"));

        bossbarEnable = config.getBoolean("BossbarEnable", "Auto_Broadcast", true, "Enable bossbar messages");
        bossbarInterval = config.getInt("BossbarInterval", "Auto_Broadcast", 1800, 1, Integer.MAX_VALUE, "Interval in seconds for bossbar messages");
        bossbarTime = config.getInt("BossbarTime", "Auto_Broadcast", 10, 1, Integer.MAX_VALUE, "How long the bossbar stays on for (seconds)");
        bossbarColor = config.getString("BossbarColor", "Auto_Broadcast", "PURPLE", "Color of the bossbar");
        bossbarMessages = Arrays.asList(config.getStringList("BossbarMessages", "Auto_Broadcast", new String[]{"{Prefix} §7This is a bossbar message."}, "Bossbar messages to broadcast"));

        if (config.hasChanged()) {
            config.save();
        }

        LOGGER.info("Configuration loaded.");
    }
}
