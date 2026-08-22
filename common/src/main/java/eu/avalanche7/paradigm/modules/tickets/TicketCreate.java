package eu.avalanche7.paradigm.modules.tickets;

public record TicketCreate(
        Ticket ticket,
        TicketMessage firstMessage,
        TicketEvent createdEvent
) {
    public TicketCreate withCreatedAtMs(long createdAtMs) {
        Ticket admittedTicket = new Ticket(ticket.ticketId(), ticket.ticketKey(), ticket.networkId(), ticket.originServerId(),
                ticket.creatorUuid(), ticket.creatorName(), ticket.category(), ticket.subject(), ticket.status(), ticket.priority(),
                ticket.assigneeUuid(), ticket.assigneeName(), createdAtMs, createdAtMs, ticket.resolvedAtMs(), ticket.closedAtMs(),
                createdAtMs, ticket.revision(), ticket.metadata());
        TicketMessage admittedMessage = firstMessage != null ? new TicketMessage(firstMessage.messageId(), firstMessage.ticketId(),
                firstMessage.networkId(), firstMessage.ticketKey(), firstMessage.authorType(), firstMessage.authorUuid(),
                firstMessage.authorName(), firstMessage.serverId(), firstMessage.text(), createdAtMs) : null;
        TicketEvent admittedEvent = createdEvent != null ? new TicketEvent(createdEvent.eventId(), createdEvent.ticketId(),
                createdEvent.networkId(), createdEvent.ticketKey(), createdEvent.eventType(), createdEvent.actorUuid(),
                createdEvent.actorName(), createdEvent.serverId(), createdEvent.oldValue(), createdEvent.newValue(), createdAtMs) : null;
        return new TicketCreate(admittedTicket, admittedMessage, admittedEvent);
    }
}
