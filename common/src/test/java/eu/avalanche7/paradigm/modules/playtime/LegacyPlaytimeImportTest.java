package eu.avalanche7.paradigm.modules.playtime;

import static org.junit.jupiter.api.Assertions.*;

import java.io.StringReader;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Proxy;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.DriverManager;
import java.util.List;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import eu.avalanche7.paradigm.platform.Interfaces.IPlayer;
import eu.avalanche7.paradigm.storage.StorageCompatibilityException;
import eu.avalanche7.paradigm.storage.StorageConfig;
import eu.avalanche7.paradigm.storage.StorageException;
import eu.avalanche7.paradigm.storage.identity.ServerIdentityService;
import eu.avalanche7.paradigm.storage.identity.StorageContext;
import eu.avalanche7.paradigm.storage.model.LegacyPlaytimeResult;
import eu.avalanche7.paradigm.storage.model.StoredPlayerProfile;
import eu.avalanche7.paradigm.storage.repository.PlayerRepository;
import eu.avalanche7.paradigm.storage.sql.SqlStorageProvider;
import eu.avalanche7.paradigm.storage.sqlite.SqliteStorageProvider;

class LegacyPlaytimeImportTest {
    @TempDir Path directory;
    final String uuid = "00000000-0000-0000-0000-000000000021";
    final String second = "00000000-0000-0000-0000-000000000022";
    final long history = 301L * 3_600_000 + 119_000;

    SqlStorageProvider provider(String server) {
        var config = new StorageConfig();
        config.provider = "sqlite";
        config.sqlite.path = directory.resolve("import.db").toString();
        config.runtimeLibraries.enabled = false;
        config.serverId = server;
        var identity = new ServerIdentityService(null, config);
        var provider = new SqliteStorageProvider(config, new StorageContext(identity.current()), identity, null, null);
        provider.initialize();
        return provider;
    }

    StoredPlayerProfile profile(String id, long ms) { return new StoredPlayerProfile(id, "Avalanche7CZ", 123, 456, ms); }
    long parse(String data) throws Exception { return LegacyPlaytimeImport.parseMilliseconds(new StringReader(data)); }
    LegacyPlaytimeImport.Scan scan() {
        return new LegacyPlaytimeImport.Scan(directory, 2, List.of(new LegacyPlaytimeImport.Entry(uuid, history),
                new LegacyPlaytimeImport.Entry(second, history)), List.of());
    }
    void execute(String sql) throws Exception {
        try (var connection = DriverManager.getConnection("jdbc:sqlite:" + directory.resolve("import.db")); var statement = connection.createStatement()) {
            statement.execute(sql);
        }
    }

    @Test void legacyTicksAndWrappedValueUseFiftyMillisecondsPerTick() throws Exception {
        assertEquals(history, parse("{\"stat.playOneMinute\":" + history / 50 + "}"));
        assertEquals(60_000, parse("{\"other\":42,\"stat.playOneMinute\":{\"value\":1200,\"progress\":[]}}"));
        assertEquals(Integer.MAX_VALUE * 50L, parse("{\"stat.playOneMinute\":2147483647}"));
        assertEquals(50, parse("{\"stat.playOneMinute\":1}"));
    }

    @Test void malformedMissingNegativeAndOverflowFormatsAreRejected() {
        for (String data : List.of("", "{", "[]", "{}", "{stat.playOneMinute:1200}",
                "{\"stat.playOneMinute\":0}", "{\"stat.playOneMinute\":-1}", "{\"stat.playOneMinute\":1.5}",
                "{\"stat.playOneMinute\":2147483648}", "{\"stat.playOneMinute\":\"1200\"}",
                "{\"stat.playOneMinute\":{}}", "{\"stat.playOneMinute\":true}",
                "{\"stat.playOneMinute\":1,\"stat.playOneMinute\":2}", "{\"stat.playOneMinute\":1} {}")) {
            assertThrows(Exception.class, () -> parse(data), data);
        }
    }


    @Test void modernStatsAndPreviousNamesUseTicksWithoutAddingAliases() throws Exception {
        assertEquals(60_000, parse("{\"DataVersion\":3955,\"stats\":{\"minecraft:custom\":{\"minecraft:play_time\":1200,\"minecraft:total_world_time\":9999},\"minecraft:mined\":{}}}"));
        assertEquals(60_000, parse("{\"stats\":{\"minecraft:custom\":{\"minecraft:play_one_minute\":1200}}}"));
        for (String data : List.of(
                "{\"stats\":{\"minecraft:custom\":{\"minecraft:play_time\":1200,\"minecraft:play_one_minute\":1200}}}",
                "{\"stat.playOneMinute\":1200,\"stats\":{\"minecraft:custom\":{\"minecraft:play_time\":1200}}}",
                "{\"stats\":{\"minecraft:custom\":{\"minecraft:play_time\":\"1200\"}}}",
                "{\"stats\":{\"minecraft:custom\":{\"minecraft:play_time\":-1}}}",
                "{\"stats\":{\"minecraft:custom\":{\"minecraft:play_time\":2147483648}}}",
                "{\"stats\":{\"minecraft:custom\":{\"minecraft:play_time\":1.5}}}",
                "{\"stats\":{\"minecraft:custom\":{\"minecraft:play_time\":0}}}",
                "{\"stats\":{\"minecraft:custom\":{\"minecraft:total_world_time\":1200}}}")) {
            assertThrows(Exception.class, () -> parse(data), data);
        }
    }

    @Test void onePlayerCanImportOnTwoServersWithoutSharingImportMarkers() throws Exception {
        try (var a = provider("gtnh"); var b = provider("stoneblock")) {
            assertEquals(history, a.players().importLegacyPlaytime(uuid, history, 0, true).totalMs());
            assertEquals(60_000, b.players().importLegacyPlaytime(uuid, 60_000, 0, true).totalMs());
            assertTrue(a.players().importLegacyPlaytime(uuid, history * 2, 0, true).alreadyImported());
            assertTrue(b.players().importLegacyPlaytime(uuid, 120_000, 0, true).alreadyImported());
            var state = a.players().readPlaytime(uuid);
            assertEquals(history + 60_000, state.profile().playtimeMs());
            assertEquals(history, state.localPlaytimeMs("gtnh"));
            assertEquals(60_000, state.localPlaytimeMs("stoneblock"));
            assertEquals(36, LegacyPlaytimeResult.writerId("stoneblock").length());
        }
    }

    @Test void releasedGtnhMarkerIsRecognizedWhileAnotherServerCanStillImport() throws Exception {
        try (var a = provider("gtnh"); var b = provider("modern")) {
            a.players().importLegacyPlaytime(uuid, history, 0, true);
            execute("UPDATE player_server_playtime_streams SET writer_id = '" + LegacyPlaytimeResult.WRITER_ID + "' WHERE writer_id = '" + LegacyPlaytimeResult.writerId("gtnh") + "'");
            assertTrue(a.players().importLegacyPlaytime(uuid, history * 2, 0, true).alreadyImported());
            assertNull(b.players().importLegacyPlaytime(uuid, 60_000, 0, true).skippedReason());
            assertEquals(history + 60_000, a.players().getProfile(uuid).orElseThrow().playtimeMs());
        }
    }

    @Test void scanMatchesCanonicalUuidOnlyAndNeverChangesSourceFiles() throws Exception {
        assertThrows(java.io.IOException.class, () -> LegacyPlaytimeImport.scan(directory.resolve("missing")));
        Files.writeString(directory.resolve(uuid + ".json"), "{\"stat.playOneMinute\":1200}");
        Files.writeString(directory.resolve("Avalanche7CZ.json"), "{\"stat.playOneMinute\":1200}");
        Files.writeString(directory.resolve(second + ".json"), "broken");
        Files.writeString(directory.resolve("1-1-1-1-1.json"), "{\"stat.playOneMinute\":1200}");
        var scan = LegacyPlaytimeImport.scan(directory);
        assertEquals(4, scan.found());
        assertEquals(List.of(new LegacyPlaytimeImport.Entry(uuid, 60_000)), scan.entries());
        assertEquals(3, scan.errors().size());
        assertEquals("broken", Files.readString(directory.resolve(second + ".json")));
    }

    @Test void duplicateUuidFilenamesAreSkippedEvenWithDifferentCase() throws Exception {
        String id = "aaaaaaaa-bbbb-cccc-dddd-eeeeeeeeeeee";
        Files.writeString(directory.resolve(id + ".json"), "{\"stat.playOneMinute\":1200}");
        Files.writeString(directory.resolve(id.toUpperCase(java.util.Locale.ROOT) + ".json"), "{\"stat.playOneMinute\":2400}");
        var scan = LegacyPlaytimeImport.scan(directory);
        assertEquals(2, scan.found()); assertEquals(2, scan.errors().size()); assertTrue(scan.entries().isEmpty());
    }

    @Test void previewWritesNothingAndConfirmationUsesTheSameScopedDecision() throws Exception {
        try (var provider = provider("gtnh")) {
            var repo = provider.players();
            repo.recordPlaytime(profile(uuid, 0), "gtnh", "normal", 60_000);
            var snapshot = new LegacyPlaytimeImport.Scan(directory, 1,
                    List.of(new LegacyPlaytimeImport.Entry(uuid, history)), List.of(), java.util.Map.of(uuid, 60_000L));
            var preview = LegacyPlaytimeImport.process(snapshot, repo, Set.of(), false);
            assertEquals(history, preview.results().get(0).totalMs());
            assertEquals(60_000, repo.getProfile(uuid).orElseThrow().playtimeMs());
            assertFalse(repo.readPlaytime(uuid).playtimeStreams().containsKey(LegacyPlaytimeResult.writerId("gtnh")));
            var confirmed = LegacyPlaytimeImport.process(snapshot, repo, Set.of(), true);
            assertEquals(preview.results(), confirmed.results());
            assertEquals(history, repo.readPlaytime(uuid).localPlaytimeMs("gtnh"));
        }
    }

    @Test void legacyNetworkOverlapIsSkippedAndReportedWithoutAMarker() {
        try (var provider = provider("gtnh")) {
            var repo = provider.players(); repo.upsertProfile(profile(uuid, 60_000));
            var result = LegacyPlaytimeImport.process(scan(), repo, Set.of(), true);
            assertNotNull(result.results().get(0).skippedReason());
            assertTrue(result.lines(true).stream().anyMatch(line -> line.contains("ambiguous: 1")));
            assertEquals(60_000, repo.getProfile(uuid).orElseThrow().playtimeMs());
            assertTrue(repo.readPlaytime(uuid).playtimeStreams().isEmpty());
        }
    }

    @Test void failedMarkerRollsBackProfileAndPartialRetryDoesNotDuplicateCompletedPlayers() throws Exception {
        try (var provider = provider("gtnh")) {
            var repo = provider.players();
            execute("CREATE TRIGGER reject_second BEFORE INSERT ON player_server_playtime_streams WHEN NEW.uuid = '" + second + "' BEGIN SELECT RAISE(ABORT, 'marker failure'); END");
            var first = LegacyPlaytimeImport.process(scan(), repo, Set.of(), true);
            assertEquals(1, first.results().size()); assertEquals(1, first.errors().size());
            assertEquals(history, repo.getProfile(uuid).orElseThrow().playtimeMs());
            assertTrue(repo.getProfile(second).isEmpty());
            execute("DROP TRIGGER reject_second");
            var retry = LegacyPlaytimeImport.process(scan(), repo, Set.of(), true);
            assertTrue(retry.results().get(0).alreadyImported());
            assertEquals(history, repo.readPlaytime(second).localPlaytimeMs("gtnh"));
        }
    }

    @Test void storageUnavailableAndOnlinePlayersCannotBeReportedAsApplied() {
        var attempted = new java.util.ArrayList<String>();
        PlayerRepository unavailable = (PlayerRepository) java.lang.reflect.Proxy.newProxyInstance(getClass().getClassLoader(),
                new Class<?>[]{PlayerRepository.class}, (proxy, method, args) -> {
                    attempted.add((String) args[0]);
                    throw new StorageException("offline");
                });
        var result = LegacyPlaytimeImport.process(scan(), unavailable, Set.of(uuid), true);
        assertEquals(1, result.skipped()); assertEquals(1, result.errors().size()); assertTrue(result.results().isEmpty());
        assertEquals(List.of(second), attempted);
    }

    @Test void importedLocalFloorSurvivesJournalAccumulationAndRestart() throws Exception {
        try (var provider = provider("gtnh")) {
            var repo = provider.players();
            repo.importLegacyPlaytime(uuid, history, 0, true);
            AtomicLong clock = new AtomicLong(1000);
            IPlayer player = player();
            Path path = directory.resolve("journal.json");
            for (int restart = 0; restart < 2; restart++) {
                try (var journal = new PlaytimeJournal(path, "test", null, "gtnh")) {
                    var service = new PlaytimeService(null, journal, () -> repo,
                            work -> CompletableFuture.completedFuture(work.get()), clock::get);
                    service.recoverJournal(); service.beginSession(player);
                    assertTrue(service.legacyImportBlocked(uuid));
                    clock.addAndGet(60_000); service.endSession(player);
                    assertFalse(service.legacyImportBlocked(uuid));
                    assertEquals(history + 60_000L * (restart + 1), repo.readPlaytime(uuid).localPlaytimeMs("gtnh"));
                    assertTrue(repo.importLegacyPlaytime(uuid, history + 180_000, 0, true).alreadyImported());
                }
            }
        }
    }
    @Test void importExcludesAnOnlinePlayerAndAnOfflinePlayerWithAnUnacknowledgedLogout() throws Exception {
        try (var provider = provider("gtnh"); var journal = new PlaytimeJournal(directory.resolve("race.json"), "identity", null, "gtnh")) {
            var repo = provider.players();
            var work = new java.util.ArrayDeque<Runnable>();
            var now = new AtomicLong(1000);
            var service = new PlaytimeService(null, journal, () -> repo, action -> {
                var future = new CompletableFuture<eu.avalanche7.paradigm.storage.model.StoredPlayerProfileState>();
                work.add(() -> future.complete(action.get()));
                return future;
            }, now::get);
            var entry = new LegacyPlaytimeImport.Entry(uuid, history);
            var player = player();
            service.start();
            work.remove().run();
            service.beginSession(player);
            work.remove().run();
            assertNull(service.importLegacyEntry(entry, 0, true));
            now.set(6500);
            service.endSession(player);
            assertNull(service.importLegacyEntry(entry, 0, true));
            assertEquals(0L, repo.readPlaytime(uuid).profile().playtimeMs());
            work.remove().run();
            assertEquals(5500L, repo.readPlaytime(uuid).profile().playtimeMs());
            assertNotNull(service.importLegacyEntry(entry, 0, true).skippedReason());
            assertTrue(repo.readPlaytime(uuid).playtimeStreams().keySet().stream().noneMatch(
                    writer -> writer.equals(LegacyPlaytimeResult.writerId("gtnh"))));
            assertEquals(history, service.importLegacyEntry(entry, 5500, false).totalMs());
            assertEquals(5500L, repo.readPlaytime(uuid).profile().playtimeMs());
            assertEquals(history, service.importLegacyEntry(entry, 5500, true).totalMs());
            assertEquals(history, repo.readPlaytime(uuid).profile().playtimeMs());
        }
    }

    @Test void joinCannotEnterBetweenOfflineCheckAndSqliteImportCommit() throws Exception {
        var entered = new CountDownLatch(1);
        var release = new CountDownLatch(1);
        var joinFinished = new CountDownLatch(1);
        var worker = Executors.newSingleThreadExecutor();
        try (var provider = provider("gtnh"); var journal = new PlaytimeJournal(directory.resolve("race.json"), "identity", null, "gtnh")) {
            var repo = provider.players();
            var controlled = (PlayerRepository) Proxy.newProxyInstance(getClass().getClassLoader(), new Class<?>[]{PlayerRepository.class},
                    (proxy, method, args) -> {
                        if (method.getName().equals("importLegacyPlaytime")) {
                            entered.countDown();
                            assertTrue(release.await(5, TimeUnit.SECONDS));
                        }
                        try { return method.invoke(repo, args); }
                        catch (InvocationTargetException failure) { throw failure.getCause(); }
                    });
            var now = new AtomicLong(1000);
            var service = new PlaytimeService(null, journal, () -> controlled, work -> CompletableFuture.completedFuture(work.get()), now::get);
            service.start();
            var entry = new LegacyPlaytimeImport.Entry(uuid, history);
            var player = player();
            var joined = new Thread(() -> {
                service.beginSession(player);
                joinFinished.countDown();
            });
            try {
                var result = worker.submit(() -> service.importLegacyEntry(entry, 0, true));
                assertTrue(entered.await(5, TimeUnit.SECONDS));
                joined.start();
                long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
                while (joined.getState() != Thread.State.BLOCKED && System.nanoTime() < deadline) Thread.yield();
                assertEquals(Thread.State.BLOCKED, joined.getState());
                assertEquals(0L, repo.readPlaytime(uuid).profile().playtimeMs());
                release.countDown();
                assertEquals(history, result.get(5, TimeUnit.SECONDS).totalMs());
                assertTrue(joinFinished.await(5, TimeUnit.SECONDS));
                now.set(3500);
                service.endSession(player);
                assertEquals(history + 2500, repo.readPlaytime(uuid).profile().playtimeMs());
            } finally {
                release.countDown();
                joined.join(5000);
            }
        } finally {
            worker.shutdownNow();
        }
    }

    @Test void aReplacedDatabaseIdentityRejectsLiveWritesAndImportInsideTheirTransactions() throws Exception {
        try (var provider = provider("gtnh")) {
            var repo = provider.players();
            repo.recordPlaytime(profile(uuid, 0), "gtnh", "writer", 1000);
            execute("UPDATE storage_metadata SET metadata_value = 'bbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbb' WHERE metadata_key = 'database_id'");
            assertThrows(StorageCompatibilityException.class, () -> repo.recordPlaytime(profile(uuid, 0), "gtnh", "writer", 5000));
            assertThrows(StorageCompatibilityException.class, () -> repo.importLegacyPlaytime(second, history, 0, true));
        }
        try (var reopened = provider("gtnh")) {
            var repo = reopened.players();
            assertEquals(1000L, repo.readPlaytime(uuid).localPlaytimeMs("gtnh"));
            assertEquals(1000L, repo.readPlaytime(uuid).playtimeStreams().get("writer"));
            assertTrue(repo.getProfile(second).isEmpty());
        }
    }

    private IPlayer player() {
        return (IPlayer) Proxy.newProxyInstance(getClass().getClassLoader(), new Class<?>[]{IPlayer.class},
                (proxy, method, args) -> switch (method.getName()) {
                    case "getUUID" -> uuid;
                    case "getName" -> "Avalanche7CZ";
                    default -> null;
                });
    }

}
