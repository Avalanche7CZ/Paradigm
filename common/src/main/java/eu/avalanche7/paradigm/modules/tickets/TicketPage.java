package eu.avalanche7.paradigm.modules.tickets;

import java.util.List;

public record TicketPage(List<Ticket> tickets, int total, int page, int pageSize) {

    public TicketPage {
        tickets = tickets != null ? List.copyOf(tickets) : List.of();
    }

    public static TicketPage empty(int page, int pageSize) {
        return new TicketPage(List.of(), 0, page, pageSize);
    }

    public int totalPages() {
        if (pageSize <= 0) {
            return 1;
        }
        return Math.max(1, (total + pageSize - 1) / pageSize);
    }

    public boolean hasNext() {
        return page < totalPages();
    }

    public boolean hasPrevious() {
        return page > 1;
    }
}
