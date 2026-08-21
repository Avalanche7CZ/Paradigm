package eu.avalanche7.paradigm.modules.tickets;

import java.util.List;

import eu.avalanche7.paradigm.configs.TicketsConfigHandler;
import eu.avalanche7.paradigm.core.Services;
import eu.avalanche7.paradigm.modules.commands.shared.ChatUi;
import eu.avalanche7.paradigm.modules.permissions.ParadigmPermissions;
import eu.avalanche7.paradigm.platform.Interfaces.IComponent;
import eu.avalanche7.paradigm.platform.Interfaces.IPlayer;

public class TicketNotifier {

    private final Services services;

    public TicketNotifier(Services services) {
        this.services = services;
    }

    public void ticketCreated(Ticket ticket, TicketEvent event) {
        onServerThread(() -> ticketCreatedOnServer(ticket));
    }

    private void ticketCreatedOnServer(Ticket ticket) {
        if (!staffNotificationsEnabled() || ticket == null) {
            return;
        }
        IComponent line = ChatUi.row(services)
                .append(ChatUi.text(services, "[Ticket] ", ChatUi.ACCENT).withFormatting("bold"))
                .append(ChatUi.text(services, ticket.ticketKey() + " ", ChatUi.TITLE).withFormatting("bold"))
                .append(ChatUi.text(services, "from " + TicketText.safe(ticket.creatorName()) + " · ", ChatUi.BODY))
                .append(ChatUi.text(services, ticket.priority().name(), TicketText.priorityColor(ticket.priority())))
                .append(ChatUi.space(services))
                .append(ChatUi.text(services, "· " + ticket.category(), ChatUi.MUTED));
        broadcastStaff(ticket, line);

        IComponent subject = ChatUi.row(services)
                .append(ChatUi.text(services, "  " + TicketText.trim(ticket.subject(), 48) + " ", ChatUi.BODY))
                .append(ChatUi.button(services, "[Open]", "/ticket view " + ticket.ticketKey(), true, "View this ticket"))
                .append(ChatUi.space(services))
                .append(ChatUi.button(services, "[Claim]", "/ticket claim " + ticket.ticketKey(), true,
                        "Claim this ticket", ChatUi.SUCCESS));
        broadcastStaff(ticket, subject);
    }

    public void ticketReplied(Ticket ticket, TicketEvent event, TicketMessage message) {
        onServerThread(() -> ticketRepliedOnServer(ticket, message));
    }

    private void ticketRepliedOnServer(Ticket ticket, TicketMessage message) {
        if (ticket == null || message == null) {
            return;
        }
        if (message.authorType() == TicketAuthorType.STAFF) {
            notifyCreatorOnServer(ticket, "Staff replied to " + ticket.ticketKey() + ".", true);
            return;
        }
        if (!staffNotificationsEnabled()) {
            return;
        }
        IComponent line = ChatUi.row(services)
                .append(ChatUi.text(services, "[Ticket] ", ChatUi.ACCENT).withFormatting("bold"))
                .append(ChatUi.text(services, TicketText.safe(ticket.creatorName()) + " replied to " + ticket.ticketKey() + " ", ChatUi.BODY))
                .append(ChatUi.button(services, "[Open]", "/ticket view " + ticket.ticketKey(), true, "View this ticket"));
        if (ticket.isAssigned()) {
            sendToPlayerOnServer(ticket.assigneeUuid(), line);
        } else {
            broadcastStaff(ticket, line);
        }
    }

    public void ticketClaimed(Ticket ticket, TicketEvent event) {
        if (ticket == null) {
            return;
        }
        onServerThread(() -> notifyCreatorOnServer(ticket, "Ticket " + ticket.ticketKey() + " was claimed by "
                + TicketText.safe(ticket.assigneeName()) + ".", false));
    }

    public void ticketAssigned(Ticket ticket, TicketEvent event) {
        onServerThread(() -> {
            if (ticket == null || !ticket.isAssigned()) {
                return;
            }
            IComponent line = ChatUi.row(services)
                    .append(ChatUi.text(services, "[Ticket] ", ChatUi.ACCENT).withFormatting("bold"))
                    .append(ChatUi.text(services, "You were assigned " + ticket.ticketKey() + " ", ChatUi.BODY))
                    .append(ChatUi.button(services, "[Open]", "/ticket view " + ticket.ticketKey(), true, "View this ticket"));
            sendToPlayerOnServer(ticket.assigneeUuid(), line);
        });
    }

    public void ticketResolved(Ticket ticket, TicketEvent event) {
        if (ticket == null) {
            return;
        }
        onServerThread(() -> notifyCreatorOnServer(ticket, "Ticket " + ticket.ticketKey() + " was marked resolved.", true));
    }

    public void ticketClosed(Ticket ticket, TicketEvent event) {
        if (ticket == null) {
            return;
        }
        onServerThread(() -> notifyCreatorOnServer(ticket, "Ticket " + ticket.ticketKey() + " was closed.", false));
    }

    public void ticketReopened(Ticket ticket, TicketEvent event) {
        if (ticket == null) {
            return;
        }
        onServerThread(() -> {
            notifyCreatorOnServer(ticket, "Ticket " + ticket.ticketKey() + " was reopened.", true);
            if (staffNotificationsEnabled()) {
                IComponent line = ChatUi.row(services)
                        .append(ChatUi.text(services, "[Ticket] ", ChatUi.ACCENT).withFormatting("bold"))
                        .append(ChatUi.text(services, ticket.ticketKey() + " was reopened ", ChatUi.BODY))
                        .append(ChatUi.button(services, "[Open]", "/ticket view " + ticket.ticketKey(), true, "View this ticket"));
                broadcastStaff(ticket, line);
            }
        });
    }

    public void remoteEvent(Ticket ticket, TicketEvent event) {
        onServerThread(() -> remoteEventOnServer(ticket, event));
    }

    private void remoteEventOnServer(Ticket ticket, TicketEvent event) {
        if (ticket == null || event == null || !staffNotificationsEnabled()) {
            return;
        }
        String description = switch (event.eventType()) {
            case CREATED -> "opened on " + TicketText.safe(event.serverId());
            case REPLIED -> "has a new reply on " + TicketText.safe(event.serverId());
            case REOPENED -> "was reopened on " + TicketText.safe(event.serverId());
            default -> null;
        };
        if (description == null) {
            return;
        }
        IComponent line = ChatUi.row(services)
                .append(ChatUi.text(services, "[Ticket] ", ChatUi.ACCENT).withFormatting("bold"))
                .append(ChatUi.text(services, ticket.ticketKey() + " " + description + " ", ChatUi.BODY))
                .append(ChatUi.button(services, "[Open]", "/ticket view " + ticket.ticketKey(), true, "View this ticket"));
        broadcastStaff(ticket, line);
    }

    private void notifyCreatorOnServer(Ticket ticket, String message, boolean actionable) {
        if (ticket == null || !creatorNotificationsEnabled()) {
            return;
        }
        IComponent line = ChatUi.row(services)
                .append(ChatUi.text(services, "[Ticket] ", ChatUi.ACCENT).withFormatting("bold"))
                .append(ChatUi.text(services, message + " ", ChatUi.BODY));
        if (actionable) {
            line = line.append(ChatUi.button(services, "[Open]", "/ticket view " + ticket.ticketKey(), true,
                    "View this ticket"));
        }
        sendToPlayerOnServer(ticket.creatorUuid(), line);
    }

    /**
     * Broadcast only to staff who can actually handle this ticket's category.
     * This keeps category-specific staffPermission metadata private too.
     */
    private void broadcastStaff(Ticket ticket, IComponent component) {
        if (ticket == null || component == null || services == null || services.getPlatformAdapter() == null) {
            return;
        }
        TicketsConfigHandler.Config config = config();
        TicketsConfigHandler.CategoryEntry category = config != null ? config.category(ticket.category()) : null;
        List<IPlayer> online = services.getPlatformAdapter().getOnlinePlayers();
        if (online == null) {
            return;
        }
        for (IPlayer player : online) {
            if (player == null) {
                continue;
            }
            TicketActor actor = TicketActor.of(player, services.getPermissionsHandler());
            if (!actor.has(ParadigmPermissions.TICKET_STAFF_VIEW)
                    || !TicketCategories.mayStaffHandle(category, actor)) {
                continue;
            }
            services.getPlatformAdapter().sendSystemMessage(player, component);
        }
    }

    private void sendToPlayerOnServer(String uuid, IComponent component) {
        if (uuid == null || services == null || services.getPlatformAdapter() == null) {
            return;
        }
        for (IPlayer online : services.getPlatformAdapter().getOnlinePlayers()) {
            if (online != null && uuid.equalsIgnoreCase(online.getUUID())) {
                services.getPlatformAdapter().sendSystemMessage(online, component);
                return;
            }
        }
    }

    private void onServerThread(Runnable task) {
        if (task == null || services == null || services.getPlatformAdapter() == null) {
            return;
        }
        services.getPlatformAdapter().executeOnServerThread(task);
    }

    private boolean staffNotificationsEnabled() {
        TicketsConfigHandler.Config config = config();
        return config != null && Boolean.TRUE.equals(config.staffNotifyEnabled.value);
    }

    private boolean creatorNotificationsEnabled() {
        TicketsConfigHandler.Config config = config();
        return config != null && Boolean.TRUE.equals(config.creatorNotifyEnabled.value);
    }

    private static TicketsConfigHandler.Config config() {
        return TicketsConfigHandler.configOrNull();
    }
}
