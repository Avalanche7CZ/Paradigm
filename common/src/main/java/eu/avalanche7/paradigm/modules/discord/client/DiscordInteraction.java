package eu.avalanche7.paradigm.modules.discord.client;

public record DiscordInteraction(
        String id,
        String token,
        int type,
        String guildId,
        String channelId,
        String commandName,
        String optionValue,
        String authorId,
        String authorDisplayName,
        boolean bot) {
    public static final int TYPE_APPLICATION_COMMAND = 2;
    public static final int TYPE_APPLICATION_COMMAND_AUTOCOMPLETE = 4;

    public DiscordInteraction {
        optionValue = optionValue == null ? "" : optionValue;
    }

    public boolean isAutocomplete() {
        return type == TYPE_APPLICATION_COMMAND_AUTOCOMPLETE;
    }

    public boolean isSubmit() {
        return type == TYPE_APPLICATION_COMMAND;
    }
}
