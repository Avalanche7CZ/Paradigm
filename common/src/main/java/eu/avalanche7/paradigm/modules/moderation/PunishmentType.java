package eu.avalanche7.paradigm.modules.moderation;

import java.util.Locale;

public enum PunishmentType {
    BAN,
    IP_BAN,
    MUTE,
    WARN,
    JAIL;

    public static PunishmentType fromLegacy(String value) {
        String normalized = value == null ? "" : value.trim().toUpperCase(Locale.ROOT).replace('-', '_');
        return switch (normalized) {
            case "TEMPBAN", "BAN" -> BAN;
            case "IPBAN", "TEMPIPBAN", "IP_BAN" -> IP_BAN;
            case "TEMPMUTE", "MUTE" -> MUTE;
            case "WARNING", "WARN" -> WARN;
            case "JAIL" -> JAIL;
            default -> throw new IllegalArgumentException("Unsupported punishment type: " + value);
        };
    }
}
