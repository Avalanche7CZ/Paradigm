package eu.avalanche7.paradigm.modules.tickets;

public record TicketMessage(
        String messageId,
        String ticketId,
        String networkId,
        String ticketKey,
        TicketAuthorType authorType,
        String authorUuid,
        String authorName,
        String serverId,
        String text,
        long createdAtMs
) {
    public TicketMessage {
        authorType = authorType != null ? authorType : TicketAuthorType.SYSTEM;
        text = text != null ? text : "";
    }
}
