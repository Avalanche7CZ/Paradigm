package eu.avalanche7.paradigm.modules.playtime;

import static org.junit.jupiter.api.Assertions.*;

import java.lang.reflect.Field;
import java.nio.file.Path;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicInteger;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.slf4j.LoggerFactory;

import eu.avalanche7.paradigm.data.PlayerDataStore;
import eu.avalanche7.paradigm.platform.Interfaces.IConfig;
import eu.avalanche7.paradigm.storage.StorageCompatibilityException;
import eu.avalanche7.paradigm.storage.json.JsonPlayerRepository;
import eu.avalanche7.paradigm.storage.model.StoredPlayerProfileState;
import eu.avalanche7.paradigm.utils.DebugLogger;

class PlaytimePersistenceSafetyTest {
    private static final String PLAYER = "00000000-0000-0000-0000-000000000022";
    private static final String OTHER = "00000000-0000-0000-0000-000000000023";
    private static final String THIRD = "00000000-0000-0000-0000-000000000024";
    @TempDir Path directory;

    private JsonPlayerRepository repository(Path root) {
        IConfig config = new IConfig() {
            @Override public Path getConfigDirectory() { return root; }
            @Override public String getModId() { return "paradigm"; }
        };
        return new JsonPlayerRepository(new PlayerDataStore(LoggerFactory.getLogger(getClass()),
                new DebugLogger(null), config), "evolution2");
    }

    private static void set(PlaytimeService service, String field, Object value) throws Exception {
        Field reflected = PlaytimeService.class.getDeclaredField(field);
        reflected.setAccessible(true);
        reflected.set(service, value);
    }

    @SuppressWarnings("unchecked")
    private static Map<String, PlaytimeService.Counter> counters(PlaytimeService service) throws Exception {
        Field reflected = PlaytimeService.class.getDeclaredField("counters");
        reflected.setAccessible(true);
        return (Map<String, PlaytimeService.Counter>) reflected.get(service);
    }

    @Test void importerRemainsBlockedUntilRecoveryIsActuallyAcknowledged() throws Exception {
        var repo = repository(directory.resolve("players"));
        var completion = new CompletableFuture<StoredPlayerProfileState>();
        try (var journal = new PlaytimeJournal(directory.resolve("journal.json"), "identity", () -> repo, "evolution2")) {
            var service = new PlaytimeService(null, journal, () -> repo, work -> {
                work.get();
                return completion;
            }, () -> 1000L);
            service.recoverJournal();
            assertEquals("recovery_pending", service.recoveryStatus(null));
            assertThrows(IllegalStateException.class, service::requireHealthyImport);
            completion.complete(null);
            assertEquals("available", service.recoveryStatus(null));
            assertFalse(service.legacyImportBlocked(PLAYER));
        }
    }

    @Test void settledOfflineCounterIsNotMistakenForAnOnlinePlayer() throws Exception {
        try (var journal = new PlaytimeJournal(directory.resolve("journal.json"), "identity")) {
            var service = new PlaytimeService(null, journal, () -> null,
                    work -> CompletableFuture.completedFuture((StoredPlayerProfileState) null), () -> 1000L);
            set(service, "recoveryPending", false);
            var counter = new PlaytimeService.Counter("Alex", 1, 2);
            counter.loaded = true;
            counter.measuredMs = 5000L;
            counter.acknowledgedMs = 5000L;
            counters(service).put(PLAYER, counter);
            assertFalse(service.legacyImportBlocked(PLAYER));
            counter.pendingRecoveryMs = 100L;
            assertTrue(service.legacyImportBlocked(PLAYER));
            counter.pendingRecoveryMs = 0L;
            counter.acknowledgedMs = 4999L;
            assertTrue(service.legacyImportBlocked(PLAYER));
        }
    }

    @Test void recoveryFailureMustBlockImportEvenIfThePlayerIsOffline() throws Exception {
        try (var journal = new PlaytimeJournal(directory.resolve("journal.json"), "identity")) {
            var service = new PlaytimeService(null, journal, () -> null,
                    work -> CompletableFuture.completedFuture((StoredPlayerProfileState) null), () -> 1000L);
            set(service, "recoveryPending", false);
            set(service, "failureStatus", "identity_conflict");
            assertThrows(IllegalStateException.class, service::requireHealthyImport);
        }
    }

    @Test void sharedIdentityFailureStopsShutdownRetryStormWithoutDiscardingJournal() throws Exception {
        AtomicInteger submissions = new AtomicInteger();
        Path path = directory.resolve("journal.json");
        try (var journal = new PlaytimeJournal(path, "old-sqlite")) {
            var service = new PlaytimeService(null, journal, () -> null, work -> {
                submissions.incrementAndGet();
                return CompletableFuture.failedFuture(new StorageCompatibilityException("wrong database"));
            }, () -> 1000L);
            for (String uuid : Set.of(PLAYER, OTHER, THIRD)) {
                var counter = new PlaytimeService.Counter("Player", 1, 2);
                counter.measuredMs = 1000L;
                counters(service).put(uuid, counter);
            }
            service.stop();
            assertEquals(1, submissions.get(), "a single shared failure should terminate the shutdown wait");
            assertTrue(java.nio.file.Files.readString(path).contains(PLAYER));
            assertTrue(java.nio.file.Files.readString(path).contains(OTHER));
            assertTrue(java.nio.file.Files.readString(path).contains(THIRD));
        }
    }

    @Test void onlinePlayerIsExcludedFromLegacyImport() throws Exception {
        try (var journal = new PlaytimeJournal(directory.resolve("journal.json"), "identity")) {
            var service = new PlaytimeService(null, journal, () -> null,
                    work -> CompletableFuture.completedFuture((StoredPlayerProfileState) null), () -> 1000L);
            set(service, "recoveryPending", false);
            eu.avalanche7.paradigm.platform.Interfaces.IPlayer player = (eu.avalanche7.paradigm.platform.Interfaces.IPlayer)
                    java.lang.reflect.Proxy.newProxyInstance(eu.avalanche7.paradigm.platform.Interfaces.IPlayer.class.getClassLoader(),
                            new Class<?>[]{eu.avalanche7.paradigm.platform.Interfaces.IPlayer.class}, (proxy, method, args) -> switch (method.getName()) {
                                case "getUUID" -> PLAYER;
                                case "getName" -> "Alex";
                                default -> null;
                            });
            service.beginSession(player);
            assertTrue(service.legacyImportBlocked(PLAYER));
            service.endSession(player);
            var counter = counters(service).get(PLAYER);
            synchronized (counter) {
                counter.loaded = true;
                counter.acknowledgedMs = counter.target();
                counter.pendingRecoveryMs = 0L;
            }
            assertFalse(service.legacyImportBlocked(PLAYER));
        }
    }

    @Test void sharedTransientStorageFailureStopsShutdownRetryStormWithoutDiscardingJournal() throws Exception {
        AtomicInteger submissions = new AtomicInteger();
        Path path = directory.resolve("transient-journal.json");
        try (var journal = new PlaytimeJournal(path, "identity")) {
            var service = new PlaytimeService(null, journal, () -> null, work -> {
                submissions.incrementAndGet();
                return CompletableFuture.failedFuture(new eu.avalanche7.paradigm.storage.StorageException("database connection pool exhausted"));
            }, () -> 1000L);
            for (String uuid : Set.of(PLAYER, OTHER, THIRD)) {
                var counter = new PlaytimeService.Counter("Player", 1, 2);
                counter.measuredMs = 1000L;
                counters(service).put(uuid, counter);
            }
            service.stop();
            assertEquals(1, submissions.get(), "transient storage failure should terminate shutdown wait without a retry storm");
            assertTrue(java.nio.file.Files.readString(path).contains(PLAYER));
            assertTrue(java.nio.file.Files.readString(path).contains(OTHER));
            assertTrue(java.nio.file.Files.readString(path).contains(THIRD));
        }
    }

    @Test void failedRecoveryDoesNotCreateEndlessFlushRetryCycles() throws Exception {
        AtomicInteger submissions = new AtomicInteger();
        Path path = directory.resolve("fail-cycle-journal.json");
        try (var journal = new PlaytimeJournal(path, "identity")) {
            var service = new PlaytimeService(null, journal, () -> null, work -> {
                submissions.incrementAndGet();
                return CompletableFuture.failedFuture(new StorageCompatibilityException("identity mismatch"));
            }, () -> 1000L);
            var counter = new PlaytimeService.Counter("Player", 1, 2);
            counter.measuredMs = 1000L;
            counters(service).put(PLAYER, counter);
            service.recoverJournal();
            int afterRecovery = submissions.get();
            assertEquals(1, afterRecovery);
            assertEquals("identity_conflict", service.recoveryStatus(null));
            assertThrows(IllegalStateException.class, service::requireHealthyImport);
        }
    }

    @Test void moduleLifecycleTransitionsPreservePlaytimeAccountingAcrossReload() throws Exception {
        var repo = repository(directory.resolve("lifecycle-players"));
        Path path = directory.resolve("lifecycle-journal.json");
        java.util.concurrent.atomic.AtomicLong now = new java.util.concurrent.atomic.AtomicLong(1000L);
        eu.avalanche7.paradigm.platform.Interfaces.IPlayer player = (eu.avalanche7.paradigm.platform.Interfaces.IPlayer)
                java.lang.reflect.Proxy.newProxyInstance(eu.avalanche7.paradigm.platform.Interfaces.IPlayer.class.getClassLoader(),
                        new Class<?>[]{eu.avalanche7.paradigm.platform.Interfaces.IPlayer.class}, (proxy, method, args) -> switch (method.getName()) {
                            case "getUUID" -> PLAYER;
                            case "getName" -> "Alex";
                            default -> null;
                        });
        try (var journal = new PlaytimeJournal(path, "identity", () -> repo, "evolution2")) {
            var service = new PlaytimeService(null, journal, () -> repo,
                    work -> CompletableFuture.completedFuture(work.get()), now::get);
            service.start();
            service.beginSession(player);
            now.addAndGet(5000L);
            service.stop();
            assertEquals(5000L, repo.readPlaytime(PLAYER).localPlaytimeMs("evolution2"));

            service.start();
            service.beginSession(player);
            now.addAndGet(3000L);
            service.stop();
            assertEquals(8000L, repo.readPlaytime(PLAYER).localPlaytimeMs("evolution2"));
        }
    }
}
