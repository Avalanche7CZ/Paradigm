package eu.avalanche7.paradigm.configs;

import com.electronwill.nightconfig.core.file.CommentedFileConfig;
import net.minecraftforge.common.ForgeConfigSpec;
import net.minecraftforge.fml.common.Mod;
import org.apache.commons.lang3.tuple.Pair;

import java.util.Arrays;
import java.util.List;

@Mod.EventBusSubscriber(modid = "paradigm", bus = Mod.EventBusSubscriber.Bus.MOD)
public class RestartConfigHandler {

    public static ForgeConfigSpec SERVER_CONFIG;
    public static final RestartConfigHandler.Config CONFIG;

    static {
        final Pair<RestartConfigHandler.Config, ForgeConfigSpec> specPair = new ForgeConfigSpec.Builder().configure(RestartConfigHandler.Config::new);
        SERVER_CONFIG = specPair.getRight();
        CONFIG = specPair.getLeft();
    }

    public static class Config {
        public final ForgeConfigSpec.ConfigValue<String> restartType;
        public final ForgeConfigSpec.ConfigValue<Double> restartInterval;
        public final ForgeConfigSpec.ConfigValue<List<? extends String>> realTimeInterval;
        public final ForgeConfigSpec.ConfigValue<Boolean> bossbarEnabled;
        public final ForgeConfigSpec.ConfigValue<String> bossBarMessage;
        public final ForgeConfigSpec.ConfigValue<Boolean> timerUseChat;
        public final ForgeConfigSpec.ConfigValue<String> BroadcastMessage;
        public final ForgeConfigSpec.ConfigValue<List<? extends Integer>> timerBroadcast;
        public final ForgeConfigSpec.ConfigValue<String> defaultRestartReason;
        public final ForgeConfigSpec.ConfigValue<Boolean> playSoundEnabled;
        public final ForgeConfigSpec.ConfigValue<Boolean> titleEnabled;
        public final ForgeConfigSpec.ConfigValue<Integer> titleStayTime;
        public final ForgeConfigSpec.ConfigValue<String> titleMessage;
        public final ForgeConfigSpec.ConfigValue<String> playSoundString;
        public final ForgeConfigSpec.ConfigValue<Double> playSoundFirstTime;

        public Config(ForgeConfigSpec.Builder builder) {
            builder.comment("Restart settings")
                    .push("restart");

            restartType = builder
                    .comment("Type of automatic restart (Fixed, Realtime, None).")
                    .define("restartType", "Realtime");

            restartInterval = builder
                    .comment("Interval for fixed restarts in hours.")
                    .define("restartInterval", 6.0);

            realTimeInterval = builder
                    .comment("Times for real-time restarts (24-hour format).")
                    .defineList("realTimeInterval", Arrays.asList("00:00", "06:00", "12:00", "18:00"), obj -> obj instanceof String);

            bossbarEnabled = builder
                    .comment("Enable boss bar for restart countdown.")
                    .define("bossbarEnabled", false);

            bossBarMessage = builder
                    .comment("Message to display in boss bar on restart warnings.")
                    .define("bossBarMessage", "The server will be restarting in {minutes}:{seconds}");

            timerUseChat = builder
                    .comment("Broadcast restart warnings in chat.")
                    .define("timerUseChat", true);

            BroadcastMessage = builder
                    .comment("Custom broadcast message for restart warnings.")
                    .define("BroadcastMessage", "The server will be restarting in {minutes}:{seconds}");

            timerBroadcast = builder
                    .comment("Warning times in seconds before reboot.")
                    .defineList("timerBroadcast", Arrays.asList(600, 300, 240, 180, 120, 60, 30, 5, 4, 3, 2, 1), obj -> obj instanceof Integer);

            defaultRestartReason = builder
                    .comment("Default reason shown for a restart.")
                    .define("defaultRestartReason", "");

            playSoundEnabled = builder
                    .comment("Enable notification sound on restart warnings.")
                    .define("playSoundEnabled", true);

            playSoundString = builder
                    .comment("Sound to play on restart warnings.")
                    .define("playSoundString", "note_block_pling");

            playSoundFirstTime = builder
                    .comment("When to start playing notification sound (same as one of broadcast timers).")
                    .define("playSoundFirstTime", 600.0);

            titleEnabled = builder
                    .comment("Enable title message on restart warnings.")
                    .define("titleEnabled", true);

            titleStayTime = builder
                    .comment("Duration of title message display (in seconds).")
                    .define("titleStayTime", 2);

            titleMessage = builder
                    .comment("Message to display in title on restart warnings.")
                    .define("titleMessage", "The server will be restarting in {minutes}:{seconds}");

            builder.pop();
        }
    }

    public static void loadConfig(ForgeConfigSpec config, String path) {
        final CommentedFileConfig file = CommentedFileConfig.builder(path)
                .sync()
                .autosave()
                .writingMode(com.electronwill.nightconfig.core.io.WritingMode.REPLACE)
                .build();
        file.load();
        config.setConfig(file);
    }
}
