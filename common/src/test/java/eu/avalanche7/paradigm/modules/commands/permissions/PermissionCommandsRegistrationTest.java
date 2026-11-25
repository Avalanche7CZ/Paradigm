package eu.avalanche7.paradigm.modules.commands.permissions;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.lang.reflect.Proxy;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Predicate;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.slf4j.LoggerFactory;

import eu.avalanche7.paradigm.ParadigmAPI;
import eu.avalanche7.paradigm.core.ParadigmModule;
import eu.avalanche7.paradigm.core.Services;
import eu.avalanche7.paradigm.modules.audit.AuditActionType;
import eu.avalanche7.paradigm.modules.audit.AuditResult;
import eu.avalanche7.paradigm.modules.audit.AuditService;
import eu.avalanche7.paradigm.modules.dashboard.DashboardConfig;
import eu.avalanche7.paradigm.modules.dashboard.DashboardService;
import eu.avalanche7.paradigm.modules.dashboard.auth.DashboardPrincipal;
import eu.avalanche7.paradigm.modules.permissions.ParadigmPermissions;
import eu.avalanche7.paradigm.modules.permissions.PermissionAdminService;
import eu.avalanche7.paradigm.modules.permissions.PermissionMutationRequest;
import eu.avalanche7.paradigm.modules.permissions.PermissionMutationResult;
import eu.avalanche7.paradigm.modules.permissions.PermissionsHandler;
import eu.avalanche7.paradigm.modules.permissions.context.PermissionContextSet;
import eu.avalanche7.paradigm.platform.Interfaces.ICommandBuilder;
import eu.avalanche7.paradigm.platform.Interfaces.ICommandContext;
import eu.avalanche7.paradigm.platform.Interfaces.ICommandSource;
import eu.avalanche7.paradigm.platform.Interfaces.IConfig;
import eu.avalanche7.paradigm.platform.Interfaces.IPlatformAdapter;
import eu.avalanche7.paradigm.platform.Interfaces.IPlayer;
import eu.avalanche7.paradigm.storage.StorageConfig;
import eu.avalanche7.paradigm.storage.StorageService;
import eu.avalanche7.paradigm.storage.model.StoredPlayerProfile;
import eu.avalanche7.paradigm.storage.repository.PlayerRepository;
import eu.avalanche7.paradigm.support.TestComponent;
import eu.avalanche7.paradigm.support.TestPlatformAdapter;
import eu.avalanche7.paradigm.support.TestPlayer;
import eu.avalanche7.paradigm.utils.DebugLogger;
import eu.avalanche7.paradigm.utils.Lang;
import eu.avalanche7.paradigm.utils.MessageParser;
import eu.avalanche7.paradigm.utils.Placeholders;
import eu.avalanche7.paradigm.utils.TaskScheduler;

class PermissionCommandsRegistrationTest {
    @Test
    void retainsPermissionGroupAndAssignmentRemovalBranches() {
        RecordingBuilder root = new RecordingBuilder();
        PermissionCommands.register(root, platform(), null);

        assertNotNull(root.child("perms"));
        RecordingBuilder permission = root.child("permission");
        assertNotNull(permission);
        assertNotNull(permission.child("check"));
        assertNotNull(permission.child("explain"));
        assertNotNull(permission.child("nodes"));
        RecordingBuilder migrate = permission.child("migrate");
        assertNotNull(migrate);
        RecordingBuilder luckPerms = migrate.child("luckperms");
        assertNotNull(luckPerms);
        assertNotNull(luckPerms.child("import").child("dry-run"));
        assertNotNull(luckPerms.child("export").child("merge"));
        assertNotNull(luckPerms.child("import").child("replace").child("confirm"));

        RecordingBuilder group = root.child("group");
        assertNotNull(group);
        assertNotNull(group.child("list"));
        assertNotNull(group.child("add"));
        assertNotNull(group.child("remove"));
        assertNotNull(group.child("info"));
        RecordingBuilder parent = group.child("parent");
        assertNotNull(parent);
        assertNotNull(parent.child("add"));
        assertNotNull(parent.child("remove"));
        assertNull(parent.descendantArgument("flags"), "group parents remain global and permanent");

        RecordingBuilder groupPermissions = group.child("perm");
        assertNotNull(groupPermissions);
        assertNotNull(groupPermissions.child("add"));
        assertNotNull(groupPermissions.child("deny"));
        assertNotNull(groupPermissions.child("remove"));
        assertNotNull(groupPermissions.child("remove-id"));
        assertNotNull(groupPermissions.child("list"));
        assertNotNull(groupPermissions.child("add").descendantArgument("flags"));
        assertNotNull(groupPermissions.child("deny").descendantArgument("flags"));
        assertNotNull(groupPermissions.child("remove").descendantArgument("flags"));

        RecordingBuilder user = group.child("user");
        assertNotNull(user);
        assertNotNull(user.child("add"));
        assertNotNull(user.child("remove"));
        assertNotNull(user.child("remove-id"));
        assertNotNull(user.child("add").descendantArgument("flags"));
        assertNotNull(user.child("remove").descendantArgument("flags"));
        RecordingBuilder userPermissions = user.child("perm");
        assertNotNull(userPermissions);
        assertNotNull(userPermissions.child("add"));
        assertNotNull(userPermissions.child("deny"));
        assertNotNull(userPermissions.child("remove"));
        assertNotNull(userPermissions.child("remove-id"));
        assertNotNull(userPermissions.child("list"));
        assertNotNull(userPermissions.child("add").descendantArgument("flags"));
        assertNotNull(userPermissions.child("deny").descendantArgument("flags"));
        assertNotNull(userPermissions.child("remove").descendantArgument("flags"));
    }

    @Test
    void consoleTargetsOnlineAndStoredPlayersAndRejectsUnknownOrAmbiguousNames(@TempDir Path directory) throws Exception {
        try (var fixture = new CommandFixture(directory)) {
            UUID uuid = UUID.randomUUID();
            fixture.online = new TestPlayer("Online", uuid.toString(), "minecraft:overworld");
            fixture.info("online");
            assertTrue(fixture.output().contains("Online"));
            assertNull(fixture.lookupThread.get());

            fixture.online = null;
            fixture.info(uuid.toString());
            assertTrue(fixture.output().contains(uuid.toString()));
            assertNull(fixture.lookupThread.get());

            fixture.save(uuid, "OldName", 1);
            assertTrue(fixture.permissions.createPermissionGroup("member"));
            assertTrue(fixture.permissions.assignPlayerGroup(uuid, "member"));
            assertTrue(fixture.permissions.addPermissionToPlayer(uuid, "example.direct", false));

            fixture.info("oldname");
            fixture.finishLookup();
            assertTrue(fixture.output().contains("OldName"));
            assertTrue(fixture.output().contains("member"));
            assertTrue(fixture.output().contains("example.direct"));
            var clicks = fixture.messages.stream().flatMap(message -> message.flatten().stream())
                    .map(component -> component.testStyle().clickValue()).filter(value -> value != null).toList();
            assertTrue(clicks.contains("/paradigm group user add " + uuid + " "));
            assertTrue(clicks.stream().anyMatch(value -> value.startsWith("/paradigm group user remove-id " + uuid + " ")));
            assertTrue(clicks.stream().anyMatch(value -> value.startsWith("/paradigm group user perm remove-id " + uuid + " ")));
            assertTrue(clicks.contains("/paradigm group user perm add " + uuid + " "));
            assertTrue(clicks.contains("/paradigm permission check " + uuid + " "));

            fixture.save(uuid, "NewName", 2);
            fixture.info("OldName");
            fixture.finishLookup();
            assertTrue(fixture.output().contains("Player not found"));
            fixture.info("NEWNAME");
            fixture.finishLookup();
            assertTrue(fixture.output().contains("NewName"));
            assertTrue(fixture.output().contains("example.direct"));

            fixture.run("permission/check/player/permission", Map.of("player", "NewName", "permission", "example.direct"));
            fixture.finishLookup();
            assertTrue(fixture.output().contains("NewName"));
            assertTrue(fixture.messages.stream().flatMap(message -> message.flatten().stream())
                    .anyMatch(component -> ("/paradigm group user info " + uuid).equals(component.testStyle().clickValue())));

            String permissionId = fixture.permissions.getPlayerPermissionInfo(uuid).assignments().get(0).id();
            fixture.run("group/user/perm/remove-id/player/assignmentId", Map.of("player", "NewName", "assignmentId", permissionId));
            fixture.finishLookup();
            assertTrue(fixture.permissions.getPlayerPermissionInfo(uuid).assignments().isEmpty());

            UUID other = UUID.randomUUID();
            fixture.save(other, "newname", 3);
            fixture.run("group/user/add/player/group", Map.of("player", "NewName", "group", "member"));
            fixture.finishLookup();
            assertTrue(fixture.output().contains("Multiple stored players"));
            assertTrue(fixture.permissions.getPlayerPermissionInfo(other).groupAssignments().isEmpty());

            String assignmentId = fixture.permissions.getPlayerPermissionInfo(uuid).groupAssignments().get(0).id();
            fixture.run("group/user/remove-id/player/assignmentId", Map.of("player", uuid.toString(), "assignmentId", assignmentId));
            assertTrue(fixture.permissions.getPlayerPermissionInfo(uuid).groupAssignments().isEmpty());
        }
    }

    @Test
    void pendingLookupRechecksAuthorityAndPlayerIdentity(@TempDir Path directory) throws Exception {
        try (var fixture = new CommandFixture(directory)) {
            UUID target = UUID.randomUUID();
            fixture.save(target, "offline", 1);
            assertTrue(fixture.permissions.createPermissionGroup("member"));
            var arguments = Map.of("player", "offline", "group", "member");
            fixture.actor = TestPlayer.named("Operator");
            fixture.online = fixture.actor;

            fixture.run("group/user/add/player/group", arguments);
            fixture.operator = false;
            fixture.finishLookup();
            assertTrue(fixture.permissions.getPlayerPermissionInfo(target).groupAssignments().isEmpty());

            fixture.actor = null;
            fixture.online = null;
            fixture.run("group/user/add/player/group", arguments);
            UUID other = UUID.randomUUID();
            fixture.online = new TestPlayer("offline", other.toString(), "minecraft:overworld");
            fixture.finishLookup();
            assertTrue(fixture.permissions.getPlayerPermissionInfo(target).groupAssignments().isEmpty());
            assertTrue(fixture.permissions.getPlayerPermissionInfo(other).groupAssignments().isEmpty());

            fixture.online = null;
            fixture.run("group/user/add/player/group", arguments);
            fixture.finishLookup();
            assertEquals(List.of("member"), fixture.permissions.getPlayerPermissionInfo(target).permanentGroups());
        }
    }

    @Test
    void commandAndDashboardGroupEditsShareValidationAndKeepConfirmationRules(@TempDir Path directory) throws Exception {
        try (var fixture = new CommandFixture(directory);
             var dashboard = new DashboardService(fixture.services, new DashboardConfig())) {
            fixture.run("group/add/name", Map.of("name", "member"));
            assertTrue(fixture.output().contains("Created"));
            assertTrue(fixture.dashboardMutation(dashboard, mutation("group_create", "staff")).applied());
            fixture.run("group/parent/add/group/parent", Map.of("group", "staff", "parent", "member"));
            assertEquals(List.of("member"), fixture.permissions.getPermissionGroupInfo("staff").inherits());

            fixture.run("group/setprefix/group/value", Map.of("group", "staff", "value", "[Staff]"));
            var metadata = mutation("group_update", "staff");
            metadata.metadata = Map.of("suffix", "suffix", "weight", "30");
            assertTrue(fixture.dashboardMutation(dashboard, metadata).applied());
            assertEquals("[Staff]", fixture.permissions.getPermissionGroupInfo("staff").prefix());
            assertEquals("suffix", fixture.permissions.getPermissionGroupInfo("staff").suffix());

            var remove = mutation("group_delete", "staff");
            assertFalse(fixture.dashboardMutation(dashboard, remove).applied());
            assertNotNull(fixture.permissions.getPermissionGroupInfo("staff"));
            fixture.run("group/remove/name", Map.of("name", "staff"));
            assertNull(fixture.permissions.getPermissionGroupInfo("staff"));
        }
    }

    @Test
    void dashboardResolvesOfflineTargetsAndRequiresIdsForAmbiguousRemoval(@TempDir Path directory) throws Exception {
        try (var fixture = new CommandFixture(directory);
             var dashboard = new DashboardService(fixture.services, new DashboardConfig())) {
            UUID user = UUID.randomUUID();
            fixture.save(user, "Offline", 1);
            var add = mutation("user_permission_add", null);
            add.user = "offline";
            add.permission = "example.offline";
            var pending = dashboard.permissionMutationAsync(CONSOLE, add);
            fixture.finishLookup();
            assertTrue(((PermissionMutationResult) pending.get(10, TimeUnit.SECONDS)).applied());
            fixture.runServerTask();
            assertTrue(fixture.permissions.addPermissionToPlayer(user, add.permission, true));

            var remove = mutation("user_permission_remove", null);
            remove.user = user.toString();
            remove.permission = add.permission;
            assertEquals("assignment_ambiguous", fixture.dashboardMutation(dashboard, remove).code());
            var assignments = fixture.permissions.getPlayerPermissionInfo(user).assignments();
            assertEquals(2, assignments.size());
            remove.assignmentId = assignments.get(0).id();
            assertTrue(fixture.dashboardMutation(dashboard, remove).applied());
            fixture.runServerTask();
            assertEquals(List.of(assignments.get(1)), fixture.permissions.getPlayerPermissionInfo(user).assignments());

            fixture.save(UUID.randomUUID(), "Offline", 2);
            pending = dashboard.permissionMutationAsync(CONSOLE, add);
            fixture.finishLookup();
            assertEquals("player_ambiguous", ((PermissionMutationResult) pending.get(10, TimeUnit.SECONDS)).code());
        }
    }

    @Test
    void dashboardRechecksMutationAuthorityAfterOfflineLookup(@TempDir Path directory) throws Exception {
        try (var fixture = new CommandFixture(directory);
             var dashboard = new DashboardService(fixture.services, new DashboardConfig())) {
            fixture.online = TestPlayer.named("Manager");
            UUID manager = UUID.fromString(fixture.online.getUUID());
            UUID target = UUID.randomUUID();
            fixture.save(target, "Offline", 1);
            assertTrue(fixture.permissions.addPermissionToPlayer(manager, ParadigmPermissions.DASHBOARD_MANAGE.node(), false));
            var add = mutation("user_permission_add", null);
            add.user = "offline";
            add.permission = "example.offline";
            var pending = dashboard.permissionMutationAsync(new DashboardPrincipal(manager.toString(), "Manager", false), add);

            Runnable recheck = fixture.serverTasks.poll(10, TimeUnit.SECONDS);
            assertNotNull(recheck);
            assertTrue(fixture.permissions.removePermissionFromPlayer(manager, ParadigmPermissions.DASHBOARD_MANAGE.node()));
            recheck.run();

            assertEquals("permission_denied", ((PermissionMutationResult) pending.get(10, TimeUnit.SECONDS)).code());
            assertTrue(fixture.permissions.getPlayerPermissionInfo(target).assignments().isEmpty());
        }
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void dashboardDeniesQueuedMutationsAfterAuthorityIsRevoked(boolean afterLiveCheck, @TempDir Path directory) throws Exception {
        try (var fixture = new CommandFixture(directory)) {
            fixture.online = TestPlayer.named("Manager");
            UUID manager = UUID.fromString(fixture.online.getUUID());
            assertTrue(fixture.permissions.addPermissionToPlayer(manager, ParadigmPermissions.DASHBOARD_MANAGE.node(), false));
            var audit = new AuditService(fixture.services, Runnable::run);
            var admin = new PermissionAdminService(fixture.services, audit);
            var worker = new LinkedBlockingQueue<Runnable>();
            var request = mutation("group_create", "queued");
            var pending = admin.mutateAsync(new DashboardPrincipal(manager.toString(), "Manager", false), request, worker::add);
            assertFalse(pending.isDone());

            if (afterLiveCheck) {
                runWorkerTask(worker);
                fixture.runServerTask();
                assertFalse(pending.isDone());
            }
            assertTrue(fixture.permissions.removePermissionFromPlayer(manager, ParadigmPermissions.DASHBOARD_MANAGE.node()));
            runWorkerTask(worker);
            if (!afterLiveCheck && !pending.isDone()) fixture.runServerTask();

            assertEquals("permission_denied", pending.get(10, TimeUnit.SECONDS).code());
            assertNull(fixture.permissions.getPermissionGroupInfo("queued"));
            assertTrue(fixture.storage.permissions().getGroup("queued").isEmpty());
            var entries = audit.actor(manager.toString(), 10);
            assertEquals(1, entries.size());
            assertEquals(AuditActionType.PERMISSION_CHANGE, entries.get(0).actionType());
            assertEquals(AuditResult.DENIED, entries.get(0).result());
            assertEquals("permission_denied", entries.get(0).details().get("reason"));
        }
    }

    @Test
    void dashboardRejectsLoggedOutConsoleSessionAtExecution(@TempDir Path directory) throws Exception {
        try (var fixture = new CommandFixture(directory)) {
            var auth = new eu.avalanche7.paradigm.modules.dashboard.auth.DashboardAuthService();
            var config = new DashboardConfig();
            var session = auth.consumeLoginToken(auth.createLoginToken(CONSOLE, config).token(), config);
            var worker = new LinkedBlockingQueue<Runnable>();
            var admin = new PermissionAdminService(fixture.services, null);
            var pending = admin.mutateAsync(CONSOLE, mutation("group_create", "queued"), worker::add,
                    () -> auth.isSessionCurrent(session.session()));
            auth.logout(session.token());
            runWorkerTask(worker);
            assertEquals("permission_denied", pending.get(10, TimeUnit.SECONDS).code());
            assertNull(fixture.permissions.getPermissionGroupInfo("queued"));
            assertTrue(fixture.storage.permissions().getGroup("queued").isEmpty());
        }
    }

    private static void runWorkerTask(LinkedBlockingQueue<Runnable> worker) throws Exception {
        Runnable task = worker.poll(10, TimeUnit.SECONDS);
        assertNotNull(task);
        CompletableFuture.runAsync(task).get(10, TimeUnit.SECONDS);
    }

    @Test
    void liveEffectsRefreshOnlyPlayersAffectedByAuthorizationChanges(@TempDir Path directory) throws Exception {
        try (var fixture = new CommandFixture(directory);
             var dashboard = new DashboardService(fixture.services, new DashboardConfig())) {
            var vanish = fixture.services.getVanishService();
            vanish.enable();
            ParadigmAPI.setInstance(new ParadigmAPI.ParadigmAccessor() {
                @Override public List<ParadigmModule> getModules() { return List.of(); }
                @Override public Services getServices() { return fixture.services; }
                @Override public String getModVersion() { return "test"; }
            });
            try {
                UUID user = UUID.randomUUID();
                fixture.online = new TestPlayer("Viewer", user.toString(), "minecraft:overworld");
                fixture.other = new TestPlayer("Hidden", UUID.randomUUID().toString(), "minecraft:overworld");
                fixture.storage.adminState().setVanished(fixture.other.getUUID(), true);
                vanish.prepare(fixture.other.getUUID(), vanish.loadAdmission(fixture.other.getUUID()).get(10, TimeUnit.SECONDS));
                vanish.refreshViewer(fixture.online);
                fixture.visibilityRefreshes.clear();

                var add = mutation("user_permission_add", null);
                add.user = user.toString();
                add.permission = ParadigmPermissions.VANISH_SEE.node();
                add.confirmed = true;
                assertTrue(fixture.dashboardMutation(dashboard, add).applied());
                assertTrue(fixture.commandRefreshes.isEmpty());
                fixture.online = new TestPlayer("Viewer", user.toString(), "minecraft:overworld");
                fixture.runServerTask();
                assertEquals(List.of(fixture.online), fixture.commandRefreshes);
                assertEquals(List.of(fixture.online), fixture.visibilityRefreshes);
                assertTrue(fixture.serverTasks.isEmpty());

                assertTrue(fixture.permissions.createPermissionGroup("member"));
                assertTrue(fixture.permissions.createPermissionGroup("parent"));
                assertTrue(fixture.permissions.assignPlayerGroup(user, "member", PermissionContextSet.empty(), null, "test"));
                fixture.commandRefreshes.clear();
                fixture.run("group/parent/add/group/parent", Map.of("group", "member", "parent", "parent"));
                assertEquals(List.of(fixture.online), fixture.commandRefreshes);
                fixture.commandRefreshes.clear();
                fixture.run("group/setprefix/group/value", Map.of("group", "member", "value", "[Member]"));
                assertTrue(fixture.commandRefreshes.isEmpty());
            } finally {
                vanish.disable();
                ParadigmAPI.setInstance(null);
            }
        }
    }

    private static final DashboardPrincipal CONSOLE = new DashboardPrincipal(null, "Console", true);

    private static PermissionMutationRequest mutation(String action, String group) {
        var request = new PermissionMutationRequest();
        request.action = action;
        request.group = group;
        request.scope = "global";
        return request;
    }

    private static final class CommandFixture implements AutoCloseable {
        final LinkedBlockingQueue<Runnable> serverTasks = new LinkedBlockingQueue<>();
        final List<TestComponent> messages = new ArrayList<>();
        final AtomicReference<Thread> lookupThread = new AtomicReference<>();
        final Thread serverThread = Thread.currentThread();
        final TaskScheduler scheduler = new TaskScheduler(null);
        final StorageService storage;
        final PermissionsHandler permissions;
        final Services services;
        final List<IPlayer> commandRefreshes = new ArrayList<>();
        final List<IPlayer> visibilityRefreshes = new ArrayList<>();
        final RecordingBuilder root = new RecordingBuilder();
        IPlayer online;
        IPlayer other;
        IPlayer actor;
        boolean operator = true;

        CommandFixture(Path directory) {
            var logger = LoggerFactory.getLogger(getClass());
            var debug = new DebugLogger(null);
            var config = new IConfig() {
                @Override public Path getConfigDirectory() { return directory; }
                @Override public String getModId() { return "paradigm"; }
            };
            var storageConfig = new StorageConfig();
            storageConfig.provider = "sqlite";
            storageConfig.sqlite.path = directory.resolve("players.db").toString();
            storageConfig.runtimeLibraries.enabled = false;
            storageConfig.persist(config, logger);
            storage = new StorageService(logger, debug, config, null, null, null, null) {
                @Override public PlayerRepository players() {
                    lookupThread.set(Thread.currentThread());
                    return super.players();
                }
            };

            scheduler.initialize(this);
            scheduler.setMainThreadExecutor(serverTasks::add);
            var placeholders = new Placeholders();
            var delegate = TestPlatformAdapter.create(placeholders, Map.of());
            var platform = (IPlatformAdapter) Proxy.newProxyInstance(IPlatformAdapter.class.getClassLoader(),
                    new Class<?>[]{IPlatformAdapter.class}, (proxy, method, args) -> switch (method.getName()) {
                        case "createCommandBuilder" -> new RecordingBuilder();
                        case "getConfig" -> config;
                        case "getOnlinePlayers" -> other != null ? List.of(online, other) : online == null ? List.of() : List.of(online);
                        case "getPlayerByName" -> online != null && online.getName().equalsIgnoreCase((String) args[0]) ? online : null;
                        case "getPlayerByUuid" -> {
                            assertEquals(serverThread, Thread.currentThread());
                            yield online != null && online.getUUID().equals(args[0]) ? online
                                    : other != null && other.getUUID().equals(args[0]) ? other : null;
                        }
                        case "isServerThread" -> serverThread == Thread.currentThread();
                        case "executeOnServerThread" -> {
                            if (serverThread == Thread.currentThread()) ((Runnable) args[0]).run();
                            else serverTasks.add((Runnable) args[0]);
                            yield null;
                        }
                        case "refreshPlayerCommandTree", "refreshVanishVisibility" -> {
                            assertEquals(serverThread, Thread.currentThread());
                            var refreshes = "refreshPlayerCommandTree".equals(method.getName()) ? commandRefreshes : visibilityRefreshes;
                            refreshes.add((IPlayer) args[0]);
                            yield null;
                        }
                        case "setPlayerVanished" -> true;
                        case "sendSuccess", "sendFailure" -> {
                            assertEquals(serverThread, Thread.currentThread());
                            messages.add((TestComponent) args[1]);
                            yield null;
                        }
                        default -> method.invoke(delegate, args);
                    });
            permissions = new PermissionsHandler(logger, null, debug, platform, null, storage) {
                @Override public boolean isInternalPermissionsEnabled() { return true; }
            };
            permissions.initialize();
            var parser = new MessageParser(placeholders, platform);
            var lang = new Lang(logger, null, parser, platform);
            lang.loadLanguage("en");
            services = new Services(logger, null, null, null, null, null, null, null, null, debug, lang,
                    parser, permissions, placeholders, scheduler, null, null, null, storage, null, platform);
            PermissionCommands.register(root, platform, services);
        }

        void save(UUID uuid, String name, long seen) {
            storage.players().mergeProfile(new StoredPlayerProfile(uuid.toString(), name, 1, seen, 0));
            lookupThread.set(null);
        }

        void info(String name) throws Exception {
            run("group/user/info/player", Map.of("player", name));
        }

        void run(String path, Map<String, String> arguments) throws Exception {
            messages.clear();
            RecordingBuilder node = root;
            for (String part : path.split("/")) {
                node = node.children.stream().filter(child -> part.equals(child.literal) || part.equals(child.argument)).findFirst().orElseThrow();
            }
            var source = new ICommandSource() {
                @Override public IPlayer getPlayer() { return actor; }
                @Override public String getSourceName() { return actor != null ? actor.getName() : "Console"; }
                @Override public boolean hasPermissionLevel(int level) { return operator; }
                @Override public boolean isConsole() { return actor == null; }
                @Override public Object getOriginalSource() { return this; }
            };
            var context = new ICommandContext() {
                @Override public ICommandSource getSource() { return source; }
                @Override public String getStringArgument(String name) { return arguments.get(name); }
                @Override public int getIntArgument(String name) { throw new UnsupportedOperationException(name); }
                @Override public boolean getBooleanArgument(String name) { throw new UnsupportedOperationException(name); }
                @Override public IPlayer getPlayerArgument(String name) { throw new UnsupportedOperationException(name); }
                @Override public Object getOriginalContext() { return this; }
            };
            assertEquals(1, node.executor.execute(context));
        }

        void finishLookup() throws InterruptedException {
            assertTrue(messages.isEmpty());
            Runnable completion = serverTasks.poll(10, TimeUnit.SECONDS);
            assertNotNull(completion);
            assertNotNull(lookupThread.get());
            assertFalse(serverThread.equals(lookupThread.get()));
            completion.run();
        }

        void runServerTask() throws InterruptedException {
            Runnable task = serverTasks.poll(10, TimeUnit.SECONDS);
            assertNotNull(task);
            task.run();
        }

        PermissionMutationResult dashboardMutation(DashboardService dashboard, PermissionMutationRequest request) throws Exception {
            return (PermissionMutationResult) dashboard.permissionMutationAsync(CONSOLE, request).get(10, TimeUnit.SECONDS);
        }

        String output() {
            return String.join("\n", messages.stream().map(TestComponent::getRawText).toList());
        }

        @Override public void close() {
            scheduler.shutdown();
            storage.close();
            Placeholders.setPermissionMetaResolver(null);
            Placeholders.setPlayerAdapter(null);
            Placeholders.setNicknameResolver(null);
        }
    }

    private static IPlatformAdapter platform() {
        return (IPlatformAdapter) Proxy.newProxyInstance(
                PermissionCommandsRegistrationTest.class.getClassLoader(),
                new Class<?>[]{IPlatformAdapter.class},
                (proxy, method, args) -> {
                    if ("createCommandBuilder".equals(method.getName())) {
                        return new RecordingBuilder();
                    }
                    throw new UnsupportedOperationException(method.getName());
                }
        );
    }

    private static final class RecordingBuilder implements ICommandBuilder {
        private String literal;
        private String argument;
        private CommandExecutor executor;
        private final List<RecordingBuilder> children = new ArrayList<>();

        @Override public ICommandBuilder literal(String name) { literal = name; return this; }
        @Override public ICommandBuilder argument(String name, ArgumentType type) { argument = name; return this; }
        @Override public ICommandBuilder requires(Predicate<eu.avalanche7.paradigm.platform.Interfaces.ICommandSource> requirement) { return this; }
        @Override public ICommandBuilder executes(CommandExecutor executor) { this.executor = executor; return this; }
        @Override public ICommandBuilder then(ICommandBuilder child) { children.add((RecordingBuilder) child); return this; }
        @Override public Object build() { return this; }

        RecordingBuilder child(String name) {
            return children.stream().filter(child -> name.equals(child.literal)).findFirst().orElse(null);
        }

        RecordingBuilder descendantArgument(String name) {
            if (name.equals(argument)) return this;
            for (RecordingBuilder child : children) {
                RecordingBuilder found = child.descendantArgument(name);
                if (found != null) return found;
            }
            return null;
        }
    }
}
