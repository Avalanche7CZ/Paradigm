package eu.avalanche7.paradigm.modules.playtime;

import static org.junit.jupiter.api.Assertions.*;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.slf4j.LoggerFactory;

import eu.avalanche7.paradigm.data.PlayerDataStore;
import eu.avalanche7.paradigm.platform.Interfaces.IConfig;
import eu.avalanche7.paradigm.storage.StorageCompatibilityException;
import eu.avalanche7.paradigm.storage.StorageConfig;
import eu.avalanche7.paradigm.storage.StorageException;
import eu.avalanche7.paradigm.storage.identity.ServerIdentity;
import eu.avalanche7.paradigm.storage.identity.StorageContext;
import eu.avalanche7.paradigm.storage.json.JsonPlayerRepository;
import eu.avalanche7.paradigm.storage.model.LegacyPlaytimeResult;
import eu.avalanche7.paradigm.storage.model.StoredPlayerProfile;
import eu.avalanche7.paradigm.storage.model.StoredPlayerProfileState;
import eu.avalanche7.paradigm.storage.model.StoredServerPlaytime;
import eu.avalanche7.paradigm.storage.repository.PlayerRepository;
import eu.avalanche7.paradigm.storage.sql.SqlConnectionProvider;
import eu.avalanche7.paradigm.storage.sql.SqlExecutor;
import eu.avalanche7.paradigm.storage.sql.SqlMigrationRunner;
import eu.avalanche7.paradigm.storage.sql.SqlPlayerRepository;
import eu.avalanche7.paradigm.storage.sqlite.SqliteDialect;
import eu.avalanche7.paradigm.utils.DebugLogger;

class ServerPlaytimeTest {
    @TempDir Path directory;
    final String uuid = UUID.randomUUID().toString();
    final List<SqlConnectionProvider> connections = new ArrayList<>();
    final long hour = 3_600_000L;

    SqlConnectionProvider connectionProvider(String file) {
        var config = new StorageConfig(); config.runtimeLibraries.enabled = false;
        config.sqlite.path = directory.resolve(file).toString();
        return new SqlConnectionProvider(config, new SqliteDialect(), null);
    }
    SqlExecutor sql(String file) {
        var connection = connectionProvider(file); connections.add(connection);
        new SqlMigrationRunner(connection, null).runAvailableMigrations();
        return new SqlExecutor(connection);
    }
    PlayerRepository repository(String server) { return repository(server, "playtime.db"); }
    PlayerRepository repository(String server, String file) {
        return new SqlPlayerRepository(sql(file), new StorageContext(new ServerIdentity("test", server, server)));
    }
    StoredPlayerProfile profile(long total) { return new StoredPlayerProfile(uuid, "Player", 100, 200, total); }
    @AfterEach void close() { connections.forEach(SqlConnectionProvider::close); }

    @Test void twoServersKeepIndependentCountersAndAnExactNetworkSum() {
        var a = repository("gtnh"); var b = repository("stoneblock");
        a.recordPlaytime(profile(0), "gtnh", "writer-a", 301 * hour);
        b.recordPlaytime(profile(0), "stoneblock", "writer-b", 24 * hour);
        a.recordPlaytime(profile(0), "gtnh", "writer-a", 301 * hour);
        var state = b.readPlaytime(uuid);
        assertEquals(301 * hour, state.localPlaytimeMs("gtnh"));
        assertEquals(24 * hour, state.localPlaytimeMs("stoneblock"));
        assertEquals(325 * hour, state.profile().playtimeMs()); assertEquals(0, state.unattributedPlaytimeMs());
        assertEquals(301 * hour, a.serverPlaytimeTotalMs("gtnh"));
        assertEquals(List.of(uuid), b.listServerPlaytime("stoneblock").stream().map(v -> v.uuid()).toList());
        assertEquals(100, state.serverPlaytimes().get("gtnh").firstSeenMs());
        assertEquals(200, state.serverPlaytimes().get("gtnh").lastSeenMs());
        assertThrows(StorageCompatibilityException.class, () -> b.recordPlaytime(profile(0), "stoneblock", "writer-a", 302 * hour));
        assertThrows(IllegalArgumentException.class, () -> a.recordPlaytime(profile(0), "stoneblock", "new", 1));
    }

    @Test void concurrentBootstrapCreatesOneStableDatabaseIdentity() throws Exception {
        var a = connectionProvider("bootstrap.db"); var b = connectionProvider("bootstrap.db");
        connections.add(a); connections.add(b);
        var pool = Executors.newFixedThreadPool(2);
        try {
            var start = new CountDownLatch(1);
            var one = pool.submit(() -> { start.await(); return new SqlMigrationRunner(a, null).runAvailableMigrations(); });
            var two = pool.submit(() -> { start.await(); return new SqlMigrationRunner(b, null).runAvailableMigrations(); });
            start.countDown(); one.get(30, TimeUnit.SECONDS); two.get(30, TimeUnit.SECONDS);
            String id = new SqlExecutor(a).query("SELECT metadata_value FROM storage_metadata WHERE metadata_key = 'database_id'", null, rows -> { assertTrue(rows.next()); return rows.getString(1); });
            assertEquals(id, new SqlExecutor(b).query("SELECT metadata_value FROM storage_metadata WHERE metadata_key = 'database_id'", null, rows -> { assertTrue(rows.next()); return rows.getString(1); }));
        } finally { pool.shutdownNow(); }
    }

    @Test void interruptedIdentityMigrationRetainsDatabaseIdAndPlayerTotals() {
        var repo = repository("gtnh"); repo.recordPlaytime(profile(0), "gtnh", "original", hour);
        var sql = sql("playtime.db");
        String id = sql.query("SELECT metadata_value FROM storage_metadata WHERE metadata_key = 'database_id'", null, rows -> { assertTrue(rows.next()); return rows.getString(1); });
        sql.update("DELETE FROM paradigm_schema_version WHERE version = 21", null);
        var reopened = repository("gtnh");
        assertEquals(hour, reopened.readPlaytime(uuid).localPlaytimeMs("gtnh"));
        assertEquals(id, sql.query("SELECT metadata_value FROM storage_metadata WHERE metadata_key = 'database_id'", null, rows -> { assertTrue(rows.next()); return rows.getString(1); }));
    }

    @Test void oldAggregateIsUnattributedAndNeverAssignedToTheCurrentServer() {
        var repo = repository("gtnh"); repo.upsertProfile(profile(50 * hour));
        repo.recordPlaytime(profile(0), "gtnh", "new-writer", hour);
        var state = repo.readPlaytime(uuid);
        assertEquals(50 * hour, state.unattributedPlaytimeMs()); assertEquals(hour, state.localPlaytimeMs("gtnh"));
        assertEquals(51 * hour, state.profile().playtimeMs()); assertEquals(hour, repo.serverPlaytimeTotalMs("gtnh"));
        for (boolean apply : List.of(false, true)) {
            var result = repo.importLegacyPlaytime(uuid, 301 * hour, hour, apply);
            assertNotNull(result.skippedReason()); assertEquals(hour, result.totalMs());
        }
        assertFalse(repo.readPlaytime(uuid).playtimeStreams().containsKey(LegacyPlaytimeResult.writerId("gtnh")));
        assertEquals(51 * hour, repo.getProfile(uuid).orElseThrow().playtimeMs());
    }

    @Test void localHistoryFloorDoesNotChangeOtherServersOrAddOverlappingLocalHours() {
        var a = repository("gtnh"); var b = repository("other");
        a.recordPlaytime(profile(0), "gtnh", "a", 5 * hour);
        b.recordPlaytime(profile(0), "other", "b", 24 * hour);
        var preview = a.importLegacyPlaytime(uuid, 301 * hour, 5 * hour, false);
        assertEquals(301 * hour, preview.totalMs()); assertNull(preview.skippedReason());
        assertEquals(29 * hour, a.getProfile(uuid).orElseThrow().playtimeMs());
        a.importLegacyPlaytime(uuid, 301 * hour, 5 * hour, true);
        assertEquals(325 * hour, b.getProfile(uuid).orElseThrow().playtimeMs());
        a.recordPlaytime(profile(0), "gtnh", "a", 6 * hour);
        assertEquals(302 * hour, a.readPlaytime(uuid).localPlaytimeMs("gtnh"));
        assertEquals(326 * hour, a.getProfile(uuid).orElseThrow().playtimeMs());
        assertTrue(a.importLegacyPlaytime(uuid, 400 * hour, 302 * hour, true).alreadyImported());
        assertEquals(326 * hour, a.getProfile(uuid).orElseThrow().playtimeMs());
        assertEquals(24 * hour, b.readPlaytime(uuid).localPlaytimeMs("other"));
    }

    @Test void changedScanBoundaryAndLateLegacyRecoveryFailWithoutMutations() {
        var repo = repository("gtnh"); repo.recordPlaytime(profile(0), "gtnh", "normal", hour);
        assertNotNull(repo.importLegacyPlaytime(uuid, 301 * hour, 0, true).skippedReason());
        assertFalse(repo.readPlaytime(uuid).playtimeStreams().containsKey(LegacyPlaytimeResult.writerId("gtnh")));
        repo.importLegacyPlaytime(uuid, 301 * hour, hour, true);
        assertThrows(StorageCompatibilityException.class, () -> repo.recordPlaytime(profile(0), "old-unscoped", hour));
        assertEquals(301 * hour, repo.getProfile(uuid).orElseThrow().playtimeMs());
    }

    @Test void previewForAbsentProfileCreatesNeitherAProfileNorAMarker() {
        var repo = repository("gtnh");
        assertEquals(301 * hour, repo.importLegacyPlaytime(uuid, 301 * hour, 0, false).totalMs());
        assertTrue(repo.getProfile(uuid).isEmpty());
        assertTrue(repo.readPlaytime(uuid).playtimeStreams().isEmpty());
        repo.importLegacyPlaytime(uuid, 301 * hour, 0, true);
        assertEquals(301 * hour, repo.getProfile(uuid).orElseThrow().playtimeMs());
    }

    @Test void zeroContributionIsStillAOneTimeImportDecision() {
        var repo = repository("gtnh"); repo.recordPlaytime(profile(0), "gtnh", "normal", 400 * hour);
        assertEquals(0, repo.importLegacyPlaytime(uuid, 301 * hour, 400 * hour, true).addedMs());
        assertTrue(repo.importLegacyPlaytime(uuid, 450 * hour, 400 * hour, true).alreadyImported());
        assertEquals(400 * hour, repo.getProfile(uuid).orElseThrow().playtimeMs());
    }

    @Test void concurrentFirstUseAndSteadyStateAreSerializedAcrossConnections() throws Exception {
        var a = repository("a"); var b = repository("b");
        var pool = Executors.newFixedThreadPool(2);
        try {
            for (int iteration = 1; iteration <= 25; iteration++) {
                long target = iteration * 1000L;
                var start = new CountDownLatch(1);
                var one = pool.submit(() -> { start.await(); return a.recordPlaytime(profile(0), "a", "writer-a", target); });
                var two = pool.submit(() -> { start.await(); return b.recordPlaytime(profile(0), "b", "writer-b", target); });
                start.countDown(); one.get(15, TimeUnit.SECONDS); two.get(15, TimeUnit.SECONDS);
                assertEquals(target * 2, a.getProfile(uuid).orElseThrow().playtimeMs());
                assertEquals(target, a.readPlaytime(uuid).localPlaytimeMs("a"));
                assertEquals(target, b.readPlaytime(uuid).localPlaytimeMs("b"));
            }
        } finally { pool.shutdownNow(); }
    }

    @Test void duplicateHistoricalImportsRaceWithOnlyOneCommittedContribution() throws Exception {
        var a = repository("gtnh"); var b = repository("gtnh");
        var pool = Executors.newFixedThreadPool(2);
        try {
            var start = new CountDownLatch(1);
            var one = pool.submit(() -> { start.await(); return a.importLegacyPlaytime(uuid, 301 * hour, 0, true); });
            var two = pool.submit(() -> { start.await(); return b.importLegacyPlaytime(uuid, 301 * hour, 0, true); });
            start.countDown(); assertNotEquals(one.get(15, TimeUnit.SECONDS).alreadyImported(), two.get(15, TimeUnit.SECONDS).alreadyImported());
            assertEquals(301 * hour, a.getProfile(uuid).orElseThrow().playtimeMs());
        } finally { pool.shutdownNow(); }
    }

    @Test void existingAndInterruptedV19MigrationRetainAggregateAndLegacyWatermarks() {
        var repo = repository("gtnh"); repo.recordPlaytime(profile(0), "old", 50 * hour);
        SqlExecutor sql = sql("playtime.db");
        sql.update("DELETE FROM paradigm_schema_version WHERE version = 20", null);
        sql.update("DROP TABLE player_server_playtime_streams", null);
        sql.update("DROP TABLE player_server_playtime", null);
        sql.update("ALTER TABLE players DROP COLUMN unattributed_playtime_ms", null);
        var migrated = repository("other");
        var state = migrated.readPlaytime(uuid);
        assertEquals(50 * hour, state.profile().playtimeMs()); assertEquals(50 * hour, state.unattributedPlaytimeMs());
        assertTrue(state.serverPlaytimes().isEmpty()); assertEquals(Map.of("old", 50 * hour), state.playtimeStreams());
        assertThrows(StorageException.class, () -> sql.query("SELECT uuid, writer_id, cumulative_ms FROM player_playtime_streams", null, rs -> null));
        assertThrows(StorageException.class, () -> sql.transaction(() -> {
            sql.update("UPDATE players SET playtime_ms = playtime_ms + 1000 WHERE uuid = '" + uuid + "'", null);
            sql.update("INSERT INTO player_playtime_streams(uuid, writer_id, cumulative_ms) VALUES('" + uuid + "', 'old', 1000)", null);
        }));
        assertEquals(50 * hour, migrated.getProfile(uuid).orElseThrow().playtimeMs());
        migrated.recordPlaytime(profile(0), "other", "new", hour);
        assertEquals(51 * hour, migrated.getProfile(uuid).orElseThrow().playtimeMs());
    }

    @Test void migrationRejectsIndependentScopedTotalsEvenWithoutWatermarks() {
        var repo = repository("gtnh");
        var existing = new StoredPlayerProfileState(profile(hour), Map.of(), 0L,
                Map.of("gtnh", new StoredServerPlaytime(uuid, "gtnh", hour, 100, 200)), Map.of());
        var incoming = new StoredPlayerProfileState(profile(2 * hour), Map.of(), 0L,
                Map.of("stoneblock", new StoredServerPlaytime(uuid, "stoneblock", 2 * hour, 100, 200)), Map.of());
        repo.importProfileState(existing);
        assertThrows(StorageException.class, () -> repo.importProfileState(incoming));
        assertEquals(existing, repo.readPlaytime(uuid));
    }

    @Test void negativeWatermarkCannotAcknowledgeOrIncreaseMeasuredTime() {
        var repo = repository("gtnh");
        repo.recordPlaytime(profile(0), "gtnh", "writer", hour);
        var sql = sql("playtime.db");
        sql.update("UPDATE player_server_playtime_streams SET cumulative_ms = -1 WHERE uuid = ?",
                statement -> statement.setString(1, uuid));
        assertThrows(StorageCompatibilityException.class, () -> repo.readPlaytime(uuid));
        assertThrows(StorageCompatibilityException.class, () -> repo.recordPlaytime(profile(0), "gtnh", "writer", 2 * hour));
        assertEquals(hour, repo.getProfile(uuid).orElseThrow().playtimeMs());
    }

    @Test void jsonLegacyUpgradeAndSqlTransfersPreserveScopedState() throws Exception {
        var config = new IConfig() { public Path getConfigDirectory() { return directory.resolve("json"); } public String getModId() { return "paradigm"; } };
        var store = new PlayerDataStore(LoggerFactory.getLogger(getClass()), new DebugLogger(null), config);
        var json = new JsonPlayerRepository(store, "gtnh");
        json.upsertProfile(profile(50 * hour)); json.recordPlaytime(profile(0), "gtnh", "writer", hour);
        var restarted = new JsonPlayerRepository(new PlayerDataStore(LoggerFactory.getLogger(getClass()), new DebugLogger(null), config), "gtnh");
        var state = restarted.getProfileState(uuid);
        assertEquals(50 * hour, state.unattributedPlaytimeMs()); assertEquals(hour, state.localPlaytimeMs("gtnh"));
        assertEquals(51 * hour, state.profile().playtimeMs());
        var sql = repository("other", "transfer.db"); sql.importProfileState(state);
        assertEquals(state, sql.readPlaytime(uuid));
        restarted.recordPlaytime(profile(0), "gtnh", "writer", hour);
        assertEquals(state, restarted.getProfileState(uuid));
        var other = new JsonPlayerRepository(store, "other");
        assertThrows(StorageCompatibilityException.class, () -> other.recordPlaytime(profile(0), "other", "writer", 2 * hour));
    }
    private eu.avalanche7.paradigm.platform.Interfaces.IPlayer player() {
        return (eu.avalanche7.paradigm.platform.Interfaces.IPlayer) java.lang.reflect.Proxy.newProxyInstance(getClass().getClassLoader(),
                new Class<?>[]{eu.avalanche7.paradigm.platform.Interfaces.IPlayer.class}, (proxy, method, args) -> switch (method.getName()) {
                    case "getUUID" -> uuid; case "getName" -> "Player"; default -> null;
                });
    }

    private PlaytimeService service(PlaytimeJournal journal, PlayerRepository repo, java.util.concurrent.atomic.AtomicLong clock) {
        return new PlaytimeService(null, journal, () -> repo, work -> java.util.concurrent.CompletableFuture.completedFuture(work.get()), clock::get);
    }

    @Test void legacyJournalRecoveryStaysUnattributedAndNewSessionsKeepTheirServerAcrossRestarts() throws Exception {
        var repo = repository("gtnh"); String oldWriter = UUID.randomUUID().toString();
        repo.recordPlaytime(profile(0), oldWriter, 3000);
        Path path = directory.resolve("legacy-journal.json");
        Files.writeString(path, "{\"writerId\":\"" + oldWriter + "\",\"storageIdentity\":\"test\",\"players\":{\"" + uuid + "\":{\"name\":\"Player\",\"firstSeenMs\":10,\"lastSeenMs\":20,\"cumulativeMs\":5000}}}");
        var clock = new java.util.concurrent.atomic.AtomicLong(1000);
        try (var journal = new PlaytimeJournal(path, "test", null, "gtnh")) {
            var service = service(journal, repo, clock); service.recoverJournal();
            assertEquals(5000, repo.readPlaytime(uuid).unattributedPlaytimeMs());
            assertEquals(0, repo.readPlaytime(uuid).localPlaytimeMs("gtnh"));
            service.beginSession(player()); clock.addAndGet(2000); service.endSession(player());
            var state = repo.readPlaytime(uuid);
            assertEquals(7000, state.profile().playtimeMs()); assertEquals(2000, state.localPlaytimeMs("gtnh"));
            assertEquals(1000, state.serverPlaytimes().get("gtnh").firstSeenMs());
        }
        byte[] before = Files.readAllBytes(path);
        try (var wrong = new PlaytimeJournal(path, "test", null, "different-server")) {
            assertThrows(StorageException.class, wrong::open); assertArrayEquals(before, Files.readAllBytes(path));
        }
        try (var journal = new PlaytimeJournal(path, "test", null, "gtnh")) {
            var service = service(journal, repo, clock); service.recoverJournal();
            assertEquals(7000, repo.getProfile(uuid).orElseThrow().playtimeMs());
            service.beginSession(player()); clock.addAndGet(1000); service.endSession(player());
            assertEquals(3000, repo.readPlaytime(uuid).localPlaytimeMs("gtnh"));
            assertEquals(8000, repo.getProfile(uuid).orElseThrow().playtimeMs());
        }
    }

    @Test void lostAcknowledgementReplaysScopedJournalExactlyOnce() throws Exception {
        var repo = repository("gtnh"); var clock = new java.util.concurrent.atomic.AtomicLong(1000);
        var lost = new java.util.concurrent.atomic.AtomicBoolean(true);
        var faulted = (PlayerRepository) java.lang.reflect.Proxy.newProxyInstance(getClass().getClassLoader(), new Class<?>[]{PlayerRepository.class}, (proxy, method, args) -> {
            Object result;
            try { result = method.invoke(repo, args); } catch (java.lang.reflect.InvocationTargetException failure) { throw failure.getCause(); }
            if (method.getName().equals("recordPlaytime") && lost.getAndSet(false)) throw new StorageException("lost acknowledgement after commit");
            return result;
        });
        Path path = directory.resolve("lost-ack-journal.json");
        try (var journal = new PlaytimeJournal(path, "test", null, "gtnh")) {
            var service = service(journal, faulted, clock); service.beginSession(player());
            clock.addAndGet(1000); service.flushAll();
            assertEquals(1000, repo.readPlaytime(uuid).localPlaytimeMs("gtnh"));
        }
        try (var journal = new PlaytimeJournal(path, "test", null, "gtnh")) {
            var service = service(journal, repo, clock); service.recoverJournal();
            assertEquals(1000, repo.readPlaytime(uuid).localPlaytimeMs("gtnh"));
            assertEquals(1000, repo.getProfile(uuid).orElseThrow().playtimeMs());
        }
    }

    @Test void unknownFutureSchemaIsRejectedBeforeRepositoryWrites() {
        var sql = sql("playtime.db");
        sql.update("INSERT INTO paradigm_schema_version(version, applied_at_ms) VALUES(99, 0)", null);
        assertThrows(StorageException.class, () -> repository("gtnh"));
    }

    @Test void previewRemainsSelectOnlyWithDatabaseWriteRejectionTriggers() {
        var repo = repository("gtnh"); var sql = sql("playtime.db");
        for (String table : List.of("players", "player_server_playtime", "player_server_playtime_streams")) {
            for (String operation : List.of("INSERT", "UPDATE")) {
                String name = table + operation;
                String body = sql.dialectName().equals("mysql") ? "FOR EACH ROW SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT = 'preview wrote'"
                        : "BEGIN SELECT RAISE(ABORT, 'preview wrote'); END";
                sql.update("CREATE TRIGGER " + name + " BEFORE " + operation + " ON " + table + " " + body, null);
            }
        }
        assertEquals(301 * hour, repo.importLegacyPlaytime(uuid, 301 * hour, 0, false).totalMs());
        assertTrue(repo.getProfile(uuid).isEmpty());
        assertThrows(StorageException.class, () -> repo.importLegacyPlaytime(uuid, 301 * hour, 0, true));
    }

    @Test void releasedJsonWithoutScopeFieldsKeepsLegacyStreamsCompatibleWithSql() throws Exception {
        var config = new IConfig() { public Path getConfigDirectory() { return directory.resolve("old-json"); } public String getModId() { return "paradigm"; } };
        Path file = config.getConfigDirectory().resolve("paradigm/playerdata/" + uuid + ".json");
        Files.createDirectories(file.getParent());
        Files.writeString(file, "{\"uuid\":\"" + uuid + "\",\"name\":\"Player\",\"firstSeenMs\":100,\"lastSeenMs\":200,\"playtimeMs\":5000,\"playtimeStreams\":{\"old\":3000}}");
        var store = new PlayerDataStore(LoggerFactory.getLogger(getClass()), new DebugLogger(null), config);
        var json = new JsonPlayerRepository(store, "gtnh");
        var before = Files.readString(file);
        var state = json.getProfileState(uuid);
        assertEquals("", state.streamServers().get("old"));
        assertEquals(before, Files.readString(file));
        assertEquals(5000, state.unattributedPlaytimeMs()); assertTrue(state.serverPlaytimes().isEmpty());
        var sql = repository("gtnh"); sql.importProfileState(state);
        assertEquals(state, sql.readPlaytime(uuid));
        sql.importProfileState(state);
        assertEquals(5000, sql.getProfile(uuid).orElseThrow().playtimeMs());
        json.recordPlaytime(profile(0), "gtnh", "new", 1000);
        store.setHome(uuid, "Player", "test", new PlayerDataStore.StoredLocation("world", 1, 2, 3, 0, 0));
        assertEquals(5000, json.getProfileState(uuid).unattributedPlaytimeMs());
        assertEquals(1000, json.getProfileState(uuid).localPlaytimeMs("gtnh"));
        assertEquals(6000, json.getProfileState(uuid).profile().playtimeMs());
    }

}
