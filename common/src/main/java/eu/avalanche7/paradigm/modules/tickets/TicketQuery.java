package eu.avalanche7.paradigm.modules.tickets;

import java.util.List;
import java.util.Locale;
import java.util.Set;

public record TicketQuery(
        String networkId,
        Set<TicketStatus> statuses,
        Set<TicketPriority> priorities,
        String category,
        String originServerId,
        String assigneeUuid,
        boolean unassignedOnly,
        String creatorUuid,
        String search,
        int page,
        int pageSize
) {
    public static final int MAX_PAGE_SIZE = 100;

    public TicketQuery {
        statuses = statuses != null ? Set.copyOf(statuses) : Set.of();
        priorities = priorities != null ? Set.copyOf(priorities) : Set.of();
        category = normalize(category);
        originServerId = normalize(originServerId);
        assigneeUuid = normalize(assigneeUuid);
        creatorUuid = normalize(creatorUuid);
        search = search != null && !search.isBlank() ? search.trim().toLowerCase(Locale.ROOT) : null;
        page = Math.max(1, page);
        pageSize = Math.max(1, Math.min(pageSize > 0 ? pageSize : 25, MAX_PAGE_SIZE));
    }

    private static String normalize(String value) {
        return value != null && !value.isBlank() ? value.trim().toLowerCase(Locale.ROOT) : null;
    }

    public int offset() {
        return (page - 1) * pageSize;
    }

    public boolean matches(Ticket ticket) {
        if (ticket == null) {
            return false;
        }
        if (networkId != null && !networkId.equalsIgnoreCase(ticket.networkId())) {
            return false;
        }
        if (!statuses.isEmpty() && !statuses.contains(ticket.status())) {
            return false;
        }
        if (!priorities.isEmpty() && !priorities.contains(ticket.priority())) {
            return false;
        }
        if (category != null && !category.equalsIgnoreCase(ticket.category())) {
            return false;
        }
        if (originServerId != null
                && (ticket.originServerId() == null || !originServerId.equalsIgnoreCase(ticket.originServerId()))) {
            return false;
        }
        if (unassignedOnly && ticket.isAssigned()) {
            return false;
        }
        if (assigneeUuid != null && !ticket.isAssignedTo(assigneeUuid)) {
            return false;
        }
        if (creatorUuid != null && !ticket.isCreatedBy(creatorUuid)) {
            return false;
        }
        if (search != null) {
            String haystack = (String.valueOf(ticket.ticketKey()) + ' '
                    + String.valueOf(ticket.subject()) + ' '
                    + String.valueOf(ticket.creatorName()) + ' '
                    + String.valueOf(ticket.assigneeName())).toLowerCase(Locale.ROOT);
            return haystack.contains(search);
        }
        return true;
    }

    public static Builder builder(String networkId) {
        return new Builder(networkId);
    }

    public static final class Builder {
        private final String networkId;
        private Set<TicketStatus> statuses = Set.of();
        private Set<TicketPriority> priorities = Set.of();
        private String category;
        private String originServerId;
        private String assigneeUuid;
        private boolean unassignedOnly;
        private String creatorUuid;
        private String search;
        private int page = 1;
        private int pageSize = 25;

        private Builder(String networkId) {
            this.networkId = networkId;
        }

        public Builder statuses(Set<TicketStatus> value) {
            this.statuses = value;
            return this;
        }

        public Builder statuses(List<TicketStatus> value) {
            this.statuses = value != null ? Set.copyOf(value) : Set.of();
            return this;
        }

        public Builder priorities(Set<TicketPriority> value) {
            this.priorities = value;
            return this;
        }

        public Builder category(String value) {
            this.category = value;
            return this;
        }

        public Builder originServerId(String value) {
            this.originServerId = value;
            return this;
        }

        public Builder assigneeUuid(String value) {
            this.assigneeUuid = value;
            return this;
        }

        public Builder unassignedOnly(boolean value) {
            this.unassignedOnly = value;
            return this;
        }

        public Builder creatorUuid(String value) {
            this.creatorUuid = value;
            return this;
        }

        public Builder search(String value) {
            this.search = value;
            return this;
        }

        public Builder page(int value) {
            this.page = value;
            return this;
        }

        public Builder pageSize(int value) {
            this.pageSize = value;
            return this;
        }

        public TicketQuery build() {
            return new TicketQuery(networkId, statuses, priorities, category, originServerId,
                    assigneeUuid, unassignedOnly, creatorUuid, search, page, pageSize);
        }
    }
}
