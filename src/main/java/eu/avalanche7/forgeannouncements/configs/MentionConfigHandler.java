package eu.avalanche7.forgeannouncements.configs;

import com.electronwill.nightconfig.core.file.CommentedFileConfig;
import com.electronwill.nightconfig.core.io.WritingMode;
import net.minecraftforge.common.ForgeConfigSpec;
import net.minecraftforge.fml.common.Mod;

import java.io.File;

@Mod.EventBusSubscriber(modid = "forgeannouncements", bus = Mod.EventBusSubscriber.Bus.MOD)
public class MentionConfigHandler {
    public static final String CATEGORY_GENERAL = "general";
    public static final String SUBCATEGORY_MENTIONS = "mentions";

    public static ForgeConfigSpec SERVER_CONFIG;

    public static ForgeConfigSpec.ConfigValue<String> MENTION_SYMBOL;
    public static ForgeConfigSpec.ConfigValue<String> INDIVIDUAL_MENTION_MESSAGE;
    public static ForgeConfigSpec.ConfigValue<String> EVERYONE_MENTION_MESSAGE;
    public static ForgeConfigSpec.ConfigValue<String> INDIVIDUAL_TITLE_MESSAGE;
    public static ForgeConfigSpec.ConfigValue<String> EVERYONE_TITLE_MESSAGE;
    public static ForgeConfigSpec.IntValue INDIVIDUAL_MENTION_RATE_LIMIT;
    public static ForgeConfigSpec.IntValue EVERYONE_MENTION_RATE_LIMIT;

    static {
        ForgeConfigSpec.Builder builder = new ForgeConfigSpec.Builder();

        builder.comment("General settings").push(CATEGORY_GENERAL);
        builder.comment("Mentions settings").push(SUBCATEGORY_MENTIONS);

        MENTION_SYMBOL = builder.comment("Symbol to mention players").define("mentionSymbol", "@");
        INDIVIDUAL_MENTION_MESSAGE = builder.comment("Message displayed to a player when they are mentioned")
                .define("individualMentionMessage", "§4%s §cmentioned you in chat!");
        EVERYONE_MENTION_MESSAGE = builder.comment("Message displayed to everyone when @everyone is used")
                .define("everyoneMentionMessage", "§4%s §cmentioned everyone in chat!");
        INDIVIDUAL_TITLE_MESSAGE = builder.comment("Title message displayed to a player when they are mentioned")
                .define("individualTitleMessage", "§4%s §cmentioned you!");
        EVERYONE_TITLE_MESSAGE = builder.comment("Title message displayed to everyone when @everyone is used")
                .define("everyoneTitleMessage", "§4%s §cmentioned everyone!");
        INDIVIDUAL_MENTION_RATE_LIMIT = builder.comment("Rate limit for individual mentions in seconds")
                .defineInRange("individualMentionRateLimit", 30, 0, Integer.MAX_VALUE);
        EVERYONE_MENTION_RATE_LIMIT = builder.comment("Rate limit for @everyone mentions in seconds")
                .defineInRange("everyoneMentionRateLimit", 60, 0, Integer.MAX_VALUE);

        builder.pop();
        builder.pop();

        SERVER_CONFIG = builder.build();
    }

    public static void loadConfig(ForgeConfigSpec config, String path) {
        final CommentedFileConfig file = CommentedFileConfig.builder(new File(path)).sync().autosave().writingMode(WritingMode.REPLACE).build();
        file.load();
        config.setConfig(file);
    }
}