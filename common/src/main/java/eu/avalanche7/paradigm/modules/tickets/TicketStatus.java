package eu.avalanche7.paradigm.modules.tickets;

import java.util.Locale;
import java.util.Optional;

public enum TicketStatus {
    OPEN,
    IN_PROGRESS,
    WAITING_PLAYER,
    WAITING_STAFF,
    RESOLVED,
    CLOSED;

    public boolean isTerminal() {
        return this == RESOLVED || this == CLOSED;
    }

    public boolean isActive() {
        return !isTerminal();
    }

    public static Optional<TicketStatus> parse(String raw) {
        if (raw == null || raw.isBlank()) {
            return Optional.empty();
        }
        String normalized = raw.trim().toUpperCase(Locale.ROOT).replace('-', '_').replace(' ', '_');
        for (TicketStatus status : values()) {
            if (status.name().equals(normalized)) {
                return Optional.of(status);
            }
        }
        return Optional.empty();
    }

    public static TicketStatus parseOr(String raw, TicketStatus fallback) {
        return parse(raw).orElse(fallback);
    }

    public String id() {
        return name().toLowerCase(Locale.ROOT);
    }
}
