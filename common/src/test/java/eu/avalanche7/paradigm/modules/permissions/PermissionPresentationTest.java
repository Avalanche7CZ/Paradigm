package eu.avalanche7.paradigm.modules.permissions;

import static org.junit.jupiter.api.Assertions.*;

import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import eu.avalanche7.paradigm.modules.permissions.context.PermissionContextResolver;
import eu.avalanche7.paradigm.modules.permissions.context.PermissionContextSet;
import eu.avalanche7.paradigm.platform.Interfaces.IConfig;
import eu.avalanche7.paradigm.platform.Interfaces.IPlayer;
import eu.avalanche7.paradigm.storage.identity.ServerIdentity;

class PermissionPresentationTest {
    @TempDir Path directory;
    final UUID uuid = UUID.fromString("00000000-0000-0000-0000-000000000123");
    final PermissionContextSet context = PermissionContextSet.server("survival");

    PermissionAPI api() {
        return api(null);
    }

    PermissionAPI api(eu.avalanche7.paradigm.data.PlayerDataStore players) {
        var config = new IConfig() {
            public Path resolveConfigPath(String path) { return directory.resolve(path); }
            public Path getConfigDirectory() { return directory; }
            public String getModId() { return "paradigm"; }
        };
        var api = new PermissionAPI(org.slf4j.LoggerFactory.getLogger(getClass()), new eu.avalanche7.paradigm.utils.DebugLogger(null), new PermissionDataStore(null, null, config), players);
        api.setContextResolver(new PermissionContextResolver(() -> new ServerIdentity("main", "survival", "survival")));
        api.initialize();
        return api;
    }

    void group(PermissionAPI api, String group, int weight) {
        assertTrue(api.createGroup(group));
        assertTrue(api.setGroupMetadata(group, "weight", String.valueOf(weight)));
        assertTrue(api.setGroupMetadata(group, "prefix", "[" + group + "]"));
    }

    @Test void builtInsCanBeDeletedWithoutReturningAfterReload() {
        var api = api();
        assertTrue(api.deleteGroup("admin"));
        assertTrue(api.deleteGroup("default"));
        assertTrue(api().listGroups().isEmpty());
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

    @Test void renameMigratesLegacyTemporaryMembershipWithoutChangingIdentityOrExpiry() {
        var config = new IConfig() {
            public Path getConfigDirectory() { return directory; }
            public String getModId() { return "paradigm"; }
        };
        var players = new eu.avalanche7.paradigm.data.PlayerDataStore(null, null, config);
        var api = api(players);
        long expiry = System.currentTimeMillis() + 600_000;
        assertTrue(api.assignGroup(uuid, "admin", expiry, "tester"));
        var before = api.getUserInfo(uuid).groupAssignments().stream().filter(a -> a.value().equals("admin")).findFirst().orElseThrow();
        assertTrue(api.renameGroup("admin", "staff"));
        assertTrue(players.getTemporaryGroups(uuid.toString()).isEmpty());
        var after = api(players).getUserInfo(uuid).groupAssignments().stream().filter(a -> a.value().equals("staff")).findFirst().orElseThrow();
        assertEquals(before.id(), after.id());
        assertEquals(expiry, after.expiresAtMs());
        assertTrue(api(players).deleteGroup("staff"));
        assertTrue(api(players).getUserInfo(uuid).groupAssignments().isEmpty());
    }

    @Test void interruptedTemporaryMigrationKeepsMembershipDurableAndCanResume() {
        var config = new IConfig() {
            public Path getConfigDirectory() { return directory; }
            public String getModId() { return "paradigm"; }
        };
        var players = new eu.avalanche7.paradigm.data.PlayerDataStore(null, null, config) {
            @Override public boolean removeTemporaryGroup(String player, String group) {
                throw new eu.avalanche7.paradigm.storage.StorageException("Injected player write failure");
            }
        };
        var api = api(players);
        assertTrue(api.assignGroup(uuid, "admin", System.currentTimeMillis() + 600_000, "tester"));
        var id = api.getUserInfo(uuid).groupAssignments().get(0).id();
        assertThrows(eu.avalanche7.paradigm.storage.StorageException.class, () -> api.renameGroup("admin", "staff"));
        var recoveredPlayers = new eu.avalanche7.paradigm.data.PlayerDataStore(null, null, config);
        var recovered = api(recoveredPlayers);
        assertNotNull(recovered.getGroupInfo("admin"));
        assertNull(recovered.getGroupInfo("staff"));
        assertEquals(1, recovered.getUserInfo(uuid).groupAssignments().size());
        assertTrue(recovered.getUserInfo(uuid).groupAssignments().stream().anyMatch(a -> id.equals(a.id())));
        assertTrue(recovered.renameGroup("admin", "staff"));
        var assignments = api(recoveredPlayers).getUserInfo(uuid).groupAssignments();
        assertEquals(1, assignments.size());
        assertEquals(id, assignments.get(0).id());
        assertEquals("staff", assignments.get(0).value());
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
        assertEquals(Boolean.TRUE, api.hasPermission(uuid, "example.member"));
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
        assertTrue(api.setGroupMetadata("unrelated", "suffix", "unrelated suffix"));
        assertTrue(api.assignGroup(uuid, "display"));
        assertTrue(api.assignGroup(uuid, "unrelated"));
        assertTrue(api.setDisplayGroup(uuid, "display", context));

        var meta = api.resolveMeta(uuid);
        assertEquals("display", meta.primaryGroup());
        assertEquals("[display]", meta.prefix());
        assertEquals("parent suffix", meta.suffix());
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
        var meta = api.resolveMeta(uuid);
        assertEquals("depth-32", meta.primaryGroup());
        assertEquals(33, meta.groups().size());
        assertFalse(meta.groups().contains("depth-33"));

        assertTrue(api.addGroupParent("default", "depth-32"));
        assertEquals("depth-33", api.resolveMeta(uuid).primaryGroup());
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
