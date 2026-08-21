package eu.avalanche7.paradigm.modules.tickets;

import java.util.Locale;
import java.util.Optional;

public enum TicketPriority {
    LOW,
    NORMAL,
    HIGH,
    URGENT;

    public boolean isElevated() {
        return this == HIGH || this == URGENT;
    }

    public static Optional<TicketPriority> parse(String raw) {
        if (raw == null || raw.isBlank()) {
            return Optional.empty();
        }
        String normalized = raw.trim().toUpperCase(Locale.ROOT);
        for (TicketPriority priority : values()) {
            if (priority.name().equals(normalized)) {
                return Optional.of(priority);
            }
        }
        return Optional.empty();
    }

    public static TicketPriority parseOr(String raw, TicketPriority fallback) {
        return parse(raw).orElse(fallback);
    }

    public String id() {
        return name().toLowerCase(Locale.ROOT);
    }
}
