package eu.avalanche7.paradigm.modules.tickets;

import java.util.Map;

public record TicketOutcome(TicketError error, Ticket ticket, Map<String, String> details) {

    public TicketOutcome {
        error = error != null ? error : TicketError.NONE;
        details = details != null ? Map.copyOf(details) : Map.of();
    }

    public boolean ok() {
        return !error.isFailure();
    }

    public static TicketOutcome ok(Ticket ticket) {
        return new TicketOutcome(TicketError.NONE, ticket, Map.of());
    }

    public static TicketOutcome fail(TicketError error) {
        return new TicketOutcome(error, null, Map.of());
    }

    public static TicketOutcome fail(TicketError error, Ticket ticket) {
        return new TicketOutcome(error, ticket, Map.of());
    }

    public static TicketOutcome fail(TicketError error, Ticket ticket, Map<String, String> details) {
        return new TicketOutcome(error, ticket, details);
    }

    public String detail(String key) {
        return details.get(key);
    }
}
