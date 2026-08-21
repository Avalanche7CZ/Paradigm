package eu.avalanche7.paradigm.modules.tickets;

import java.io.IOException;
import java.io.Reader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.stream.Stream;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import org.slf4j.Logger;

import eu.avalanche7.paradigm.platform.Interfaces.IConfig;
import eu.avalanche7.paradigm.storage.identity.StorageContext;
import eu.avalanche7.paradigm.utils.AtomicFileIO;

public class JsonTicketRepository implements TicketRepository {

    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().disableHtmlEscaping().create();
    private static final String ROOT = "paradigm/tickets";

    private final IConfig config;
    private final StorageContext context;
    private final Logger logger;
    private final Object lock = new Object();
    private final Map<String, Map<String, Ticket>> index = new LinkedHashMap<>();
    private boolean indexLoaded;

    public JsonTicketRepository(IConfig config, StorageContext context, Logger logger) {
        this.config = config;
        this.context = context;
        this.logger = logger;
    }

    @Override
    public long allocateTicketNumber(String networkId) {
        String network = network(networkId);
        synchronized (lock) {
            SequenceState state = loadSequences();
            long next = state.sequences.getOrDefault(network, 0L) + 1L;
            state.sequences.put(network, next);
            saveSequences(state);
            return next;
        }
    }

    @Override
    public TicketWriteResult createTicket(TicketCreate create) {
        Ticket ticket = create.ticket();
        synchronized (lock) {
            ensureIndex();
            String network = network(ticket.networkId());
            ThreadFile existing = readThread(network, ticket.ticketKey());
            if (existing != null) {
                if (existing.ticket != null) {
                    indexOf(network).put(existing.ticket.ticketKey(), existing.ticket);
                }
                return TicketWriteResult.stale(existing.ticket);
            }
            ThreadFile file = new ThreadFile();
            file.ticket = ticket;
            file.messages = create.firstMessage() != null ? new ArrayList<>(List.of(create.firstMessage())) : new ArrayList<>();
            file.events = create.createdEvent() != null ? new ArrayList<>(List.of(create.createdEvent())) : new ArrayList<>();
            if (!writeThread(network, file)) {
                return TicketWriteResult.unsupported();
            }
            indexOf(network).put(ticket.ticketKey(), ticket);
            return TicketWriteResult.ok(ticket);
        }
    }

    @Override
    public TicketWriteResult applyMutation(TicketMutation mutation) {
        synchronized (lock) {
            ensureIndex();
            String network = network(mutation.networkId());
            ThreadFile file = readThread(network, mutation.ticketKey());
            if (file == null || file.ticket == null) {
                indexOf(network).remove(mutation.ticketKey());
                return TicketWriteResult.notFound();
            }
            Ticket current = file.ticket;
            if (mutation.requireUnassigned() && current.isAssigned()) {
                indexOf(network).put(current.ticketKey(), current);
                return TicketWriteResult.alreadyClaimed(current);
            }
            if (current.revision() != mutation.expectedRevision()) {
                indexOf(network).put(current.ticketKey(), current);
                return TicketWriteResult.stale(current);
            }
            Ticket updated = mutation.updated().withRevision(mutation.expectedRevision() + 1);
            file.ticket = updated;
            if (mutation.message() != null) {
                file.messages.add(mutation.message());
            }
            file.events.addAll(mutation.events());
            if (!writeThread(network, file)) {
                return TicketWriteResult.unsupported();
            }
            indexOf(network).put(updated.ticketKey(), updated);
            return TicketWriteResult.ok(updated);
        }
    }

    @Override
    public Optional<Ticket> findTicket(String networkId, String ticketKey) {
        if (ticketKey == null) {
            return Optional.empty();
        }
        synchronized (lock) {
            ensureIndex();
            String network = network(networkId);
            ThreadFile file = readThread(network, ticketKey);
            if (file == null || file.ticket == null) {
                indexOf(network).remove(ticketKey);
                return Optional.empty();
            }
            indexOf(network).put(ticketKey, file.ticket);
            return Optional.of(file.ticket);
        }
    }

    @Override
    public List<Ticket> listTickets(TicketQuery query) {
        synchronized (lock) {
            ensureIndex();
            return matching(query).stream()
                    .skip(query.offset())
                    .limit(query.pageSize())
                    .toList();
        }
    }

    @Override
    public int countTickets(TicketQuery query) {
        synchronized (lock) {
            ensureIndex();
            return matching(query).size();
        }
    }

    @Override
    public Map<String, Integer> summaryCounts(String networkId) {
        synchronized (lock) {
            ensureIndex();
            Map<String, Integer> counts = new LinkedHashMap<>();
            for (Ticket ticket : indexOf(network(networkId)).values()) {
                TicketSummaries.accumulate(counts, ticket.status(), ticket.priority(), !ticket.isAssigned());
            }
            return TicketSummaries.finish(counts);
        }
    }

    @Override
    public int countActiveByCreator(String networkId, String creatorUuid) {
        if (creatorUuid == null) {
            return 0;
        }
        synchronized (lock) {
            ensureIndex();
            return (int) indexOf(network(networkId)).values().stream()
                    .filter(ticket -> ticket.status().isActive() && ticket.isCreatedBy(creatorUuid))
                    .count();
        }
    }

    @Override
    public Optional<Long> lastCreatedAtByCreator(String networkId, String creatorUuid) {
        if (creatorUuid == null) {
            return Optional.empty();
        }
        synchronized (lock) {
            ensureIndex();
            return indexOf(network(networkId)).values().stream()
                    .filter(ticket -> ticket.isCreatedBy(creatorUuid))
                    .map(Ticket::createdAtMs)
                    .max(Comparator.naturalOrder());
        }
    }

    @Override
    public List<TicketMessage> listMessages(String networkId, String ticketKey, int offset, int limit) {
        synchronized (lock) {
            ThreadFile file = readThread(network(networkId), ticketKey);
            if (file == null) {
                return List.of();
            }
            return file.messages.stream()
                    .sorted(Comparator.comparingLong(TicketMessage::createdAtMs))
                    .skip(Math.max(0, offset))
                    .limit(Math.max(1, Math.min(limit, 500)))
                    .toList();
        }
    }

    @Override
    public List<TicketEvent> listEvents(String networkId, String ticketKey) {
        synchronized (lock) {
            ThreadFile file = readThread(network(networkId), ticketKey);
            if (file == null) {
                return List.of();
            }
            return file.events.stream()
                    .sorted(Comparator.comparingLong(TicketEvent::createdAtMs))
                    .toList();
        }
    }

    @Override
    public List<Ticket> findAutoCloseCandidates(String networkId, Long resolvedBeforeMs, Long waitingBeforeMs, int limit) {
        if (resolvedBeforeMs == null && waitingBeforeMs == null) {
            return List.of();
        }
        synchronized (lock) {
            ensureIndex();
            return indexOf(network(networkId)).values().stream()
                    .filter(ticket -> isAutoCloseCandidate(ticket, resolvedBeforeMs, waitingBeforeMs))
                    .sorted(Comparator.comparingLong(Ticket::updatedAtMs))
                    .limit(Math.max(1, Math.min(limit, 200)))
                    .toList();
        }
    }

    private static boolean isAutoCloseCandidate(Ticket ticket, Long resolvedBeforeMs, Long waitingBeforeMs) {
        if (resolvedBeforeMs != null && ticket.status() == TicketStatus.RESOLVED && ticket.updatedAtMs() <= resolvedBeforeMs) {
            return true;
        }
        return waitingBeforeMs != null && ticket.status() == TicketStatus.WAITING_PLAYER && ticket.updatedAtMs() <= waitingBeforeMs;
    }

    private List<Ticket> matching(TicketQuery query) {
        return indexOf(network(query.networkId())).values().stream()
                .filter(query::matches)
                .sorted(Comparator.comparingLong(Ticket::lastActivityAtMs).reversed()
                        .thenComparing(Comparator.comparingLong(Ticket::createdAtMs).reversed()))
                .toList();
    }

    private Map<String, Ticket> indexOf(String network) {
        return index.computeIfAbsent(network, key -> new LinkedHashMap<>());
    }

    private void ensureIndex() {
        if (indexLoaded) {
            return;
        }
        indexLoaded = true;
        index.clear();
        Path root = resolve(ROOT);
        if (root == null || !Files.isDirectory(root)) {
            return;
        }
        try (Stream<Path> networks = Files.list(root)) {
            networks.filter(Files::isDirectory).forEach(this::indexNetwork);
        } catch (IOException | RuntimeException t) {
            warn("failed to scan ticket directory", t);
        }
    }

    private void indexNetwork(Path directory) {
        String network = directory.getFileName().toString();
        try (Stream<Path> files = Files.list(directory)) {
            files.filter(path -> path.getFileName().toString().endsWith(".json")).forEach(path -> {
                ThreadFile file = readThreadFile(path);
                if (file != null && file.ticket != null) {
                    indexOf(network).put(file.ticket.ticketKey(), file.ticket);
                }
            });
        } catch (IOException | RuntimeException t) {
            warn("failed to index ticket network " + network, t);
        }
    }

    private ThreadFile readThread(String network, String ticketKey) {
        Path path = resolveTicket(network, ticketKey);
        return path != null ? readThreadFile(path) : null;
    }

    private ThreadFile readThreadFile(Path path) {
        if (!Files.exists(path)) {
            return null;
        }
        try (Reader reader = Files.newBufferedReader(path, StandardCharsets.UTF_8)) {
            ThreadFile file = GSON.fromJson(reader, ThreadFile.class);
            if (file == null) {
                return null;
            }
            file.normalize();
            return file;
        } catch (IOException | RuntimeException t) {
            Path quarantined = AtomicFileIO.quarantine(path, "corrupt");
            warn("quarantined corrupt ticket file " + path.getFileName()
                    + (quarantined != null ? " as " + quarantined.getFileName() : ""), t);
            return null;
        }
    }

    private boolean writeThread(String network, ThreadFile file) {
        Path path = resolveTicket(network, file.ticket.ticketKey());
        if (path == null) {
            return false;
        }
        try {
            AtomicFileIO.writeUtf8Atomic(path, writer -> GSON.toJson(file, writer));
            return true;
        } catch (IOException | RuntimeException t) {
            warn("failed to save ticket " + file.ticket.ticketKey(), t);
            return false;
        }
    }

    private SequenceState loadSequences() {
        Path path = resolve(ROOT + "/sequence.json");
        if (path == null || !Files.exists(path)) {
            return recoverSequences();
        }
        try (Reader reader = Files.newBufferedReader(path, StandardCharsets.UTF_8)) {
            SequenceState state = GSON.fromJson(reader, SequenceState.class);
            if (state == null) {
                return new SequenceState();
            }
            state.normalize();
            return state;
        } catch (IOException | RuntimeException t) {
            AtomicFileIO.quarantine(path, "corrupt");
            warn("quarantined corrupt ticket sequence file", t);
            return recoverSequences();
        }
    }

    private SequenceState recoverSequences() {
        SequenceState state = new SequenceState();
        ensureIndex();
        for (Map.Entry<String, Map<String, Ticket>> entry : index.entrySet()) {
            long highest = 0L;
            for (String key : entry.getValue().keySet()) {
                highest = Math.max(highest, numberOf(key));
            }
            state.sequences.put(entry.getKey(), highest);
        }
        return state;
    }

    private static long numberOf(String ticketKey) {
        if (ticketKey == null) {
            return 0L;
        }
        int dash = ticketKey.indexOf('-');
        if (dash < 0 || dash + 1 >= ticketKey.length()) {
            return 0L;
        }
        try {
            return Long.parseLong(ticketKey.substring(dash + 1));
        } catch (NumberFormatException ignored) {
            return 0L;
        }
    }

    private void saveSequences(SequenceState state) {
        Path path = resolve(ROOT + "/sequence.json");
        if (path == null) {
            throw new IllegalStateException("Ticket sequence path is unavailable.");
        }
        try {
            AtomicFileIO.writeUtf8Atomic(path, writer -> GSON.toJson(state, writer));
        } catch (IOException | RuntimeException t) {
            warn("failed to save ticket sequence", t);
            throw new IllegalStateException("Failed to persist ticket sequence.", t);
        }
    }

    private Path resolveTicket(String network, String ticketKey) {
        String normalized = TicketIds.normalizeKey(ticketKey);
        if (normalized == null) {
            return null;
        }
        return resolve(ROOT + "/" + sanitize(network) + "/" + normalized + ".json");
    }

    private Path resolve(String relativePath) {
        return config != null ? config.resolveConfigPath(relativePath) : null;
    }

    private static String sanitize(String value) {
        String trimmed = value != null ? value.trim().toLowerCase(Locale.ROOT) : "";
        String cleaned = trimmed.replaceAll("[^a-z0-9_.-]", "_");
        return cleaned.isEmpty() ? "default" : cleaned;
    }

    private String network(String networkId) {
        if (networkId != null && !networkId.isBlank()) {
            return sanitize(networkId);
        }
        return sanitize(context != null ? context.networkId() : "default");
    }

    private void warn(String message, Throwable failure) {
        if (logger != null) {
            logger.warn("Paradigm tickets: {}: {}", message, failure != null ? failure.getMessage() : "unknown");
        }
    }

    static final class ThreadFile {
        Ticket ticket;
        List<TicketMessage> messages = new ArrayList<>();
        List<TicketEvent> events = new ArrayList<>();

        void normalize() {
            if (messages == null) {
                messages = new ArrayList<>();
            }
            if (events == null) {
                events = new ArrayList<>();
            }
            messages.removeIf(java.util.Objects::isNull);
            events.removeIf(java.util.Objects::isNull);
        }
    }

    static final class SequenceState {
        Map<String, Long> sequences = new LinkedHashMap<>();

        void normalize() {
            if (sequences == null) {
                sequences = new LinkedHashMap<>();
            }
        }
    }
}
