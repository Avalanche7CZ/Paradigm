package eu.avalanche7.paradigm.modules.tickets;

public record TicketCreate(
        Ticket ticket,
        TicketMessage firstMessage,
        TicketEvent createdEvent
) {
}
