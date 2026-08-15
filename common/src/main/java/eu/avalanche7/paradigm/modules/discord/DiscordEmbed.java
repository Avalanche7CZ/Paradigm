package eu.avalanche7.paradigm.modules.discord;

public record DiscordEmbed(String title, String description, Integer colorRgb, String footer) {
    public static DiscordEmbed of(String title, String description, Integer colorRgb) {
        return new DiscordEmbed(title, description, colorRgb, null);
    }
}
