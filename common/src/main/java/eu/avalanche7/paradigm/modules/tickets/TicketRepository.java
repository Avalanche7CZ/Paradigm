package eu.avalanche7.paradigm.modules.tickets;

import java.util.List;
import java.util.Map;
import java.util.Optional;

public interface TicketRepository {

    long allocateTicketNumber(String networkId);

    TicketWriteResult createTicket(TicketCreate create);

    TicketWriteResult applyMutation(TicketMutation mutation);

    Optional<Ticket> findTicket(String networkId, String ticketKey);

    List<Ticket> listTickets(TicketQuery query);

    int countTickets(TicketQuery query);

    Map<String, Integer> summaryCounts(String networkId);

    int countActiveByCreator(String networkId, String creatorUuid);

    Optional<Long> lastCreatedAtByCreator(String networkId, String creatorUuid);

    List<TicketMessage> listMessages(String networkId, String ticketKey, int offset, int limit);

    List<TicketEvent> listEvents(String networkId, String ticketKey);

    List<Ticket> findAutoCloseCandidates(String networkId, Long resolvedBeforeMs, Long waitingBeforeMs, int limit);

    default List<TicketEvent> listEventsSince(String networkId, long sinceMs, String excludeServerId, int limit) {
        return List.of();
    }

    default List<TicketEvent> listEventsSince(String networkId, long sinceMs, String afterEventId,
                                              String excludeServerId, int limit) {
        return listEventsSince(networkId, sinceMs, excludeServerId, limit);
    }

    default boolean supportsCrossServerFeed() {
        return false;
    }
}
