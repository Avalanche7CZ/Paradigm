package eu.avalanche7.paradigm.modules.tickets;

import java.util.Locale;

public enum TicketEventType {
    CREATED,
    CLAIMED,
    UNCLAIMED,
    ASSIGNED,
    PRIORITY_CHANGED,
    CATEGORY_CHANGED,
    STATUS_CHANGED,
    REPLIED,
    RESOLVED,
    CLOSED,
    REOPENED;

    public static TicketEventType parseOr(String raw, TicketEventType fallback) {
        if (raw == null || raw.isBlank()) {
            return fallback;
        }
        String normalized = raw.trim().toUpperCase(Locale.ROOT);
        for (TicketEventType type : values()) {
            if (type.name().equals(normalized)) {
                return type;
            }
        }
        return fallback;
    }

    public String id() {
        return name().toLowerCase(Locale.ROOT);
    }
}
