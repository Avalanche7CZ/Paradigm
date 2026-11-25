package eu.avalanche7.paradigm.modules.permissions;

import static org.junit.jupiter.api.Assertions.*;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.slf4j.LoggerFactory;

import eu.avalanche7.paradigm.configs.MainConfigHandler;
import eu.avalanche7.paradigm.data.AdminUtilityDataStore;
import eu.avalanche7.paradigm.data.PlayerDataStore;
import eu.avalanche7.paradigm.modules.permissions.context.PermissionContextResolver;
import eu.avalanche7.paradigm.modules.permissions.context.PermissionContextSet;
import eu.avalanche7.paradigm.platform.Interfaces.IConfig;
import eu.avalanche7.paradigm.platform.Interfaces.IPlayer;
import eu.avalanche7.paradigm.storage.StorageConfig;
import eu.avalanche7.paradigm.storage.StorageException;
import eu.avalanche7.paradigm.storage.StorageService;
import eu.avalanche7.paradigm.storage.StorageTransactionException;
import eu.avalanche7.paradigm.storage.identity.ServerIdentity;
import eu.avalanche7.paradigm.storage.json.JsonPermissionRepository;
import eu.avalanche7.paradigm.storage.migration.StorageMigrationOptions;
import eu.avalanche7.paradigm.utils.DebugLogger;

class PermissionPresentationTest {
    @TempDir Path directory;
    final UUID uuid = UUID.fromString("00000000-0000-0000-0000-000000000123");
    final PermissionContextSet context = PermissionContextSet.server("survival");

    PermissionAPI api() {
        return api(null);
    }

    private IConfig permissionConfig() {
        return new IConfig() {
            public Path getConfigDirectory() { return directory; }
            public String getModId() { return "paradigm"; }
        };
    }

    PermissionAPI api(PlayerDataStore players) {
        return api(players, new PermissionDataStore(null, null, permissionConfig()));
    }

    PermissionAPI api(PlayerDataStore players, PermissionDataStore store) {
        var api = new PermissionAPI(LoggerFactory.getLogger(getClass()), new DebugLogger(null), store, players);
        api.setContextResolver(new PermissionContextResolver(() -> new ServerIdentity("main", "survival", "survival")));
        api.initialize();
        return api;
    }

    private PlayerDataStore playerData() {
        return new PlayerDataStore(LoggerFactory.getLogger(getClass()), new DebugLogger(null), permissionConfig());
    }

    private PermissionDataStore rejectableStore(AtomicBoolean reject) {
        return new PermissionDataStore(null, null, permissionConfig()) {
            @Override public void saveOrThrow(PermissionState state) {
                if (reject.get()) throw new StorageException("Injected permissions.json write failure");
                super.saveOrThrow(state);
            }
        };
    }

    void group(PermissionAPI api, String group, int weight) {
        assertTrue(api.createGroup(group));
        assertTrue(api.setGroupMetadata(group, "weight", String.valueOf(weight)));
        assertTrue(api.setGroupMetadata(group, "prefix", "[" + group + "]"));
    }

    private PermissionAPI.PermissionExplain assertDecision(PermissionAPI api, String node, PermissionContextSet contexts,
                                                            Boolean allowed, String sourceType, String sourceName, String rule) {
        var explanation = api.explainPermission(uuid, node, contexts);
        assertEquals(allowed, api.hasPermission(uuid, node, contexts), node);
        assertEquals(allowed, explanation.allowed(), node);
        assertEquals(sourceType, explanation.sourceType(), node);
        assertEquals(sourceName, explanation.sourceName(), node);
        assertEquals(rule, explanation.rule(), node);
        return explanation;
    }

    @Test void checksAndExplanationsSharePrecedenceAndWildcardMatching() {
        var api = api();
        var other = PermissionContextSet.server("other");
        var world = PermissionContextSet.of(Map.of("server", "survival", "world", "minecraft:the_nether"));
        String user = uuid.toString();
        assertEquals(List.of("default"),
                assertDecision(api, "example.unknown", context, null, "none", "", "").groupsChecked());
        assertEquals(List.of(),
                assertDecision(api, " ", context, null, "invalid", "", "").groupsChecked());

        assertTrue(api.addGroupPermission("default", "example.read", false));
        assertTrue(api.addUserPermission(uuid, "example.*", true));
        assertDecision(api, " EXAMPLE.READ ", context, true, "group", "default", "example.read");
        assertDecision(api, "example", context, false, "user", user, "-example.*");
        assertDecision(api, "example.child.deep", context, false, "user", user, "-example.*");
        assertDecision(api, "examples.child", context, null, "none", "", "");

        assertTrue(api.addUserPermission(uuid, "example.read", false));
        assertTrue(api.addGroupPermission("default", "example.read", true));
        assertDecision(api, "example.read", other, true, "user", user, "example.read");
        assertTrue(api.addUserPermission(uuid, "example.read", true));
        assertDecision(api, "example.read", other, false, "user", user, "-example.read");

        assertTrue(api.addGroupPermission("default", "*", false, context, null));
        assertDecision(api, "example.read", context, true, "group", "default", "*");
        assertDecision(api, "unrelated.node", context, true, "group", "default", "*");
        assertTrue(api.addGroupPermission("default", "*", true, world, null));
        assertDecision(api, "example.read", world, false, "group", "default", "-*");
        assertTrue(api.addUserPermission(uuid, "custom*action", false));
        assertDecision(api, "custom_test_action", other, true, "user", user, "custom*action");
    }

    @Test void contextualTemporaryAssignmentsAndRulesAreEvaluatedTogether() {
        var api = api();
        var other = PermissionContextSet.server("other");
        long now = System.currentTimeMillis();
        group(api, "active", 10);
        group(api, "expired", 100);
        assertTrue(api.assignGroup(uuid, "active", now + 600_000L, "test", context));
        assertTrue(api.assignGroup(uuid, "expired", now - 1_000L, "test", context));
        assertTrue(api.addGroupPermission("active", "example.temporary", false, context, now + 600_000L));
        assertTrue(api.addGroupPermission("active", "example.temporary", true, context, now - 1_000L));
        assertTrue(api.addGroupPermission("expired", "example.temporary", true, context, now + 600_000L));
        assertTrue(api.addUserPermission(uuid, "example.temporary", true, context, now - 1_000L));
        assertTrue(api.addUserPermission(uuid, "example.direct", false, context, now + 600_000L));

        assertEquals(List.of("default", "active"),
                assertDecision(api, "example.temporary", context, true, "group", "active", "example.temporary").groupsChecked());
        assertDecision(api, "example.temporary", other, null, "none", "", "");
        assertDecision(api, "example.direct", context, true, "user", uuid.toString(), "example.direct");
        assertDecision(api, "example.direct", other, null, "none", "", "");
    }

    @Test void sharedAncestorsAndDiamondUseBreadthFirstSourceOrderWithoutGroupWeight() {
        var api = api();
        group(api, "left", 10);
        group(api, "right", 20);
        group(api, "shared", 100);
        group(api, "root", 1);
        assertTrue(api.addGroupParent("default", "left"));
        assertTrue(api.addGroupParent("default", "right"));
        assertTrue(api.addGroupParent("left", "shared"));
        assertTrue(api.addGroupParent("right", "shared"));
        assertTrue(api.addGroupParent("root", "shared"));
        assertTrue(api.assignGroup(uuid, "root"));
        assertTrue(api.addGroupPermission("shared", "example.inherited", false));
        assertTrue(api.addGroupPermission("shared", "example.tie", false));
        assertTrue(api.addGroupPermission("root", "example.tie", false));
        assertTrue(api.addGroupPermission("root", "example.deny", true));
        assertTrue(api.addGroupPermission("shared", "example.deny", false));

        assertEquals(List.of("default", "root", "left", "right", "shared"), api.resolveMeta(uuid).groups());
        assertEquals(List.of("default", "root"),
                assertDecision(api, "example.inherited", context, true, "group", "shared", "example.inherited").groupsChecked());
        assertDecision(api, "example.tie", context, true, "group", "root", "example.tie");
        assertDecision(api, "example.deny", context, false, "group", "root", "-example.deny");
        assertDecision(api(), "example.tie", context, true, "group", "root", "example.tie");
    }

    @Test void missingGroupsDoNotInterruptTraversal() throws Exception {
        Path config = Files.createDirectories(directory.resolve("paradigm"));
        Files.writeString(config.resolve("permissions.json"), """
                {
                  "default_group": "missing-default",
                  "groups": {
                    "root": {"inherits": ["missing-parent", "parent"]},
                    "parent": {"permissions": ["example.inherited"]}
                  },
                  "users": {"%s": {"groups": ["missing-root", "root"]}}
                }
                """.formatted(uuid));
        var api = api();

        assertEquals(List.of("missing-root", "root"),
                assertDecision(api, "example.inherited", context, true, "group", "parent", "example.inherited").groupsChecked());
        assertEquals(List.of("root", "parent"), api.resolveMeta(uuid).groups());
    }

    @Test void builtInsCanBeDeletedWithoutReturningAfterReload() {
        var api = api();
        assertTrue(api.deleteGroup("admin"));
        assertTrue(api.deleteGroup("default"));
        assertTrue(api().listGroups().isEmpty());
    }

    @Test void metadataBatchValidatesEveryFieldAndRetainsUntouchedGroupData() {
        var api = api();
        group(api, "staff", 10);
        assertTrue(api.setGroupMetadata("staff", "suffix", "existing suffix"));
        assertTrue(api.addGroupParent("staff", "default"));
        assertTrue(api.addGroupPermission("staff", "example.staff", false, context, null));
        var original = api.getGroupInfo("staff");
        long version = api.stateVersion();
        var metadata = new java.util.LinkedHashMap<String, String>();
        metadata.put("prefix", "new prefix");
        metadata.put("weight", "invalid");
        assertFalse(api.setGroupMetadata("staff", metadata));
        metadata.put("weight", "12");
        metadata.put("chatMessageFormat", "<bold>missing placeholder</bold>");
        assertFalse(api.setGroupMetadata("staff", metadata));
        assertEquals(original, api.getGroupInfo("staff"));
        assertEquals(version, api.stateVersion());

        metadata.put("chatMessageFormat", "<bold>{message}</bold>");
        assertTrue(api.setGroupMetadata("staff", metadata));
        assertEquals(version + 1, api.stateVersion());
        var updated = api.getGroupInfo("staff");
        assertEquals("new prefix", updated.prefix());
        assertEquals(12, updated.weight());
        assertEquals("<bold>{message}</bold>", updated.chatMessageFormat());
        assertEquals(original.suffix(), updated.suffix());
        assertEquals(original.inherits(), updated.inherits());
        assertEquals(original.assignments(), updated.assignments());
        assertEquals(updated, api().getGroupInfo("staff"));
    }

    @Test void renameRechecksDashboardMutationAuthorityIncludingManageBypass() {
        var mode = new java.util.concurrent.atomic.AtomicInteger(0);
        var writes = new java.util.concurrent.atomic.AtomicInteger();
        var handler = new PermissionsHandler(null, null, null, null, null, null) {
            @Override public boolean hasPermission(String uuid, String node, int fallback) {
                return switch (mode.get()) {
                    case 1 -> !node.equals(ParadigmPermissions.GROUP_MANAGE.node()) && !node.equals(ParadigmPermissions.DASHBOARD_MANAGE.node());
                    case 2 -> !node.equals(ParadigmPermissions.DASHBOARD_MANAGE.node());
                    case 3 -> node.equals(ParadigmPermissions.DASHBOARD_MANAGE.node());
                    default -> false;
                };
            }
            @Override public PermissionAPI.GroupInfo getPermissionGroupInfo(String group) {
                return group.equals("admin") ? new PermissionAPI.GroupInfo("admin", "", "", "", 0, List.of(), List.of()) : null;
            }
            @Override public boolean renamePermissionGroup(String source, String target) { writes.incrementAndGet(); return true; }
        };
        var services = new eu.avalanche7.paradigm.core.Services(null, null, null, null, null, null, null, null, null, null, null, null, handler, null, null, null, null, null, null, null, null);
        var service = new PermissionAdminService(services, null);
        var actor = new eu.avalanche7.paradigm.modules.dashboard.auth.DashboardPrincipal(uuid.toString(), "Tester", false);
        var request = new PermissionMutationRequest();
        request.action = "group_rename"; request.group = "admin"; request.target = "staff";
        request.scope = "custom"; request.contexts = Map.of(); request.confirmed = true;
        assertEquals("permission_denied", service.mutate(actor, request).code());
        mode.set(1);
        assertEquals("permission_denied", service.mutate(actor, request).code());
        assertEquals(0, writes.get());
        mode.set(2); assertTrue(service.mutate(actor, request).applied());
        mode.set(3); assertTrue(service.mutate(actor, request).applied());
        mode.set(0); assertEquals("permission_denied", service.mutate(actor, request).code());
        assertEquals(2, writes.get());
    }

    @Test void renamePreservesAssignmentsInheritanceTracksAndDefaultRole() {
        var api = api();
        group(api, "child", 10);
        api.addGroupParent("child", "default");
        api.addGroupPermission("default", "example.allow", false);
        api.assignGroup(uuid, "default");
        api.setDisplayGroup(uuid, "default", context);
        api.createTrack("ranks"); api.appendTrackGroup("ranks", "default");
        var permissionIds = api.getGroupInfo("default").assignments().stream().map(PermissionAssignment::id).toList();
        var membershipId = api.getUserInfo(uuid).groupAssignments().stream().filter(a -> a.value().equals("default")).findFirst().orElseThrow().id();
        assertFalse(api.renameGroup("default", "admin"));
        assertTrue(api.renameGroup("default", "members"));
        api = api();
        assertNull(api.getGroupInfo("default"));
        assertEquals(permissionIds, api.getGroupInfo("members").assignments().stream().map(PermissionAssignment::id).toList());
        assertTrue(api.getUserInfo(uuid).groupAssignments().stream().anyMatch(a -> a.id().equals(membershipId) && a.value().equals("members")));
        assertEquals(List.of("members"), api.getGroupInfo("child").inherits());
        assertEquals(List.of("members"), api.getTrack("ranks").groups());
        assertEquals("members", api.displayGroupPreference(uuid));
        assertEquals(Boolean.TRUE, api.hasPermission(UUID.randomUUID(), "example.allow"));
    }

    @Test void migratesLegacyTemporaryMembershipAndPreservesItsIdentityThroughRenameAndDeletion() {
        var players = playerData();
        long expiry = System.currentTimeMillis() + 600_000;
        assertTrue(players.setTemporaryGroup(uuid.toString(), "admin", expiry, 123L, "tester"));
        var api = api(players);
        assertDecision(api, "paradigm.legacy_mirror", context, true, "group", "admin", "paradigm.*");
        var before = api.getUserInfo(uuid).groupAssignments().get(0);
        var expectedId = PermissionAssignmentId.deterministic("USER_GROUP", uuid.toString(), "admin", false,
                PermissionContextSet.empty(), expiry, "tester@123");
        assertEquals(expectedId, before.id());
        assertEquals(new PermissionAPI.TemporaryGroupInfo("admin", expiry, 123L, "tester"),
                api.getUserGroups(uuid).temporaryGroups().get(0));
        api.reload();
        assertEquals(List.of(before), api.getUserInfo(uuid).groupAssignments());

        assertTrue(api.renameGroup("admin", "staff"));
        assertEquals(1, players.getTemporaryGroups(uuid.toString()).size());
        var after = api(playerData()).getUserInfo(uuid).groupAssignments().get(0);
        assertEquals(expectedId, after.id());
        assertEquals(expiry, after.expiresAtMs());
        assertEquals("staff", after.value());
        assertTrue(api.deleteGroup("staff"));
        assertTrue(api.createGroup("admin"));
        assertTrue(api(playerData()).getUserInfo(uuid).groupAssignments().isEmpty());
    }

    @Test void failedMigrationPublishesNothingAndBlocksMutationsUntilRetry() throws Exception {
        var players = playerData();
        assertTrue(players.setTemporaryGroup(uuid.toString(), "admin", System.currentTimeMillis() + 600_000, 123L, "tester"));
        api(); // Create a legacy permissions file.
        var path = permissionConfig().resolveConfigPath("paradigm/permissions.json");
        byte[] original = Files.readAllBytes(path);
        var reject = new AtomicBoolean(true);
        var api = api(players, rejectableStore(reject));

        assertEquals("unavailable", api.explainPermission(uuid, "paradigm.legacy_mirror").sourceType());
        assertTrue(api.getUserInfo(uuid).groupAssignments().isEmpty());
        assertThrows(StorageException.class, () -> api.createGroup("unsafe"));
        assertNull(api.getGroupInfo("unsafe"));
        assertArrayEquals(original, Files.readAllBytes(path));
        assertEquals(1, players.getTemporaryGroups(uuid.toString()).size());

        reject.set(false);
        api.reloadFromStore();
        assertEquals(1, api.getUserInfo(uuid).groupAssignments().size());
        assertEquals(Boolean.TRUE, api.hasPermission(uuid, "paradigm.legacy_mirror"));
        assertTrue(api.createGroup("safe"));
    }

    @Test void migrationDoesNotReplaceNewerAssignmentOrRestoreExpiredGrant() {
        var beforeMigration = api();
        var players = playerData();
        UUID expiredUser = UUID.randomUUID();
        long now = System.currentTimeMillis();
        assertTrue(beforeMigration.assignGroup(uuid, "admin", now + 600_000, "newer"));
        var expected = beforeMigration.getUserInfo(uuid).groupAssignments();
        assertTrue(players.setTemporaryGroup(uuid.toString(), "admin", now + 900_000, 1L, "old"));
        assertTrue(players.setTemporaryGroup(expiredUser.toString(), "admin", now - 10_000, 1L, "old"));
        var api = api(players);
        assertEquals(expected, api.getUserInfo(uuid).groupAssignments());
        assertTrue(api.getUserInfo(expiredUser).groupAssignments().isEmpty());
        assertNull(api.hasPermission(expiredUser, "paradigm.legacy_mirror"));
    }

    @Test void failedSingleFileTemporaryGrantRevocationAndPermanentReplacementRollBack() throws Exception {
        var players = playerData();
        var reject = new AtomicBoolean();
        var api = api(players, rejectableStore(reject));
        var expiry = System.currentTimeMillis() + 600_000;
        var path = permissionConfig().resolveConfigPath("paradigm/permissions.json");
        assertTrue(api.addUserPermission(uuid, "example.present", false));

        byte[] initial = Files.readAllBytes(path);
        reject.set(true);
        assertThrows(StorageException.class, () -> api.assignGroup(uuid, "admin", expiry, "tester"));
        assertNull(api.hasPermission(uuid, "paradigm.temporary"));
        assertNull(api(playerData()).hasPermission(uuid, "paradigm.temporary"));
        assertArrayEquals(initial, Files.readAllBytes(path));

        reject.set(false);
        assertTrue(api.assignGroup(uuid, "admin", expiry, "tester"));
        var expected = api.getUserInfo(uuid).groupAssignments();
        var id = expected.get(0).id();
        byte[] granted = Files.readAllBytes(path);
        assertTrue(players.getTemporaryGroups(uuid.toString()).isEmpty());
        reject.set(true);
        assertThrows(StorageException.class, () -> api.revokeGroup(uuid, "admin"));
        assertThrows(StorageException.class, () -> api.revokeGroupById(uuid, id));
        assertThrows(StorageException.class, () -> api.assignGroup(uuid, "admin"));
        assertEquals(expected, api.getUserInfo(uuid).groupAssignments());
        assertEquals(expected, api(playerData()).getUserInfo(uuid).groupAssignments());
        assertArrayEquals(granted, Files.readAllBytes(path));

        reject.set(false);
        assertTrue(api.assignGroup(uuid, "admin"));
        var reloaded = api(playerData());
        assertTrue(reloaded.getUserGroups(uuid).temporaryGroups().isEmpty());
        assertEquals(List.of("admin"), reloaded.getUserGroups(uuid).permanentGroups());
    }

    @Test void temporaryTrackChangesKeepAssignmentIdAndMetadata() {
        var players = playerData();
        var api = api(players);
        var global = PermissionContextSet.empty();
        assertTrue(api.createGroup("vip"));
        assertTrue(api.createTrack("ranks").applied());
        assertTrue(api.appendTrackGroup("ranks", "vip").applied());
        assertTrue(api.appendTrackGroup("ranks", "admin").applied());
        long expiry = System.currentTimeMillis() + 600_000;
        assertTrue(api.moveUserOnTrack(uuid, "ranks", global, "set", false, false, expiry, "tester", "vip").applied());
        var before = api.getUserGroups(uuid).temporaryGroups().get(0);
        var id = api.getUserInfo(uuid).groupAssignments().get(0).id();
        assertTrue(api.moveUserOnTrack(uuid, "ranks", global, "promote", false, false, null, "operator", null).applied());
        api.reload();
        assertEquals(List.of(new PermissionAPI.TemporaryGroupInfo("admin", expiry, before.assignedAtMs(), "tester")),
                api.getUserGroups(uuid).temporaryGroups());
        assertEquals(id, api.getUserInfo(uuid).groupAssignments().get(0).id());
        assertTrue(api.moveUserOnTrack(uuid, "ranks", global, "demote", false, false, null, "operator", null).applied());
        assertEquals(id, api.getUserInfo(uuid).groupAssignments().get(0).id());
        assertTrue(api.moveUserOnTrack(uuid, "ranks", global, "clear", false, false, null, "operator", null).applied());
        assertTrue(api(playerData()).getUserInfo(uuid).groupAssignments().isEmpty());
    }

    @Test void uncertainJsonWriteRequiresReloadBeforeAnotherCommit() {
        var reject = new AtomicBoolean();
        var api = api(playerData(), new PermissionDataStore(null, null, permissionConfig()) {
            @Override public void saveOrThrow(PermissionState state) {
                super.saveOrThrow(state);
                if (reject.get()) throw new StorageTransactionException("Injected failure after replacement", null,
                        StorageTransactionException.Outcome.UNKNOWN);
            }
        });
        reject.set(true);
        var expiry = System.currentTimeMillis() + 600_000;
        assertEquals(StorageTransactionException.Outcome.UNKNOWN,
                assertThrows(StorageTransactionException.class, () -> api.assignGroup(uuid, "admin", expiry, "tester")).outcome());
        assertNull(api.hasPermission(uuid, "paradigm.temporary"));
        reject.set(false);
        assertThrows(StorageTransactionException.class, () -> api.createGroup("pending"));
        assertNull(api.getGroupInfo("pending"));

        api.reloadFromStore();
        assertEquals(Boolean.TRUE, api.hasPermission(uuid, "paradigm.temporary"));
        assertTrue(api.revokeGroup(uuid, "admin"));
        assertNull(api(playerData()).hasPermission(uuid, "paradigm.temporary"));
    }

    @Test void jsonToSqlTransfersAuthoritativeMembershipsAndNeverImportsRevokedMirrors() throws Exception {
        var players = playerData();
        UUID revoked = UUID.randomUUID();
        long expiry = System.currentTimeMillis() + 600_000;
        assertTrue(players.setTemporaryGroup(uuid.toString(), "admin", expiry, 1L, "tester"));
        assertTrue(players.setTemporaryGroup(revoked.toString(), "admin", expiry, 2L, "tester"));
        var json = new JsonPermissionRepository(null, null, permissionConfig(), players);
        assertTrue(json.listUsers().stream().anyMatch(user -> user.uuid().equals(uuid.toString())));
        var path = permissionConfig().resolveConfigPath("paradigm/permissions.json");
        assertFalse(Files.exists(path));

        var logger = LoggerFactory.getLogger(getClass());
        var config = new StorageConfig();
        config.provider = "sqlite";
        config.sqlite.path = directory.resolve("migrated.db").toString();
        config.runtimeLibraries.enabled = false;
        config.persist(permissionConfig(), logger);
        var mainConfig = MainConfigHandler.class.getDeclaredField("INSTANCE");
        mainConfig.setAccessible(true);
        var previous = mainConfig.get(null);
        var debug = new DebugLogger(null);
        var adminData = new AdminUtilityDataStore(logger, permissionConfig());
        MainConfigHandler.init(permissionConfig(), debug);
        try (var storage = new StorageService(logger, debug, permissionConfig(), players, null, null, adminData)) {
            var dryRun = new StorageMigrationOptions(true, StorageMigrationOptions.ConflictPolicy.OVERWRITE, false);
            assertTrue(storage.migrateAsync("json", "sql", dryRun).get(10, TimeUnit.SECONDS).success());
            assertFalse(Files.exists(path));
            assertTrue(storage.migrateAsync("json", "sql").get(10, TimeUnit.SECONDS).success());
            assertTrue(Files.exists(path));
            var api = api(players);
            var active = api.getUserInfo(uuid).groupAssignments().get(0);
            assertTrue(storage.permissions().deleteUser(revoked.toString()));
            assertTrue(api.revokeGroupById(revoked, api.getUserInfo(revoked).groupAssignments().get(0).id()));
            assertEquals(1, players.getTemporaryGroups(revoked.toString()).size());
            api.reload();
            assertTrue(api.getUserInfo(revoked).groupAssignments().isEmpty());

            assertTrue(storage.migrateAsync("json", "sql").get(10, TimeUnit.SECONDS).success());
            var stored = storage.permissions().getUser(uuid.toString()).orElseThrow().groups().get(0);
            assertEquals(active.id(), stored.assignmentId());
            assertEquals(expiry, stored.expiresAtMs());
            assertEquals("tester", stored.assignedBy());
            assertTrue(storage.permissions().getUser(revoked.toString()).stream().flatMap(user -> user.groups().stream()).findAny().isEmpty());
            api.setPermissionRepository(storage.permissions());
            api.reloadFromStore();
            assertEquals(Boolean.TRUE, api.hasPermission(uuid, "paradigm.legacy_mirror"));
            assertNull(api.hasPermission(revoked, "paradigm.legacy_mirror"));
        } finally {
            mainConfig.set(null, previous);
        }
    }

    @Test void implicitDefaultUsesEffectiveWeightWithoutCreatingMemberships() {
        var api = api();
        group(api, "hrac", 100);
        assertTrue(api.setGroupMetadata("default", "weight", "90"));
        assertTrue(api.setGroupMetadata("hrac", "suffix", " <bold>member</bold>"));
        assertTrue(api.addGroupParent("default", "hrac"));
        assertTrue(api.addGroupPermission("hrac", "example.member", false));

        var info = api.getUserInfo(uuid);
        assertEquals("hrac", info.meta().primaryGroup());
        assertEquals(List.of("default", "hrac"), info.meta().groups());
        assertEquals("[hrac]", info.meta().prefix());
        assertEquals(" <bold>member</bold>", info.meta().suffix());
        assertEquals(List.of("default"),
                assertDecision(api, "example.member", context, true, "group", "hrac", "example.member").groupsChecked());
        assertTrue(info.groupAssignments().isEmpty());
        assertTrue(info.permanentGroups().isEmpty());
        assertFalse(api.listUserIds().contains(uuid));
    }

    @Test void metadataSkipsBlankFieldsAndUsesStableWeightAndNamePriority() {
        var api = api();
        group(api, "zeta", 100);
        group(api, "alpha", 100);
        group(api, "high", 200);
        assertTrue(api.addGroupParent("default", "high"));
        assertTrue(api.addGroupParent("high", "zeta"));
        assertTrue(api.addGroupParent("high", "alpha"));
        assertTrue(api.setGroupMetadata("high", "prefix", " "));
        assertTrue(api.setGroupMetadata("high", "suffix", "high suffix"));
        assertTrue(api.setGroupMetadata("zeta", "suffix", "zeta suffix"));

        var meta = api.resolveMeta(uuid);
        assertEquals("high", meta.primaryGroup());
        assertEquals("[alpha]", meta.prefix());
        assertEquals("high suffix", meta.suffix());
        assertEquals(meta, api().resolveMeta(uuid));
    }

    @Test void displayGroupPrefersItsOwnMetadataAndThenItsAncestors() {
        var api = api();
        group(api, "display", 10);
        group(api, "parent", 20);
        group(api, "unrelated", 100);
        assertTrue(api.addGroupParent("display", "parent"));
        assertTrue(api.setGroupMetadata("parent", "suffix", "parent suffix"));
        assertTrue(api.setGroupMetadata("parent", "chatNameFormat", "<bold>{player_name}</bold>"));
        assertTrue(api.setGroupMetadata("parent", "chatMessageFormat", "<bold>{message}</bold>"));
        assertTrue(api.setGroupMetadata("unrelated", "suffix", "unrelated suffix"));
        assertTrue(api.assignGroup(uuid, "display"));
        assertTrue(api.assignGroup(uuid, "unrelated"));
        assertTrue(api.setDisplayGroup(uuid, "display", context));

        var meta = api.resolveMeta(uuid);
        assertEquals("display", meta.primaryGroup());
        assertEquals("[display]", meta.prefix());
        assertEquals("parent suffix", meta.suffix());
        assertEquals("<bold>{player_name}</bold>", meta.chatNameFormat());
        assertEquals("<bold>{message}</bold>", meta.chatMessageFormat());
        assertEquals(10, meta.weight());
    }

    @Test void effectiveGroupsStopAtTheDepthLimitAndHandleCyclesAndShorterPaths() {
        var api = api();
        String previous = "default";
        for (int depth = 1; depth <= 33; depth++) {
            String next = "depth-" + depth;
            group(api, next, depth);
            assertTrue(api.addGroupParent(previous, next));
            previous = next;
        }
        assertTrue(api.addGroupParent("depth-32", "default"));
        assertTrue(api.addGroupPermission("depth-32", "example.boundary", false));
        assertTrue(api.addGroupPermission("depth-33", "example.beyond", false));
        var meta = api.resolveMeta(uuid);
        assertEquals("depth-32", meta.primaryGroup());
        assertEquals(33, meta.groups().size());
        assertFalse(meta.groups().contains("depth-33"));
        assertDecision(api, "example.boundary", context, true, "group", "depth-32", "example.boundary");
        assertDecision(api, "example.beyond", context, null, "none", "", "");

        assertTrue(api.addGroupParent("default", "depth-32"));
        assertEquals("depth-33", api.resolveMeta(uuid).primaryGroup());
        assertDecision(api, "example.beyond", context, true, "group", "depth-33", "example.beyond");
    }

    @Test void highestWeightAndAlphabeticTiesIgnoreAssignmentOrderAndOp() {
        var api = api();
        group(api, "zeta", 10);
        group(api, "alpha", 10);
        api.assignGroup(uuid, "zeta");
        api.assignGroup(uuid, "alpha");
        assertEquals("alpha", api.resolveMeta(uuid).primaryGroup());
        api.setGroupMetadata("zeta", "weight", "11");
        assertEquals("zeta", api.resolveMeta(uuid).primaryGroup());
        assertEquals("[zeta]", api.resolveMeta(uuid).prefix());
        IPlayer player = new IPlayer() {
            public String getUUID() { return uuid.toString(); }
            public String getName() { return "Operator"; }
            public Object getOriginalPlayer() { return null; }
        };
        assertEquals(api.resolveMeta(uuid), api.resolveMeta(player));
    }

    @Test void preferencePersistsWithoutChangingPermissionsAndFallsBackAfterRemoval() {
        var api = api();
        group(api, "vip", 10);
        group(api, "mod", 100);
        api.assignGroup(uuid, "vip");
        api.assignGroup(uuid, "mod");
        api.addGroupPermission("mod", "example.staff", false);
        assertTrue(api.setDisplayGroup(uuid, "vip", context));
        assertEquals("vip", api.resolveMeta(uuid).primaryGroup());
        assertEquals(Boolean.TRUE, api.hasPermission(uuid, "example.staff"));
        api.addUserPermission(uuid, "example.staff", true, PermissionContextSet.empty(), null);
        assertEquals(Boolean.FALSE, api.hasPermission(uuid, "example.staff"));
        var loaded = api();
        assertEquals("vip", loaded.resolveMeta(uuid).primaryGroup());
        loaded.revokeGroup(uuid, "vip");
        assertEquals("mod", loaded.resolveMeta(uuid).primaryGroup());
        assertTrue(loaded.setDisplayGroup(uuid, "auto", context));
        assertEquals("", loaded.displayGroupPreference(uuid));
    }

    @Test void implicitAndInheritedGroupsCannotBeSelectedAndWorldContextsAreLive() {
        var api = api();
        group(api, "worldrank", 100);
        group(api, "parent", 500);
        api.addGroupParent("worldrank", "parent");
        var world = PermissionContextSet.of(Map.of("server", "survival", "world", "minecraft:the_nether"));
        api.assignGroup(uuid, "worldrank", 0L, "test", world);
        assertFalse(api.setDisplayGroup(uuid, "default", context));
        assertFalse(api.setDisplayGroup(uuid, "parent", world));
        assertFalse(api.setDisplayGroup(uuid, "worldrank", context));
        assertEquals("parent", api.resolveMeta(uuid, world).primaryGroup());
        assertEquals("default", api.resolveMeta(uuid, context).primaryGroup());
        assertTrue(api.setDisplayGroup(uuid, "worldrank", world));
        IPlayer player = new IPlayer() {
            public String getUUID() { return uuid.toString(); }
            public String getName() { return "Alex"; }
            public String getWorldId() { return "minecraft:the_nether"; }
            public Object getOriginalPlayer() { return null; }
        };
        assertEquals("worldrank", api.resolveMeta(player).primaryGroup());
        assertEquals("default", api.resolveMeta(uuid).primaryGroup());
        assertEquals("worldrank", api.displayGroupPreference(uuid));
    }

    @Test void selectedTemporaryAssignmentFallsBackAtExpiryWithoutMutationOrRelog() throws Exception {
        var api = api();
        group(api, "temporary", 10);
        long expiry = System.currentTimeMillis() + 500L;
        assertTrue(api.assignGroup(uuid, "temporary", expiry, "test", context));
        assertTrue(api.setDisplayGroup(uuid, "temporary", context));
        assertEquals("temporary", api.resolveMeta(uuid).primaryGroup());
        Thread.sleep(Math.max(1L, expiry - System.currentTimeMillis() + 25L));
        assertEquals("default", api.resolveMeta(uuid).primaryGroup());
        assertEquals("temporary", api.displayGroupPreference(uuid));
    }

    @Test void jsonPreferenceWriteFailureIsNotReportedAsSuccess() throws Exception {
        java.nio.file.Files.writeString(directory.resolve("paradigm"), "blocks the config directory");
        var config = new IConfig() {
            public Path getConfigDirectory() { return directory; }
            public String getModId() { return "paradigm"; }
        };
        var api = new PermissionAPI(null, null, new PermissionDataStore(null, null, config), null);
        assertThrows(eu.avalanche7.paradigm.storage.StorageException.class,
                () -> api.setDisplayGroup(uuid, "auto", context));
    }

    @Test void expiredAssignmentsAreNeverDisplayCandidates() {
        var api = api();
        group(api, "expired", 1000);
        group(api, "expired-parent", 2000);
        assertTrue(api.addGroupParent("expired", "expired-parent"));
        api.assignGroup(uuid, "expired", System.currentTimeMillis() - 1000L, "test", context);
        assertFalse(api.availableDisplayGroups(uuid, context).contains("expired"));
        assertFalse(api.setDisplayGroup(uuid, "expired", context));
        assertFalse(api.resolveMeta(uuid).groups().contains("expired-parent"));
        assertEquals("default", api.resolveMeta(uuid).primaryGroup());
    }

    @Test void promotionOnlyChangesSelectedTrackAndStylesFollowDisplayPreference() {
        var api = api();
        group(api, "helper", 10);
        group(api, "mod", 100);
        group(api, "vip", 50);
        api.createTrack("staff");
        api.appendTrackGroup("staff", "helper");
        api.appendTrackGroup("staff", "mod");
        api.assignGroup(uuid, "helper");
        api.assignGroup(uuid, "vip");
        assertTrue(api.setGroupMetadata("vip", "chatNameFormat", "<bold>{player_name}</bold>"));
        assertTrue(api.setGroupMetadata("vip", "chatMessageFormat", "<color:#abcdef>{message}</color>"));
        assertFalse(api.setGroupMetadata("vip", "chatMessageFormat", "hidden"));
        assertTrue(api.setDisplayGroup(uuid, "vip", context));
        assertTrue(api.moveUserOnTrack(uuid, "staff", PermissionContextSet.empty(), "promote", false, false, null, "test", null).applied());
        assertTrue(api.getUserInfo(uuid).permanentGroups().containsAll(java.util.List.of("vip", "mod")));
        assertFalse(api.getUserInfo(uuid).permanentGroups().contains("helper"));
        assertEquals("vip", api.resolveMeta(uuid).primaryGroup());
        assertEquals("<bold>{player_name}</bold>", api.resolveMeta(uuid).chatNameFormat());
        assertEquals(api.resolveMeta(uuid), api().resolveMeta(uuid));
    }
}
