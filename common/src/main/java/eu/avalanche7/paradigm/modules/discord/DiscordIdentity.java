package eu.avalanche7.paradigm.modules.discord;

public record DiscordIdentity(String username, String avatarUrl) {
    public static final int MAX_USERNAME_LENGTH = 80;

    public DiscordIdentity {
        username = username != null ? DiscordSanitizer.truncate(username.trim(), MAX_USERNAME_LENGTH) : "";
        avatarUrl = avatarUrl != null ? avatarUrl.trim() : "";
    }

    public boolean isUsable() {
        return !username.isBlank();
    }
}
