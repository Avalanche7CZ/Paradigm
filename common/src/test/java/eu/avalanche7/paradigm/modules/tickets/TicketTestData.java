package eu.avalanche7.paradigm.modules.tickets;

import java.util.Map;

final class TicketTestData {

    static final String CREATOR_UUID = "11111111-1111-1111-1111-111111111111";

    private TicketTestData() {
    }

    static Ticket ticket(String key, String networkId, String serverId, TicketStatus status, TicketPriority priority) {
        return new Ticket(
                "TK-" + networkId + "-" + key,
                key,
                networkId,
                serverId,
                CREATOR_UUID,
                "Creator",
                "general",
                "subject for " + key,
                status,
                priority,
                null,
                null,
                1_000L,
                1_000L,
                null,
                null,
                1_000L,
                0L,
                Map.of()
        );
    }

    static TicketCreate create(Ticket ticket, String firstMessage) {
        return new TicketCreate(
                ticket,
                new TicketMessage(TicketIds.messageId(), ticket.ticketId(), ticket.networkId(), ticket.ticketKey(),
                        TicketAuthorType.PLAYER, ticket.creatorUuid(), ticket.creatorName(), ticket.originServerId(),
                        firstMessage, ticket.createdAtMs()),
                new TicketEvent(TicketIds.eventId(), ticket.ticketId(), ticket.networkId(), ticket.ticketKey(),
                        TicketEventType.CREATED, ticket.creatorUuid(), ticket.creatorName(), ticket.originServerId(),
                        null, ticket.status().name(), ticket.createdAtMs())
        );
    }

    static TicketMessage message(String ticketKey, TicketAuthorType authorType, String text) {
        return new TicketMessage(TicketIds.messageId(), "TK-" + ticketKey, "net", ticketKey, authorType,
                "22222222-2222-2222-2222-222222222222", "Staff", "alpha", text, 5_000L);
    }

    static TicketEvent event(String ticketKey, TicketEventType type, String oldValue, String newValue) {
        return new TicketEvent(TicketIds.eventId(), "TK-" + ticketKey, "net", ticketKey, type,
                "22222222-2222-2222-2222-222222222222", "Staff", "alpha", oldValue, newValue, 5_000L);
    }
}
