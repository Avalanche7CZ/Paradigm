package eu.avalanche7.forgeannouncements.configs;

import net.minecraftforge.common.config.Configuration;

public class MOTDConfigHandler {

    private static Configuration motdConfig;
    public static String motdMessage;

    public static void init(Configuration config) {
        motdConfig = config;
        loadConfig();
    }

    public static void loadConfig() {
        String category = "MOTD";
        motdConfig.addCustomCategoryComment(category, "MOTD Configuration");
        String[] defaultMessageLines = getDefaultMotdMessage().split("\n");
        StringBuilder defaultMessage = new StringBuilder();
        for (String line : defaultMessageLines) {
            defaultMessage.append(line.trim()).append("\\n");
        }

        motdMessage = motdConfig.getString("Message", category, defaultMessage.toString(), "MOTD Message");

        motdMessage = motdMessage.replace("\\n", "\n");

        if (motdConfig.hasChanged()) {
            motdConfig.save();
        }
    }

    private static String getDefaultMotdMessage() {
        return "§a[title=Welcome Message]\n" +
                "§7[subtitle=Server Information]\n" +
                "§aWelcome to the server!\n" +
                "§7Visit our website: §c[link=http://example.com]\n" +
                "§bType [command=/help] for commands\n" +
                "§eHover over this message [hover=This is a hover text!] to see more info.\n" +
                "§7[divider]";
    }

    public static Configuration getConfig() {
        return motdConfig;
    }
}