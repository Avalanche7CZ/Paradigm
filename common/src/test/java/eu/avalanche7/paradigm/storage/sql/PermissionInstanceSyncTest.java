package eu.avalanche7.paradigm.storage.sql;

import static org.junit.jupiter.api.Assertions.*;

import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Proxy;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.slf4j.LoggerFactory;

import eu.avalanche7.paradigm.modules.permissions.PermissionAPI;
import eu.avalanche7.paradigm.modules.permissions.context.PermissionContextSet;
import eu.avalanche7.paradigm.storage.StorageConfig;
import eu.avalanche7.paradigm.storage.StorageException;
import eu.avalanche7.paradigm.storage.StorageTransactionException;
import eu.avalanche7.paradigm.storage.identity.ServerIdentity;
import eu.avalanche7.paradigm.storage.identity.ServerIdentityService;
import eu.avalanche7.paradigm.storage.identity.StorageContext;
import eu.avalanche7.paradigm.storage.model.StoredPermissionGroup;
import eu.avalanche7.paradigm.storage.model.StoredPermissionNode;
import eu.avalanche7.paradigm.storage.model.StoredUserPermissionData;
import eu.avalanche7.paradigm.storage.repository.PermissionRepository;
import eu.avalanche7.paradigm.storage.sqlite.SqliteStorageProvider;
import eu.avalanche7.paradigm.utils.DebugLogger;

class PermissionInstanceSyncTest {
    @TempDir Path directory;
    final UUID user = UUID.randomUUID();
    final PermissionContextSet modern = PermissionContextSet.server("tfg-modern");

    SqlStorageProvider provider(String server) {
        var config = new StorageConfig();
        config.provider = "sqlite";
        config.sqlite.path = directory.resolve("shared.db").toString();
        config.runtimeLibraries.enabled = false;
        config.serverId = server;
        config.networkId = "isolated";
        var identity = new ServerIdentityService(null, config);
        var provider = new SqliteStorageProvider(config, new StorageContext(identity.current()), identity, null, null);
        provider.initialize();
        return provider;
    }

    PermissionAPI api(PermissionRepository repository, String server) {
        var api = new PermissionAPI(LoggerFactory.getLogger(getClass()), new DebugLogger(null), null, null);
        api.setPermissionRepository(repository);
        api.setContextResolver(new eu.avalanche7.paradigm.modules.permissions.context.PermissionContextResolver(
                () -> new ServerIdentity("isolated", server, server)));
        api.initialize();
        return api;
    }

    void seed(PermissionRepository repository) {
        repository.saveGroup(new StoredPermissionGroup("vip", "", "VIP", "", 100, List.of(),
                List.of(new StoredPermissionNode("example.vip", false, null, null))));
    }

    PermissionRepository failing(PermissionRepository repository, AtomicBoolean unavailable) {
        return (PermissionRepository) Proxy.newProxyInstance(getClass().getClassLoader(), new Class<?>[]{PermissionRepository.class}, (proxy, method, args) -> {
            if (unavailable.get()) throw new StorageException("Simulated unavailable connection");
            try { return method.invoke(repository, args); }
            catch (InvocationTargetException failure) { throw failure.getCause(); }
        });
    }

    @Test void contextVipGrantAndRevocationPropagateWithoutReload() {
        try (var gtnh = provider("gtnh"); var tfg = provider("tfg-modern")) {
            seed(gtnh.permissions());
            var a = api(gtnh.permissions(), "gtnh"); var b = api(tfg.permissions(), "tfg-modern");
            assertTrue(a.assignGroup(user, "vip", 0L, "dashboard", modern));
            assertEquals(1, tfg.permissions().getUser(user.toString()).orElseThrow().groups().size());
            assertFalse(b.synchronizeDefinitions());
            assertNull(b.hasPermission(user, "example.vip"));
            assertTrue(b.synchronizePermissions());
            assertEquals(Boolean.TRUE, b.hasPermission(user, "example.vip"));
            assertNull(a.hasPermission(user, "example.vip"));
            long version = b.stateVersion();
            assertFalse(b.synchronizePermissions());
            assertEquals(version, b.stateVersion());
            assertTrue(a.revokeGroup(user, "vip", modern));
            assertTrue(b.synchronizePermissions());
            assertNull(b.hasPermission(user, "example.vip"));
            assertTrue(b.getUserInfo(user).assignments().isEmpty());
        }
    }

    @Test void nodesDenyExpiryDefinitionsAndDeletionPropagate() {
        try (var first = provider("gtnh"); var second = provider("tfg-modern")) {
            seed(first.permissions());
            var a = api(first.permissions(), "gtnh"); var b = api(second.permissions(), "tfg-modern");
            long expiry = System.currentTimeMillis() + 600_000;
            assertTrue(a.addUserPermission(user, "example.temporary", true, modern, expiry));
            assertTrue(a.assignGroup(user, "vip", expiry, "command", modern));
            assertTrue(b.synchronizePermissions());
            assertEquals(Boolean.FALSE, b.hasPermission(user, "example.temporary"));
            assertEquals(Boolean.TRUE, b.hasPermission(user, "example.vip"));
            assertNull(b.hasPermission(user, "example.temporary", PermissionContextSet.server("gtnh")));
            assertTrue(b.getUserInfo(user).assignments().stream().allMatch(assignment -> assignment.expiresAtMs().equals(expiry)));
            assertTrue(a.setGroupMetadata("vip", "prefix", "Updated"));
            assertTrue(a.addGroupPermission("vip", "example.definition", false));
            assertTrue(b.synchronizePermissions());
            assertEquals("Updated", b.getGroupInfo("vip").prefix());
            assertEquals(Boolean.TRUE, b.hasPermission(user, "example.definition"));
            assertTrue(a.removeUserPermission(user, "example.temporary", modern));
            assertTrue(b.synchronizePermissions());
            assertNull(b.hasPermission(user, "example.temporary"));
            assertTrue(first.permissions().deleteUser(user.toString()));
            assertTrue(b.synchronizePermissions());
            assertNull(b.hasPermission(user, "example.vip"));
            assertFalse(b.synchronizePermissions());
        }
    }

    @Test void unavailableReadsKeepStateAndRecoverWithoutAdvancingPastMissedChanges() {
        try (var first = provider("gtnh"); var second = provider("tfg-modern")) {
            seed(first.permissions());
            var unavailable = new AtomicBoolean();
            var a = api(first.permissions(), "gtnh"); var b = api(failing(second.permissions(), unavailable), "tfg-modern");
            a.assignGroup(user, "vip", 0L, "dashboard", modern);
            b.synchronizePermissions();
            long version = b.stateVersion();
            unavailable.set(true);
            a.revokeGroup(user, "vip", modern);
            assertThrows(StorageException.class, b::synchronizePermissions);
            assertEquals(version, b.stateVersion());
            assertEquals(Boolean.TRUE, b.hasPermission(user, "example.vip"));
            unavailable.set(false);
            assertTrue(b.synchronizePermissions());
            assertNull(b.hasPermission(user, "example.vip"));
        }
    }

    @Test void queuedLocalEditsSurviveRemoteChangesAndLaterCallbacks() {
        try (var first = provider("gtnh"); var second = provider("tfg-modern")) {
            seed(first.permissions());
            var a = api(first.permissions(), "gtnh"); var b = api(second.permissions(), "tfg-modern");
            var queue = new ArrayList<Runnable>();
            b.setAsyncPersistenceExecutor((key, task) -> queue.add(task));
            assertTrue(b.addUserPermission(user, "example.local", false));
            assertTrue(a.assignGroup(user, "vip", 0L, "dashboard", modern));
            assertTrue(b.synchronizePermissions());
            assertEquals(Boolean.TRUE, b.hasPermission(user, "example.local"));
            assertEquals(Boolean.TRUE, b.hasPermission(user, "example.vip"));
            assertTrue(a.revokeGroup(user, "vip", modern));
            assertTrue(b.synchronizePermissions());
            queue.forEach(Runnable::run);
            assertTrue(second.permissions().getUser(user.toString()).orElseThrow().groups().isEmpty());
            assertEquals(1, second.permissions().getUser(user.toString()).orElseThrow().permissions().size());
        }
    }

    @Test void knownFailedPendingWriteRetriesAfterConnectionRecovery() {
        try (var first = provider("gtnh"); var second = provider("tfg-modern")) {
            seed(first.permissions());
            var unavailable = new AtomicBoolean();
            var a = api(first.permissions(), "gtnh"); var b = api(failing(second.permissions(), unavailable), "tfg-modern");
            var queue = new ArrayList<Runnable>();
            b.setAsyncPersistenceExecutor((key, task) -> queue.add(task));
            b.addUserPermission(user, "example.local", false);
            unavailable.set(true);
            assertThrows(StorageException.class, queue.get(0)::run);
            a.assignGroup(user, "vip", 0L, "dashboard", modern);
            assertThrows(StorageException.class, b::synchronizePermissions);
            unavailable.set(false);
            assertTrue(b.synchronizePermissions());
            assertEquals(Boolean.TRUE, b.hasPermission(user, "example.local"));
            assertEquals(Boolean.TRUE, b.hasPermission(user, "example.vip"));
        }
    }

    @Test void rollbackDoesNotPublishRevisionAndUnknownCommitCannotBeAcknowledgedByPolling() {
        try (var provider = provider("gtnh")) {
            seed(provider.permissions());
            long before = provider.permissions().changesSince(-1).revision();
            assertThrows(IllegalStateException.class, () -> provider.permissions().changeGlobalGroups(user.toString(),
                    List.of(new PermissionRepository.GroupChange("vip", true)), () -> { throw new IllegalStateException("effect failed"); }));
            assertEquals(before, provider.permissions().changesSince(before).revision());
            assertTrue(provider.permissions().getUser(user.toString()).isEmpty());
            var repository = (PermissionRepository) Proxy.newProxyInstance(getClass().getClassLoader(), new Class<?>[]{PermissionRepository.class}, (proxy, method, args) -> {
                Object result;
                try { result = method.invoke(provider.permissions(), args); }
                catch (InvocationTargetException failure) { throw failure.getCause(); }
                if (method.getName().equals("applyUserChanges")) throw new StorageTransactionException("Unknown commit", null, StorageTransactionException.Outcome.UNKNOWN);
                return result;
            });
            var api = api(repository, "gtnh");
            assertThrows(StorageTransactionException.class, () -> api.addUserPermission(user, "example.unknown", false));
            assertFalse(api.synchronizePermissions());
            assertThrows(StorageTransactionException.class, () -> api.addUserPermission(user, "example.blocked", false));
        }
    }

    @Test void revisionSnapshotFetchesOnlyChangedUsersAndRemembersDeletions() {
        try (var provider = provider("gtnh")) {
            seed(provider.permissions());
            var other = UUID.randomUUID();
            provider.permissions().saveUser(new StoredUserPermissionData(other.toString(), "Other", List.of(), List.of()));
            long revision = provider.permissions().changesSince(-1).revision();
            provider.permissions().addUserPermission(user.toString(), new StoredPermissionNode("example.new", false, null, null));
            var changes = provider.permissions().changesSince(revision);
            assertNull(changes.definitions());
            assertEquals(List.of(user.toString()), changes.users().stream().map(StoredUserPermissionData::uuid).toList());
            provider.permissions().deleteUser(user.toString());
            var deleted = provider.permissions().changesSince(changes.revision());
            assertEquals(1, deleted.users().size());
            assertTrue(deleted.users().get(0).permissions().isEmpty());
            assertTrue(provider.permissions().changesSince(deleted.revision()).users().isEmpty());
        }
    }
    @Test void simultaneousFirstUserWritesAndSubsequentWritesRemainVisible() throws Exception {
        try (var first = provider("gtnh"); var second = provider("tfg-modern")) {
            seed(first.permissions());
            var a = api(first.permissions(), "gtnh"); var b = api(second.permissions(), "tfg-modern");
            var pool = java.util.concurrent.Executors.newFixedThreadPool(2);
            try {
                for (int round = 0; round < 2; round++) {
                    var start = new java.util.concurrent.CountDownLatch(1);
                    String suffix = Integer.toString(round);
                    var left = pool.submit(() -> { start.await(); return a.addUserPermission(user, "example.left" + suffix, false); });
                    var right = pool.submit(() -> { start.await(); return b.addUserPermission(user, "example.right" + suffix, false); });
                    start.countDown();
                    assertTrue(left.get(20, java.util.concurrent.TimeUnit.SECONDS));
                    assertTrue(right.get(20, java.util.concurrent.TimeUnit.SECONDS));
                    a.synchronizePermissions(); b.synchronizePermissions();
                    assertEquals((round + 1) * 2, first.permissions().getUser(user.toString()).orElseThrow().permissions().size());
                    assertEquals(Boolean.TRUE, a.hasPermission(user, "example.right" + suffix));
                    assertEquals(Boolean.TRUE, b.hasPermission(user, "example.left" + suffix));
                }
            } finally { pool.shutdownNow(); }
        }
    }

    @Test void sqlTemporaryGroupsIgnoreObsoletePlayerdataMirrorsAfterRevocationAndRestart() {
        try (var first = provider("gtnh"); var second = provider("tfg-modern")) {
            seed(first.permissions());
            var config = new eu.avalanche7.paradigm.platform.Interfaces.IConfig() {
                @Override public Path getConfigDirectory() { return directory; }
                @Override public String getModId() { return "paradigm"; }
            };
            var playerData = new eu.avalanche7.paradigm.data.PlayerDataStore(LoggerFactory.getLogger(getClass()), new DebugLogger(null), config);
            long expiry = System.currentTimeMillis() + 600_000;
            playerData.setTemporaryGroup(user.toString(), "vip", expiry, 1L, "old-mirror");
            var a = api(first.permissions(), "gtnh");
            var b = new PermissionAPI(LoggerFactory.getLogger(getClass()), new DebugLogger(null), null, playerData);
            b.setPermissionRepository(second.permissions()); b.initialize();
            assertNull(b.hasPermission(user, "example.vip"));
            assertTrue(a.assignGroup(user, "vip", expiry, "dashboard", PermissionContextSet.empty()));
            assertTrue(b.synchronizePermissions());
            assertEquals(Boolean.TRUE, b.hasPermission(user, "example.vip"));
            assertEquals(1, b.getUserGroups(user).temporaryGroups().size());
            assertTrue(a.revokeGroup(user, "vip"));
            assertTrue(b.synchronizePermissions());
            assertNull(b.hasPermission(user, "example.vip"));
            b.reload();
            assertNull(b.hasPermission(user, "example.vip"));
            assertTrue(b.assignGroup(user, "vip", expiry, "command", PermissionContextSet.empty()));
            assertTrue(b.assignGroup(user, "vip", expiry + 600_000, "renewal", PermissionContextSet.empty()));
            assertEquals(1, b.getUserGroups(user).temporaryGroups().size());
            assertEquals(expiry + 600_000, b.getUserGroups(user).temporaryGroups().get(0).expiresAtMs());
            assertTrue(b.assignGroup(user, "vip"));
            assertTrue(b.getUserGroups(user).temporaryGroups().isEmpty());
            assertTrue(b.revokeGroup(user, "vip"));
            assertTrue(second.permissions().getUser(user.toString()).orElseThrow().groups().isEmpty());
        }
    }

    @Test void dashboardMutationServiceAndCommandHandlerUseTheSameCommittedRevision() {
        try (var first = provider("gtnh"); var second = provider("tfg-modern")) {
            seed(first.permissions());
            var config = new eu.avalanche7.paradigm.platform.Interfaces.IConfig() {
                @Override public Path getConfigDirectory() { return directory; }
                @Override public String getModId() { return "paradigm"; }
            };
            var storage = new eu.avalanche7.paradigm.storage.StorageService(null, null, config, null, null, null, null) {
                @Override public boolean isSqlActive() { return true; }
                @Override public PermissionRepository permissions() { return first.permissions(); }
                @Override public StorageContext context() { return new StorageContext(new ServerIdentity("isolated", "gtnh", "GTNH")); }
                @Override public void runStorageAsync(String key, Runnable task) { task.run(); }
            };
            var handler = new eu.avalanche7.paradigm.modules.permissions.PermissionsHandler(LoggerFactory.getLogger(getClass()), null, new DebugLogger(null), null, null, storage) {
                @Override public boolean isInternalPermissionsEnabled() { return true; }
            };
            handler.initialize();
            var services = new eu.avalanche7.paradigm.core.Services(null, null, null, null, null, null, null, null, null, null, null,
                    null, handler, null, null, null, null, null, storage, null, null);
            try {
                var admin = new eu.avalanche7.paradigm.modules.permissions.PermissionAdminService(services, null);
                var request = new eu.avalanche7.paradigm.modules.permissions.PermissionMutationRequest();
                request.action = "user_group_add"; request.user = user.toString(); request.group = "vip";
                request.scope = "custom"; request.contexts = java.util.Map.of("server", "tfg-modern"); request.permanent = true;
                var reader = api(second.permissions(), "tfg-modern");
                assertTrue(admin.mutate(new eu.avalanche7.paradigm.modules.dashboard.auth.DashboardPrincipal("console", "Console", true), request).applied());
                assertTrue(reader.synchronizePermissions());
                assertEquals(Boolean.TRUE, reader.hasPermission(user, "example.vip"));
                assertTrue(handler.revokePlayerGroup(user, "vip", modern));
                assertTrue(reader.synchronizePermissions());
                assertNull(reader.hasPermission(user, "example.vip"));
            } finally { services.shutdown(); storage.close(); }
        }
    }

}
