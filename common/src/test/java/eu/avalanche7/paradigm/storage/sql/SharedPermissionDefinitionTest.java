package eu.avalanche7.paradigm.storage.sql;

import static org.junit.jupiter.api.Assertions.*;

import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.slf4j.LoggerFactory;

import eu.avalanche7.paradigm.modules.permissions.PermissionAPI;
import eu.avalanche7.paradigm.modules.permissions.context.PermissionContextSet;
import eu.avalanche7.paradigm.storage.StorageConfig;
import eu.avalanche7.paradigm.storage.StorageConflictException;
import eu.avalanche7.paradigm.storage.StorageException;
import eu.avalanche7.paradigm.storage.StorageTransactionException;
import eu.avalanche7.paradigm.storage.identity.ServerIdentityService;
import eu.avalanche7.paradigm.storage.identity.StorageContext;
import eu.avalanche7.paradigm.storage.model.StoredPermissionGroup;
import eu.avalanche7.paradigm.storage.model.StoredPermissionNode;
import eu.avalanche7.paradigm.storage.model.StoredPermissionTrack;
import eu.avalanche7.paradigm.storage.mysql.MysqlDialect;
import eu.avalanche7.paradigm.storage.repository.PermissionRepository;
import eu.avalanche7.paradigm.storage.sqlite.SqliteDialect;
import eu.avalanche7.paradigm.storage.sqlite.SqliteStorageProvider;
import eu.avalanche7.paradigm.utils.DebugLogger;

class SharedPermissionDefinitionTest {
    @TempDir Path directory;

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

    Connection connection() throws Exception { return DriverManager.getConnection("jdbc:sqlite:" + directory.resolve("shared.db")); }

    PermissionAPI api(SqlStorageProvider provider) { return api(provider.permissions()); }
    PermissionAPI api(PermissionRepository repository) {
        var api = new PermissionAPI(LoggerFactory.getLogger(getClass()), new DebugLogger(null), null, null);
        api.setPermissionRepository(repository);
        api.initialize();
        return api;
    }

    void seed(PermissionRepository repository) {
        for (String name : List.of("one", "two", "three"))
            repository.saveGroup(new StoredPermissionGroup(name, "", "", "", 0, List.of(), List.of()));
        repository.saveTrack(new StoredPermissionTrack("ranks", List.of("one", "two", "three")));
    }

    @Test void builtInDeletionAndEmptyStorageDoNotBootstrapAgain() {
        try (var p = provider("first")) {
            var a = api(p);
            assertTrue(a.deleteGroup("admin")); assertTrue(a.deleteGroup("default"));
            assertTrue(api(p).listGroups().isEmpty());
        }
    }

    @Test void renameIsSharedAndPreservesOriginalAssignmentIdsAndDefaultRole() {
        try (var first = provider("first"); var second = provider("second")) {
            var a = api(first); var b = api(second);
            UUID user = UUID.randomUUID();
            a.assignGroup(user, "default");
            a.setDisplayGroup(user, "default", PermissionContextSet.empty());
            a.addGroupPermission("default", "example.inherited", false);
            a.createGroup("child"); a.addGroupParent("child", "default");
            a.createTrack("ranks"); a.appendTrackGroup("ranks", "default");
            a.reload(); b.reload();
            var ids = a.getGroupInfo("default").assignments().stream().map(eu.avalanche7.paradigm.modules.permissions.PermissionAssignment::id).toList();
            var membershipId = first.permissions().getUser(user.toString()).orElseThrow().groups().get(0).assignmentId();
            assertTrue(a.renameGroup("default", "members"));
            assertTrue(b.synchronizeDefinitions());
            assertNull(b.getGroupInfo("default"));
            assertEquals(ids, b.getGroupInfo("members").assignments().stream().map(eu.avalanche7.paradigm.modules.permissions.PermissionAssignment::id).toList());
            assertEquals(List.of("members"), b.getTrack("ranks").groups());
            assertEquals(List.of("members"), b.getGroupInfo("child").inherits());
            assertEquals(Boolean.TRUE, b.hasPermission(UUID.randomUUID(), "example.inherited"));
            assertTrue(b.synchronizePermissions());
            assertEquals("members", b.displayGroupPreference(user));
            assertTrue(b.getUserInfo(user).groupAssignments().stream().anyMatch(row -> membershipId.equals(row.id()) && row.value().equals("members")));
            assertFalse(a.renameGroup("members", "admin"));
            assertTrue(a.deleteGroup("members"));
            assertNull(api(first).getGroupInfo("members"));
        }
    }

    @Test void renameRejectsStaleSourceAndSerializesCompetingTargets() throws Exception {
        try (var first = provider("first"); var second = provider("second")) {
            var a = api(first); var b = api(second);
            assertTrue(a.setGroupMetadata("admin", "prefix", "[Changed]"));
            assertThrows(StorageConflictException.class, () -> b.renameGroup("admin", "stale"));
            assertTrue(second.permissions().getGroup("admin").isPresent());
            assertTrue(second.permissions().getGroup("stale").isEmpty());
            b.reload();
            var pool = Executors.newFixedThreadPool(2);
            try {
                var start = new CountDownLatch(1);
                var one = pool.submit(() -> { start.await(); try { return a.renameGroup("admin", "staff"); } catch (StorageConflictException rejected) { return false; } });
                var two = pool.submit(() -> { start.await(); try { return b.renameGroup("default", "staff"); } catch (StorageConflictException rejected) { return false; } });
                start.countDown();
                assertNotEquals(one.get(5, TimeUnit.SECONDS), two.get(5, TimeUnit.SECONDS));
                assertTrue(first.permissions().getGroup("staff").isPresent());
                assertEquals(2, first.permissions().listGroups().size());
            } finally { pool.shutdownNow(); }
        }
    }

    @Test void failedRenameRollsBackReferencesSettingsAndVersions() throws Exception {
        try (var provider = provider("first")) {
            var a = api(provider);
            var user = UUID.randomUUID();
            a.assignGroup(user, "default");
            a.createTrack("ranks"); a.appendTrackGroup("ranks", "default");
            var before = provider.permissions().definitions();
            var membership = provider.permissions().getUser(user.toString());
            try (var connection = connection(); var statement = connection.createStatement()) {
                boolean maria = connection.getMetaData().getDatabaseProductName().toLowerCase().contains("mariadb")
                        || connection.getMetaData().getDatabaseProductName().toLowerCase().contains("mysql");
                statement.execute(maria
                        ? "CREATE TRIGGER reject_group BEFORE UPDATE ON permission_settings FOR EACH ROW BEGIN IF NEW.default_group = 'blocked' THEN SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT = 'injected rename failure'; END IF; END"
                        : "CREATE TRIGGER reject_group BEFORE UPDATE ON permission_settings WHEN NEW.default_group = 'blocked' BEGIN SELECT RAISE(ABORT, 'injected rename failure'); END");
                try {
                    assertThrows(StorageException.class, () -> a.renameGroup("default", "blocked"));
                    assertEquals(before, provider.permissions().definitions());
                    assertEquals(membership, provider.permissions().getUser(user.toString()));
                    assertNotNull(a.getGroupInfo("default"));
                    assertNull(a.getGroupInfo("blocked"));
                } finally { statement.execute("DROP TRIGGER reject_group"); }
            }
            assertTrue(a.renameGroup("default", "members"));
        }
    }

    @Test void settingsOnlyDefaultChangeSynchronizesWithoutChangingGroups() {
        try (var first = provider("first"); var second = provider("second")) {
            var a = api(first); var b = api(second);
            a.addGroupPermission("admin", "example.implicit", false);
            b.synchronizePermissions();
            first.permissions().setDefaultGroup("admin");
            assertTrue(b.synchronizePermissions());
            assertEquals(Boolean.TRUE, b.hasPermission(UUID.randomUUID(), "example.implicit"));
        }
    }

    @Test void remotePrefixRemainsStaleUntilDefinitionSyncThenUsesFreshWriteBaseline() {
        try (var first = provider("first"); var second = provider("second")) {
            seed(first.permissions());
            var a = api(first); var b = api(second);
            long before = b.stateVersion();
            assertTrue(a.setGroupMetadata("one", "prefix", "&a[Changed] "));
            assertEquals("", b.getGroupInfo("one").prefix());
            assertTrue(b.synchronizeDefinitions());
            assertEquals("&a[Changed] ", b.getGroupInfo("one").prefix());
            assertTrue(b.stateVersion() > before);
            before = b.stateVersion();
            assertFalse(b.synchronizeDefinitions());
            assertEquals(before, b.stateVersion());
            assertTrue(b.setGroupMetadata("one", "suffix", "remote"));
            assertEquals("&a[Changed] ", first.permissions().getGroup("one").orElseThrow().prefix());
        }
    }

    @Test void definitionSyncPreservesQueuedUserEditsAndDoesNotPersistWhileReading() {
        try (var first = provider("first"); var second = provider("second")) {
            seed(first.permissions());
            var a = api(first); var b = api(second);
            var queue = new java.util.ArrayList<Runnable>();
            b.setAsyncPersistenceExecutor((key, task) -> queue.add(task));
            var user = UUID.randomUUID();
            assertTrue(b.addUserPermission(user, "example.queued", false));
            assertTrue(a.setGroupMetadata("one", "prefix", "remote"));
            assertTrue(b.synchronizeDefinitions());
            assertTrue(second.permissions().getUser(user.toString()).isEmpty());
            queue.forEach(Runnable::run);
            assertTrue(second.permissions().getUser(user.toString()).orElseThrow().permissions().stream()
                    .anyMatch(node -> node.permission().equals("example.queued")));
            assertEquals("remote", second.permissions().getGroup("one").orElseThrow().prefix());
        }
    }

    @Test void failedDefinitionReadKeepsCacheAndCanRetry() {
        try (var provider = provider("first")) {
            seed(provider.permissions());
            var actual = provider.permissions();
            var failing = new java.util.concurrent.atomic.AtomicBoolean();
            var repository = (PermissionRepository) java.lang.reflect.Proxy.newProxyInstance(getClass().getClassLoader(), new Class<?>[]{PermissionRepository.class}, (proxy, method, args) -> {
                if (method.getName().equals("definitions") && failing.get()) throw new StorageException("injected read failure");
                try { return method.invoke(actual, args); }
                catch (java.lang.reflect.InvocationTargetException failure) { throw failure.getCause(); }
            });
            var reader = api(repository); var writer = api(actual);
            assertTrue(writer.setGroupMetadata("one", "prefix", "remote"));
            long before = reader.stateVersion();
            failing.set(true);
            assertThrows(StorageException.class, reader::synchronizeDefinitions);
            assertEquals("", reader.getGroupInfo("one").prefix());
            assertEquals(before, reader.stateVersion());
            failing.set(false);
            assertTrue(reader.synchronizeDefinitions());
            assertEquals("remote", reader.getGroupInfo("one").prefix());
        }
    }

    @Test void definitionSyncDoesNotClearConflictRecovery() {
        try (var first = provider("first"); var second = provider("second")) {
            seed(first.permissions());
            var a = api(first); var b = api(second);
            assertTrue(a.setGroupMetadata("one", "prefix", "remote"));
            assertThrows(StorageConflictException.class, () -> b.setGroupMetadata("one", "prefix", "stale"));
            assertFalse(b.synchronizeDefinitions());
            assertThrows(StorageConflictException.class, () -> b.createGroup("blocked"));
            b.reload();
            assertEquals("remote", b.getGroupInfo("one").prefix());
        }
    }

    @Test void definitionSyncLoadsCreatedDeletedGroupsAndTrackChanges() {
        try (var first = provider("first"); var second = provider("second")) {
            seed(first.permissions());
            var a = api(first); var b = api(second);
            assertTrue(a.createGroup("newgroup"));
            assertTrue(a.deleteGroup("three"));
            assertTrue(a.renameTrack("ranks", "renamed").applied());
            assertTrue(b.synchronizeDefinitions());
            assertNotNull(b.getGroupInfo("newgroup"));
            assertNull(b.getGroupInfo("three"));
            assertNull(b.getTrack("ranks"));
            assertEquals(List.of("one", "two"), b.getTrack("renamed").groups());
        }
    }

    @Test void staleGroupChangeCannotOverwriteRemotePermissionAndCanRecoverAfterReload() {
        try (var first = provider("first"); var second = provider("second")) {
            seed(first.permissions());
            var a = api(first);
            var b = api(second);
            assertTrue(a.addGroupPermission("one", "example.first", false));
            assertThrows(StorageConflictException.class, () -> b.addGroupPermission("one", "example.second", true));
            assertEquals(List.of("example.first"), first.permissions().getGroup("one").orElseThrow().permissions().stream().map(StoredPermissionNode::permission).toList());
            assertTrue(b.getGroupInfo("one").assignments().isEmpty());
            assertThrows(StorageConflictException.class, () -> b.createGroup("blocked"));
            assertTrue(first.permissions().getGroup("blocked").isEmpty());
            b.reload();
            assertTrue(b.addGroupPermission("one", "example.second", true));
            assertEquals(2, first.permissions().getGroup("one").orElseThrow().permissions().size());
        }
    }

    @Test void editsToDifferentDefinitionsPreserveBothServersChanges() {
        try (var first = provider("first"); var second = provider("second")) {
            seed(first.permissions());
            var a = api(first); var b = api(second);
            assertTrue(a.addGroupPermission("one", "example.first", false));
            assertTrue(b.addGroupPermission("two", "example.second", true));
            assertEquals("example.first", first.permissions().getGroup("one").orElseThrow().permissions().get(0).permission());
            assertEquals("example.second", first.permissions().getGroup("two").orElseThrow().permissions().get(0).permission());
        }
    }

    @Test void staleMetadataAndParentEditsCannotRestoreRemovedAssignments() {
        try (var first = provider("first"); var second = provider("second")) {
            seed(first.permissions());
            first.permissions().addGroupPermission("one", new StoredPermissionNode("example.old", true, null, null));
            var a = api(first); var b = api(second);
            assertTrue(a.removeGroupPermission("one", "example.old"));
            assertThrows(StorageConflictException.class, () -> b.addGroupParent("one", "two"));
            var stored = first.permissions().getGroup("one").orElseThrow();
            assertTrue(stored.permissions().isEmpty());
            assertTrue(stored.parents().isEmpty());
        }
    }

    @Test void groupEditsPreserveStoredIdsContextsAndExpiredRecords() {
        try (var provider = provider("first")) {
            seed(provider.permissions());
            var node = new StoredPermissionNode("example.context", true, System.currentTimeMillis() + 600000, null, PermissionContextSet.server("first"), "stable-context-id");
            var expired = new StoredPermissionNode("example.expired", false, 1L, null, PermissionContextSet.server("second"), "stable-expired-id");
            provider.permissions().addGroupPermission("one", node);
            provider.permissions().addGroupPermission("one", expired);
            var api = api(provider);
            assertTrue(api.addGroupParent("one", "two"));
            var raw = provider.permissions().definitions().groups().stream().filter(g -> g.name().equals("one")).findFirst().orElseThrow();
            assertTrue(raw.permissions().contains(node));
            assertTrue(raw.permissions().contains(expired));
            assertTrue(api.addGroupPermission("one", "example.new", false));
        }
    }

    @Test void staleTrackOrderAndDeletionAreRejectedWithoutChangingPersistedOrder() {
        try (var first = provider("first"); var second = provider("second")) {
            seed(first.permissions());
            var a = api(first); var b = api(second);
            assertTrue(a.moveTrackGroup("ranks", "three", 1).applied());
            assertThrows(StorageConflictException.class, () -> b.deleteTrack("ranks"));
            assertEquals(List.of("three", "one", "two"), first.permissions().getTrack("ranks").orElseThrow().groups());
            assertEquals(List.of("one", "two", "three"), b.getTrack("ranks").groups());
            b.reload();
            assertTrue(b.removeTrackGroup("ranks", "one").applied());
            a.reload();
            assertEquals(List.of("three", "two"), a.getTrack("ranks").groups());
        }
    }

    @Test void renameCollisionCannotDeleteSourceOrReplaceTarget() {
        try (var first = provider("first"); var second = provider("second")) {
            seed(first.permissions());
            var a = api(first); var b = api(second);
            assertTrue(a.cloneTrack("ranks", "target").applied());
            assertThrows(StorageConflictException.class, () -> b.renameTrack("ranks", "target"));
            assertEquals(List.of("one", "two", "three"), first.permissions().getTrack("ranks").orElseThrow().groups());
            assertEquals(List.of("one", "two", "three"), first.permissions().getTrack("target").orElseThrow().groups());
        }
    }

    @Test void groupDeletionCannotOverwriteConcurrentTrackMoveOrGroupEdit() {
        try (var first = provider("first"); var second = provider("second")) {
            seed(first.permissions());
            var a = api(first); var b = api(second);
            assertTrue(a.moveTrackGroup("ranks", "three", 1).applied());
            assertThrows(StorageConflictException.class, () -> b.deleteGroup("two"));
            assertTrue(first.permissions().getGroup("two").isPresent());
            assertEquals(List.of("three", "one", "two"), first.permissions().getTrack("ranks").orElseThrow().groups());
            b.reload();
            assertTrue(b.deleteGroup("two"));
            assertEquals(List.of("three", "one"), first.permissions().getTrack("ranks").orElseThrow().groups());
        }
    }

    @Test void staleTrackEditCannotReintroduceDeletedGroup() {
        try (var first = provider("first"); var second = provider("second")) {
            seed(first.permissions());
            var a = api(first); var b = api(second);
            assertTrue(a.deleteGroup("two"));
            assertThrows(StorageConflictException.class, () -> b.cloneTrack("ranks", "copy"));
            assertTrue(first.permissions().getTrack("copy").isEmpty());
            assertTrue(first.permissions().getGroup("two").isEmpty());
        }
    }

    @Test void concurrentCreationOfSameDefinitionHasOneWinner() throws Exception {
        try (var first = provider("first"); var second = provider("second")) {
            seed(first.permissions());
            var a = api(first); var b = api(second);
            var pool = Executors.newFixedThreadPool(2);
            try {
                var start = new CountDownLatch(1);
                var one = pool.submit(() -> { start.await(); try { return a.createGroup("created"); } catch (StorageConflictException conflict) { return false; } });
                var two = pool.submit(() -> { start.await(); try { return b.createGroup("created"); } catch (StorageConflictException conflict) { return false; } });
                start.countDown();
                assertNotEquals(one.get(20, TimeUnit.SECONDS), two.get(20, TimeUnit.SECONDS));
                assertTrue(first.permissions().getGroup("created").isPresent());
            } finally { pool.shutdownNow(); }
        }
    }

    @Test void simultaneousEmptyDatabaseBootstrapDoesNotOverwriteFirstDefinitions() throws Exception {
        try (var first = provider("first"); var second = provider("second")) {
            var pool = Executors.newFixedThreadPool(2);
            try {
                var start = new CountDownLatch(1);
                var one = pool.submit(() -> { start.await(); var a = api(first); a.addGroupPermission("default", "example.first", false); return a; });
                var two = pool.submit(() -> { start.await(); return api(second); });
                start.countDown();
                one.get(20, TimeUnit.SECONDS); two.get(20, TimeUnit.SECONDS);
                assertTrue(first.permissions().getGroup("default").orElseThrow().permissions().stream().anyMatch(n -> n.permission().equals("example.first")));
                assertTrue(first.permissions().getGroup("admin").isPresent());
            } finally { pool.shutdownNow(); }
        }
    }

    @Test void definitionChangesAreAcknowledgedEvenWhenUserPersistenceIsQueued() {
        try (var provider = provider("first")) {
            seed(provider.permissions());
            var api = api(provider);
            var queue = new java.util.ArrayList<Runnable>();
            api.setAsyncPersistenceExecutor((key, task) -> queue.add(task));
            assertTrue(api.addUserPermission(UUID.randomUUID(), "example.queued", false));
            assertFalse(queue.isEmpty());
            assertTrue(api.renameTrack("ranks", "renamed").applied());
            assertTrue(provider.permissions().getTrack("ranks").isEmpty());
            assertTrue(provider.permissions().getTrack("renamed").isPresent());
            queue.forEach(Runnable::run);
        }
    }

    @Test void lostDefinitionCommitAcknowledgementBlocksWritesUntilReload() {
        try (var provider = provider("first")) {
            seed(provider.permissions());
            var actual = provider.permissions();
            var writes = new java.util.concurrent.atomic.AtomicInteger();
            var uncertain = (PermissionRepository) java.lang.reflect.Proxy.newProxyInstance(getClass().getClassLoader(), new Class<?>[]{PermissionRepository.class}, (proxy, method, args) -> {
                Object result;
                try { result = method.invoke(actual, args); }
                catch (java.lang.reflect.InvocationTargetException failure) { throw failure.getCause(); }
                if (method.getName().equals("applyDefinitionChanges") && writes.incrementAndGet() == 1)
                    throw new StorageTransactionException("Lost definition commit acknowledgement", null, StorageTransactionException.Outcome.UNKNOWN);
                return result;
            });
            var api = api(uncertain);
            assertThrows(StorageTransactionException.class, () -> api.renameTrack("ranks", "renamed"));
            assertTrue(actual.getTrack("renamed").isPresent());
            assertFalse(api.synchronizeDefinitions());
            assertThrows(StorageTransactionException.class, () -> api.createGroup("blocked"));
            assertEquals(1, writes.get());
            api.reload();
            assertTrue(api.createGroup("blocked"));
        }
    }

    @Test void identicalTrackRecreationCannotBeDeletedByAnOldInstance() {
        try (var first = provider("first"); var second = provider("second")) {
            seed(first.permissions());
            var stale = api(first);
            var track = second.permissions().getTrack("ranks").orElseThrow();
            second.permissions().deleteTrack("ranks");
            second.permissions().saveTrack(track);
            assertThrows(StorageConflictException.class, () -> stale.deleteTrack("ranks"));
            assertEquals(track, first.permissions().getTrack("ranks").orElseThrow());
        }
    }

    @Test void identicalGroupRecreationCannotBeOverwrittenByAnOldInstance() {
        try (var first = provider("first"); var second = provider("second")) {
            seed(first.permissions());
            var stale = api(first);
            var group = second.permissions().getGroup("one").orElseThrow();
            second.permissions().deleteGroup("one");
            second.permissions().saveGroup(group);
            assertThrows(StorageConflictException.class, () -> stale.addGroupPermission("one", "example.stale", false));
            assertEquals(group, first.permissions().getGroup("one").orElseThrow());
        }
    }

    @Test void concurrentEditsOfExistingGroupHaveOneWinner() throws Exception {
        try (var first = provider("first"); var second = provider("second")) {
            seed(first.permissions());
            var a = api(first); var b = api(second);
            var pool = Executors.newFixedThreadPool(2);
            try {
                var start = new CountDownLatch(1);
                var one = pool.submit(() -> { start.await(); try { return a.addGroupPermission("one", "example.first", false); } catch (StorageConflictException conflict) { return false; } });
                var two = pool.submit(() -> { start.await(); try { return b.addGroupPermission("one", "example.second", true); } catch (StorageConflictException conflict) { return false; } });
                start.countDown();
                assertNotEquals(one.get(20, TimeUnit.SECONDS), two.get(20, TimeUnit.SECONDS));
                assertEquals(1, first.permissions().getGroup("one").orElseThrow().permissions().size());
            } finally { pool.shutdownNow(); }
        }
    }

    @Test void staleMetadataUpdateCannotLoseNewPermissionOrOtherMetadata() {
        try (var first = provider("first"); var second = provider("second")) {
            seed(first.permissions());
            var a = api(first); var b = api(second);
            assertTrue(a.setGroupMetadata("one", "prefix", "[Remote]"));
            assertThrows(StorageConflictException.class, () -> b.setGroupMetadata("one", "weight", "100"));
            assertEquals("[Remote]", first.permissions().getGroup("one").orElseThrow().prefix());
            assertEquals(0, first.permissions().getGroup("one").orElseThrow().weight());
            b.reload();
            assertTrue(b.setGroupMetadata("one", "weight", "100"));
            assertTrue(b.addGroupPermission("one", "example.new", true));
            assertEquals("[Remote]", first.permissions().getGroup("one").orElseThrow().prefix());
        }
    }

    @Test void missingDefinitionLockFailsBeforeAnyMutation() throws Exception {
        try (var provider = provider("first")) {
            seed(provider.permissions());
            var api = api(provider);
            try (var connection = connection(); var statement = connection.createStatement()) {
                statement.executeUpdate("DELETE FROM permission_definition_lock WHERE id = 1");
                try {
                    assertThrows(StorageException.class, () -> api.deleteTrack("ranks"));
                    assertTrue(provider.permissions().getTrack("ranks").isPresent());
                } finally { statement.executeUpdate("INSERT INTO permission_definition_lock(id) VALUES(1)"); }
            }
        }
    }

    @Test void reapplyingV17PreservesDefinitionsAssignmentsAndTrackOrder() throws Exception {
        StoredPermissionGroup original;
        try (var provider = provider("before-upgrade")) {
            seed(provider.permissions());
            provider.permissions().addGroupPermission("one", new StoredPermissionNode("example.existing", true, null, null, PermissionContextSet.server("first"), "released-id"));
            original = provider.permissions().getGroup("one").orElseThrow();
            try (var connection = connection(); var statement = connection.createStatement()) {
                statement.execute("DROP TABLE permission_definition_versions");
                statement.execute("DROP TABLE permission_definition_lock");
                statement.executeUpdate("DELETE FROM paradigm_schema_version WHERE version = 17");
            }
        }
        try (var upgraded = provider("after-upgrade")) {
            SqlDialect dialect = "mysql".equals(upgraded.displayName()) ? new MysqlDialect() : new SqliteDialect();
            assertEquals(SqlMigrationRunner.latestAvailableVersion(dialect), upgraded.migrationVersion());
            assertEquals(original, upgraded.permissions().getGroup("one").orElseThrow());
            assertEquals(List.of("one", "two", "three"), upgraded.permissions().getTrack("ranks").orElseThrow().groups());
            var api = api(upgraded);
            assertTrue(api.setGroupMetadata("one", "weight", "10"));
            assertTrue(upgraded.permissions().getGroup("one").orElseThrow().permissions().containsAll(original.permissions()));
        }
    }

    @Test void rejectedDeletionKeepsTemporaryMembershipAndSuccessfulDeletionCannotResurrectIt() {
        try (var first = provider("first"); var second = provider("second")) {
            seed(first.permissions());
            String user = UUID.randomUUID().toString();
            var cache = new eu.avalanche7.paradigm.data.PlayerDataStore(LoggerFactory.getLogger(getClass()), new DebugLogger(null), null) {
                final java.util.List<TemporaryGroupEntry> groups = new java.util.ArrayList<>();
                @Override public List<TemporaryGroupEntry> getTemporaryGroups(String uuid) { return List.copyOf(groups); }
                @Override public boolean setTemporaryGroup(String uuid, String group, long expiry, long assignedAt, String assignedBy) {
                    groups.removeIf(entry -> entry.getGroup().equals(group));
                    groups.add(new TemporaryGroupEntry(group, expiry, assignedAt, assignedBy)); return true;
                }
                @Override public boolean removeTemporaryGroup(String uuid, String group) { return groups.removeIf(entry -> entry.getGroup().equals(group)); }
                @Override public List<PlayerEntry> listPlayerEntries() { var entry = new PlayerEntry(); entry.setUuid(user); return List.of(entry); }
            };
            first.permissions().addUserGroup(user, new eu.avalanche7.paradigm.storage.model.StoredUserPermissionData.GroupAssignment("one", System.currentTimeMillis() + 600000, "test", 1));
            var local = new PermissionAPI(LoggerFactory.getLogger(getClass()), new DebugLogger(null), null, cache);
            local.setPermissionRepository(first.permissions()); local.initialize();
            var remote = api(second);
            assertTrue(remote.addGroupPermission("one", "example.remote", false));
            assertThrows(StorageConflictException.class, () -> local.deleteGroup("one"));
            assertEquals(1, local.getUserInfo(UUID.fromString(user)).groupAssignments().size());
            assertTrue(cache.getTemporaryGroups(user).isEmpty());
            local.reload();
            assertTrue(local.deleteGroup("one"));
            assertTrue(cache.getTemporaryGroups(user).isEmpty());
            assertTrue(first.permissions().getUser(user).orElseThrow().groups().isEmpty());
        }
    }

    void rejectTrackInsert(Connection connection) throws Exception {
        try (var statement = connection.createStatement()) {
            statement.execute("CREATE TRIGGER reject_track BEFORE INSERT ON permission_tracks WHEN NEW.name = 'blocked' BEGIN SELECT RAISE(ABORT, 'injected track failure'); END");
        }
    }

    @Test void failureDuringRenameRollsBackSourceDeletionAndTargetCreation() throws Exception {
        try (var provider = provider("first")) {
            seed(provider.permissions());
            var api = api(provider);
            try (var connection = connection()) { rejectTrackInsert(connection); }
            try {
                assertThrows(StorageException.class, () -> api.renameTrack("ranks", "blocked"));
                assertTrue(provider.permissions().getTrack("ranks").isPresent());
                assertTrue(provider.permissions().getTrack("blocked").isEmpty());
                assertNotNull(api.getTrack("ranks"));
            } finally {
                try (var connection = connection(); var statement = connection.createStatement()) { statement.execute("DROP TRIGGER reject_track"); }
            }
        }
    }
}
