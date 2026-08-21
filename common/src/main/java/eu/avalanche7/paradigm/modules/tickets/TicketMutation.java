package eu.avalanche7.paradigm.modules.tickets;

import java.util.List;

public record TicketMutation(
        String networkId,
        String ticketKey,
        long expectedRevision,
        Ticket updated,
        TicketMessage message,
        List<TicketEvent> events,
        boolean requireUnassigned
) {
    public TicketMutation {
        events = events != null ? List.copyOf(events) : List.of();
    }

    public static TicketMutation of(Ticket updated, long expectedRevision, TicketMessage message, List<TicketEvent> events) {
        return new TicketMutation(updated.networkId(), updated.ticketKey(), expectedRevision, updated, message, events, false);
    }

    public static TicketMutation claim(Ticket updated, long expectedRevision, List<TicketEvent> events) {
        return new TicketMutation(updated.networkId(), updated.ticketKey(), expectedRevision, updated, null, events, true);
    }
}
