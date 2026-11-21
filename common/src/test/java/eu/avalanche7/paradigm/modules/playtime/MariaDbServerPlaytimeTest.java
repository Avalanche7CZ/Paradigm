package eu.avalanche7.paradigm.modules.playtime;

import static org.junit.jupiter.api.Assertions.*;

import java.nio.file.Files;
import java.sql.DriverManager;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;

import eu.avalanche7.paradigm.storage.StorageConfig;
import eu.avalanche7.paradigm.storage.identity.ServerIdentity;
import eu.avalanche7.paradigm.storage.identity.StorageContext;
import eu.avalanche7.paradigm.storage.mysql.MysqlDialect;
import eu.avalanche7.paradigm.storage.sql.SqlConnectionProvider;
import eu.avalanche7.paradigm.storage.sql.SqlExecutor;
import eu.avalanche7.paradigm.storage.sql.SqlPlayerRepository;

@EnabledIfEnvironmentVariable(named = "PARADIGM_PLAYTIME_MARIADB", matches = "true")
class MariaDbServerPlaytimeTest extends ServerPlaytimeTest {
    @Override SqlConnectionProvider connectionProvider(String ignored) {
        var config = new StorageConfig(); config.runtimeLibraries.enabled = false;
        config.sql.host = "127.0.0.1"; config.sql.port = 33527; config.sql.database = "paradigm_playtime_test";
        config.sql.username = "playtime_test"; config.sql.password = "disposable-playtime";
        return new SqlConnectionProvider(config, new MysqlDialect(), null);
    }

    @org.junit.jupiter.api.Test void logicalIdentityAndLostAcknowledgementReplayAgainstInnoDb() throws Exception {
        var repo = repository("gtnh");
        var sql = sql("ignored");
        java.util.function.Supplier<String> identity = () -> sql.query("SELECT metadata_value FROM storage_metadata WHERE metadata_key = 'database_id'", null,
                rows -> { org.junit.jupiter.api.Assertions.assertTrue(rows.next()); return "sql:" + rows.getString(1); });
        var path = directory.resolve("mariadb-recovery.json");
        try (var journal = new PlaytimeJournal(path, "mysql:original-host/database", null, "gtnh", identity)) {
            String writer = journal.open().writerId;
            journal.save(uuid, new PlaytimeJournal.Entry("Player", 100, 200, 5_000));
            repo.recordPlaytime(profile(0), "gtnh", writer, 3_000);
            journal.verifyTarget();
        }
        var lost = new java.util.concurrent.atomic.AtomicBoolean(true);
        var faulted = (eu.avalanche7.paradigm.storage.repository.PlayerRepository) java.lang.reflect.Proxy.newProxyInstance(getClass().getClassLoader(),
                new Class<?>[]{eu.avalanche7.paradigm.storage.repository.PlayerRepository.class}, (proxy, method, args) -> {
                    Object result;
                    try { result = method.invoke(repo, args); }
                    catch (java.lang.reflect.InvocationTargetException failure) { throw failure.getCause(); }
                    if (method.getName().equals("recordPlaytime") && lost.getAndSet(false)) throw new IllegalStateException("committed; acknowledgement lost");
                    return result;
                });
        for (int replay = 0; replay < 3; replay++) {
            try (var journal = new PlaytimeJournal(path, "mysql:new-host/database", null, "gtnh", identity)) {
                var service = new PlaytimeService(null, journal, () -> faulted, work -> {
                    try { return java.util.concurrent.CompletableFuture.completedFuture(work.get()); }
                    catch (RuntimeException failure) { return java.util.concurrent.CompletableFuture.failedFuture(failure); }
                }, () -> 1_000);
                service.recoverJournal();
                org.junit.jupiter.api.Assertions.assertEquals(5_000, repo.readPlaytime(uuid).localPlaytimeMs("gtnh"));
                if (replay > 0) org.junit.jupiter.api.Assertions.assertTrue(journal.open().streams.isEmpty());
            }
        }
    }

    @Test void sqliteJournalCannotReplayIntoUnrelatedMariaDbWithTheSameServerId() throws Exception {
        String server = "evolution2";
        String sqlite = "sqlite:/home/container/config/paradigm/data/paradigm.db";
        var path = directory.resolve("sqlite-journal.json");
        try (var journal = new PlaytimeJournal(path, sqlite, null, server)) {
            journal.save(uuid, new PlaytimeJournal.Entry("Player", 100, 200, 1_593_522));
        }
        byte[] before = Files.readAllBytes(path);
        var repo = repository(server);
        var sql = sql("ignored");
        var id = sql.query("SELECT metadata_value FROM storage_metadata WHERE metadata_key = 'database_id'", null,
                rows -> { assertTrue(rows.next()); return "sql:" + rows.getString(1); });
        try (var journal = new PlaytimeJournal(path, id, () -> repo, server)) {
            var service = new PlaytimeService(null, journal, () -> repo, work -> CompletableFuture.completedFuture(work.get()), () -> 1000);
            service.start();
            assertEquals("identity_conflict", service.recoveryStatus(null));
            assertThrows(IllegalStateException.class, service::requireHealthyImport);
            service.stop();
            assertArrayEquals(before, Files.readAllBytes(path));
            assertEquals(0L, repo.readPlaytime(uuid).profile().playtimeMs());
            assertTrue(repo.readPlaytime(uuid).playtimeStreams().isEmpty());
        }
    }

    @Test void composedReadRemainsConsistentWhileAnotherServerCommitsUnderReadCommitted() throws Exception {
        var first = repository("gtnh");
        var second = repository("stoneblock");
        first.recordPlaytime(profile(0), "gtnh", "initial", hour);
        var profileRead = new CountDownLatch(1);
        var release = new CountDownLatch(1);
        try (var connection = connectionProvider("ignored")) {
            var observed = new SqlExecutor(connection) {
                @Override public <T> T query(String sql, Binder binder, ResultMapper<T> mapper) {
                    var result = super.query(sql, binder, mapper);
                    if (sql.startsWith("SELECT uuid, name") && sql.contains("WHERE uuid")) {
                        profileRead.countDown();
                        try { assertTrue(release.await(5, TimeUnit.SECONDS)); }
                        catch (InterruptedException failure) { Thread.currentThread().interrupt(); throw new AssertionError(failure); }
                    }
                    return result;
                }
            };
            var reader = new SqlPlayerRepository(observed, new StorageContext(new ServerIdentity("test", "gtnh", "GTNH")));
            var worker = Executors.newSingleThreadExecutor();
            try {
                var result = worker.submit(() -> reader.readPlaytime(uuid));
                assertTrue(profileRead.await(5, TimeUnit.SECONDS));
                second.recordPlaytime(profile(0), "stoneblock", "another-server", 2 * hour);
                release.countDown();
                var snapshot = result.get(5, TimeUnit.SECONDS);
                assertEquals(hour, snapshot.profile().playtimeMs());
                assertEquals(hour, snapshot.localPlaytimeMs("gtnh"));
                assertEquals(0L, snapshot.localPlaytimeMs("stoneblock"));
                assertEquals(3 * hour, first.readPlaytime(uuid).profile().playtimeMs());
            } finally {
                release.countDown();
                worker.shutdownNow();
            }
        }
    }

    @BeforeEach void clearDisposableSchema() throws Exception {
        try (var connection = DriverManager.getConnection("jdbc:mariadb://127.0.0.1:33527/paradigm_playtime_test", "playtime_test", "disposable-playtime");
             var statement = connection.createStatement()) {
            statement.execute("SET FOREIGN_KEY_CHECKS=0");
            java.util.List<String> views = new java.util.ArrayList<>(), tables = new java.util.ArrayList<>();
            try (var rows = statement.executeQuery("SHOW FULL TABLES")) {
                while (rows.next()) (rows.getString(2).equals("VIEW") ? views : tables).add(rows.getString(1));
            }
            for (String view : views) statement.execute("DROP VIEW `" + view + "`");
            for (String table : tables) statement.execute("DROP TABLE `" + table + "`");
        }
    }
}
