package eu.avalanche7.paradigm.modules.tickets;

import java.util.List;

import eu.avalanche7.paradigm.core.Services;
import eu.avalanche7.paradigm.modules.commands.shared.ChatUi;
import eu.avalanche7.paradigm.modules.permissions.ParadigmPermissions;
import eu.avalanche7.paradigm.platform.Interfaces.ICommandSource;
import eu.avalanche7.paradigm.platform.Interfaces.IComponent;
import eu.avalanche7.paradigm.utils.DurationFormatter;

public class TicketChatView {

    private final Services services;

    public TicketChatView(Services services) {
        this.services = services;
    }

    public void sendCreated(ICommandSource source, Ticket ticket) {
        onServerThread(() -> sendCreatedNow(source, ticket));
    }

    private void sendCreatedNow(ICommandSource source, Ticket ticket) {
        sendNow(source, ChatUi.header(services, "Ticket " + ticket.ticketKey() + " created"));
        sendNow(source, ChatUi.row(services)
                .append(ChatUi.text(services, "  " + ticket.subject() + " ", ChatUi.BODY)));
        sendNow(source, ChatUi.row(services)
                .append(ChatUi.text(services, "  ", ChatUi.MUTED))
                .append(ChatUi.button(services, "[View Ticket]", "/ticket view " + ticket.ticketKey(), true,
                        "Show this ticket"))
                .append(ChatUi.space(services))
                .append(ChatUi.button(services, "[Add Reply]", "/ticket reply " + ticket.ticketKey() + " ", false,
                        "Add another message")));
    }

    public void sendList(ICommandSource source, TicketActor actor, TicketPage page, boolean staffView) {
        onServerThread(() -> sendListNow(source, actor, page, staffView));
    }

    private void sendListNow(ICommandSource source, TicketActor actor, TicketPage page, boolean staffView) {
        String title = staffView ? "Ticket Queue" : "Your Tickets";
        sendNow(source, ChatUi.header(services, title + " (" + page.total() + ")"));
        if (page.tickets().isEmpty()) {
            sendNow(source, ChatUi.row(services)
                    .append(ChatUi.text(services, "  No tickets to show.", ChatUi.MUTED)));
            return;
        }
        for (Ticket ticket : page.tickets()) {
            sendNow(source, summaryLine(ticket, staffView));
            sendNow(source, detailLine(ticket, actor, staffView));
        }
        sendPagination(source, page, staffView);
    }

    public void sendTicket(ICommandSource source, TicketActor actor, Ticket ticket,
                           List<TicketMessage> messages, boolean staffView) {
        List<TicketMessage> snapshot = messages != null ? List.copyOf(messages) : List.of();
        onServerThread(() -> sendTicketNow(source, actor, ticket, snapshot, staffView));
    }

    private void sendTicketNow(ICommandSource source, TicketActor actor, Ticket ticket,
                               List<TicketMessage> messages, boolean staffView) {
        sendNow(source, ChatUi.header(services, "Ticket " + ticket.ticketKey()));
        sendNow(source, ChatUi.row(services)
                .append(ChatUi.text(services, "  " + ticket.subject(), ChatUi.TITLE).withFormatting("bold")));
        sendNow(source, ChatUi.row(services)
                .append(ChatUi.text(services, "  " + ticket.status().name() + " ", TicketText.statusColor(ticket.status())))
                .append(ChatUi.text(services, "· " + ticket.priority().name() + " ", TicketText.priorityColor(ticket.priority())))
                .append(ChatUi.text(services, "· " + ticket.category() + " ", ChatUi.MUTED))
                .append(ChatUi.text(services, "· " + relative(ticket.lastActivityAtMs()), ChatUi.MUTED)));
        sendNow(source, ChatUi.row(services)
                .append(ChatUi.text(services, "  by " + TicketText.safe(ticket.creatorName()), ChatUi.BODY))
                .append(ChatUi.text(services, ticket.isAssigned()
                        ? " · assigned to " + TicketText.safe(ticket.assigneeName())
                        : " · unassigned", ChatUi.MUTED))
                .append(ChatUi.text(services, " · from " + TicketText.safe(ticket.originServerId()), ChatUi.MUTED)));

        if (!messages.isEmpty()) {
            sendNow(source, ChatUi.row(services).append(ChatUi.text(services, "  ---- thread ----", ChatUi.DIM)));
            for (TicketMessage message : messages) {
                sendNow(source, ChatUi.row(services)
                        .append(ChatUi.text(services, "  " + authorTag(message) + " ", authorColor(message)))
                        .append(ChatUi.text(services, message.text(), ChatUi.BODY)));
            }
        }
        sendNow(source, actionRow(ticket, actor, staffView));
    }

    private IComponent summaryLine(Ticket ticket, boolean staffView) {
        IComponent line = ChatUi.row(services)
                .append(ChatUi.text(services, "[" + ticket.ticketKey() + "] ", ChatUi.ACCENT).withFormatting("bold"));
        if (staffView) {
            line = line.append(ChatUi.text(services, TicketText.safe(ticket.creatorName()) + " · ", ChatUi.BODY))
                    .append(ChatUi.text(services, ticket.priority().name() + " ", TicketText.priorityColor(ticket.priority())))
                    .append(ChatUi.text(services, "· " + ticket.status().name(), TicketText.statusColor(ticket.status())));
        } else {
            line = line.append(ChatUi.text(services, ticket.subject(), ChatUi.BODY));
        }
        return line;
    }

    private IComponent detailLine(Ticket ticket, TicketActor actor, boolean staffView) {
        IComponent line = ChatUi.row(services).append(ChatUi.text(services, "  ", ChatUi.MUTED));
        if (staffView) {
            line = line.append(ChatUi.text(services, TicketText.trim(ticket.subject(), 42) + " ", ChatUi.MUTED));
        } else {
            line = line.append(ChatUi.text(services, ticket.status().name() + " ", TicketText.statusColor(ticket.status())))
                    .append(ChatUi.text(services, "· " + relative(ticket.lastActivityAtMs()) + " ", ChatUi.MUTED));
        }
        line = line.append(ChatUi.button(services, "[Open]", "/ticket view " + ticket.ticketKey(), true,
                "Show this ticket"));
        if (staffView && !ticket.isAssigned() && ticket.status().isActive()
                && actor.has(ParadigmPermissions.TICKET_STAFF_CLAIM)) {
            line = line.append(ChatUi.space(services))
                    .append(ChatUi.button(services, "[Claim]", "/ticket claim " + ticket.ticketKey(), true,
                            "Assign this ticket to yourself", ChatUi.SUCCESS));
        }
        return line;
    }

    private IComponent actionRow(Ticket ticket, TicketActor actor, boolean staffView) {
        IComponent row = ChatUi.row(services).append(ChatUi.text(services, "  ", ChatUi.MUTED));
        boolean creator = ticket.isCreatedBy(actor.uuid());
        boolean any = false;

        if (!ticket.status().isTerminal()
                && (creator || (staffView && actor.has(ParadigmPermissions.TICKET_STAFF_REPLY)))) {
            row = row.append(ChatUi.button(services, "[Reply]", "/ticket reply " + ticket.ticketKey() + " ", false,
                    "Add a message to this ticket"));
            any = true;
        }
        if (staffView && ticket.status().isActive() && actor.has(ParadigmPermissions.TICKET_STAFF_CLAIM)) {
            row = appendSpaced(row, any, ticket.isAssigned()
                    ? ChatUi.button(services, "[Unclaim]", "/ticket unclaim " + ticket.ticketKey(), true,
                            "Remove the current assignment", ChatUi.SUGGEST)
                    : ChatUi.button(services, "[Claim]", "/ticket claim " + ticket.ticketKey(), true,
                            "Assign this ticket to yourself", ChatUi.SUCCESS));
            any = true;
        }
        if (staffView && ticket.status().isActive() && actor.has(ParadigmPermissions.TICKET_STAFF_RESOLVE)) {
            row = appendSpaced(row, any, ChatUi.button(services, "[Resolve]", "/ticket resolve " + ticket.ticketKey(),
                    true, "Mark this ticket resolved", ChatUi.SUCCESS));
            any = true;
        }
        if (ticket.status() != TicketStatus.CLOSED
                && (creator || (staffView && actor.has(ParadigmPermissions.TICKET_STAFF_CLOSE)))) {
            row = appendSpaced(row, any, ChatUi.button(services, "[Close]", "/ticket close " + ticket.ticketKey(),
                    true, "Close this ticket", ChatUi.DANGER));
            any = true;
        }
        if (ticket.status().isTerminal()
                && (creator || (staffView && actor.has(ParadigmPermissions.TICKET_STAFF_REOPEN)))) {
            row = appendSpaced(row, any, ChatUi.button(services, "[Reopen]", "/ticket reopen " + ticket.ticketKey(),
                    true, "Reopen this ticket", ChatUi.INFO));
            any = true;
        }
        if (!any) {
            return ChatUi.row(services).append(ChatUi.text(services, "  No actions available.", ChatUi.MUTED));
        }
        return row;
    }

    private void sendPagination(ICommandSource source, TicketPage page, boolean staffView) {
        if (page.totalPages() <= 1) {
            return;
        }
        String root = staffView ? "/tickets " : "/ticket list ";
        IComponent row = ChatUi.row(services)
                .append(ChatUi.text(services, "  page " + page.page() + "/" + page.totalPages() + " ", ChatUi.MUTED));
        if (page.hasPrevious()) {
            row = row.append(ChatUi.button(services, "[<-]", root + (page.page() - 1), true, "Previous page"))
                    .append(ChatUi.space(services));
        }
        if (page.hasNext()) {
            row = row.append(ChatUi.button(services, "[->]", root + (page.page() + 1), true, "Next page"));
        }
        sendNow(source, row);
    }

    private IComponent appendSpaced(IComponent row, boolean needsSpace, IComponent addition) {
        return needsSpace ? row.append(ChatUi.space(services)).append(addition) : row.append(addition);
    }

    private void sendNow(ICommandSource source, IComponent component) {
        services.getPlatformAdapter().sendSuccess(source, component, false);
    }

    private void onServerThread(Runnable task) {
        if (task == null || services == null || services.getPlatformAdapter() == null) {
            return;
        }
        services.getPlatformAdapter().executeOnServerThread(task);
    }

    private static String authorTag(TicketMessage message) {
        String name = message.authorName() != null ? message.authorName() : "system";
        return switch (message.authorType()) {
            case STAFF -> name + " (staff):";
            case SYSTEM -> "system:";
            default -> name + ":";
        };
    }

    private static String authorColor(TicketMessage message) {
        return switch (message.authorType()) {
            case STAFF -> ChatUi.INFO;
            case SYSTEM -> ChatUi.MUTED;
            default -> ChatUi.SUCCESS;
        };
    }

    private static String relative(long timestampMs) {
        long delta = Math.max(0L, System.currentTimeMillis() - timestampMs);
        return DurationFormatter.humanize(delta) + " ago";
    }
}
