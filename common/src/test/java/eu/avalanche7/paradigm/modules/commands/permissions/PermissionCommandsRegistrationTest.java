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
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Predicate;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.slf4j.LoggerFactory;

import eu.avalanche7.paradigm.core.Services;
import eu.avalanche7.paradigm.modules.permissions.PermissionsHandler;
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

    private static final class CommandFixture implements AutoCloseable {
        final LinkedBlockingQueue<Runnable> serverTasks = new LinkedBlockingQueue<>();
        final List<TestComponent> messages = new ArrayList<>();
        final AtomicReference<Thread> lookupThread = new AtomicReference<>();
        final Thread serverThread = Thread.currentThread();
        final TaskScheduler scheduler = new TaskScheduler(null);
        final StorageService storage;
        final PermissionsHandler permissions;
        final RecordingBuilder root = new RecordingBuilder();
        IPlayer online;
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
                        case "getOnlinePlayers" -> online == null ? List.of() : List.of(online);
                        case "getPlayerByName" -> online != null && online.getName().equalsIgnoreCase((String) args[0]) ? online : null;
                        case "getPlayerByUuid" -> online != null && online.getUUID().equals(args[0]) ? online : null;
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
            var services = new Services(logger, null, null, null, null, null, null, null, null, debug, lang,
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
