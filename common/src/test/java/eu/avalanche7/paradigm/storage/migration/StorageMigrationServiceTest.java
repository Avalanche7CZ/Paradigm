package eu.avalanche7.paradigm.storage.migration;

import eu.avalanche7.paradigm.modules.moderation.PunishmentIds;
import eu.avalanche7.paradigm.modules.moderation.PunishmentRecord;
import eu.avalanche7.paradigm.modules.moderation.PunishmentType;
import eu.avalanche7.paradigm.storage.StorageProvider;
import eu.avalanche7.paradigm.storage.StorageProviderType;
import eu.avalanche7.paradigm.storage.StorageService;
import eu.avalanche7.paradigm.storage.identity.ServerIdentity;
import eu.avalanche7.paradigm.storage.identity.ServerScope;
import eu.avalanche7.paradigm.storage.model.StoredAdminState;
import eu.avalanche7.paradigm.storage.model.StoredHome;
import eu.avalanche7.paradigm.storage.model.StoredJailState;
import eu.avalanche7.paradigm.storage.model.StoredLocation;
import eu.avalanche7.paradigm.storage.model.StoredPermissionGroup;
import eu.avalanche7.paradigm.storage.model.StoredPermissionNode;
import eu.avalanche7.paradigm.storage.model.StoredPlayerProfile;
import eu.avalanche7.paradigm.storage.model.StoredPunishment;
import eu.avalanche7.paradigm.storage.model.StoredUserPermissionData;
import eu.avalanche7.paradigm.storage.model.StoredWarning;
import eu.avalanche7.paradigm.storage.model.StoredWarp;
import eu.avalanche7.paradigm.storage.repository.AdminStateRepository;
import eu.avalanche7.paradigm.storage.repository.ModerationRepository;
import eu.avalanche7.paradigm.storage.repository.PermissionRepository;
import eu.avalanche7.paradigm.storage.repository.PlayerRepository;
import eu.avalanche7.paradigm.storage.repository.ServerRepository;
import eu.avalanche7.paradigm.storage.repository.WarpRepository;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class StorageMigrationServiceTest {
    private static final ServerIdentity IDENTITY = new ServerIdentity("network", "server", "Server");
    private static final StoredLocation LOCATION = new StoredLocation("minecraft:overworld", 1.0, 64.0, 2.0, 0.0f, 0.0f);
    private static final String UUID = "00000000-0000-0000-0000-000000000001";

    @Test
    void dryRunCountsRecordsWithoutWritingTarget() {
        FakeProvider source = providerWithCoreData();
        FakeProvider target = new FakeProvider();

        StorageMigrationService.MigrationSummary summary = new StorageMigrationService(null)
                .migrate(source, target, IDENTITY, new StorageMigrationOptions(true, StorageMigrationOptions.ConflictPolicy.OVERWRITE, false));

        assertTrue(summary.dryRun());
        assertTrue(summary.success());
        assertEquals(1, summary.players());
        assertEquals(1, summary.homes());
        assertEquals(1, summary.warps());
        assertTrue(target.players.profiles.isEmpty());
        assertTrue(target.players.homes.isEmpty());
        assertTrue(target.warps.warps.isEmpty());
    }

    @Test
    void skipConflictPolicySkipsExistingRecords() {
        FakeProvider source = providerWithCoreData();
        FakeProvider target = providerWithCoreData();

        StorageMigrationService.MigrationSummary summary = new StorageMigrationService(null)
                .migrate(source, target, IDENTITY, new StorageMigrationOptions(false, StorageMigrationOptions.ConflictPolicy.SKIP, false));

        assertTrue(summary.success());
        assertEquals(3, summary.conflicts());
        assertEquals(3, summary.skipped());
        assertEquals(0, summary.players());
        assertEquals(0, summary.homes());
        assertEquals(0, summary.warps());
    }

    @Test
    void failConflictPolicyMarksMigrationUnsuccessful() {
        FakeProvider source = providerWithCoreData();
        FakeProvider target = new FakeProvider();
        target.players.upsertProfile(new StoredPlayerProfile(UUID, "Existing", 10L, 20L));

        StorageMigrationService.MigrationSummary summary = new StorageMigrationService(null)
                .migrate(source, target, IDENTITY, new StorageMigrationOptions(false, StorageMigrationOptions.ConflictPolicy.FAIL, false));

        assertFalse(summary.success());
        assertEquals(1, summary.conflicts());
        assertEquals(1, summary.failures());
        assertEquals(0, summary.players());
        assertEquals(1, summary.homes());
        assertEquals(1, summary.warps());
    }

    @Test
    void migratesUnifiedPunishmentLedgerByStableId() {
        FakeProvider source = new FakeProvider();
        FakeProvider target = new FakeProvider();
        String id = PunishmentIds.create();
        source.moderation.addPunishmentRecord(new PunishmentRecord(id, PunishmentType.BAN, ServerScope.GLOBAL,
                "network", null, UUID, "Player", null, null, "Reason", null, "Staff",
                10L, 10L, null, null, null, null, null, 10L, Map.of()));

        StorageMigrationService.MigrationSummary summary = new StorageMigrationService(null)
                .migrate(source, target, IDENTITY);

        assertTrue(summary.success());
        assertEquals(1, summary.moderationRecords());
        assertEquals(id, target.moderation.findPunishmentRecord(id).orElseThrow().punishmentId());
    }

    private static FakeProvider providerWithCoreData() {
        FakeProvider provider = new FakeProvider();
        provider.players.upsertProfile(new StoredPlayerProfile(UUID, "Player", 1L, 2L));
        provider.players.saveHome(new StoredHome(UUID, "home", LOCATION, 3L, 4L));
        provider.warps.saveWarp(new StoredWarp("spawn", LOCATION, "paradigm.warp.spawn", "", "test", 5L, 6L));
        return provider;
    }

    private static class FakeProvider implements StorageProvider {
        private final FakePlayerRepository players = new FakePlayerRepository();
        private final FakeWarpRepository warps = new FakeWarpRepository();
        private final FakePermissionRepository permissions = new FakePermissionRepository();
        private final FakeModerationRepository moderation = new FakeModerationRepository();
        private final FakeAdminStateRepository adminState = new FakeAdminStateRepository();
        private final FakeServerRepository servers = new FakeServerRepository();

        @Override public StorageProviderType type() { return StorageProviderType.JSON; }
        @Override public String displayName() { return "fake"; }
        @Override public void initialize() {}
        @Override public PlayerRepository players() { return players; }
        @Override public WarpRepository warps() { return warps; }
        @Override public PermissionRepository permissions() { return permissions; }
        @Override public ModerationRepository moderation() { return moderation; }
        @Override public AdminStateRepository adminState() { return adminState; }
        @Override public ServerRepository servers() { return servers; }
        @Override public StorageService.StorageTestResult test() { return new StorageService.StorageTestResult(true, "fake", true, "ok", 0, "not_needed", "not_needed"); }
        @Override public int migrationVersion() { return 0; }
    }

    private static class FakePlayerRepository implements PlayerRepository {
        private final Map<String, StoredPlayerProfile> profiles = new LinkedHashMap<>();
        private final Map<String, Map<String, StoredHome>> homes = new LinkedHashMap<>();
        private final Map<String, StoredLocation> backLocations = new LinkedHashMap<>();
        private final Map<String, Set<String>> ignored = new LinkedHashMap<>();

        @Override public List<StoredPlayerProfile> listProfiles() { return new ArrayList<>(profiles.values()); }
        @Override public Optional<StoredPlayerProfile> getProfile(String uuid) { return Optional.ofNullable(profiles.get(uuid)); }
        @Override public void upsertProfile(StoredPlayerProfile profile) { profiles.put(profile.uuid(), profile); }
        @Override public List<StoredHome> listHomes(String uuid) { return new ArrayList<>(homes.getOrDefault(uuid, Map.of()).values()); }
        @Override public Optional<StoredHome> getHome(String uuid, String homeName) { return Optional.ofNullable(homes.getOrDefault(uuid, Map.of()).get(homeName)); }
        @Override public void saveHome(StoredHome home) { homes.computeIfAbsent(home.uuid(), ignored -> new LinkedHashMap<>()).put(home.name(), home); }
        @Override public boolean deleteHome(String uuid, String homeName) {
            Map<String, StoredHome> playerHomes = homes.get(uuid);
            return playerHomes != null && playerHomes.remove(homeName) != null;
        }
        @Override public Optional<StoredLocation> getBackLocation(String uuid) { return Optional.ofNullable(backLocations.get(uuid)); }
        @Override public void setBackLocation(String uuid, StoredLocation location) { backLocations.put(uuid, location); }
        @Override public Set<String> listIgnoredPlayers(String uuid) { return ignored.getOrDefault(uuid, Set.of()); }
        @Override public boolean addIgnoredPlayer(String uuid, String ignoredUuid) { return ignored.computeIfAbsent(uuid, ignored -> new LinkedHashSet<>()).add(ignoredUuid); }
        @Override public boolean removeIgnoredPlayer(String uuid, String ignoredUuid) {
            Set<String> ignoredPlayers = ignored.get(uuid);
            return ignoredPlayers != null && ignoredPlayers.remove(ignoredUuid);
        }
    }

    private static class FakeWarpRepository implements WarpRepository {
        private final Map<String, StoredWarp> warps = new LinkedHashMap<>();
        private StoredLocation spawn;

        @Override public void saveWarp(StoredWarp warp) { warps.put(warp.name(), warp); }
        @Override public Optional<StoredWarp> getWarp(String name) { return Optional.ofNullable(warps.get(name)); }
        @Override public boolean deleteWarp(String name) { return warps.remove(name) != null; }
        @Override public List<StoredWarp> listWarps() { return new ArrayList<>(warps.values()); }
        @Override public Optional<StoredLocation> getGlobalSpawn() { return Optional.ofNullable(spawn); }
        @Override public void setGlobalSpawn(StoredLocation location) { spawn = location; }
    }

    private static class FakePermissionRepository implements PermissionRepository {
        private final Map<String, StoredPermissionGroup> groups = new LinkedHashMap<>();
        private final Map<String, StoredUserPermissionData> users = new LinkedHashMap<>();

        @Override public List<StoredPermissionGroup> listGroups() { return new ArrayList<>(groups.values()); }
        @Override public Optional<StoredPermissionGroup> getGroup(String groupName) { return Optional.ofNullable(groups.get(groupName)); }
        @Override public void saveGroup(StoredPermissionGroup group) { groups.put(group.name(), group); }
        @Override public boolean deleteGroup(String groupName) { return groups.remove(groupName) != null; }
        @Override public void addGroupParent(String groupName, String parentName) {}
        @Override public boolean removeGroupParent(String groupName, String parentName) { return false; }
        @Override public void addGroupPermission(String groupName, StoredPermissionNode permission) {}
        @Override public boolean removeGroupPermission(String groupName, String permission) { return false; }
        @Override public List<StoredUserPermissionData> listUsers() { return new ArrayList<>(users.values()); }
        @Override public Optional<StoredUserPermissionData> getUser(String uuid) { return Optional.ofNullable(users.get(uuid)); }
        @Override public void saveUser(StoredUserPermissionData user) { users.put(user.uuid(), user); }
        @Override public void addUserGroup(String uuid, StoredUserPermissionData.GroupAssignment assignment) {}
        @Override public boolean removeUserGroup(String uuid, String groupName) { return false; }
        @Override public void addUserPermission(String uuid, StoredPermissionNode permission) {}
        @Override public boolean removeUserPermission(String uuid, String permission) { return false; }
    }

    private static class FakeModerationRepository implements ModerationRepository {
        private final Map<String, PunishmentRecord> ledger = new LinkedHashMap<>();
        @Override public PunishmentRecord addPunishmentRecord(PunishmentRecord punishment) { ledger.put(punishment.punishmentId(), punishment); return punishment; }
        @Override public Optional<PunishmentRecord> findPunishmentRecord(String punishmentId) { return Optional.ofNullable(ledger.get(punishmentId)); }
        @Override public boolean revokePunishmentRecord(String punishmentId, long revokedAtMs, String actorUuid, String actorName, String reason) {
            PunishmentRecord current = ledger.get(punishmentId);
            if (current == null || current.revokedAtMs() != null) return false;
            ledger.put(punishmentId, current.revoked(revokedAtMs, actorUuid, actorName, reason));
            return true;
        }
        @Override public List<PunishmentRecord> listPunishmentRecords(String subjectUuid, int offset, int limit) {
            return ledger.values().stream().filter(record -> subjectUuid == null || subjectUuid.equals(record.subjectUuid()))
                    .skip(offset).limit(limit).toList();
        }
        @Override public List<PunishmentRecord> listActivePunishmentRecords(long updatedAfterMs) {
            return ledger.values().stream().filter(record -> record.activeAt(System.currentTimeMillis())).toList();
        }
        @Override public long addPunishment(StoredPunishment punishment) { return 0; }
        @Override public boolean deactivatePunishment(long id) { return false; }
        @Override public boolean deactivateActivePunishments(String type, String uuid, String name) { return false; }
        @Override public List<StoredPunishment> listPunishments() { return List.of(); }
        @Override public List<StoredPunishment> activePunishments(String uuid, ServerScope scope) { return List.of(); }
        @Override public List<StoredPunishment> consumeExpiredPunishments(long nowMs) { return List.of(); }
        @Override public long addWarning(StoredWarning warning) { return 0; }
        @Override public List<StoredWarning> listWarnings() { return List.of(); }
        @Override public List<StoredWarning> listWarnings(String uuid) { return List.of(); }
        @Override public void setJailLocation(StoredLocation location) {}
        @Override public Optional<StoredLocation> getJailLocation() { return Optional.empty(); }
        @Override public void setJailState(StoredJailState jailState) {}
        @Override public Optional<StoredJailState> getJailState(String uuid) { return Optional.empty(); }
        @Override public List<StoredJailState> listJailStates() { return List.of(); }
        @Override public boolean clearJailState(String uuid) { return false; }
        @Override public List<StoredJailState> consumeExpiredJails(long nowMs) { return List.of(); }
    }

    private static class FakeAdminStateRepository implements AdminStateRepository {
        @Override public boolean isGod(String uuid) { return false; }
        @Override public void setGod(String uuid, boolean enabled) {}
        @Override public boolean isVanished(String uuid) { return false; }
        @Override public void setVanished(String uuid, boolean enabled) {}
        @Override public Optional<StoredAdminState> getState(String key) { return Optional.empty(); }
        @Override public void setState(String key, String value) {}
        @Override public boolean deleteState(String key) { return false; }
        @Override public Set<String> keys() { return Set.of(); }
    }

    private static class FakeServerRepository implements ServerRepository {
        @Override public void registerServer(ServerIdentity identity) {}
        @Override public void updateLastSeen(ServerIdentity identity) {}
        @Override public List<ServerIdentity> listServers() { return List.of(); }
        @Override public Optional<ServerIdentity> getServer(String serverId) { return Optional.empty(); }
    }
}
