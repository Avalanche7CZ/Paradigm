package eu.avalanche7.paradigm.configs;

import net.minecraftforge.common.config.Configuration;

import java.util.Arrays;
import java.util.List;
import java.util.stream.Collectors;
import java.util.stream.IntStream;

public class RestartConfigHandler {

    public static Configuration restartConfig;
    public static String restartType;
    public static double restartInterval;
    public static List<String> realTimeInterval;
    public static boolean bossbarEnabled;
    public static String bossBarMessage;
    public static boolean timerUseChat;
    public static String broadcastMessage;
    public static List<Integer> timerBroadcast;
    public static String defaultRestartReason;
    public static boolean playSoundEnabled;
    public static String playSoundString;
    public static double playSoundFirstTime;
    public static boolean titleEnabled;
    public static int titleStayTime;
    public static String titleMessage;

    public static void init(Configuration config) {
        restartConfig = config;
        loadConfig();
    }

    public static void loadConfig() {
        String category = "restart";

        restartType = restartConfig.getString("restartType", category, "Realtime", "Type of automatic restart (Fixed, Realtime, None).");
        restartInterval = restartConfig.getFloat("restartInterval", category, 6.0f, 0.0f, Float.MAX_VALUE, "Interval for fixed restarts in hours.");
        realTimeInterval = Arrays.asList(restartConfig.getStringList("realTimeInterval", category, new String[]{"00:00", "06:00", "12:00", "18:00"}, "Times for real-time restarts (24-hour format)."));
        bossbarEnabled = restartConfig.getBoolean("bossbarEnabled", category, false, "Enable boss bar for restart countdown.");
        bossBarMessage = restartConfig.getString("bossBarMessage", category, "The server will be restarting in {minutes}:{seconds}", "Message to display in boss bar on restart warnings.");
        timerUseChat = restartConfig.getBoolean("timerUseChat", category, true, "Broadcast restart warnings in chat.");
        broadcastMessage = restartConfig.getString("BroadcastMessage", category, "The server will be restarting in {minutes}:{seconds}", "Custom broadcast message for restart warnings.");
        timerBroadcast = IntStream.of(restartConfig.get(category, "timerBroadcast", new int[]{600, 300, 240, 180, 120, 60, 30, 5, 4, 3, 2, 1}, "Warning times in seconds before reboot.").getIntList())
                .boxed()
                .collect(Collectors.toList());
        defaultRestartReason = restartConfig.getString("defaultRestartReason", category, "", "Default reason shown for a restart.");
        playSoundEnabled = restartConfig.getBoolean("playSoundEnabled", category, true, "Enable notification sound on restart warnings.");
        playSoundString = restartConfig.getString("playSoundString", category, "NOTE_BLOCK_PLING", "Sound to play on restart warnings.");
        playSoundFirstTime = restartConfig.getFloat("playSoundFirstTime", category, 600.0f, 0.0f, Float.MAX_VALUE, "When to start playing notification sound (same as one of broadcast timers).");
        titleEnabled = restartConfig.getBoolean("titleEnabled", category, true, "Enable title message on restart warnings.");
        titleStayTime = restartConfig.getInt("titleStayTime", category, 2, 0, Integer.MAX_VALUE, "Duration of title message display (in seconds).");
        titleMessage = restartConfig.getString("titleMessage", category, "The server will be restarting in {minutes}:{seconds}", "Message to display in title on restart warnings.");

        if (restartConfig.hasChanged()) {
            restartConfig.save();
        }
    }
}

