package eu.avalanche7.paradigm.modules.moderation;

public enum ModerationActionType {
    WARN,
    MUTE,
    TEMPMUTE,
    UNMUTE,
    BAN,
    TEMPBAN,
    UNBAN,
    IPBAN,
    TEMPIPBAN,
    UNIPBAN,
    REVOKE,
    JAIL,
    UNJAIL;

    public static ModerationActionType parse(String value) {
        String normalized = value != null ? value.trim().toUpperCase(java.util.Locale.ROOT) : "";
        try {
            return normalized.isBlank() ? null : valueOf(normalized);
        } catch (Throwable ignored) {
            return null;
        }
    }
}
