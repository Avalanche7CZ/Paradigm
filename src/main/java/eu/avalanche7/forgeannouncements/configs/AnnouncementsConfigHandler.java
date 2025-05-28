package eu.avalanche7.forgeannouncements.configs;

import com.electronwill.nightconfig.core.file.CommentedFileConfig;
import net.minecraftforge.common.ForgeConfigSpec;
import net.minecraftforge.fml.common.Mod;
import org.apache.commons.lang3.tuple.Pair;
import org.slf4j.Logger;
import com.mojang.logging.LogUtils;

import java.util.List;

@Mod.EventBusSubscriber(modid = "forgeannouncements", bus = Mod.EventBusSubscriber.Bus.MOD)
public class AnnouncementsConfigHandler {

    public static final ForgeConfigSpec SERVER_CONFIG;
    public static final Config CONFIG;

    static {
        final Pair<Config, ForgeConfigSpec> specPair = new ForgeConfigSpec.Builder().configure(Config::new);
        SERVER_CONFIG = specPair.getRight();
        CONFIG = specPair.getLeft();
    }

    public static class Config {
        public final ForgeConfigSpec.ConfigValue<String> orderMode;
        public final ForgeConfigSpec.BooleanValue globalEnable;
        public final ForgeConfigSpec.BooleanValue headerAndFooter;
        public final ForgeConfigSpec.IntValue globalInterval;
        public final ForgeConfigSpec.ConfigValue<String> prefix;
        public final ForgeConfigSpec.ConfigValue<String> header;
        public final ForgeConfigSpec.ConfigValue<String> footer;
        public final ForgeConfigSpec.ConfigValue<String> sound;
        public final ForgeConfigSpec.ConfigValue<List<? extends String>> globalMessages;

        public final ForgeConfigSpec.BooleanValue actionbarEnable;
        public final ForgeConfigSpec.IntValue actionbarInterval;
        public final ForgeConfigSpec.ConfigValue<List<? extends String>> actionbarMessages;

        public final ForgeConfigSpec.BooleanValue titleEnable;
        public final ForgeConfigSpec.IntValue titleInterval;
        public final ForgeConfigSpec.ConfigValue<List<? extends String>> titleMessages;

        public final ForgeConfigSpec.BooleanValue bossbarEnable;
        public final ForgeConfigSpec.IntValue bossbarInterval;
        public final ForgeConfigSpec.ConfigValue<String> bossbarColor;
        public final ForgeConfigSpec.IntValue bossbarTime;
        public final ForgeConfigSpec.ConfigValue<List<? extends String>> bossbarMessages;

        public Config(ForgeConfigSpec.Builder builder) {

            builder.comment("Auto Broadcast Settings")
                    .push("Auto_Broadcast");

            orderMode = builder.comment("Order mode for messages (RANDOM or SEQUENTIAL)")
                    .define("Order_Mode", "RANDOM");

            // Global Messages
            globalEnable = builder.comment("Enable global messages")
                    .define("Global_Messages.Enable", true);

            headerAndFooter = builder.comment("Enable header and footer")
                    .define("Global_Messages.Header_And_Footer", true);

            globalInterval = builder.comment("Interval in seconds for global messages")
                    .defineInRange("Global_Messages.Interval", 1800, 1, Integer.MAX_VALUE);

            prefix = builder.comment("Prefix for messages")
                    .define("Global_Messages.Prefix", "§9§l[§b§lPREFIX§9§l]");

            header = builder.comment("Header for messages")
                    .define("Global_Messages.Header", "§7*§7§m---------------------------------------------------§7*");

            footer = builder.comment("Footer for messages")
                    .define("Global_Messages.Footer", "§7*§7§m---------------------------------------------------§7*");

            sound = builder.comment("Sound to play")
                    .define("Global_Messages.Sound", "");

            globalMessages = builder.comment("Global messages to broadcast")
                    .defineList("Global_Messages.Messages",
                            List.of(
                                    "{Prefix} §7This is global message with link: https://link/."
                            ),
                            obj -> obj instanceof String);

            actionbarEnable = builder.comment("Enable actionbar messages")
                    .define("Actionbar_Messages.Enable", true);

            actionbarInterval = builder.comment("Interval in seconds for actionbar messages")
                    .defineInRange("Actionbar_Messages.Interval", 1800, 1, Integer.MAX_VALUE);

            actionbarMessages = builder.comment("Actionbar messages to broadcast")
                    .defineList("Actionbar_Messages.Messages",
                            List.of(
                                    "{Prefix} §7This is an actionbar message."
                            ),
                            obj -> obj instanceof String);

            titleEnable = builder.comment("Enable title messages")
                    .define("Title_Messages.Enable", true);

            titleInterval = builder.comment("Interval in seconds for title messages")
                    .defineInRange("Title_Messages.Interval", 1800, 1, Integer.MAX_VALUE);

            titleMessages = builder.comment("Title messages to broadcast")
                    .defineList("Title_Messages.Messages",
                            List.of(
                                    "{Prefix} §7This is a title message."
                            ),
                            obj -> obj instanceof String);

            bossbarEnable = builder.comment("Enable bossbar messages")
                    .define("Bossbar_Messages.Enable", true);

            bossbarInterval = builder.comment("Interval in seconds for bossbar messages")
                    .defineInRange("Bossbar_Messages.Interval", 1800, 1, Integer.MAX_VALUE);

            bossbarTime = builder.comment("How long the bossbar stays on for (seconds)")
                    .defineInRange("Bossbar.Bar_Time", 10, 1, Integer.MAX_VALUE);

            bossbarColor = builder.comment("Color of the bossbar")
                    .define("Bossbar_Messages.Color", "PURPLE");

            bossbarMessages = builder.comment("Bossbar messages to broadcast")
                    .defineList("Bossbar_Messages.Messages",
                            List.of(
                                    "{Prefix} §7This is a bossbar message."
                            ),
                            obj -> obj instanceof String);

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