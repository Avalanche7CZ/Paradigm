package eu.avalanche7.paradigm.modules.tickets;

public record TicketEvent(
        String eventId,
        String ticketId,
        String networkId,
        String ticketKey,
        TicketEventType eventType,
        String actorUuid,
        String actorName,
        String serverId,
        String oldValue,
        String newValue,
        long createdAtMs
) {
    public TicketEvent {
        eventType = eventType != null ? eventType : TicketEventType.STATUS_CHANGED;
    }
}
