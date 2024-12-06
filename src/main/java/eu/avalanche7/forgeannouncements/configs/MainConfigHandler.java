package eu.avalanche7.forgeannouncements.configs;

import com.electronwill.nightconfig.core.file.CommentedFileConfig;
import net.minecraftforge.common.ForgeConfigSpec;

public class MainConfigHandler {
    public static final ForgeConfigSpec SERVER_CONFIG;
    public static final Config CONFIG;

    static {
        final ForgeConfigSpec.Builder builder = new ForgeConfigSpec.Builder();
        CONFIG = new Config(builder);
        SERVER_CONFIG = builder.build();
    }

    public static class Config {
        public final ForgeConfigSpec.BooleanValue announcementsEnable;
        public final ForgeConfigSpec.BooleanValue motdEnable;
        public final ForgeConfigSpec.BooleanValue mentionsEnable;
        public final ForgeConfigSpec.BooleanValue restartEnable;
        public final ForgeConfigSpec.BooleanValue debugEnable;
        public final ForgeConfigSpec.ConfigValue<String> defaultLanguage;


        public Config(ForgeConfigSpec.Builder builder) {
            builder.push("main");

            announcementsEnable = builder
                    .comment("Enable or disable announcements feature")
                    .define("announcementsEnable", true);

            motdEnable = builder
                    .comment("Enable or disable MOTD feature")
                    .define("motdEnable", true);

            mentionsEnable = builder
                    .comment("Enable or disable mentions feature")
                    .define("mentionsEnable", true);

            restartEnable = builder
                    .comment("Enable or disable restart feature")
                    .define("restartEnable", true);

            debugEnable = builder
                    .comment("Enable or disable debug mode")
                    .define("debugEnable", false);
            defaultLanguage = builder
                    .comment("Set the default language")
                    .define("defaultLanguage", "en");

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
