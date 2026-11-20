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
        boolean bot,
        String modalId,
        String modalCode) {
    public static final String LINK_MODAL_ID = "paradigm:account-link";
    public static final String LINK_CODE_FIELD = "code";
    public static final int TYPE_APPLICATION_COMMAND = 2;
    public static final int TYPE_APPLICATION_COMMAND_AUTOCOMPLETE = 4;

    public static final int TYPE_MODAL_SUBMIT = 5;

    public DiscordInteraction(String id, String token, int type, String guildId, String channelId,
                              String commandName, String optionValue, String authorId, String authorDisplayName, boolean bot) {
        this(id, token, type, guildId, channelId, commandName, optionValue, authorId, authorDisplayName, bot, null, null);
    }

    public DiscordInteraction {
        optionValue = optionValue == null ? "" : optionValue;
    }

    public boolean isLinkModal() {
        return type == TYPE_MODAL_SUBMIT && LINK_MODAL_ID.equals(modalId);
    }

    @Override
    public String toString() {
        return "DiscordInteraction[id=" + id + ", type=" + type + "]";
    }

    public boolean isAutocomplete() {
        return type == TYPE_APPLICATION_COMMAND_AUTOCOMPLETE;
    }

    public boolean isSubmit() {
        return type == TYPE_APPLICATION_COMMAND;
    }
}
