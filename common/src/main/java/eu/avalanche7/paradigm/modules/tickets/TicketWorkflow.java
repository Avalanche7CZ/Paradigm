package eu.avalanche7.paradigm.modules.tickets;

import java.util.concurrent.TimeUnit;

public final class TicketWorkflow {

    private static final int MAX_SUBJECT_COLUMN_LENGTH = 255;

    private TicketWorkflow() {
    }

    public static TicketStatus statusAfterClaim(TicketStatus current) {
        if (current == TicketStatus.OPEN) {
            return TicketStatus.IN_PROGRESS;
        }
        return current;
    }

    public static TicketStatus statusAfterUnclaim(TicketStatus current) {
        if (current == TicketStatus.IN_PROGRESS) {
            return TicketStatus.OPEN;
        }
        return current;
    }

    public static TicketStatus statusAfterReply(TicketStatus current, TicketAuthorType authorType) {
        if (authorType == TicketAuthorType.SYSTEM) {
            return current;
        }
        if (authorType == TicketAuthorType.STAFF) {
            return TicketStatus.WAITING_PLAYER;
        }
        return TicketStatus.WAITING_STAFF;
    }

    public static TicketStatus statusAfterReopen(Ticket ticket) {
        return ticket != null && ticket.isAssigned() ? TicketStatus.IN_PROGRESS : TicketStatus.OPEN;
    }

    /** Replies never reopen terminal tickets implicitly. Reopening has its own permission/window checks. */
    public static boolean acceptsReply(Ticket ticket) {
        return ticket != null && !ticket.status().isTerminal();
    }

    public static boolean acceptsStaffWorkflow(Ticket ticket) {
        return ticket != null && ticket.status() != TicketStatus.CLOSED;
    }

    public static boolean isReopenable(Ticket ticket) {
        return ticket != null && ticket.status().isTerminal();
    }

    public static boolean playerMayReopen(Ticket ticket, long nowMs, int windowHours) {
        if (ticket == null || windowHours <= 0) {
            return false;
        }
        if (ticket.status() != TicketStatus.RESOLVED) {
            return false;
        }
        Long resolvedAt = ticket.resolvedAtMs();
        if (resolvedAt == null) {
            return false;
        }
        long deadline = resolvedAt + TimeUnit.HOURS.toMillis(windowHours);
        return nowMs <= deadline;
    }

    public static Long resolvedStampFor(TicketStatus status, Ticket previous, long nowMs) {
        if (status == TicketStatus.RESOLVED) {
            return previous != null && previous.status() == TicketStatus.RESOLVED && previous.resolvedAtMs() != null
                    ? previous.resolvedAtMs()
                    : nowMs;
        }
        return null;
    }

    public static Long closedStampFor(TicketStatus status, Ticket previous, long nowMs) {
        if (status == TicketStatus.CLOSED) {
            return previous != null && previous.status() == TicketStatus.CLOSED && previous.closedAtMs() != null
                    ? previous.closedAtMs()
                    : nowMs;
        }
        return null;
    }

    public static Ticket applyStatus(Ticket ticket, TicketStatus status, long nowMs) {
        return ticket.toBuilder()
                .status(status)
                .resolvedAtMs(resolvedStampFor(status, ticket, nowMs))
                .closedAtMs(closedStampFor(status, ticket, nowMs))
                .touch(nowMs)
                .build();
    }

    public static String deriveSubject(String message, int maxLength) {
        if (message == null) {
            return "";
        }
        String firstLine = message.strip();
        int newline = firstLine.indexOf('\n');
        if (newline >= 0) {
            firstLine = firstLine.substring(0, newline).strip();
        }
        firstLine = firstLine.replaceAll("\\s+", " ");
        int limit = Math.min(MAX_SUBJECT_COLUMN_LENGTH, Math.max(8, maxLength));
        if (firstLine.length() <= limit) {
            return firstLine;
        }
        return firstLine.substring(0, limit - 1).stripTrailing() + "…";
    }

    public static TicketError validateMessage(String message, int maxLength) {
        if (message == null || message.isBlank()) {
            return TicketError.MESSAGE_EMPTY;
        }
        if (maxLength > 0 && message.strip().length() > maxLength) {
            return TicketError.MESSAGE_TOO_LONG;
        }
        return TicketError.NONE;
    }

    public static TicketError validateOpenLimit(int currentOpen, int maxOpen) {
        if (maxOpen > 0 && currentOpen >= maxOpen) {
            return TicketError.TICKET_LIMIT_REACHED;
        }
        return TicketError.NONE;
    }

    public static long cooldownRemainingMs(Long lastCreatedAtMs, long nowMs, int cooldownSeconds) {
        if (lastCreatedAtMs == null || cooldownSeconds <= 0) {
            return 0L;
        }
        long ready = lastCreatedAtMs + TimeUnit.SECONDS.toMillis(cooldownSeconds);
        return Math.max(0L, ready - nowMs);
    }
}
