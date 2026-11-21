package eu.avalanche7.paradigm.modules.playtime;

import static org.junit.jupiter.api.Assertions.*;

import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Proxy;
import java.nio.file.Path;
import java.util.ArrayDeque;
import java.util.Queue;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Supplier;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.slf4j.LoggerFactory;

import eu.avalanche7.paradigm.data.PlayerDataStore;
import eu.avalanche7.paradigm.platform.Interfaces.IConfig;
import eu.avalanche7.paradigm.platform.Interfaces.IPlayer;
import eu.avalanche7.paradigm.storage.json.JsonPlayerRepository;
import eu.avalanche7.paradigm.storage.model.StoredPlayerProfile;
import eu.avalanche7.paradigm.storage.model.StoredPlayerProfileState;
import eu.avalanche7.paradigm.storage.repository.PlayerRepository;
import eu.avalanche7.paradigm.utils.DebugLogger;

class PlaytimeDurabilityTest {
    @TempDir Path directory;
    final AtomicLong now = new AtomicLong(1_000L);
    final Queue<Runnable> work = new ArrayDeque<>();
    int fault;
    int recordAttempts;
    boolean reject;
    boolean holdAcknowledgements;
    final Queue<Runnable> acknowledgements = new ArrayDeque<>();
    final String uuid = "00000000-0000-0000-0000-000000000021";
    final IPlayer player = (IPlayer) Proxy.newProxyInstance(IPlayer.class.getClassLoader(), new Class<?>[]{IPlayer.class}, (p, method, args) -> {
        return switch (method.getName()) { case "getUUID" -> uuid; case "getName" -> "Alex"; default -> null; };
    });

    PlayerRepository repository() {
        return new JsonPlayerRepository(new PlayerDataStore(LoggerFactory.getLogger(getClass()), new DebugLogger(null), new IConfig() {
            public Path getConfigDirectory() { return directory; }
            public String getModId() { return "paradigm"; }
        }));
    }

    PlaytimeService service(PlaytimeJournal journal, PlayerRepository repository) {
        PlayerRepository faulted = (PlayerRepository) Proxy.newProxyInstance(PlayerRepository.class.getClassLoader(), new Class<?>[]{PlayerRepository.class}, (p, method, args) -> {
            if (fault == 4 && (method.getName().equals("getProfileState") || method.getName().equals("recordPlaytime"))) throw new IllegalStateException("SQL read unavailable");
            boolean write = method.getName().equals("recordPlaytime");
            if (write) recordAttempts++;
            if (write && fault == 3 && ((StoredPlayerProfile) args[0]).uuid().equals(uuid)) throw new IllegalStateException("one player failed before commit");
            if (write && fault == 1) throw new IllegalStateException("before commit");
            Object result;
            try { result = method.invoke(repository, args); }
            catch (InvocationTargetException failure) { throw failure.getCause(); }
            if (write && fault == 2) throw new IllegalStateException("lost acknowledgement after commit");
            return result;
        });
        return new PlaytimeService(null, journal, () -> faulted, this::submit, now::get);
    }

    CompletableFuture<StoredPlayerProfileState> submit(Supplier<StoredPlayerProfileState> action) {
        if (reject) return CompletableFuture.failedFuture(new java.util.concurrent.RejectedExecutionException());
        var future = new CompletableFuture<StoredPlayerProfileState>();
        work.add(() -> {
            try {
                var result = action.get();
                if (holdAcknowledgements) acknowledgements.add(() -> future.complete(result));
                else future.complete(result);
            } catch (Throwable failure) { future.completeExceptionally(failure); }
        });
        return future;
    }
    void drain() { while (!work.isEmpty()) work.remove().run(); }
    long stored(PlayerRepository repository) { return repository.getProfile(uuid).map(StoredPlayerProfile::playtimeMs).orElse(0L); }

    @Test void sessionBoundariesRequestAndAcknowledgeFlushWithoutAnExplicitFlush() throws Exception {
        var repository = repository();
        try (var journal = new PlaytimeJournal(directory.resolve("journal.json"), "test")) {
            var service = service(journal, repository);
            service.beginSession(player);
            assertFalse(work.isEmpty());
            drain();
            assertTrue(repository.getProfile(uuid).isPresent());
            now.set(6_000L);
            service.endSession(player);
            assertFalse(work.isEmpty());
            drain();
            assertEquals(5_000L, stored(repository()));
            assertEquals(0L, service.sessionMs(player));
        }
    }

    @Test void sqlReadFailureBeforeInitializationIsJournaledAcrossCrash() throws Exception {
        readOutageSurvivesCrash(false);
    }

    @Test void sqlReadFailureAfterInitializationIsJournaledAcrossCrash() throws Exception {
        readOutageSurvivesCrash(true);
    }

    private void readOutageSurvivesCrash(boolean initialized) throws Exception {
        var repository = repository();
        repository.recordPlaytime(new StoredPlayerProfile(uuid, "Alex", 100, 200, 0), "default", "seed", 10_000);
        Path path = directory.resolve("read-outage.json");
        try (var journal = new PlaytimeJournal(path, "test")) {
            var service = service(journal, repository);
            if (!initialized) fault = 4;
            service.beginSession(player); drain();
            fault = 4; now.set(6_000); service.flushAll(); drain();
            assertEquals(5_000, journal.open().players.get(uuid).cumulativeMs());
            assertEquals(10_000, stored(repository));
        }
        fault = 0;
        for (int replay = 0; replay < 2; replay++) {
            try (var journal = new PlaytimeJournal(path, "test")) {
                var restarted = service(journal, repository);
                restarted.recoverJournal(); drain();
                assertEquals(15_000, stored(repository));
            }
        }
    }

    @Test void compactionPreservesPendingStreamsAndCurrentBootCounter() throws Exception {
        var repo = repository();
        Path path = directory.resolve("compact/journal.json");
        String writer;
        try (var journal = new PlaytimeJournal(path, "test")) {
            writer = journal.open().writerId;
            journal.save(uuid, new PlaytimeJournal.Entry("Alex", 100, 200, 5_000));
        }
        repo.recordPlaytime(new StoredPlayerProfile(uuid, "Alex", 100, 200, 0), "default", writer, 3_000);
        try (var journal = new PlaytimeJournal(path, "test")) {
            String current = journal.open().writerId;
            journal.save(uuid, new PlaytimeJournal.Entry("Alex", 200, 300, 2_000));
            journal.compact(java.util.Map.of(uuid, repo.readPlaytime(uuid)));
            assertEquals(5_000, journal.open().streams.get(writer).get(uuid).cumulativeMs());
            repo.recordPlaytime(new StoredPlayerProfile(uuid, "Alex", 100, 200, 0), "default", writer, 5_000);
            journal.compact(java.util.Map.of(uuid, repo.readPlaytime(uuid)));
            assertFalse(journal.open().streams.containsKey(writer));
            assertEquals(2_000, journal.open().streams.get(current).get(uuid).cumulativeMs());
        }
        for (int replay = 0; replay < 2; replay++) {
            try (var journal = new PlaytimeJournal(path, "test")) {
                var service = service(journal, repo); service.recoverJournal(); drain();
                assertEquals(7_000, stored(repo));
            }
        }
    }

    @Test void failedCompactionKeepsTheRecoveryRecordUntilDurableReplacement() throws Exception {
        var repo = repository();
        Path parent = directory.resolve("compact-failure"), backup = directory.resolve("moved");
        Path path = parent.resolve("journal.json");
        String writer;
        try (var journal = new PlaytimeJournal(path, "test")) {
            writer = journal.open().writerId;
            journal.save(uuid, new PlaytimeJournal.Entry("Alex", 100, 200, 5_000));
        }
        repo.recordPlaytime(new StoredPlayerProfile(uuid, "Alex", 100, 200, 0), "default", writer, 5_000);
        try (var journal = new PlaytimeJournal(path, "test")) {
            journal.open();
            java.nio.file.Files.move(parent, backup);
            java.nio.file.Files.writeString(parent, "disk unavailable");
            assertThrows(RuntimeException.class, () -> journal.compact(java.util.Map.of(uuid, repo.readPlaytime(uuid))));
            assertTrue(journal.open().streams.containsKey(writer));
            java.nio.file.Files.delete(parent);
            java.nio.file.Files.move(backup, parent);
            journal.compact(java.util.Map.of(uuid, repo.readPlaytime(uuid)));
            assertFalse(journal.open().streams.containsKey(writer));
            assertEquals(5_000, stored(repo));
        }
    }

    @Test void logicalIdentitySurvivesHostnameChangesAndRejectsAnotherDatabase() throws Exception {
        Path path = directory.resolve("logical-identity.json");
        String first = "sql:11111111111111111111111111111111", second = "sql:22222222222222222222222222222222";
        try (var journal = new PlaytimeJournal(path, "mysql:old-host:3306/database", null, "default", () -> first)) {
            journal.save(uuid, new PlaytimeJournal.Entry("Alex", 100, 200, 5_000));
            journal.verifyTarget();
            assertEquals(first, journal.open().storageIdentity);
        }
        try (var journal = new PlaytimeJournal(path, "mysql:new-host:3306/database", null, "default", () -> first)) {
            journal.verifyTarget();
            assertEquals(first, journal.open().storageIdentity);
        }
        byte[] before = java.nio.file.Files.readAllBytes(path);
        try (var journal = new PlaytimeJournal(path, "mysql:new-host:3306/database", null, "default", () -> second)) {
            assertThrows(RuntimeException.class, journal::verifyTarget);
            assertEquals(first, journal.open().storageIdentity);
            assertArrayEquals(before, java.nio.file.Files.readAllBytes(path));
        }
    }

    @Test void shutdownDuringReadOutageLeavesDurableMeasurementsForRestart() throws Exception {
        var repo = repository();
        var offline = new java.util.concurrent.atomic.AtomicBoolean(true);
        var path = directory.resolve("shutdown-outage.json");
        String logical = "sql:11111111111111111111111111111111";
        try (var journal = new PlaytimeJournal(path, logical, null, "default", () -> {
            if (offline.get()) throw new eu.avalanche7.paradigm.storage.StorageException("SQL unavailable");
            return logical;
        })) {
            var service = new PlaytimeService(null, journal, () -> repo, action -> {
                try { return CompletableFuture.completedFuture(action.get()); }
                catch (RuntimeException failure) { return CompletableFuture.failedFuture(failure); }
            }, now::get);
            service.beginSession(player);
            assertFalse(service.isPlaytimeLoaded(uuid));
            assertEquals("sql_unavailable", service.recoveryStatus(uuid));
            now.set(6_000); service.stop();
            assertEquals(5_000, journal.open().players.get(uuid).cumulativeMs());
            assertEquals(0, stored(repo));
        }
        offline.set(false);
        try (var journal = new PlaytimeJournal(path, logical, null, "default", () -> logical)) {
            var restarted = service(journal, repo); restarted.recoverJournal(); drain();
            assertEquals(5_000, stored(repo));
            assertTrue(journal.open().streams.isEmpty());
        }
    }

    @Test void sqlReadOutageRecoversInProcessWithoutRebasingTheCurrentWriter() throws Exception {
        var repo = repository();
        repo.recordPlaytime(new StoredPlayerProfile(uuid, "Alex", 100, 200, 0), "default", "seed", 10_000);
        try (var journal = new PlaytimeJournal(directory.resolve("in-process-outage.json"), "test")) {
            var service = service(journal, repo);
            fault = 4; service.beginSession(player); drain();
            now.set(6_000); service.flushAll(); drain();
            assertEquals(5_000, journal.open().players.get(uuid).cumulativeMs());
            fault = 0; now.set(8_000); service.flushAll(); drain();
            assertEquals(17_000, stored(repo)); assertEquals(17_000, service.onlinePlaytimeMs(player));
            assertTrue(service.isPlaytimeLoaded(uuid)); assertEquals("available", service.recoveryStatus(uuid));
            now.set(10_000); service.flushAll(); drain();
            assertEquals(19_000, stored(repo)); assertEquals(19_000, service.onlinePlaytimeMs(player));
        }
    }

    @Test void failureRetainsTimeAndRecoveryAppliesExactlyOnce() throws Exception {
        var repository = repository();
        try (var journal = new PlaytimeJournal(directory.resolve("journal.json"), "test")) {
            var service = service(journal, repository);
            service.beginSession(player); drain();
            now.set(6_000L); fault = 1; service.flushAll(); drain();
            assertEquals(0L, stored(repository));
            assertEquals(5_000L, service.onlinePlaytimeMs(player));
            fault = 0; service.flushAll(); drain(); service.flushAll(); drain();
            assertEquals(5_000L, stored(repository));
            assertEquals(5_000L, service.onlinePlaytimeMs(player));
        }
    }

    @Test void commitWithLostAcknowledgementSurvivesRestartWithoutDuplication() throws Exception {
        var repository = repository();
        Path path = directory.resolve("journal.json");
        try (var journal = new PlaytimeJournal(path, "test")) {
            var service = service(journal, repository);
            service.beginSession(player); drain();
            now.set(6_000L); fault = 2; service.endSession(player); drain();
            assertEquals(5_000L, stored(repository));
        }
        fault = 0;
        try (var journal = new PlaytimeJournal(path, "test")) {
            var restarted = service(journal, repository);
            restarted.recoverJournal(); drain();
            assertEquals(5_000L, stored(repository));
            restarted.beginSession(player); drain();
            now.set(8_000L); restarted.endSession(player); drain();
            assertEquals(7_000L, stored(repository));
        }
    }

    @Test void overlappingFlushLogoutReconnectAndLateCompletionKeepAllIntervals() throws Exception {
        var repository = repository();
        repository.upsertProfile(new StoredPlayerProfile(uuid, "Alex", 100L, 200L, 60_000L));
        try (var journal = new PlaytimeJournal(directory.resolve("journal.json"), "test")) {
            var service = service(journal, repository);
            service.beginSession(player);
            now.set(4_000L); service.flushAll(); service.flushAll();
            now.set(6_000L); service.endSession(player);
            now.set(7_000L); service.beginSession(player);
            assertEquals(1, work.size());
            drain();
            assertEquals(5_000L, service.onlinePlaytimeMs(player));
            now.set(9_000L); service.flushAll(); drain();
            assertEquals(67_000L, stored(repository));
            assertEquals(7_000L, service.onlinePlaytimeMs(player));
            service.endSession(player); drain();
            service.flushAllBlocking();
            assertEquals(67_000L, stored(repository));
        }
    }

    @Test void executorRejectionDoesNotConsumePendingTime() throws Exception {
        var repository = repository();
        try (var journal = new PlaytimeJournal(directory.resolve("journal.json"), "test")) {
            var service = service(journal, repository);
            reject = true; service.beginSession(player); now.set(6_000L); service.flushAll();
            assertEquals(0L, stored(repository));
            assertEquals(5_000L, service.onlinePlaytimeMs(player));
            reject = false; service.flushAll(); drain();
            assertEquals(5_000L, stored(repository));
        }
    }

    @Test void logoutReconnectAndShutdownAfterCommitButBeforeCallbackDoNotRegressTotals() throws Exception {
        var repository = repository();
        repository.upsertProfile(new StoredPlayerProfile(uuid, "Alex", 100L, 200L, 60_000L));
        try (var journal = new PlaytimeJournal(directory.resolve("journal.json"), "test")) {
            var service = service(journal, repository);
            service.beginSession(player); drain();
            now.set(6_000L); holdAcknowledgements = true; service.flushAll(); drain();
            assertEquals(65_000L, stored(repository));
            now.set(8_000L); service.endSession(player); service.beginSession(player);
            assertEquals(7_000L, service.onlinePlaytimeMs(player));
            holdAcknowledgements = false; acknowledgements.remove().run();
            assertEquals(7_000L, service.onlinePlaytimeMs(player));
            now.set(10_000L); service.flushAll(); drain(); service.endSession(player); drain();
            service.stop();
            assertEquals(69_000L, stored(repository));
        }
    }

    @Test void logoutAndShutdownWaitForInflightCommitAndPersistTheRemainingIntervalOnce() throws Exception {
        var repository = repository();
        var committed = new java.util.concurrent.CountDownLatch(1);
        var release = new java.util.concurrent.CountDownLatch(1);
        var block = new java.util.concurrent.atomic.AtomicBoolean();
        var worker = java.util.concurrent.Executors.newSingleThreadExecutor(r -> new Thread(r,"test-storage-worker"));
        try (var journal = new PlaytimeJournal(directory.resolve("journal.json"),"test")) {
            PlayerRepository faulted = (PlayerRepository) Proxy.newProxyInstance(PlayerRepository.class.getClassLoader(),new Class<?>[]{PlayerRepository.class},(p,method,args) -> {
                assertEquals("test-storage-worker",Thread.currentThread().getName());
                Object value;
                try { value = method.invoke(repository,args); } catch (InvocationTargetException failure) { throw failure.getCause(); }
                if(method.getName().equals("recordPlaytime") && block.compareAndSet(true,false)) {
                    committed.countDown();assertTrue(release.await(10,java.util.concurrent.TimeUnit.SECONDS));
                }
                return value;
            });
            var service = new PlaytimeService(null,journal,()->faulted,action->CompletableFuture.supplyAsync(action,worker),now::get);
            service.beginSession(player);
            worker.submit(()->{}).get(10,java.util.concurrent.TimeUnit.SECONDS);
            block.set(true);now.set(6_000L);service.flushAll();
            assertTrue(committed.await(10,java.util.concurrent.TimeUnit.SECONDS));
            now.set(8_000L);service.endSession(player);
            var shutdown = new Thread(service::stop,"test-server-shutdown");shutdown.start();
            long deadline = System.nanoTime()+java.util.concurrent.TimeUnit.SECONDS.toNanos(5);
            while(shutdown.getState()!=Thread.State.TIMED_WAITING && System.nanoTime()<deadline) Thread.yield();
            assertEquals(Thread.State.TIMED_WAITING,shutdown.getState());
            release.countDown();shutdown.join(10000);assertFalse(shutdown.isAlive());
            assertEquals(7_000L,stored(repository));
        } finally { release.countDown();worker.shutdownNow(); }
    }

    @Test void completedMigrationCarriesWatermarksAndAllowsJournalRetargeting() throws Exception {
        var source = repository();
        var path = directory.resolve("journal.json");
        String writer;
        try (var journal = new PlaytimeJournal(path, "source")) {
            var service = service(journal, source);
            service.beginSession(player); drain(); now.set(6_000L); service.endSession(player); drain();
            writer = journal.open().writerId;
        }
        Path targetDirectory = directory.resolve("target");
        var target = new JsonPlayerRepository(new PlayerDataStore(LoggerFactory.getLogger(getClass()), new DebugLogger(null), new IConfig() {
            public Path getConfigDirectory() { return targetDirectory; }
            public String getModId() { return "paradigm"; }
        }));
        target.importProfileState(source.getProfileState(uuid));
        try (var journal = new PlaytimeJournal(path, "target", () -> target)) {
            journal.verifyTarget();
            assertNotEquals(writer, journal.open().writerId);
            assertTrue(journal.open().streams.containsKey(writer));
            var service = service(journal, target);
            service.recoverJournal(); drain();
            assertEquals(5_000L, stored(target));
        }
    }

    @Test void journalIoFailureRetainsMeasuredTimeUntilDiskRecovers() throws Exception {
        var repository = repository();
        Path obstruction = directory.resolve("blocked");
        java.nio.file.Files.writeString(obstruction, "not a directory");
        try (var journal = new PlaytimeJournal(obstruction.resolve("journal.json"), "test")) {
            var service = service(journal, repository);
            service.beginSession(player); drain(); now.set(6_000L); service.flushAll(); drain();
            assertEquals(0L, stored(repository)); assertEquals(5_000L, service.onlinePlaytimeMs(player));
            java.nio.file.Files.delete(obstruction);
            service.flushAll(); drain();
            assertEquals(5_000L, stored(repository));
        }
    }

    @Test void corruptJournalAndChangedStorageFailClosed() throws Exception {
        var path = directory.resolve("journal.json");
        try (var journal = new PlaytimeJournal(path, "old-target")) { journal.open(); }
        try (var journal = new PlaytimeJournal(path, "new-target")) { assertThrows(RuntimeException.class, journal::verifyTarget); }
        String missingIdentity = "{\"writerId\":\"00000000-0000-0000-0000-000000000001\",\"storageIdentity\":null,\"players\":{}}";
        java.nio.file.Files.writeString(path,missingIdentity);
        try(var journal = new PlaytimeJournal(path,"unrelated-target")) { assertThrows(RuntimeException.class,journal::open); }
        assertEquals(missingIdentity,java.nio.file.Files.readString(path));
        java.nio.file.Files.writeString(path, "corrupt");
        try (var journal = new PlaytimeJournal(path, "old-target")) { assertThrows(RuntimeException.class, journal::open); }
    }

    @Test void restoredBaselineRemainsBlockedWhenNewMeasurementsCatchUpAndExceedWatermark() throws Exception {
        var repository = repository();
        var path = directory.resolve("restored.json");
        String writer;
        try (var journal = new PlaytimeJournal(path, "test")) {
            writer = journal.open().writerId;
            journal.save(uuid, new PlaytimeJournal.Entry("Alex",100,200,5000));
        }
        repository.recordPlaytime(new StoredPlayerProfile(uuid,"Alex",100,200,0),"default",writer,10000);
        try (var journal = new PlaytimeJournal(path,"test")) {
            var service = service(journal,repository);
            service.beginSession(player); drain();
            now.set(6000); service.flushAll(); drain();
            assertEquals(15000,service.onlinePlaytimeMs(player));
            now.set(16000); service.flushAll(); drain();
            assertEquals(25000,service.onlinePlaytimeMs(player));
            service.endSession(player); drain();
            assertEquals(10000,stored(repository));
            assertEquals(5000, journal.open().streams.get(writer).get(uuid).cumulativeMs());
        }
        try (var journal = new PlaytimeJournal(path,"test")) {
            var restarted = service(journal,repository);
            restarted.recoverJournal(); drain();
            assertEquals(10000,stored(repository));
            assertEquals(5000, journal.open().streams.get(writer).get(uuid).cumulativeMs());
        }
        try(var retargeted = new PlaytimeJournal(path,"another-target",()->repository)) {
            byte[] conflict = java.nio.file.Files.readAllBytes(path);
            assertThrows(RuntimeException.class,retargeted::verifyTarget);
            assertArrayEquals(conflict,java.nio.file.Files.readAllBytes(path));
        }
    }

    @Test void initiallyUnavailableRecoveryDiscoversOfflinePlayersWithoutRestart() throws Exception {
        var repository = repository();
        var parent = directory.resolve("recovery");
        var backup = directory.resolve("recovery-backup");
        var path = parent.resolve("journal.json");
        try (var journal = new PlaytimeJournal(path,"test")) {
            journal.save(uuid,new PlaytimeJournal.Entry("Alex",100,200,5000));
        }
        java.nio.file.Files.move(parent,backup);
        java.nio.file.Files.writeString(parent,"obstruction");
        try (var journal = new PlaytimeJournal(path,"test")) {
            var service = service(journal,repository);
            service.recoverJournal(); drain(); assertEquals(0,stored(repository));
            java.nio.file.Files.delete(parent); java.nio.file.Files.move(backup,parent);
            service.flushAll(); drain(); service.flushAll(); drain();
            assertEquals(5000,stored(repository));
        }
    }

    @Test void batchJournalFailureAndDatabaseOutageRetainBothPlayersAndLostAcknowledgementsDeduplicate() throws Exception {
        var repository = repository();
        String other = "00000000-0000-0000-0000-000000000022";
        IPlayer second = (IPlayer) Proxy.newProxyInstance(IPlayer.class.getClassLoader(),new Class<?>[]{IPlayer.class},(p,m,a) ->
                switch(m.getName()) { case "getUUID" -> other; case "getName" -> "Bob"; default -> null; });
        var path = directory.resolve("batch.json");
        try (var journal = new PlaytimeJournal(path,"test")) {
            var service = service(journal,repository);
            service.beginSession(player); service.beginSession(second); drain();
            java.nio.file.Files.delete(path); java.nio.file.Files.createDirectory(path);
            now.set(6000); service.flushAll(); drain();
            assertEquals(0,stored(repository)); assertEquals(0,repository.getProfile(other).orElseThrow().playtimeMs());
            java.nio.file.Files.delete(path);
            fault = 1; service.flushAll(); drain();
            assertEquals(5000,journal.open().players.get(uuid).cumulativeMs());
            assertEquals(5000,journal.open().players.get(other).cumulativeMs());
            assertEquals(0,stored(repository));
            fault = 2; service.flushAll(); drain();
            assertEquals(5000,stored(repository));
            assertEquals(5000,repository.getProfile(other).orElseThrow().playtimeMs());
            fault = 0; service.flushAll(); drain();
            assertEquals(5000,stored(repository));
            assertEquals(5000,repository.getProfile(other).orElseThrow().playtimeMs());
        }
    }

    @Test void aPartialDatabaseFailureAcknowledgesSuccessfulPlayersAndRetriesOnlyPendingCounters() throws Exception {
        var repository = repository();
        String other = "00000000-0000-0000-0000-000000000022";
        IPlayer second = (IPlayer) Proxy.newProxyInstance(IPlayer.class.getClassLoader(),new Class<?>[]{IPlayer.class},(p,m,a) ->
                switch(m.getName()) { case "getUUID" -> other; case "getName" -> "Bob"; default -> null; });
        try(var journal = new PlaytimeJournal(directory.resolve("partial.json"),"test")) {
            var service = service(journal,repository);
            service.beginSession(player); service.beginSession(second); drain();
            recordAttempts = 0; fault = 3; now.set(6000); service.flushAll(); drain();
            assertEquals(0,stored(repository));
            assertEquals(5000,repository.getProfile(other).orElseThrow().playtimeMs());
            assertEquals(2,recordAttempts);
            fault = 0; service.flushAll(); drain();
            assertEquals(5000,stored(repository));
            assertEquals(5000,repository.getProfile(other).orElseThrow().playtimeMs());
            assertEquals(3,recordAttempts);
        }
    }
}
