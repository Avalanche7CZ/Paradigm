package eu.avalanche7.paradigm.storage.sqlite;

import eu.avalanche7.paradigm.modules.audit.AuditActionType;
import eu.avalanche7.paradigm.modules.audit.AuditEntry;
import eu.avalanche7.paradigm.modules.audit.AuditResult;
import eu.avalanche7.paradigm.modules.audit.AuditSource;
import eu.avalanche7.paradigm.storage.StorageConfig;
import eu.avalanche7.paradigm.storage.identity.ServerIdentityService;
import eu.avalanche7.paradigm.storage.identity.StorageContext;
import eu.avalanche7.paradigm.storage.model.StoredHome;
import eu.avalanche7.paradigm.storage.model.StoredLocation;
import eu.avalanche7.paradigm.storage.model.StoredPlayerProfile;
import eu.avalanche7.paradigm.storage.model.StoredWarp;
import eu.avalanche7.paradigm.storage.model.StoredPermissionNode;
import eu.avalanche7.paradigm.modules.permissions.context.PermissionContextSet;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.Map;
import java.sql.DriverManager;
import java.util.HashSet;
import java.util.Set;
import eu.avalanche7.paradigm.modules.moderation.PunishmentIds;
import eu.avalanche7.paradigm.modules.moderation.PunishmentRecord;
import eu.avalanche7.paradigm.modules.moderation.PunishmentType;
import eu.avalanche7.paradigm.storage.identity.ServerScope;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

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
}
