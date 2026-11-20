package eu.avalanche7.paradigm.modules.tickets;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.ResultSetMetaData;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import eu.avalanche7.paradigm.storage.StorageConfig;
import eu.avalanche7.paradigm.storage.identity.ServerIdentityService;
import eu.avalanche7.paradigm.storage.identity.StorageContext;
import eu.avalanche7.paradigm.storage.sql.SqlMigrationRunner;
import eu.avalanche7.paradigm.storage.sqlite.SqliteDialect;
import eu.avalanche7.paradigm.storage.sqlite.SqliteStorageProvider;

class SqlTicketRepositoryTest {

    @TempDir
    Path tempDir;

    @Test
    void createStoresTicketThreadAndEventTogether() {
        TicketRepository repository = repository("create.db");
        Ticket ticket = TicketTestData.ticket("T-1", "net", "alpha", TicketStatus.OPEN, TicketPriority.NORMAL);

        assertTrue(repository.createTicket(TicketTestData.create(ticket, "hello there")).ok());

        Ticket stored = repository.findTicket("net", "T-1").orElseThrow();
        assertEquals("T-1", stored.ticketKey());
        assertEquals(TicketStatus.OPEN, stored.status());
        assertEquals(0L, stored.revision());
        assertEquals(1, repository.listMessages("net", "T-1", 0, 50).size());
        assertEquals(1, repository.listEvents("net", "T-1").size());
        assertEquals(TicketEventType.CREATED, repository.listEvents("net", "T-1").get(0).eventType());
    }

    @Test
    void sequenceIsPerNetworkAndNeverRepeats() {
        TicketRepository repository = repository("sequence.db");
        assertEquals(1L, repository.allocateTicketNumber("alpha"));
        assertEquals(2L, repository.allocateTicketNumber("alpha"));
        assertEquals(1L, repository.allocateTicketNumber("beta"));
        assertEquals(3L, repository.allocateTicketNumber("alpha"));
    }

    @Test
    void concurrentSequenceAllocationYieldsNoDuplicates() throws Exception {
        TicketRepository repository = repository("sequence-race.db");
        int threads = 8;
        int perThread = 15;
        List<Long> allocated = java.util.Collections.synchronizedList(new ArrayList<>());
        CountDownLatch start = new CountDownLatch(1);
        CountDownLatch done = new CountDownLatch(threads);
        for (int i = 0; i < threads; i++) {
            new Thread(() -> {
                try {
                    start.await();
                    for (int n = 0; n < perThread; n++) {
                        allocated.add(repository.allocateTicketNumber("net"));
                    }
                } catch (InterruptedException ignored) {
                    Thread.currentThread().interrupt();
                } finally {
                    done.countDown();
                }
            }).start();
        }
        start.countDown();
        assertTrue(done.await(30, TimeUnit.SECONDS));
        assertEquals(threads * perThread, allocated.size());
        assertEquals(threads * perThread, Set.copyOf(allocated).size());
    }

    @Test
    void constrainedCreateEnforcesCreatorLimitAndCooldownInsideTheWriteTransaction() {
        TicketRepository repository = repository("create-admission.db");
        Ticket first = TicketTestData.ticket("T-1", "net", "alpha", TicketStatus.OPEN, TicketPriority.NORMAL);
        Ticket second = TicketTestData.ticket("T-2", "net", "alpha", TicketStatus.OPEN, TicketPriority.NORMAL);

        assertTrue(repository.createTicketIfAllowed(TicketTestData.create(first, "first"), 1, 0L).ok());
        assertEquals(TicketWriteResult.Status.LIMIT_REACHED,
                repository.createTicketIfAllowed(TicketTestData.create(second, "second"), 1, 0L).status());

        TicketRepository cooldownRepository = repository("create-cooldown.db");
        long now = System.currentTimeMillis();
        Ticket currentFirst = ticketAt("T-3", now);
        Ticket currentSecond = ticketAt("T-4", now);
        assertTrue(cooldownRepository.createTicketIfAllowed(TicketTestData.create(currentFirst, "first"), 0, 5_000L).ok());
        TicketWriteResult cooldown = cooldownRepository.createTicketIfAllowed(TicketTestData.create(currentSecond, "second"), 0, 5_000L);
        assertEquals(TicketWriteResult.Status.COOLDOWN, cooldown.status());
        assertTrue(cooldown.cooldownRemainingMs() > 0L);
        assertTrue(cooldown.cooldownRemainingMs() <= 5_000L);
    }

    private static Ticket ticketAt(String key, long createdAtMs) {
        Ticket ticket = TicketTestData.ticket(key, "net", "alpha", TicketStatus.OPEN, TicketPriority.NORMAL);
        return new Ticket(ticket.ticketId(), ticket.ticketKey(), ticket.networkId(), ticket.originServerId(), ticket.creatorUuid(),
                ticket.creatorName(), ticket.category(), ticket.subject(), ticket.status(), ticket.priority(), ticket.assigneeUuid(),
                ticket.assigneeName(), createdAtMs, createdAtMs, ticket.resolvedAtMs(), ticket.closedAtMs(), createdAtMs,
                ticket.revision(), ticket.metadata());
    }

    @Test
    void mutationBumpsRevisionByExactlyOneAndRejectsStaleWrites() {
        TicketRepository repository = repository("revision.db");
        Ticket ticket = TicketTestData.ticket("T-5", "net", "alpha", TicketStatus.OPEN, TicketPriority.NORMAL);
        repository.createTicket(TicketTestData.create(ticket, "hi"));

        Ticket updated = ticket.toBuilder().status(TicketStatus.IN_PROGRESS).touch(2_000L).build();
        TicketWriteResult first = repository.applyMutation(TicketMutation.of(updated, 0L, null,
                List.of(TicketTestData.event("T-5", TicketEventType.STATUS_CHANGED, "OPEN", "IN_PROGRESS"))));
        assertTrue(first.ok());
        assertEquals(1L, first.ticket().revision());

        TicketWriteResult stale = repository.applyMutation(TicketMutation.of(updated, 0L, null, List.of()));
        assertEquals(TicketWriteResult.Status.STALE_REVISION, stale.status());
        assertNotNull(stale.ticket());
        assertEquals(1L, stale.ticket().revision());
        assertEquals(TicketStatus.IN_PROGRESS, stale.ticket().status());
    }

    @Test
    void concurrentClaimHasExactlyOneWinner() throws Exception {
        TicketRepository repository = repository("claim-race.db");
        Ticket ticket = TicketTestData.ticket("T-9", "net", "alpha", TicketStatus.OPEN, TicketPriority.NORMAL);
        repository.createTicket(TicketTestData.create(ticket, "help"));

        int threads = 6;
        AtomicInteger winners = new AtomicInteger();
        AtomicInteger losers = new AtomicInteger();
        CountDownLatch start = new CountDownLatch(1);
        CountDownLatch done = new CountDownLatch(threads);
        for (int i = 0; i < threads; i++) {
            String staff = "staff-" + i;
            new Thread(() -> {
                try {
                    start.await();
                    Ticket claimed = ticket.toBuilder()
                            .status(TicketStatus.IN_PROGRESS)
                            .assignee(staff, staff)
                            .touch(3_000L)
                            .build();
                    TicketWriteResult result = repository.applyMutation(TicketMutation.claim(claimed, 0L,
                            List.of(TicketTestData.event("T-9", TicketEventType.CLAIMED, null, staff))));
                    if (result.ok()) {
                        winners.incrementAndGet();
                    } else {
                        losers.incrementAndGet();
                    }
                } catch (InterruptedException ignored) {
                    Thread.currentThread().interrupt();
                } finally {
                    done.countDown();
                }
            }).start();
        }
        start.countDown();
        assertTrue(done.await(30, TimeUnit.SECONDS));
        assertEquals(1, winners.get());
        assertEquals(threads - 1, losers.get());
        assertTrue(repository.findTicket("net", "T-9").orElseThrow().isAssigned());
    }

    @Test
    void failedMutationLeavesNoPartialMessageOrEvent() {
        TicketRepository repository = repository("atomic.db");
        Ticket ticket = TicketTestData.ticket("T-7", "net", "alpha", TicketStatus.OPEN, TicketPriority.NORMAL);
        repository.createTicket(TicketTestData.create(ticket, "first"));

        Ticket updated = ticket.toBuilder().status(TicketStatus.WAITING_PLAYER).touch(4_000L).build();
        TicketWriteResult stale = repository.applyMutation(new TicketMutation("net", "T-7", 99L, updated,
                TicketTestData.message("T-7", TicketAuthorType.STAFF, "should not persist"),
                List.of(TicketTestData.event("T-7", TicketEventType.REPLIED, null, null)), false));

        assertEquals(TicketWriteResult.Status.STALE_REVISION, stale.status());
        assertEquals(1, repository.listMessages("net", "T-7", 0, 50).size());
        assertEquals(1, repository.listEvents("net", "T-7").size());
        assertEquals(TicketStatus.OPEN, repository.findTicket("net", "T-7").orElseThrow().status());
    }

    @Test
    void listFiltersByStatusPriorityCategoryAndSearchWithPagination() {
        TicketRepository repository = repository("filters.db");
        repository.createTicket(TicketTestData.create(
                TicketTestData.ticket("T-1", "net", "alpha", TicketStatus.OPEN, TicketPriority.HIGH), "alpha issue"));
        repository.createTicket(TicketTestData.create(
                TicketTestData.ticket("T-2", "net", "beta", TicketStatus.CLOSED, TicketPriority.LOW), "beta issue"));
        repository.createTicket(TicketTestData.create(
                TicketTestData.ticket("T-3", "net", "alpha", TicketStatus.WAITING_STAFF, TicketPriority.URGENT), "gamma"));

        assertEquals(2, repository.countTickets(TicketQuery.builder("net")
                .statuses(Set.of(TicketStatus.OPEN, TicketStatus.WAITING_STAFF)).build()));
        assertEquals(1, repository.countTickets(TicketQuery.builder("net")
                .priorities(Set.of(TicketPriority.URGENT)).build()));
        assertEquals(2, repository.countTickets(TicketQuery.builder("net").originServerId("alpha").build()));
        assertEquals(1, repository.countTickets(TicketQuery.builder("net").search("t-2").build()));
        assertEquals(1, repository.countTickets(TicketQuery.builder("net").search("subject for t-3").build()));
        assertEquals(3, repository.countTickets(TicketQuery.builder("net").search("creator").build()));
        assertEquals(3, repository.countTickets(TicketQuery.builder("net").unassignedOnly(true).build()));

        assertEquals(2, repository.listTickets(TicketQuery.builder("net").pageSize(2).page(1).build()).size());
        assertEquals(1, repository.listTickets(TicketQuery.builder("net").pageSize(2).page(2).build()).size());
    }

    @Test
    void networkScopingKeepsTicketsSeparate() {
        TicketRepository repository = repository("networks.db");
        repository.createTicket(TicketTestData.create(
                TicketTestData.ticket("T-1", "one", "alpha", TicketStatus.OPEN, TicketPriority.NORMAL), "a"));
        repository.createTicket(TicketTestData.create(
                TicketTestData.ticket("T-1", "two", "beta", TicketStatus.OPEN, TicketPriority.NORMAL), "b"));

        assertEquals("alpha", repository.findTicket("one", "T-1").orElseThrow().originServerId());
        assertEquals("beta", repository.findTicket("two", "T-1").orElseThrow().originServerId());
        assertEquals(1, repository.countTickets(TicketQuery.builder("one").build()));
    }

    @Test
    void summaryCountsCoverActiveUnassignedAndElevated() {
        TicketRepository repository = repository("summary.db");
        repository.createTicket(TicketTestData.create(
                TicketTestData.ticket("T-1", "net", "alpha", TicketStatus.OPEN, TicketPriority.URGENT), "a"));
        repository.createTicket(TicketTestData.create(
                TicketTestData.ticket("T-2", "net", "alpha", TicketStatus.WAITING_STAFF, TicketPriority.NORMAL), "b"));
        repository.createTicket(TicketTestData.create(
                TicketTestData.ticket("T-3", "net", "alpha", TicketStatus.CLOSED, TicketPriority.LOW), "c"));

        Map<String, Integer> counts = repository.summaryCounts("net");
        assertEquals(3, counts.get(TicketSummaries.TOTAL));
        assertEquals(2, counts.get(TicketSummaries.ACTIVE));
        assertEquals(2, counts.get(TicketSummaries.UNASSIGNED));
        assertEquals(1, counts.get(TicketSummaries.ELEVATED));
        assertEquals(1, counts.get("closed"));
        assertEquals(1, counts.get("waiting_staff"));
    }

    @Test
    void antiSpamCountersSeeActiveTicketsAndLatestCreation() {
        TicketRepository repository = repository("antispam.db");
        Ticket open = TicketTestData.ticket("T-1", "net", "alpha", TicketStatus.OPEN, TicketPriority.NORMAL);
        Ticket closed = new Ticket("TK-2", "T-2", "net", "alpha", TicketTestData.CREATOR_UUID, "Creator",
                "general", "closed one", TicketStatus.CLOSED, TicketPriority.NORMAL, null, null,
                9_000L, 9_000L, null, 9_000L, 9_000L, 0L, Map.of());
        repository.createTicket(TicketTestData.create(open, "a"));
        repository.createTicket(TicketTestData.create(closed, "b"));

        assertEquals(1, repository.countActiveByCreator("net", TicketTestData.CREATOR_UUID));
        assertEquals(Optional.of(9_000L), repository.lastCreatedAtByCreator("net", TicketTestData.CREATOR_UUID));
    }

    @Test
    void crossServerFeedExcludesLocallyOriginatedEvents() {
        TicketRepository repository = repository("feed.db");
        Ticket ticket = TicketTestData.ticket("T-1", "net", "alpha", TicketStatus.OPEN, TicketPriority.NORMAL);
        repository.createTicket(TicketTestData.create(ticket, "a"));

        assertTrue(repository.supportsCrossServerFeed());
        assertTrue(repository.listEventsSince("net", 0L, "alpha", 50).isEmpty());
        assertEquals(1, repository.listEventsSince("net", 0L, "beta", 50).size());
        assertTrue(repository.listEventsSince("net", 5_000L, "beta", 50).isEmpty());
    }

    @Test
    void autoCloseCandidatesRespectThresholdsAndStatuses() {
        TicketRepository repository = repository("autoclose.db");
        Ticket resolved = new Ticket("TK-1", "T-1", "net", "alpha", TicketTestData.CREATOR_UUID, "Creator",
                "general", "resolved", TicketStatus.RESOLVED, TicketPriority.NORMAL, null, null,
                100L, 100L, 100L, null, 100L, 0L, Map.of());
        Ticket waiting = new Ticket("TK-2", "T-2", "net", "alpha", TicketTestData.CREATOR_UUID, "Creator",
                "general", "waiting", TicketStatus.WAITING_PLAYER, TicketPriority.NORMAL, null, null,
                100L, 100L, null, null, 100L, 0L, Map.of());
        Ticket fresh = new Ticket("TK-3", "T-3", "net", "alpha", TicketTestData.CREATOR_UUID, "Creator",
                "general", "fresh", TicketStatus.RESOLVED, TicketPriority.NORMAL, null, null,
                9_000L, 9_000L, 9_000L, null, 9_000L, 0L, Map.of());
        repository.createTicket(TicketTestData.create(resolved, "a"));
        repository.createTicket(TicketTestData.create(waiting, "b"));
        repository.createTicket(TicketTestData.create(fresh, "c"));

        List<Ticket> candidates = repository.findAutoCloseCandidates("net", 1_000L, 1_000L, 50);
        assertEquals(2, candidates.size());
        assertTrue(candidates.stream().noneMatch(t -> "T-3".equals(t.ticketKey())));
        assertTrue(repository.findAutoCloseCandidates("net", null, null, 50).isEmpty());
    }

    @Test
    void ticketViewsExistAndExposeNoInternalMetadata() throws Exception {
        SqliteStorageProvider provider = provider("views.db");
        provider.tickets().createTicket(TicketTestData.create(
                TicketTestData.ticket("T-1", "net", "alpha", TicketStatus.OPEN, TicketPriority.HIGH), "view me"));
        provider.close();

        try (Connection connection = DriverManager.getConnection("jdbc:sqlite:" + tempDir.resolve("views.db"))) {
            List<String> openColumns = columnsOf(connection, "paradigm_v_open_tickets");
            assertTrue(openColumns.contains("ticket_key"));
            assertTrue(openColumns.contains("status"));
            assertTrue(openColumns.contains("priority"));
            assertFalse(openColumns.contains("metadata_json"));
            assertFalse(openColumns.contains("ticket_id"));

            List<String> summaryColumns = columnsOf(connection, "paradigm_v_ticket_summary");
            assertTrue(summaryColumns.contains("ticket_count"));
            assertTrue(summaryColumns.contains("category"));

            List<String> messageColumns = columnsOf(connection, "paradigm_v_ticket_messages");
            assertTrue(messageColumns.contains("message_text"));
            assertTrue(messageColumns.contains("author_type"));
            assertFalse(messageColumns.contains("metadata_json"));
        }
    }

    @Test
    void migrationVersionIncludesIntegrationViews() {
        SqliteStorageProvider provider = provider("migration.db");
        assertEquals(SqlMigrationRunner.latestAvailableVersion(new SqliteDialect()), provider.migrationVersion());
        provider.close();
    }

    private static List<String> columnsOf(Connection connection, String view) throws Exception {
        try (Statement statement = connection.createStatement();
             ResultSet rs = statement.executeQuery("SELECT * FROM " + view + " LIMIT 1")) {
            ResultSetMetaData metaData = rs.getMetaData();
            List<String> columns = new ArrayList<>();
            for (int i = 1; i <= metaData.getColumnCount(); i++) {
                columns.add(metaData.getColumnLabel(i).toLowerCase(java.util.Locale.ROOT));
            }
            return columns;
        }
    }

    private TicketRepository repository(String fileName) {
        return provider(fileName).tickets();
    }

    private SqliteStorageProvider provider(String fileName) {
        StorageConfig config = new StorageConfig();
        config.provider = "sqlite";
        config.networkId = "test-network";
        config.serverId = "alpha";
        config.serverName = "Alpha";
        config.sqlite.path = tempDir.resolve(fileName).toString();
        config.runtimeLibraries.enabled = false;
        ServerIdentityService identityService = new ServerIdentityService(null, config);
        StorageContext context = new StorageContext(identityService.current());
        SqliteStorageProvider provider = new SqliteStorageProvider(config, context, identityService, null, null);
        provider.initialize();
        return provider;
    }
}
