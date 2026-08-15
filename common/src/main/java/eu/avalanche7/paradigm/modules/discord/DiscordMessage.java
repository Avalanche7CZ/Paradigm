package eu.avalanche7.paradigm.modules.discord;

public record DiscordMessage(
        DiscordDestination destination,
        String content,
        DiscordEmbed embed,
        boolean allowMentions,
        String dedupeKey,
        DiscordIdentity identity) {
    public DiscordMessage {
        content = content == null ? "" : content;
    }

    public DiscordMessage(DiscordDestination destination, String content, DiscordEmbed embed,
                          boolean allowMentions, String dedupeKey) {
        this(destination, content, embed, allowMentions, dedupeKey, null);
    }

    public static DiscordMessage plain(DiscordDestination destination, String content, boolean allowMentions) {
        return new DiscordMessage(destination, content, null, allowMentions, null, null);
    }

    public static DiscordMessage identified(DiscordDestination destination, String content, boolean allowMentions,
                                            DiscordIdentity identity) {
        return new DiscordMessage(destination, content, null, allowMentions, null, identity);
    }

    public static DiscordMessage embed(DiscordDestination destination, DiscordEmbed embed, String dedupeKey) {
        return new DiscordMessage(destination, "", embed, false, dedupeKey, null);
    }

    public boolean isEmpty() {
        return content.isBlank() && embed == null;
    }

    public boolean hasIdentity() {
        return identity != null && identity.isUsable();
    }
}
