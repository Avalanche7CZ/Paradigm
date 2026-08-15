package eu.avalanche7.paradigm.modules.discord;

public enum DiscordConnectionState {
    DISABLED,

    DISCONNECTED,

    CONNECTING,

    CONNECTED,

    RECONNECTING,

    FAILED;

    public boolean isUsable() {
        return this == CONNECTED;
    }

    public boolean isTerminal() {
        return this == FAILED;
    }
}
