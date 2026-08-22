package eu.avalanche7.paradigm.modules.tickets;

public record TicketWriteResult(Status status, Ticket ticket, Long cooldownRemainingMs) {

    public enum Status {
        OK,
        NOT_FOUND,
        STALE_REVISION,
        ALREADY_CLAIMED,
        LIMIT_REACHED,
        COOLDOWN,
        UNSUPPORTED
    }

    public boolean ok() {
        return status == Status.OK;
    }

    public static TicketWriteResult ok(Ticket ticket) {
        return new TicketWriteResult(Status.OK, ticket, null);
    }

    public static TicketWriteResult notFound() {
        return new TicketWriteResult(Status.NOT_FOUND, null, null);
    }

    public static TicketWriteResult stale(Ticket current) {
        return new TicketWriteResult(Status.STALE_REVISION, current, null);
    }

    public static TicketWriteResult alreadyClaimed(Ticket current) {
        return new TicketWriteResult(Status.ALREADY_CLAIMED, current, null);
    }

    public static TicketWriteResult limitReached() {
        return new TicketWriteResult(Status.LIMIT_REACHED, null, null);
    }

    public static TicketWriteResult cooldown(long remainingMs) {
        return new TicketWriteResult(Status.COOLDOWN, null, Math.max(0L, remainingMs));
    }

    public static TicketWriteResult unsupported() {
        return new TicketWriteResult(Status.UNSUPPORTED, null, null);
    }
}
