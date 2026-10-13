package eu.avalanche7.paradigm.storage;

import static org.junit.jupiter.api.Assertions.*;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import eu.avalanche7.paradigm.data.ModerationDataStore;
import eu.avalanche7.paradigm.data.PlayerDataStore;
import eu.avalanche7.paradigm.data.WarpStore;
import eu.avalanche7.paradigm.platform.Interfaces.IConfig;
import eu.avalanche7.paradigm.storage.identity.ServerIdentityService;
import eu.avalanche7.paradigm.storage.identity.StorageContext;
import eu.avalanche7.paradigm.storage.json.JsonModerationRepository;
import eu.avalanche7.paradigm.storage.json.JsonPlayerRepository;
import eu.avalanche7.paradigm.storage.json.JsonWarpRepository;
import eu.avalanche7.paradigm.storage.model.StoredHome;
import eu.avalanche7.paradigm.storage.model.StoredJailState;
import eu.avalanche7.paradigm.storage.model.StoredLocation;
import eu.avalanche7.paradigm.storage.model.StoredWarp;
import eu.avalanche7.paradigm.storage.repository.ModerationRepository;
import eu.avalanche7.paradigm.storage.repository.PlayerRepository;
import eu.avalanche7.paradigm.storage.repository.WarpRepository;
import eu.avalanche7.paradigm.storage.sqlite.SqliteStorageProvider;

class StoredLocationCompatibilityTest {
    private static final List<String> IDENTIFIERS = List.of("0", "-1", "1", "7", "28", "DIM7", "DIM-1", "DIM28",
            "world", "PERSONAL_DIM_213", "spawn", "eventy", "hraci", "PlayerOne",
            "minecraft:overworld", "minecraft:the_nether", "minecraft:the_end", "example:moon/base");
    @TempDir
    Path directory;

    @Test
    void jsonLocationsSurviveReloadWithoutIdentifierRewrites() throws IOException {
        IConfig config = new IConfig() {
            @Override public Path getConfigDirectory() { return directory; }
            @Override public String getModId() { return "paradigm"; }
        };
        StorageContext context = context("server-a");
        PlayerRepository players = new JsonPlayerRepository(new PlayerDataStore(null, null, config));
        WarpRepository warps = new JsonWarpRepository(new WarpStore(null, null, config));
        ModerationRepository moderation = new JsonModerationRepository(new ModerationDataStore(null, null, config), context);
        saveLocations(players, warps, moderation);
        assertLocations(players, warps, moderation);
        List<Path> files;
        try (var paths = Files.walk(directory)) {
            files = paths.filter(Files::isRegularFile).sorted().toList();
        }
        List<String> contents = files.stream().map(StoredLocationCompatibilityTest::read).toList();
        players = new JsonPlayerRepository(new PlayerDataStore(null, null, config));
        warps = new JsonWarpRepository(new WarpStore(null, null, config));
        moderation = new JsonModerationRepository(new ModerationDataStore(null, null, config), context);
        assertLocations(players, warps, moderation);
        assertEquals(contents, files.stream().map(StoredLocationCompatibilityTest::read).toList());
    }

    @ParameterizedTest
    @ValueSource(strings = {"server-a", "server-b"})
    void sqliteLocationsSurviveRestartAndRemainServerScoped(String serverId) {
        StorageConfig config = new StorageConfig();
        config.provider = "sqlite";
        config.networkId = "test-network";
        config.serverId = serverId;
        config.sqlite.path = directory.resolve("locations.db").toString();
        config.runtimeLibraries.enabled = false;
        ServerIdentityService identity = new ServerIdentityService(null, config);
        StorageContext context = new StorageContext(identity.current());
        try (SqliteStorageProvider provider = new SqliteStorageProvider(config, context, identity, null, null)) {
            provider.initialize();
            saveLocations(provider.players(), provider.warps(), provider.moderation());
            provider.warps().setGlobalSpawn(location("DIM28"));
            provider.moderation().setJailLocation(location("DIM-1"));
        }
        try (SqliteStorageProvider restarted = new SqliteStorageProvider(config, context, identity, null, null)) {
            restarted.initialize();
            assertLocations(restarted.players(), restarted.warps(), restarted.moderation());
            assertEquals(location("DIM28"), restarted.warps().getGlobalSpawn().orElseThrow());
            assertEquals(location("DIM-1"), restarted.moderation().getJailLocation().orElseThrow());
        }
        config.serverId = serverId.equals("server-a") ? "server-b" : "server-a";
        ServerIdentityService otherIdentity = new ServerIdentityService(null, config);
        try (SqliteStorageProvider other = new SqliteStorageProvider(config,
                new StorageContext(otherIdentity.current()), otherIdentity, null, null)) {
            other.initialize();
            assertTrue(other.players().listHomes("owner").isEmpty());
            assertTrue(other.players().getBackLocation("owner0").isEmpty());
            assertTrue(other.warps().listWarps().isEmpty());
            assertTrue(other.warps().getGlobalSpawn().isEmpty());
            assertTrue(other.moderation().getJailLocation().isEmpty());
            assertTrue(other.moderation().listJailStates().isEmpty());
        }
    }

    private static void saveLocations(PlayerRepository players, WarpRepository warps, ModerationRepository moderation) {
        for (int i = 0; i < IDENTIFIERS.size(); i++) {
            StoredLocation location = location(IDENTIFIERS.get(i));
            players.saveHome(new StoredHome("owner", "home" + i, location, 1, 1));
            players.setBackLocation("owner" + i, location);
            warps.saveWarp(new StoredWarp("warp" + i, location, "paradigm.warp.test", "", "test", 1, 1));
            moderation.setJailState(new StoredJailState("server-a", "jailed" + i, "Player", "test", "staff", location, 1, null));
        }
    }

    private static void assertLocations(PlayerRepository players, WarpRepository warps, ModerationRepository moderation) {
        for (int i = 0; i < IDENTIFIERS.size(); i++) {
            StoredLocation expected = location(IDENTIFIERS.get(i));
            assertEquals(expected, players.getHome("owner", "home" + i).orElseThrow().location());
            assertEquals(expected, players.getBackLocation("owner" + i).orElseThrow());
            assertEquals(expected, warps.getWarp("warp" + i).orElseThrow().location());
            assertEquals(expected, moderation.getJailState("jailed" + i).orElseThrow().location());
        }
    }

    private static StorageContext context(String serverId) {
        StorageConfig config = new StorageConfig();
        config.serverId = serverId;
        return new StorageContext(new ServerIdentityService(null, config).current());
    }

    private static StoredLocation location(String id) {
        return new StoredLocation(id, 120.25, 70.5, -84.75, 137.5f, -22.5f);
    }

    private static String read(Path path) {
        try {
            return Files.readString(path);
        } catch (IOException failure) {
            throw new AssertionError(failure);
        }
    }
}
