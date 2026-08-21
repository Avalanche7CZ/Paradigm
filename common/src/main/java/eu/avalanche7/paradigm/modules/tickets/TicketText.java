package eu.avalanche7.paradigm.modules.tickets;

import eu.avalanche7.paradigm.modules.commands.shared.ChatUi;

final class TicketText {

    private TicketText() {
    }

    static String statusColor(TicketStatus status) {
        return switch (status) {
            case OPEN -> ChatUi.SUCCESS;
            case IN_PROGRESS -> ChatUi.INFO;
            case WAITING_STAFF -> ChatUi.SUGGEST;
            case WAITING_PLAYER -> ChatUi.MUTED;
            case RESOLVED -> ChatUi.ACCENT;
            case CLOSED -> ChatUi.DIM;
        };
    }

    static String priorityColor(TicketPriority priority) {
        return switch (priority) {
            case URGENT -> ChatUi.DANGER;
            case HIGH -> ChatUi.SUGGEST;
            case LOW -> ChatUi.MUTED;
            default -> ChatUi.BODY;
        };
    }

    static String safe(String value) {
        return value != null && !value.isBlank() ? value : "unknown";
    }

    static String trim(String value, int maxLength) {
        if (value == null) {
            return "";
        }
        return value.length() <= maxLength ? value : value.substring(0, maxLength - 1) + "…";
    }
}
