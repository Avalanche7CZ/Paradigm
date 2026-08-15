package eu.avalanche7.paradigm.modules.discord;

public enum DiscordInboundCapability {
    UNKNOWN,

    AVAILABLE,

    CONTENT_INTENT_MISSING;

    public boolean blocksInboundRelay() {
        return this == CONTENT_INTENT_MISSING;
    }
}
