package eu.avalanche7.paradigm.modules.tickets;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import eu.avalanche7.paradigm.core.Services;
import eu.avalanche7.paradigm.core.network.NetworkEvent;
import eu.avalanche7.paradigm.core.network.NetworkEventBus;
import eu.avalanche7.paradigm.core.network.NetworkEventHandler;
import eu.avalanche7.paradigm.core.network.NetworkEventSource;
import eu.avalanche7.paradigm.storage.StorageService;

public final class TicketNetworkEvents {

    public static final String CHANNEL = "paradigm:tickets";
    public static final String PAYLOAD_TICKET_KEY = "ticketKey";
    public static final String PAYLOAD_ACTOR_NAME = "actorName";

    private TicketNetworkEvents() {
    }

    public static NetworkEventSource source(Services services, TicketService tickets) {
        return new TicketEventSource(services, tickets);
    }

    public static NetworkEventHandler handler(TicketService tickets, TicketNotifier notifier) {
        return event -> {
            if (event == null || notifier == null || tickets == null) {
                return;
            }
            String ticketKey = event.payload(PAYLOAD_TICKET_KEY);
            TicketRepository repository = tickets.repository();
            if (ticketKey == null || repository == null) {
                return;
            }
            repository.findTicket(event.networkId(), ticketKey)
                    .ifPresent(ticket -> notifier.remoteEvent(ticket, toTicketEvent(event, ticket)));
        };
    }

    public static void install(NetworkEventBus bus, Services services, TicketService tickets, TicketNotifier notifier) {
        if (bus == null) {
            return;
        }
        bus.registerSource(source(services, tickets));
        bus.subscribe(CHANNEL, handler(tickets, notifier));
    }

    public static NetworkEvent toNetworkEvent(TicketEvent event) {
        Map<String, String> payload = new LinkedHashMap<>();
        payload.put(PAYLOAD_TICKET_KEY, event.ticketKey());
        if (event.actorName() != null) {
            payload.put(PAYLOAD_ACTOR_NAME, event.actorName());
        }
        return new NetworkEvent(CHANNEL, event.eventId(), event.networkId(), event.serverId(),
                event.eventType().name(), event.createdAtMs(), payload);
    }

    private static TicketEvent toTicketEvent(NetworkEvent event, Ticket ticket) {
        return new TicketEvent(event.eventId(), ticket.ticketId(), event.networkId(),
                ticket.ticketKey(), TicketEventType.parseOr(event.type(), TicketEventType.STATUS_CHANGED),
                null, event.payload(PAYLOAD_ACTOR_NAME), event.originServerId(), null, null, event.createdAtMs());
    }

    private record TicketEventSource(Services services, TicketService tickets) implements NetworkEventSource {

        @Override
        public String channel() {
            return CHANNEL;
        }

        @Override
        public boolean available() {
            if (services == null || tickets == null) {
                return false;
            }
            StorageService storage = services.getStorageService();
            if (storage == null || !storage.isSqlActive()) {
                return false;
            }
            TicketRepository repository = tickets.repository();
            return repository != null && repository.supportsCrossServerFeed();
        }

        @Override
        public List<NetworkEvent> fetchSince(long sinceMs, String afterEventId, String excludeServerId, int limit) {
            TicketRepository repository = tickets != null ? tickets.repository() : null;
            if (repository == null) {
                return List.of();
            }
            List<NetworkEvent> events = new ArrayList<>();
            for (TicketEvent event : repository.listEventsSince(tickets.networkId(), sinceMs, afterEventId,
                    excludeServerId, limit)) {
                events.add(toNetworkEvent(event));
            }
            return events;
        }
    }
}
