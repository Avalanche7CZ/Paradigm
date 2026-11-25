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

import eu.avalanche7.paradigm.modules.dashboard.auth.DashboardPrincipal;
import eu.avalanche7.paradigm.modules.permissions.ParadigmPermissions;
import eu.avalanche7.paradigm.modules.permissions.PermissionAPI;
import eu.avalanche7.paradigm.modules.permissions.PermissionAdminService;
import eu.avalanche7.paradigm.modules.permissions.PermissionMutationRequest;
import eu.avalanche7.paradigm.modules.permissions.PermissionsHandler;
import eu.avalanche7.paradigm.modules.permissions.context.PermissionContextSet;
import eu.avalanche7.paradigm.platform.Interfaces.IConfig;
import eu.avalanche7.paradigm.storage.StorageConfig;
import eu.avalanche7.paradigm.storage.StorageException;
import eu.avalanche7.paradigm.storage.StorageService;
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

    @Test void paidRankWaitsForPersistentServerScopedAssignment() {
        try (var writer = provider("gtnh"); var reader = provider("tfg-modern")) {
            seed(writer.permissions());
            var a = api(writer.permissions(), "gtnh");
            a.setAsyncPersistenceExecutor((label, task) -> { throw new AssertionError("Paid assignment must not be queued"); });
            long expiry = System.currentTimeMillis() + 86_400_000L;
            assertTrue(a.assignGroupPersisted(user, "vip", expiry, "ArcturusTokens:paid-test", modern));
            var b = api(reader.permissions(), "tfg-modern");
            assertEquals(Boolean.TRUE, b.hasPermission(user, "example.vip"));
            assertNull(a.hasPermission(user, "example.vip"));
            assertFalse(a.assignGroupPersisted(user, "vip", expiry, "ArcturusTokens:paid-test", modern));
            var assignment = reader.permissions().getUser(user.toString()).orElseThrow().groups().get(0);
            assertEquals(expiry, assignment.expiresAtMs());
            assertEquals(modern, assignment.contextSet());
        }
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
            var explanation = b.explainPermission(user, "example.vip");
            assertEquals(Boolean.TRUE, explanation.allowed());
            assertEquals("vip", explanation.sourceName());
            unavailable.set(false);
            assertTrue(b.synchronizePermissions());
            assertNull(b.hasPermission(user, "example.vip"));
        }
    }

    @Test void initialSqlOutageFailsClosedAndExplainsTheUnavailableState() {
        try (var provider = provider("tfg-modern")) {
            var unavailable = new AtomicBoolean(true);
            var reader = api(failing(provider.permissions(), unavailable), "tfg-modern");
            String node = "paradigm.msg";
            assertEquals(Boolean.FALSE, reader.hasPermission(user, node));
            assertEquals(new PermissionAPI.PermissionExplain(false, "unavailable", "", "", List.of()),
                    reader.explainPermission(user, node));
            assertThrows(StorageException.class, () -> reader.hasStoredPermission(user, node));

            unavailable.set(false);
            var writer = api(provider.permissions(), "tfg-modern");
            assertEquals(Boolean.TRUE, writer.hasPermission(user, node));
            assertEquals(Boolean.TRUE, reader.hasStoredPermission(user, node));
            assertEquals(Boolean.FALSE, reader.hasPermission(user, node));

            reader.reload();
            assertEquals(Boolean.TRUE, reader.hasPermission(user, node));
            assertEquals(new PermissionAPI.PermissionExplain(true, "group", "default", node, List.of("default")),
                    reader.explainPermission(user, node));
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

    @Test void backgroundReadsAndQueuedWritesDoNotHoldTheRuntimeLock() throws Exception {
        try (var provider = provider("gtnh")) {
            var blockedMethod = new java.util.concurrent.atomic.AtomicReference<String>();
            var entered = new java.util.concurrent.Semaphore(0);
            var release = new java.util.concurrent.Semaphore(0);
            var repository = (PermissionRepository) Proxy.newProxyInstance(getClass().getClassLoader(), new Class<?>[]{PermissionRepository.class}, (proxy, method, args) -> {
                String blocked = blockedMethod.get();
                if (method.getName().equals(blocked) && blockedMethod.compareAndSet(blocked, null)) {
                    entered.release();
                    if (!release.tryAcquire(10, java.util.concurrent.TimeUnit.SECONDS)) throw new StorageException("Fixture timed out");
                }
                try { return method.invoke(provider.permissions(), args); }
                catch (InvocationTargetException failure) { throw failure.getCause(); }
            });
            var api = api(repository, "gtnh");
            var queue = new ArrayList<Runnable>();
            api.setAsyncPersistenceExecutor((key, work) -> queue.add(work));
            var pool = java.util.concurrent.Executors.newFixedThreadPool(2);
            try {
                provider.permissions().addUserPermission(user.toString(), new StoredPermissionNode("example.remote", false, null, null));
                blockedMethod.set("changesSince");
                var syncing = pool.submit(api::synchronizePermissions);
                assertTrue(entered.tryAcquire(5, java.util.concurrent.TimeUnit.SECONDS));
                assertEquals(Boolean.TRUE, pool.submit(() -> api.hasPermission(user, "paradigm.msg")).get(3, java.util.concurrent.TimeUnit.SECONDS));
                assertTrue(pool.submit(() -> api.addUserPermission(user, "example.first", false)).get(3, java.util.concurrent.TimeUnit.SECONDS));
                release.release();
                syncing.get(5, java.util.concurrent.TimeUnit.SECONDS);

                blockedMethod.set("applyUserChanges");
                var writing = pool.submit(queue.get(0));
                assertTrue(entered.tryAcquire(5, java.util.concurrent.TimeUnit.SECONDS));
                assertEquals(Boolean.TRUE, pool.submit(() -> api.hasPermission(user, "example.first")).get(3, java.util.concurrent.TimeUnit.SECONDS));
                assertTrue(pool.submit(() -> api.addUserPermission(user, "example.second", false)).get(3, java.util.concurrent.TimeUnit.SECONDS));
                release.release();
                writing.get(5, java.util.concurrent.TimeUnit.SECONDS);
                assertEquals(Boolean.TRUE, api.hasStoredPermission(user, "example.first"));
                assertNull(api.hasStoredPermission(user, "example.second"));
                queue.get(1).run();
                assertEquals(Boolean.TRUE, api.hasStoredPermission(user, "example.second"));
                assertTrue(api.synchronizePermissions());
                assertEquals(Boolean.TRUE, api.hasPermission(user, "example.remote"));
                assertEquals(Boolean.TRUE, api.hasPermission(user, "example.second"));
            } finally {
                release.release();
                pool.shutdownNow();
            }
        }
    }

    @Test void reloadInvalidatesQueuedCallbacksEvenWhenTheRepositoryIsUnchanged() {
        try (var provider = provider("gtnh")) {
            var api = api(provider.permissions(), "gtnh");
            var queue = new ArrayList<Runnable>();
            api.setAsyncPersistenceExecutor((key, write) -> queue.add(write));
            assertTrue(api.addUserPermission(user, "example.discarded", false));
            api.reload();
            assertTrue(api.addUserPermission(user, "example.current", false));
            queue.get(0).run();
            assertNull(api.hasStoredPermission(user, "example.current"));
            queue.get(1).run();
            assertEquals(Boolean.TRUE, api.hasStoredPermission(user, "example.current"));
            assertNull(api.hasStoredPermission(user, "example.discarded"));
        }
    }

    @Test void rejectedQueuedSubmissionRetainsRuntimeAcceptanceUntilTheNextFlush() {
        try (var provider = provider("gtnh")) {
            var api = api(provider.permissions(), "gtnh");
            api.setAsyncPersistenceExecutor((key, write) -> { throw new java.util.concurrent.RejectedExecutionException(); });
            assertTrue(api.addUserPermission(user, "example.pending", false));
            assertEquals(Boolean.TRUE, api.hasPermission(user, "example.pending"));
            assertNull(api.hasStoredPermission(user, "example.pending"));
            api.synchronizePermissions();
            assertEquals(Boolean.TRUE, api.hasStoredPermission(user, "example.pending"));
        }
    }

    @Test void queuedCommitWithLostAcknowledgementRequiresExplicitRecovery() {
        try (var provider = provider("gtnh")) {
            var actual = provider.permissions();
            var loseAcknowledgement = new AtomicBoolean(true);
            var repository = (PermissionRepository) Proxy.newProxyInstance(getClass().getClassLoader(), new Class<?>[]{PermissionRepository.class}, (proxy, method, args) -> {
                try {
                    Object result = method.invoke(actual, args);
                    if (method.getName().equals("applyUserChanges") && loseAcknowledgement.getAndSet(false)) {
                        throw new StorageTransactionException("Lost acknowledgement", null, StorageTransactionException.Outcome.UNKNOWN);
                    }
                    return result;
                } catch (InvocationTargetException failure) { throw failure.getCause(); }
            });
            var api = api(repository, "gtnh");
            var queue = new ArrayList<Runnable>();
            api.setAsyncPersistenceExecutor((key, write) -> queue.add(write));
            assertTrue(api.addUserPermission(user, "example.committed", false));
            assertThrows(StorageTransactionException.class, queue.get(0)::run);
            long revision = actual.changesSince(-1).revision();
            assertFalse(api.synchronizePermissions());
            assertThrows(StorageTransactionException.class, () -> api.addUserPermission(user, "example.rejected", false));
            assertNull(api.hasPermission(user, "example.rejected"));
            assertEquals(revision, actual.changesSince(-1).revision());
            api.reload();
            assertEquals(Boolean.TRUE, api.hasPermission(user, "example.committed"));
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
            assertNull(api.hasPermission(user, "example.blocked"));
        }
    }

    @Test void transactionalRevocationRemovesReloadedGlobalAssignmentAndCannotBeRequeued() {
        try (var provider = provider("gtnh")) {
            seed(provider.permissions());
            var api = api(provider.permissions(), "gtnh");
            assertTrue(api.changeGlobalGroups(user, List.of(new PermissionRepository.GroupChange("vip", true)), () -> {}));
            assertTrue(api.assignGroup(user, "vip", 0L, "dashboard", modern));
            api.reload();
            assertEquals(Boolean.TRUE, api.hasPermission(user, "example.vip"));
            assertTrue(api.changeGlobalGroups(user, List.of(new PermissionRepository.GroupChange("vip", false)), () -> {}));
            assertNull(api.hasPermission(user, "example.vip"));
            assertEquals(Boolean.TRUE, api.hasPermission(user, "example.vip", modern));

            var queue = new ArrayList<Runnable>();
            api.setAsyncPersistenceExecutor((key, task) -> queue.add(task));
            assertTrue(api.addUserPermission(user, "example.unrelated", false));
            queue.forEach(Runnable::run);
            assertNull(api.hasStoredPermission(user, "example.vip"));
            assertEquals(Boolean.TRUE, api.hasStoredPermission(user, "example.vip", modern));
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
            assertTrue(a.addUserPermission(user, "example.direct", false));
            var b = new PermissionAPI(LoggerFactory.getLogger(getClass()), new DebugLogger(null), null, playerData);
            b.setPermissionRepository(second.permissions()); b.initialize();
            assertNull(b.hasPermission(user, "example.vip"));
            assertNull(b.hasStoredPermission(user, "example.vip"));
            assertTrue(a.assignGroup(user, "vip", expiry, "dashboard", PermissionContextSet.empty()));
            assertEquals(Boolean.TRUE, b.hasStoredPermission(user, "example.vip"));
            assertNull(b.hasPermission(user, "example.vip"));
            assertTrue(b.synchronizePermissions());
            assertEquals(Boolean.TRUE, b.hasPermission(user, "example.vip"));
            assertEquals(1, b.getUserGroups(user).temporaryGroups().size());
            assertTrue(a.revokeGroup(user, "vip"));
            assertNull(b.hasStoredPermission(user, "example.vip"));
            assertEquals(Boolean.TRUE, b.hasPermission(user, "example.vip"));
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

    @Test void dashboardLoginUsesAuthoritativePermissionsAndPropagatesReadFailures() {
        try (var writer = provider("gtnh"); var host = provider("tfg-modern")) {
            var permission = ParadigmPermissions.DASHBOARD_ACCESS;
            writer.permissions().saveUser(new StoredUserPermissionData(user.toString(), "Player", List.of(),
                    List.of(new StoredPermissionNode(permission.node(), false, null, "tfg-modern"))));
            var unavailable = new AtomicBoolean();
            var repository = failing(host.permissions(), unavailable);
            var config = new IConfig() {
                @Override public Path getConfigDirectory() { return directory; }
                @Override public String getModId() { return "paradigm"; }
            };
            try (var storage = new StorageService(null, null, config, null, null, null, null) {
                @Override public boolean isMysqlActive() { return true; }
                @Override public boolean isSqlActive() { return true; }
                @Override public PermissionRepository permissions() { return repository; }
                @Override public StorageContext context() { return new StorageContext(new ServerIdentity("isolated", "tfg-modern", "Host")); }
            }) {
                var reader = new PermissionsHandler(LoggerFactory.getLogger(getClass()), null, new DebugLogger(null), null, null, storage) {
                    @Override public boolean isInternalPermissionsEnabled() { return true; }
                };
                reader.initialize();
                assertTrue(reader.hasPermission(user.toString(), permission));
                assertTrue(reader.hasDashboardLoginPermission(user.toString(), permission));

                writer.permissions().saveUser(new StoredUserPermissionData(user.toString(), "Player", List.of(),
                        List.of(new StoredPermissionNode(permission.node(), true, null, "tfg-modern"))));
                assertTrue(reader.hasPermission(user.toString(), permission));
                assertFalse(reader.hasDashboardLoginPermission(user.toString(), permission));

                unavailable.set(true);
                assertThrows(StorageException.class, () -> reader.hasDashboardLoginPermission(user.toString(), permission));
            }
        }
    }

    @Test void dashboardWaitsForCommitAndShutdownDrainsAcceptedUserWrites() throws Exception {
        try (var provider = provider("tfg-modern")) {
            var entered = new java.util.concurrent.Semaphore(0);
            var proceed = new java.util.concurrent.Semaphore(0);
            var block = new AtomicBoolean(true);
            var reject = new AtomicBoolean();
            var rejectSubmission = new AtomicBoolean();
            var repository = (PermissionRepository) Proxy.newProxyInstance(getClass().getClassLoader(), new Class<?>[]{PermissionRepository.class}, (proxy, method, args) -> {
                if (method.getName().equals("applyUserChanges")) {
                    if (reject.get()) throw new StorageException("Known write failure");
                    if (block.get()) {
                        entered.release();
                        if (!proceed.tryAcquire(10, java.util.concurrent.TimeUnit.SECONDS)) throw new StorageException("Fixture timed out");
                    }
                }
                try { return method.invoke(provider.permissions(), args); }
                catch (InvocationTargetException failure) { throw failure.getCause(); }
            });
            var config = new IConfig() {
                @Override public Path getConfigDirectory() { return directory; }
                @Override public String getModId() { return "paradigm"; }
            };
            try (var storage = new StorageService(null, null, config, null, null, null, null) {
                @Override public boolean isSqlActive() { return true; }
                @Override public PermissionRepository permissions() { return repository; }
                @Override public <T> java.util.concurrent.CompletableFuture<T> submitAcknowledged(java.util.function.Supplier<T> work) {
                    if (rejectSubmission.get()) return java.util.concurrent.CompletableFuture.failedFuture(new StorageException("Storage is closing."));
                    return super.submitAcknowledged(work);
                }
            }) {
                var handler = new PermissionsHandler(LoggerFactory.getLogger(getClass()), null, new DebugLogger(null), null, null, storage) {
                    @Override public boolean isInternalPermissionsEnabled() { return true; }
                };
                handler.initialize();
                var services = new eu.avalanche7.paradigm.core.Services(null, null, null, null, null, null, null, null, null, null, null,
                        null, handler, null, null, null, null, null, storage, null, null);
                var admin = new PermissionAdminService(services, null);
                var console = new DashboardPrincipal(null, "Console", true);
                var pool = java.util.concurrent.Executors.newSingleThreadExecutor();
                try {
                    var dashboardWrite = admin.mutateAsync(console, userGrant("example.acknowledged"), pool);
                    assertTrue(entered.tryAcquire(5, java.util.concurrent.TimeUnit.SECONDS));
                    assertFalse(dashboardWrite.isDone());
                    assertTrue(provider.permissions().getUser(user.toString()).isEmpty());
                    proceed.release();
                    assertEquals("ok", dashboardWrite.get(5, java.util.concurrent.TimeUnit.SECONDS).code());

                    reject.set(true);
                    var failure = admin.mutateAsync(console, userGrant("example.rejected"), pool).get(5, java.util.concurrent.TimeUnit.SECONDS);
                    assertFalse(failure.applied());
                    assertEquals("persistence_failed", failure.code());
                    assertNull(handler.queryDefinedPermission(user, "example.rejected"));
                    reject.set(false);

                    rejectSubmission.set(true);
                    var rejected = admin.mutateTrusted(console, userGrant("example.not_queued"));
                    assertFalse(rejected.applied());
                    assertEquals("persistence_failed", rejected.code());
                    assertNull(handler.queryDefinedPermission(user, "example.not_queued"));
                    rejectSubmission.set(false);

                    var pending = admin.mutateTrusted(console, userGrant("example.shutdown"));
                    assertTrue(pending.applied());
                    assertEquals("persistence_pending", pending.code());
                    assertTrue(entered.tryAcquire(5, java.util.concurrent.TimeUnit.SECONDS));
                    assertTrue(provider.permissions().getUser(user.toString()).orElseThrow().permissions().stream()
                            .noneMatch(node -> "example.shutdown".equals(node.permission())));
                    var closeStarted = new java.util.concurrent.CountDownLatch(1);
                    var closing = pool.submit(() -> {
                        closeStarted.countDown();
                        storage.close();
                    });
                    assertTrue(closeStarted.await(5, java.util.concurrent.TimeUnit.SECONDS));
                    proceed.release();
                    closing.get(5, java.util.concurrent.TimeUnit.SECONDS);
                    assertTrue(provider.permissions().getUser(user.toString()).orElseThrow().permissions().stream()
                            .anyMatch(node -> "example.shutdown".equals(node.permission())));
                    assertTrue(provider.permissions().getUser(user.toString()).orElseThrow().permissions().stream()
                            .noneMatch(node -> "example.not_queued".equals(node.permission())));

                    var afterClose = admin.mutateAsync(console, userGrant("example.closed"), pool).get(5, java.util.concurrent.TimeUnit.SECONDS);
                    assertEquals("persistence_failed", afterClose.code());
                    assertNull(handler.queryDefinedPermission(user, "example.closed"));
                } finally {
                    proceed.release(2);
                    pool.shutdownNow();
                }
            }
        }
    }

    private PermissionMutationRequest userGrant(String node) {
        var request = new PermissionMutationRequest();
        request.action = "user_permission_add";
        request.user = user.toString();
        request.permission = node;
        return request;
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
                storage.submitAcknowledged(() -> null).join();
                assertTrue(reader.synchronizePermissions());
                assertNull(reader.hasPermission(user, "example.vip"));
            } finally { services.shutdown(); storage.close(); }
        }
    }

}
