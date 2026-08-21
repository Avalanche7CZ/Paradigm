package eu.avalanche7.paradigm.modules.tickets;

import java.util.Locale;
import java.util.Map;

public record Ticket(
        String ticketId,
        String ticketKey,
        String networkId,
        String originServerId,
        String creatorUuid,
        String creatorName,
        String category,
        String subject,
        TicketStatus status,
        TicketPriority priority,
        String assigneeUuid,
        String assigneeName,
        long createdAtMs,
        long updatedAtMs,
        Long resolvedAtMs,
        Long closedAtMs,
        long lastActivityAtMs,
        long revision,
        Map<String, String> metadata
) {
    public Ticket {
        status = status != null ? status : TicketStatus.OPEN;
        priority = priority != null ? priority : TicketPriority.NORMAL;
        category = category != null && !category.isBlank() ? category.trim().toLowerCase(Locale.ROOT) : "general";
        subject = subject != null ? subject : "";
        metadata = metadata != null ? Map.copyOf(metadata) : Map.of();
        revision = Math.max(0L, revision);
    }

    public boolean isAssigned() {
        return assigneeUuid != null && !assigneeUuid.isBlank();
    }

    public boolean isCreatedBy(String uuid) {
        return creatorUuid != null && uuid != null && creatorUuid.equalsIgnoreCase(uuid);
    }

    public boolean isAssignedTo(String uuid) {
        return isAssigned() && uuid != null && assigneeUuid.equalsIgnoreCase(uuid);
    }

    public Ticket withRevision(long newRevision) {
        return new Ticket(ticketId, ticketKey, networkId, originServerId, creatorUuid, creatorName, category, subject,
                status, priority, assigneeUuid, assigneeName, createdAtMs, updatedAtMs, resolvedAtMs, closedAtMs,
                lastActivityAtMs, newRevision, metadata);
    }

    public Builder toBuilder() {
        return new Builder(this);
    }

    public static final class Builder {
        private final Ticket base;
        private String category;
        private String subject;
        private TicketStatus status;
        private TicketPriority priority;
        private String assigneeUuid;
        private String assigneeName;
        private long updatedAtMs;
        private Long resolvedAtMs;
        private Long closedAtMs;
        private long lastActivityAtMs;

        private Builder(Ticket base) {
            this.base = base;
            this.category = base.category();
            this.subject = base.subject();
            this.status = base.status();
            this.priority = base.priority();
            this.assigneeUuid = base.assigneeUuid();
            this.assigneeName = base.assigneeName();
            this.updatedAtMs = base.updatedAtMs();
            this.resolvedAtMs = base.resolvedAtMs();
            this.closedAtMs = base.closedAtMs();
            this.lastActivityAtMs = base.lastActivityAtMs();
        }

        public Builder category(String value) {
            this.category = value;
            return this;
        }

        public Builder subject(String value) {
            this.subject = value;
            return this;
        }

        public Builder status(TicketStatus value) {
            this.status = value;
            return this;
        }

        public Builder priority(TicketPriority value) {
            this.priority = value;
            return this;
        }

        public Builder assignee(String uuid, String name) {
            this.assigneeUuid = uuid;
            this.assigneeName = name;
            return this;
        }

        public Builder touch(long nowMs) {
            this.updatedAtMs = nowMs;
            this.lastActivityAtMs = nowMs;
            return this;
        }

        public Builder resolvedAtMs(Long value) {
            this.resolvedAtMs = value;
            return this;
        }

        public Builder closedAtMs(Long value) {
            this.closedAtMs = value;
            return this;
        }

        public Ticket build() {
            return new Ticket(
                    base.ticketId(),
                    base.ticketKey(),
                    base.networkId(),
                    base.originServerId(),
                    base.creatorUuid(),
                    base.creatorName(),
                    category,
                    subject,
                    status,
                    priority,
                    assigneeUuid,
                    assigneeName,
                    base.createdAtMs(),
                    updatedAtMs,
                    resolvedAtMs,
                    closedAtMs,
                    lastActivityAtMs,
                    base.revision(),
                    base.metadata()
            );
        }
    }
}
