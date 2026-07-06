package eu.avalanche7.paradigm.storage.sqlite;

import eu.avalanche7.paradigm.storage.StorageConfig;
import eu.avalanche7.paradigm.storage.identity.ServerIdentityService;
import eu.avalanche7.paradigm.storage.identity.StorageContext;
import eu.avalanche7.paradigm.storage.model.StoredHome;
import eu.avalanche7.paradigm.storage.model.StoredLocation;
import eu.avalanche7.paradigm.storage.model.StoredPlayerProfile;
import eu.avalanche7.paradigm.storage.model.StoredWarp;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;

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
        assertEquals(3, provider.migrationVersion());
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
        assertEquals(3, reloaded.migrationVersion());
        reloaded.close();
    }
}
