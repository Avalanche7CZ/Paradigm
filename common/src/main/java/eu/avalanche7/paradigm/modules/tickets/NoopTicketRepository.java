package eu.avalanche7.paradigm.modules.tickets;

import java.util.List;
import java.util.Map;
import java.util.Optional;

public final class NoopTicketRepository implements TicketRepository {

    public static final NoopTicketRepository INSTANCE = new NoopTicketRepository();

    private NoopTicketRepository() {
    }

    @Override
    public long allocateTicketNumber(String networkId) {
        return 0L;
    }

    @Override
    public TicketWriteResult createTicket(TicketCreate create) {
        return TicketWriteResult.unsupported();
    }

    @Override
    public TicketWriteResult applyMutation(TicketMutation mutation) {
        return TicketWriteResult.unsupported();
    }

    @Override
    public Optional<Ticket> findTicket(String networkId, String ticketKey) {
        return Optional.empty();
    }

    @Override
    public List<Ticket> listTickets(TicketQuery query) {
        return List.of();
    }

    @Override
    public int countTickets(TicketQuery query) {
        return 0;
    }

    @Override
    public Map<String, Integer> summaryCounts(String networkId) {
        return TicketSummaries.empty();
    }

    @Override
    public int countActiveByCreator(String networkId, String creatorUuid) {
        return 0;
    }

    @Override
    public Optional<Long> lastCreatedAtByCreator(String networkId, String creatorUuid) {
        return Optional.empty();
    }

    @Override
    public List<TicketMessage> listMessages(String networkId, String ticketKey, int offset, int limit) {
        return List.of();
    }

    @Override
    public List<TicketEvent> listEvents(String networkId, String ticketKey) {
        return List.of();
    }

    @Override
    public List<Ticket> findAutoCloseCandidates(String networkId, Long resolvedBeforeMs, Long waitingBeforeMs, int limit) {
        return List.of();
    }
}
