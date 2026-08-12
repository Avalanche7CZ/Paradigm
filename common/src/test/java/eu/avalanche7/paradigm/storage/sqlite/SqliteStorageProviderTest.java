package eu.avalanche7.paradigm.storage.sqlite;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import eu.avalanche7.paradigm.modules.audit.AuditActionType;
import eu.avalanche7.paradigm.modules.audit.AuditEntry;
import eu.avalanche7.paradigm.modules.audit.AuditResult;
import eu.avalanche7.paradigm.modules.audit.AuditSource;
import eu.avalanche7.paradigm.modules.moderation.PunishmentIds;
import eu.avalanche7.paradigm.modules.moderation.PunishmentRecord;
import eu.avalanche7.paradigm.modules.moderation.PunishmentType;
import eu.avalanche7.paradigm.modules.permissions.context.PermissionContextSet;
import eu.avalanche7.paradigm.storage.StorageConfig;
import eu.avalanche7.paradigm.storage.identity.ServerIdentityService;
import eu.avalanche7.paradigm.storage.identity.ServerScope;
import eu.avalanche7.paradigm.storage.identity.StorageContext;
import eu.avalanche7.paradigm.storage.model.StoredHome;
import eu.avalanche7.paradigm.storage.model.StoredLocation;
import eu.avalanche7.paradigm.storage.model.StoredPermissionNode;
import eu.avalanche7.paradigm.storage.model.StoredPlayerProfile;
import eu.avalanche7.paradigm.storage.model.StoredWarp;
import eu.avalanche7.paradigm.storage.sql.SqlConnectionProvider;
import eu.avalanche7.paradigm.storage.sql.SqlExecutor;

class SqliteStorageProviderTest {
    @TempDir
    Path tempDir;

    @Test
    void persistsCoreRepositoriesThroughSqlite() {
        StorageConfig config = new StorageConfig();
        config.provider = "sqlite";
        config.networkId = "test-network";
        config.serverId = "test-server";
        config.serverName = "Test Server";
        config.sqlite.path = tempDir.resolve("paradigm.db").toString();
        config.runtimeLibraries.enabled = false;

        ServerIdentityService identityService = new ServerIdentityService(null, config);
        StorageContext context = new StorageContext(identityService.current());
        StoredLocation location = new StoredLocation("minecraft:overworld", 10.0, 65.0, -3.0, 90.0f, 0.0f);

        SqliteStorageProvider provider = new SqliteStorageProvider(config, context, identityService, null, null);
        provider.initialize();
        provider.players().upsertProfile(new StoredPlayerProfile("00000000-0000-0000-0000-000000000001", "Player", 1L, 2L));
        provider.players().saveHome(new StoredHome("00000000-0000-0000-0000-000000000001", "home", location, 3L, 4L));
        provider.warps().saveWarp(new StoredWarp("Spawn", location, "paradigm.warp.spawn", "", "test", 5L, 6L));
        provider.warps().setGlobalSpawn(location);
        provider.permissions().addUserPermission("00000000-0000-0000-0000-000000000001",
                new StoredPermissionNode("paradigm.test", false, null, "test-server", PermissionContextSet.server("test-server"), "assignment-test"));
        provider.audit().append(new AuditEntry("audit-1", 7L, "test-network", "test-server", "actor", "Actor", AuditSource.DASHBOARD,
                AuditActionType.DASHBOARD_LOGIN, null, null, AuditResult.SUCCESS, "login", Map.of("k", "v")));
        assertEquals(1, provider.audit().recent(10).size());
        String punishmentId = PunishmentIds.create();
        provider.moderation().addPunishmentRecord(new PunishmentRecord(punishmentId, PunishmentType.BAN, ServerScope.GLOBAL,
                "test-network", null, "00000000-0000-0000-0000-000000000001", "Player", null, null, "reason",
                null, "Staff", 10L, 10L, null, null, null, null, null, 10L, Map.of()));
        assertEquals(punishmentId, provider.moderation().findPunishmentRecord(punishmentId).orElseThrow().punishmentId());
        try (var connection = DriverManager.getConnection("jdbc:sqlite:" + config.sqlite.path);
             var statement = connection.createStatement();
             var result = statement.executeQuery("SELECT * FROM paradigm_v_punishments_public LIMIT 1")) {
            Set<String> columns = new HashSet<>();
            for (int i = 1; i <= result.getMetaData().getColumnCount(); i++) columns.add(result.getMetaData().getColumnLabel(i));
            assertTrue(columns.contains("punishment_id"));
            assertTrue(columns.contains("status"));
            assertFalse(columns.contains("subject_ip_address"));
            assertFalse(columns.contains("subject_ip_hash"));
        } catch (Exception e) {
            throw new AssertionError(e);
        }
        assertEquals(6, provider.migrationVersion());
        assertTrue(provider.serverRegistered());
        provider.close();

        SqliteStorageProvider reloaded = new SqliteStorageProvider(config, context, identityService, null, null);
        reloaded.initialize();
        assertTrue(reloaded.players().getProfile("00000000-0000-0000-0000-000000000001").isPresent());
        assertTrue(reloaded.players().getHome("00000000-0000-0000-0000-000000000001", "home").isPresent());
        assertTrue(reloaded.warps().getWarp("spawn").isPresent());
        assertTrue(reloaded.warps().getWarp("SPAWN").isPresent());
        assertEquals("Spawn", reloaded.warps().getWarp("spawn").orElseThrow().name());
        assertTrue(reloaded.warps().deleteWarp("SPAWN"));
        assertFalse(reloaded.warps().getWarp("spawn").isPresent());
        assertTrue(reloaded.warps().getGlobalSpawn().isPresent());
        assertEquals("assignment-test", reloaded.permissions().getUser("00000000-0000-0000-0000-000000000001")
                .orElseThrow().permissions().get(0).assignmentId());
        assertEquals(punishmentId, reloaded.moderation().findPunishmentRecord(punishmentId).orElseThrow().punishmentId());
        assertTrue(reloaded.moderation().revokePunishmentRecord(punishmentId, 20L, null, "Staff", "done"));
        assertEquals(20L, reloaded.moderation().findPunishmentRecord(punishmentId).orElseThrow().revokedAtMs());
        assertEquals(6, reloaded.migrationVersion());
        reloaded.close();
    }

    @Test
    void createsMissingSqliteParentDirectoryBeforeOpeningConnection() {
        StorageConfig config = new StorageConfig();
        config.provider = "sqlite";
        config.networkId = "test-network";
        config.serverId = "test-server";
        config.serverName = "Test Server";
        Path database = tempDir.resolve("missing").resolve("data").resolve("paradigm.db");
        config.sqlite.path = database.toString();
        config.runtimeLibraries.enabled = false;

        ServerIdentityService identityService = new ServerIdentityService(null, config);
        StorageContext context = new StorageContext(identityService.current());

        SqliteStorageProvider provider = new SqliteStorageProvider(config, context, identityService, null, null);
        provider.initialize();

        assertTrue(database.getParent().toFile().isDirectory());
        assertTrue(database.toFile().isFile());
        provider.close();
    }

    @Test
    void configuresBusyTimeoutOnEveryConnection() throws Exception {
        SqlConnectionProvider connections = connections("busy-timeout.db");

        try (Connection connection = connections.getConnection();
             var statement = connection.createStatement();
             var result = statement.executeQuery("PRAGMA busy_timeout")) {
            result.next();
            assertEquals(10_000, result.getInt(1));
        }
    }

    @Test
    void waitsForAnExternalWriterInsteadOfFailingWithDatabaseLocked() throws Exception {
        SqlConnectionProvider connections = connections("external-lock.db");
        SqlExecutor sql = new SqlExecutor(connections);
        sql.update("CREATE TABLE lock_probe(id INTEGER PRIMARY KEY)", null);
        ExecutorService worker = Executors.newSingleThreadExecutor();

        try (Connection blocker = DriverManager.getConnection("jdbc:sqlite:" + tempDir.resolve("external-lock.db"));
             var statement = blocker.createStatement()) {
            statement.execute("BEGIN EXCLUSIVE");
            statement.executeUpdate("INSERT INTO lock_probe(id) VALUES(1)");
            Future<Integer> waiting = worker.submit(() -> sql.query("SELECT COUNT(*) FROM lock_probe", null, result -> {
                result.next();
                return result.getInt(1);
            }));

            TimeUnit.MILLISECONDS.sleep(150);
            assertFalse(waiting.isDone());
            statement.execute("COMMIT");
            assertEquals(1, waiting.get(2, TimeUnit.SECONDS));
        } finally {
            worker.shutdownNow();
        }
    }

    @Test
    void keepsReplacementWritesAtomicAcrossRepositoryThreads() throws Exception {
        SqlExecutor first = new SqlExecutor(connections("atomic.db"));
        SqlExecutor second = new SqlExecutor(connections("atomic.db"));
        first.update("CREATE TABLE state(id INTEGER PRIMARY KEY, value TEXT NOT NULL)", null);
        first.update("INSERT INTO state(id, value) VALUES(1, 'old')", null);
        CountDownLatch deleted = new CountDownLatch(1);
        CountDownLatch continueWrite = new CountDownLatch(1);
        ExecutorService workers = Executors.newFixedThreadPool(2);

        try {
            Future<?> replacement = workers.submit(() -> first.transaction(() -> {
                first.update("DELETE FROM state WHERE id = 1", null);
                deleted.countDown();
                await(continueWrite);
                first.update("INSERT INTO state(id, value) VALUES(1, 'new')", null);
            }));
            assertTrue(deleted.await(2, TimeUnit.SECONDS));

            Future<String> reader = workers.submit(() -> second.query("SELECT value FROM state WHERE id = 1", null,
                    result -> result.next() ? result.getString(1) : "missing"));
            TimeUnit.MILLISECONDS.sleep(150);
            assertFalse(reader.isDone());

            continueWrite.countDown();
            replacement.get(2, TimeUnit.SECONDS);
            assertEquals("new", reader.get(2, TimeUnit.SECONDS));
        } finally {
            continueWrite.countDown();
            workers.shutdownNow();
        }
    }

    private SqlConnectionProvider connections(String fileName) {
        StorageConfig config = new StorageConfig();
        config.provider = "sqlite";
        config.sqlite.path = tempDir.resolve(fileName).toString();
        config.runtimeLibraries.enabled = false;
        return new SqlConnectionProvider(config, new SqliteDialect(), null);
    }

    private static void await(CountDownLatch latch) {
        try {
            if (!latch.await(2, TimeUnit.SECONDS)) {
                throw new AssertionError("Timed out waiting for test coordination latch.");
            }
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            throw new AssertionError(interrupted);
        }
    }
}
