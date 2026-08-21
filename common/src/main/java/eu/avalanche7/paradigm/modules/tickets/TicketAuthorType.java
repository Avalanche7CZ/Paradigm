package eu.avalanche7.paradigm.modules.tickets;

import java.util.Locale;

public enum TicketAuthorType {
    PLAYER,
    STAFF,
    SYSTEM;

    public static TicketAuthorType parseOr(String raw, TicketAuthorType fallback) {
        if (raw == null || raw.isBlank()) {
            return fallback;
        }
        String normalized = raw.trim().toUpperCase(Locale.ROOT);
        for (TicketAuthorType type : values()) {
            if (type.name().equals(normalized)) {
                return type;
            }
        }
        return fallback;
    }
}
