package eu.avalanche7.paradigm.modules.playtime;

import static org.junit.jupiter.api.Assertions.*;

import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Proxy;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.slf4j.LoggerFactory;

import eu.avalanche7.paradigm.core.CommonRuntime;
import eu.avalanche7.paradigm.core.Services;
import eu.avalanche7.paradigm.data.PlayerDataStore;
import eu.avalanche7.paradigm.modules.StorageLifecycle;
import eu.avalanche7.paradigm.platform.Interfaces.IConfig;
import eu.avalanche7.paradigm.platform.Interfaces.IPlayer;
import eu.avalanche7.paradigm.storage.StorageCompatibilityException;
import eu.avalanche7.paradigm.storage.StorageService;
import eu.avalanche7.paradigm.storage.json.JsonPlayerRepository;
import eu.avalanche7.paradigm.storage.model.StoredPlayerProfile;
import eu.avalanche7.paradigm.storage.repository.PlayerRepository;
import eu.avalanche7.paradigm.utils.DebugLogger;
import eu.avalanche7.paradigm.utils.TaskScheduler;

class PlaytimeShutdownRecoveryTest {
    @TempDir Path directory;
    private static final String UUID = "00000000-0000-0000-0000-000000000022";
    private final StoredPlayerProfile profile = new StoredPlayerProfile(UUID, "Alex", 1, 2, 0);

    private IConfig config(Path root) {
        return new IConfig() {
            @Override public Path getConfigDirectory() { return root; }
            @Override public String getModId() { return "paradigm"; }
        };
    }

    private PlayerDataStore data(Path root) {
        return new PlayerDataStore(LoggerFactory.getLogger(getClass()), new DebugLogger(null), config(root));
    }

    private PlayerRepository repository(Path root) {
        return new JsonPlayerRepository(data(root));
    }

    @Test void changedTargetWithPendingStreamsDoesNotReplayOrRewriteAndOriginalTargetRecoversOnce() throws Exception {
        Path path = directory.resolve("journal.json");
        String writer;
        try (var journal = new PlaytimeJournal(path, "original")) {
            writer = journal.open().writerId;
            journal.save(UUID, new PlaytimeJournal.Entry("Alex", 1, 2, 5000));
        }
        byte[] before = Files.readAllBytes(path);
        var other = repository(directory.resolve("other"));
        try (var journal = new PlaytimeJournal(path, "other", () -> other)) {
            var failure = assertThrows(StorageCompatibilityException.class, journal::verifyTarget);
            assertTrue(failure.getMessage().contains("explicit, quiesced migration"));
            assertTrue(failure.getMessage().contains(path.toString()));
            assertTrue(failure.getMessage().contains(writer));
            assertEquals("original", journal.open().storageIdentity);
            assertArrayEquals(before, Files.readAllBytes(path));
            assertEquals(0, other.readPlaytime(UUID).profile().playtimeMs());
            assertTrue(other.readPlaytime(UUID).playtimeStreams().isEmpty());
        }
        var original = repository(directory.resolve("original"));
        for (int restart = 0; restart < 2; restart++) {
            try (var journal = new PlaytimeJournal(path, "original", () -> original)) {
                var service = new PlaytimeService(null, journal, () -> original,
                        work -> CompletableFuture.completedFuture(work.get()), () -> 1L);
                service.recoverJournal();
                service.stop();
                service.stop();
                assertEquals(5000, original.readPlaytime(UUID).profile().playtimeMs());
                assertEquals(5000, original.readPlaytime(UUID).playtimeStreams().get(writer));
            }
        }
    }

    @Test void legacyTargetAdoptionRequiresExactWatermarksAndServerScopes() throws Exception {
        Path path = directory.resolve("journal.json");
        String writer;
        try (var journal = new PlaytimeJournal(path, "source", null, "server-a")) {
            writer = journal.open().writerId;
            journal.save(UUID, new PlaytimeJournal.Entry("Alex", 1, 2, 5000));
        }
        var target = new JsonPlayerRepository(data(directory.resolve("target")), "server-b");
        target.recordPlaytime(profile, "server-b", writer, 5000);
        byte[] before = Files.readAllBytes(path);
        try (var journal = new PlaytimeJournal(path, "target", () -> target, "server-a")) {
            assertThrows(StorageCompatibilityException.class, journal::verifyTarget);
            assertArrayEquals(before, Files.readAllBytes(path));
        }
        var coherent = new JsonPlayerRepository(data(directory.resolve("coherent")), "server-a");
        coherent.recordPlaytime(profile, "server-a", writer, 5000);
        try (var journal = new PlaytimeJournal(path, "target", () -> coherent, "server-a")) {
            journal.verifyTarget();
            assertEquals("target", journal.open().storageIdentity);
            assertEquals(5000, coherent.readPlaytime(UUID).profile().playtimeMs());
        }
    }

    @Test void differentLogicalSqlIdentityRemainsBlockedEvenWithMatchingMigratedCounters() throws Exception {
        Path path = directory.resolve("sql-journal.json");
        String writer;
        try (var journal = new PlaytimeJournal(path, "sql:original")) {
            writer = journal.open().writerId;
            journal.save(UUID, new PlaytimeJournal.Entry("Alex", 1, 2, 5000));
        }
        var target = repository(directory.resolve("target"));
        target.recordPlaytime(profile, "default", writer, 5000);
        byte[] before = Files.readAllBytes(path);
        try (var journal = new PlaytimeJournal(path, "sql:different", () -> target)) {
            assertThrows(StorageCompatibilityException.class, journal::verifyTarget);
            assertArrayEquals(before, Files.readAllBytes(path));
        }
    }

    @Test void repeatedStopDoesNotResubmitFailedPersistence() throws Exception {
        var submissions = new AtomicInteger();
        var repository = repository(directory);
        try (var journal = new PlaytimeJournal(directory.resolve("journal.json"), "original")) {
            var service = new PlaytimeService(null, journal, () -> repository, work -> {
                submissions.incrementAndGet();
                return CompletableFuture.failedFuture(new IllegalStateException("storage unavailable"));
            }, () -> 1000L);
            service.recoverJournal();
            service.stop();
            int afterStop = submissions.get();
            service.stop();
            assertEquals(afterStop, submissions.get());
        }
    }

    @Test void emptyJournalDoesNotAuthorizeAnUnrelatedTarget() throws Exception {
        Path path = directory.resolve("empty-journal.json");
        try (var journal = new PlaytimeJournal(path, "original")) { journal.open(); }
        byte[] before = Files.readAllBytes(path);
        var target = repository(directory.resolve("target"));
        try (var journal = new PlaytimeJournal(path, "target", () -> target)) {
            assertThrows(StorageCompatibilityException.class, journal::verifyTarget);
            assertArrayEquals(before, Files.readAllBytes(path));
        }
    }

    @Test void databaseAheadOfJournalRequiresReconciliationInsteadOfRetargetingOrCompacting() throws Exception {
        Path path = directory.resolve("restored-journal.json");
        String writer;
        try (var journal = new PlaytimeJournal(path, "original")) {
            writer = journal.open().writerId;
            journal.save(UUID, new PlaytimeJournal.Entry("Alex", 1, 2, 5000));
        }
        var target = repository(directory.resolve("target"));
        target.recordPlaytime(profile, "default", writer, 10000);
        byte[] before = Files.readAllBytes(path);
        try (var journal = new PlaytimeJournal(path, "target", () -> target)) {
            assertThrows(StorageCompatibilityException.class, journal::verifyTarget);
            assertArrayEquals(before, Files.readAllBytes(path));
            assertEquals(5000, journal.open().streams.get(writer).get(UUID).cumulativeMs());
            assertEquals(10000, target.readPlaytime(UUID).profile().playtimeMs());
        }
    }

    @Test void shutdownWaitsForInflightCommitAndPersistsFinalIntervalBeforeClosingJournal() throws Exception {
        inflightShutdown(false);
    }

    @Test void interruptedWaitRetainsFinalIntervalWhenFollowupSubmissionRacesStorageClosure() throws Exception {
        inflightShutdown(true);
    }

    private void inflightShutdown(boolean interruptWait) throws Exception {
        Files.createDirectories(directory.resolve("paradigm"));
        Files.writeString(directory.resolve("paradigm/storage.json"), "{\"provider\":\"json\"}");
        var scheduler = new TaskScheduler(null);
        var storage = new StorageService(LoggerFactory.getLogger(getClass()), new DebugLogger(null), config(directory),
                data(directory), null, null, null);
        var services = new Services(LoggerFactory.getLogger(getClass()), null, null, null, null, null, null, null,
                null, new DebugLogger(null), null, null, null, null, scheduler, null, null, null, storage, null, null);
        var committed = new CountDownLatch(1);
        var release = new CountDownLatch(1);
        var closing = new CountDownLatch(1);
        var stopping = new CountDownLatch(1);
        var block = new AtomicBoolean();
        var now = new AtomicLong(1000);
        var delegate = storage.players();
        PlayerRepository repository = (PlayerRepository) Proxy.newProxyInstance(PlayerRepository.class.getClassLoader(),
                new Class<?>[]{PlayerRepository.class}, (proxy, method, args) -> {
                    Object result;
                    try { result = method.invoke(delegate, args); }
                    catch (InvocationTargetException failure) { throw failure.getCause(); }
                    if (method.getName().equals("recordPlaytime") && block.compareAndSet(true, false)) {
                        committed.countDown();
                        assertTrue(release.await(20, TimeUnit.SECONDS));
                    }
                    return result;
                });
        IPlayer player = (IPlayer) Proxy.newProxyInstance(IPlayer.class.getClassLoader(), new Class<?>[]{IPlayer.class},
                (proxy, method, args) -> switch (method.getName()) {
                    case "getUUID" -> UUID;
                    case "getName" -> "Alex";
                    default -> null;
                });
        var path = storage.playtimeJournalPath();
        var journal = new PlaytimeJournal(path, "original", () -> repository, storage.context().serverId());
        storage.registerStorageResource(journal);
        var playtime = new PlaytimeService(services, journal, () -> repository, storage::submitAcknowledged, now::get);
        var module = new StorageLifecycle() {
            @Override public void onServerStopping(Object event, Services context) {
                closing.countDown();
                playtime.stop();
            }
        };
        var runtime = new CommonRuntime.Runtime(List.of(module), services, null);
        runtime.prepareModules();
        var shutdown = new Thread(() -> {
            if (interruptWait) {
                stopping.countDown();
                playtime.stop();
                Thread.interrupted();
            }
            runtime.serverStopped(null);
        });
        try {
            playtime.beginSession(player);
            storage.submitAcknowledged(() -> null).get(5, TimeUnit.SECONDS);
            block.set(true);
            now.set(6000);
            playtime.flushAll();
            assertTrue(committed.await(5, TimeUnit.SECONDS));
            now.set(8000);
            shutdown.start();
            if (interruptWait) {
                assertTrue(stopping.await(5, TimeUnit.SECONDS));
                awaitWaiting(shutdown);
                shutdown.interrupt();
            }
            assertTrue(closing.await(5, TimeUnit.SECONDS));
            assertTrue(shutdown.isAlive());
            if (interruptWait) {
                awaitWaiting(shutdown);
                var saved = com.google.gson.JsonParser.parseString(Files.readString(path)).getAsJsonObject();
                assertTrue(saved.getAsJsonObject("streams").entrySet().stream().anyMatch(stream -> {
                    var entry = stream.getValue().getAsJsonObject().getAsJsonObject(UUID);
                    return entry != null && entry.get("cumulativeMs").getAsLong() == 7000;
                }), "final interval must be durable before the in-flight acknowledgement is released");
            }
            release.countDown();
            shutdown.join(5000);
            assertFalse(shutdown.isAlive());
            assertTrue(scheduler.isShutdown());
            var restartedRepository = new JsonPlayerRepository(data(directory), storage.context().serverId());
            if (!interruptWait) assertEquals(7000, restartedRepository.readPlaytime(UUID).profile().playtimeMs());
            for (int restart = 0; restart < 2; restart++) {
                try (var recovered = new PlaytimeJournal(path, "original", () -> restartedRepository, storage.context().serverId())) {
                    var restarted = new PlaytimeService(null, recovered, () -> restartedRepository,
                            work -> CompletableFuture.completedFuture(work.get()), now::get);
                    restarted.recoverJournal();
                    restarted.stop();
                    assertEquals(7000, restartedRepository.readPlaytime(UUID).profile().playtimeMs());
                }
            }
        } finally {
            release.countDown();
            shutdown.join(5000);
            scheduler.shutdown();
            storage.close();
        }
    }

    private void awaitWaiting(Thread thread) {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
        while (System.nanoTime() < deadline) {
            if (thread.getState() == Thread.State.TIMED_WAITING) return;
            Thread.onSpinWait();
        }
        fail("Shutdown did not reach its persistence wait: " + java.util.Arrays.toString(thread.getStackTrace()));
    }

    @Test void sharedStorageFailureShutdownProducesSingleBoundedDiagnosticWithoutRetryStorm() throws Exception {
        AtomicInteger submissions = new AtomicInteger();
        Path path = directory.resolve("shared-failure-journal.json");
        String second = "00000000-0000-0000-0000-000000000023";
        String third = "00000000-0000-0000-0000-000000000024";
        try (var journal = new PlaytimeJournal(path, "original")) {
            var service = new PlaytimeService(null, journal, () -> null, work -> {
                submissions.incrementAndGet();
                return CompletableFuture.failedFuture(new eu.avalanche7.paradigm.storage.StorageException("database connection closed"));
            }, () -> 1000L);
            java.lang.reflect.Field field = PlaytimeService.class.getDeclaredField("counters");
            field.setAccessible(true);
            @SuppressWarnings("unchecked")
            var counters = (java.util.Map<String, PlaytimeService.Counter>) field.get(service);
            for (String id : List.of(UUID, second, third)) {
                var counter = new PlaytimeService.Counter("Player", 1, 2);
                counter.measuredMs = 2000L;
                counters.put(id, counter);
            }
            service.stop();
            assertEquals(2, submissions.get(), "shared storage failure must terminate shutdown without a per-player retry storm");
            String content = Files.readString(path);
            assertTrue(content.contains(UUID));
            assertTrue(content.contains(second));
            assertTrue(content.contains(third));
        }
    }

    @Test void moduleLifecycleTransitionsPreservePlaytimeAccountingAcrossReload() throws Exception {
        var repository = repository(directory.resolve("reload-test"));
        Path path = directory.resolve("reload-journal.json");
        AtomicLong now = new AtomicLong(1000L);
        IPlayer player = (IPlayer) Proxy.newProxyInstance(IPlayer.class.getClassLoader(), new Class<?>[]{IPlayer.class},
                (proxy, method, args) -> switch (method.getName()) {
                    case "getUUID" -> UUID;
                    case "getName" -> "Alex";
                    default -> null;
                });
        try (var journal = new PlaytimeJournal(path, "original", () -> repository, "default")) {
            var service = new PlaytimeService(null, journal, () -> repository,
                    work -> CompletableFuture.completedFuture(work.get()), now::get);
            service.start();
            service.beginSession(player);
            now.addAndGet(5000L);
            service.stop();
            assertEquals(5000L, repository.readPlaytime(UUID).profile().playtimeMs());

            // Reload transition: restart service with same repository and journal
            service.start();
            service.beginSession(player);
            now.addAndGet(4000L);
            service.stop();
            assertEquals(9000L, repository.readPlaytime(UUID).profile().playtimeMs());
            assertEquals(9000L, repository.readPlaytime(UUID).localPlaytimeMs("default"));
        }
    }
}
