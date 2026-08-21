package eu.avalanche7.paradigm.modules.tickets;

public record TicketWriteResult(Status status, Ticket ticket) {

    public enum Status {
        OK,
        NOT_FOUND,
        STALE_REVISION,
        ALREADY_CLAIMED,
        UNSUPPORTED
    }

    public boolean ok() {
        return status == Status.OK;
    }

    public static TicketWriteResult ok(Ticket ticket) {
        return new TicketWriteResult(Status.OK, ticket);
    }

    public static TicketWriteResult notFound() {
        return new TicketWriteResult(Status.NOT_FOUND, null);
    }

    public static TicketWriteResult stale(Ticket current) {
        return new TicketWriteResult(Status.STALE_REVISION, current);
    }

    public static TicketWriteResult alreadyClaimed(Ticket current) {
        return new TicketWriteResult(Status.ALREADY_CLAIMED, current);
    }

    public static TicketWriteResult unsupported() {
        return new TicketWriteResult(Status.UNSUPPORTED, null);
    }
}
