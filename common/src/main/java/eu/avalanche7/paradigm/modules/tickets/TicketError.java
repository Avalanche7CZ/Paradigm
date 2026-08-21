package eu.avalanche7.paradigm.modules.tickets;

public enum TicketError {
    NONE("ok", "tickets.error.ok", "Done."),
    TICKETS_DISABLED("tickets_disabled", "tickets.error.disabled", "The ticket system is disabled."),
    STORAGE_UNAVAILABLE("storage_unavailable", "tickets.error.storage_unavailable", "Ticket storage is unavailable."),
    TICKET_NOT_FOUND("ticket_not_found", "tickets.error.not_found", "Ticket {ticket} was not found."),
    TICKET_CLOSED("ticket_closed", "tickets.error.closed", "Ticket {ticket} is closed. Reopen it first."),
    STALE_TICKET("stale_ticket", "tickets.error.stale", "Ticket {ticket} changed while you were working on it. Reload and try again."),
    ALREADY_CLAIMED("ticket_already_claimed", "tickets.error.already_claimed", "Ticket {ticket} is already claimed by {actor}."),
    NOT_CLAIMED("ticket_not_claimed", "tickets.error.not_claimed", "Ticket {ticket} is not assigned to anyone."),
    INVALID_ASSIGNEE("invalid_assignee", "tickets.error.invalid_assignee", "{actor} is not a player Paradigm knows about."),
    INVALID_STATUS("invalid_status", "tickets.error.invalid_status", "Unknown ticket status."),
    INVALID_STATUS_TRANSITION("invalid_status_transition", "tickets.error.invalid_transition", "Ticket {ticket} cannot move to that status."),
    INVALID_PRIORITY("invalid_priority", "tickets.error.invalid_priority", "Unknown ticket priority."),
    INVALID_CATEGORY("invalid_category", "tickets.error.invalid_category", "Unknown or disabled ticket category."),
    URGENT_NOT_ALLOWED("urgent_not_allowed", "tickets.error.urgent_not_allowed", "You may not set URGENT priority."),
    TICKET_LIMIT_REACHED("ticket_limit_reached", "tickets.error.limit_reached", "You already have {limit} open tickets."),
    TICKET_COOLDOWN("ticket_cooldown", "tickets.error.cooldown", "Please wait {duration} before creating another ticket."),
    MESSAGE_EMPTY("message_empty", "tickets.error.message_empty", "Your message cannot be empty."),
    MESSAGE_TOO_LONG("message_too_long", "tickets.error.message_too_long", "Your message is longer than {limit} characters."),
    REOPEN_WINDOW_EXPIRED("reopen_window_expired", "tickets.error.reopen_window", "Ticket {ticket} can no longer be reopened by you. Ask staff."),
    PERMISSION_DENIED("permission_denied", "tickets.error.permission_denied", "You do not have permission to do that.");

    private final String code;
    private final String langKey;
    private final String fallback;

    TicketError(String code, String langKey, String fallback) {
        this.code = code;
        this.langKey = langKey;
        this.fallback = fallback;
    }

    public String code() {
        return code;
    }

    public String langKey() {
        return langKey;
    }

    public String fallback() {
        return fallback;
    }

    public boolean isFailure() {
        return this != NONE;
    }
}
