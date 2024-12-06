package eu.avalanche7.forgeannouncements.configs;

import net.minecraftforge.common.config.Configuration;

public class MentionConfigHandler {
    private static Configuration mentionsConfig;

    public static String MENTION_SYMBOL;
    public static String INDIVIDUAL_MENTION_MESSAGE;
    public static String EVERYONE_MENTION_MESSAGE;
    public static String INDIVIDUAL_TITLE_MESSAGE;
    public static String EVERYONE_TITLE_MESSAGE;
    public static int INDIVIDUAL_MENTION_RATE_LIMIT;
    public static int EVERYONE_MENTION_RATE_LIMIT;

    public static void init(Configuration config) {
        mentionsConfig = config;
        loadConfig();
    }

    private static void loadConfig() {
        MENTION_SYMBOL = mentionsConfig.getString("mentionSymbol", "general", "@", "Symbol to mention players");
        INDIVIDUAL_MENTION_MESSAGE = mentionsConfig.getString("individualMentionMessage", "mentions", "§4%s §cmentioned you in chat!", "Message displayed to a player when they are mentioned");
        EVERYONE_MENTION_MESSAGE = mentionsConfig.getString("everyoneMentionMessage", "mentions", "§4%s §cmentioned everyone in chat!", "Message displayed to everyone when @everyone is used");
        INDIVIDUAL_TITLE_MESSAGE = mentionsConfig.getString("individualTitleMessage", "mentions", "§4%s §cmentioned you!", "Title message displayed to a player when they are mentioned");
        EVERYONE_TITLE_MESSAGE = mentionsConfig.getString("everyoneTitleMessage", "mentions", "§4%s §cmentioned everyone!", "Title message displayed to everyone when @everyone is used");
        INDIVIDUAL_MENTION_RATE_LIMIT = mentionsConfig.getInt("individualMentionRateLimit", "mentions", 60, 0, Integer.MAX_VALUE, "Rate limit for individual mentions in seconds");
        EVERYONE_MENTION_RATE_LIMIT = mentionsConfig.getInt("everyoneMentionRateLimit", "mentions", 300, 0, Integer.MAX_VALUE, "Rate limit for everyone mentions in seconds");

        if (mentionsConfig.hasChanged()) {
            mentionsConfig.save();
        }
    }
}