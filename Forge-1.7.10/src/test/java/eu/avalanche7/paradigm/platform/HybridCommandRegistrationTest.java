package eu.avalanche7.paradigm.platform;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;

import cpw.mods.fml.relauncher.ReflectionHelper;
import net.minecraft.command.CommandHandler;
import net.minecraft.command.ICommand;
import net.minecraft.command.ICommandSender;
import net.minecraft.entity.player.EntityPlayerMP;
import net.minecraft.network.NetHandlerPlayServer;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.management.ServerConfigurationManager;
import net.minecraftforge.event.CommandEvent;
import org.bukkit.Bukkit;
import org.bukkit.craftbukkit.v1_7_R4.command.ModCustomCommand;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.slf4j.LoggerFactory;
import serverutils.lib.command.CommandTreeBase;

import eu.avalanche7.paradigm.configs.MainConfigHandler;
import eu.avalanche7.paradigm.core.Services;
import eu.avalanche7.paradigm.mixin.PlayerCommandAccess;
import eu.avalanche7.paradigm.modules.commands.FlyCommand;
import eu.avalanche7.paradigm.modules.commands.HomeCommand;
import eu.avalanche7.paradigm.modules.commands.shared.CommandCatalog;
import eu.avalanche7.paradigm.modules.dashboard.auth.DashboardPrincipal;
import eu.avalanche7.paradigm.modules.permissions.PermissionAdminService;
import eu.avalanche7.paradigm.modules.permissions.PermissionMutationRequest;
import eu.avalanche7.paradigm.modules.permissions.PermissionsHandler;
import eu.avalanche7.paradigm.utils.CommandToggleStore;
import eu.avalanche7.paradigm.utils.DebugLogger;
import eu.avalanche7.paradigm.utils.Placeholders;
import eu.avalanche7.paradigm.utils.TaskScheduler;

public class HybridCommandRegistrationTest {
    @TempDir Path directory;
    private PlatformAdapterImpl platform;
    private PermissionsHandler permissions;
    private CommandToggleStore toggles;
    private final AtomicInteger executions = new AtomicInteger();

    @BeforeEach void setup() {
        Bukkit.server = null;
        DebugLogger debug = new DebugLogger(null);
        platform = new PlatformAdapterImpl(null, new Placeholders(), new TaskScheduler(debug), debug,
                new ForgeConfig(directory));
        MainConfigHandler.init(platform.getConfig(), debug);
        MainConfigHandler.getConfig().internalPermissionsEnable.value = true;
        MainConfigHandler.getConfig().externalCommandPermissionsEnable.value = true;
        MainConfigHandler.getConfig().externalCommandPermissionMode.value = "deny_only";
        MainConfigHandler.getConfig().forceCommandPriorityEnable.value = false;
        permissions = new PermissionsHandler(LoggerFactory.getLogger(getClass()), null, debug, platform, null, null);
        permissions.initialize();
        platform.setPermissionsHandler(permissions);
        toggles = new CommandToggleStore(LoggerFactory.getLogger(getClass()), debug, platform.getConfig());
        for (CommandCatalog.Entry entry : CommandCatalog.entries()) {
            toggles.registerCommand(entry.id(), entry.defaultEnabled(), entry.protectedCommand(),
                    entry.roots().toArray(String[]::new));
        }
        platform.setCommandToggleStore(toggles);
    }

    @AfterEach void cleanup() {
        platform.clear();
        Bukkit.server = null;
    }

    @Test void classNameGateReproducesTheNonOpFailure() {
        SimulatedCrucible manager = hybrid();
        EntityPlayerMP player = player(false);
        assertTrue(permissions.addPermissionToPlayer(player.getUniqueID(), "paradigm.fly", false));
        ICommand command = new ForgeCommand(root("fly"), platform);
        manager.bridge = false;
        manager.registerCommand(command); // Baseline native registration without the adapter fix.
        assertTrue(command.canCommandSenderUseCommand(player));
        manager.forward("fly", player);
        assertEquals(0, executions.get());
        when(player.canCommandSenderUseCommand(2, "paradigm")).thenReturn(true);
        manager.forward("fly", player);
        assertEquals(1, executions.get());
    }

    @Test void hybridForwardsNonOpInternalGrantsAndStillRechecksDeniesAndMissingPermissions() {
        SimulatedCrucible manager = hybrid();
        EntityPlayerMP player = player(false);
        platform.registerCommand(root("fly"));
        assertEquals("", manager.wrappers.get("fly").getPermission());
        manager.forward("fly", player);
        assertEquals(0, executions.get());

        assertTrue(permissions.createPermissionGroup("parent"));
        assertTrue(permissions.createPermissionGroup("child"));
        assertTrue(permissions.addPermissionToGroup("parent", "paradigm.*", false));
        assertTrue(permissions.addPermissionGroupParent("child", "parent"));
        assertTrue(permissions.assignPlayerGroup(player.getUniqueID(), "child"));
        manager.forward("fly", player);
        assertEquals(1, executions.get());

        assertTrue(permissions.addPermissionToPlayer(player.getUniqueID(), "paradigm.fly", true));
        manager.forward("fly", player);
        assertEquals(1, executions.get());
        assertFalse(player.canCommandSenderUseCommand(2, "paradigm"));
    }

    @Test void opFallbackAndExplicitDenyRemainUnchanged() {
        SimulatedCrucible manager = hybrid();
        EntityPlayerMP player = player(true);
        platform.registerCommand(root("fly"));
        manager.forward("fly", player);
        assertEquals(1, executions.get());
        assertTrue(permissions.addPermissionToPlayer(player.getUniqueID(), "paradigm.fly", true));
        manager.forward("fly", player);
        assertEquals(1, executions.get());
    }

    @Test void realHomeAndFlyKeepTheirExistingAuthorizationBoundaries() {
        SimulatedCrucible manager = hybrid();
        Services services = mock(Services.class);
        when(services.getPlatformAdapter()).thenReturn(platform);
        when(services.getPermissionsHandler()).thenReturn(permissions);
        when(services.getCommandToggleStore()).thenReturn(toggles);
        new FlyCommand().registerCommands(manager, null, services);
        HomeCommand home = new HomeCommand();
        home.onLoad(null, services, null);
        home.registerCommands(manager, null, services);
        EntityPlayerMP player = player(false);
        for (String name : List.of("fly", "home")) {
            assertTrue(permissions.addPermissionToPlayer(player.getUniqueID(), "paradigm." + name, false));
            ICommand command = (ICommand) manager.getCommands().get(name);
            assertEquals("", manager.wrappers.get(name).getPermission());
            assertTrue(command.canCommandSenderUseCommand(player));
            assertTrue(permissions.removePermissionFromPlayer(player.getUniqueID(), "paradigm." + name));
            assertTrue(permissions.addPermissionToPlayer(player.getUniqueID(), "paradigm." + name, true));
            // /home admits players at the root and checks HOME_USE in its executor;
            // /fly checks its permission in the root predicate.
            assertEquals(name.equals("home"), command.canCommandSenderUseCommand(player));
            manager.forward(name, player);
            verify(services, never()).getStorageService();
        }
        assertFalse(player.canCommandSenderUseCommand(2, "paradigm"));
    }

    @Test void aliasesAndDisabledInternalPermissionsKeepExistingFallbacks() {
        SimulatedCrucible manager = hybrid();
        for (String alias : List.of("gamemode", "gmc", "creative")) {
            platform.registerCommand(root(alias));
            assertEquals("", manager.wrappers.get(alias).getPermission());
        }
        MainConfigHandler.getConfig().internalPermissionsEnable.value = false;
        platform.registerCommand(root("fly"));
        manager.forward("fly", player(false));
        assertEquals(0, executions.get());
        manager.forward("fly", player(true));
        assertEquals(1, executions.get());
    }

    @Test void nestedRequirementsAndRequirementFailuresRemainClosed() {
        SimulatedCrucible manager = hybrid();
        EntityPlayerMP player = player(false);
        platform.registerCommand(new ForgeCommandBuilder().literal("nested")
                .then(new ForgeCommandBuilder().literal("denied").requires(source -> false)
                        .executes(ctx -> executions.incrementAndGet()))
                .then(new ForgeCommandBuilder().literal("failure").requires(source -> {
                    throw new IllegalStateException("failed requirement");
                }).executes(ctx -> executions.incrementAndGet())));
        manager.forward("nested", player, "denied");
        manager.forward("nested", player, "failure");
        assertEquals(0, executions.get());
    }

    @Test void standardForgeAndUnavailableOrFailedHybridApiKeepNativeRegistration() {
        CommandHandler vanilla = bind(new CommandHandler());
        platform.registerCommand(root("fly"));
        assertTrue(vanilla.getCommands().get("fly") instanceof ForgeCommand);

        SimulatedCrucible unavailable = bind(new SimulatedCrucible());
        Bukkit.server = new Object(); // No optional CraftServer API.
        platform.registerCommand(root("fly"));
        assertEquals(ForgeCommand.class.getName(), unavailable.wrappers.get("fly").getPermission());

        SimulatedCrucible failed = hybrid();
        failed.failOpen = true;
        platform.registerCommand(root("fly"));
        assertTrue(failed.getCommands().get("fly") instanceof ForgeCommand);
        assertEquals(ForgeCommand.class.getName(), failed.wrappers.get("fly").getPermission());
    }

    @Test void collisionsAndForeignWrappersAreNotRelaxed() {
        SimulatedCrucible manager = hybrid();
        ICommand external = external("fly");
        manager.registerCommand(external);
        String originalPermission = manager.wrappers.get("fly").getPermission();
        platform.registerCommand(root("fly"));
        assertSame(external, manager.getCommands().get("fly"));
        assertEquals(originalPermission, manager.wrappers.get("fly").getPermission());
        assertEquals(1, manager.registrations);

        ModCustomCommand foreign = new ModCustomCommand("home", "other.mod.permission");
        manager.wrappers.put("other:home", foreign);
        platform.registerCommand(root("home"));
        assertEquals("other.mod.permission", foreign.getPermission());
        assertEquals("", manager.wrappers.get("home").getPermission());
    }

    @Test void sharedRootsRefreshAndReregistrationReopenOnlyOwnedWrappers() {
        SimulatedCrucible manager = hybrid();
        platform.registerCommandContributor("fly-owner", () -> platform.registerCommand(root("fly")));
        ICommand first = (ICommand) manager.getCommands().get("fly");
        platform.registerCommand(root("fly"));
        assertSame(first, manager.getCommands().get("fly"));
        assertEquals(1, manager.registrations);
        assertTrue(platform.unregisterCommandRoot("fly"));
        assertEquals(ForgeCommand.class.getName(), manager.wrappers.get("fly").getPermission());
        platform.refreshRegisteredCommandContributors();
        assertNotSame(first, manager.getCommands().get("fly"));
        assertEquals("", manager.wrappers.get("fly").getPermission());
        assertEquals("", manager.wrappers.get("mod:fly").getPermission());
        platform.clear();
        assertEquals(ForgeCommand.class.getName(), manager.wrappers.get("fly").getPermission());
    }

    @Test void priorityAndDisableRestoreExternalCommandWithoutLeavingAnOpenGate() {
        SimulatedCrucible manager = hybrid();
        ICommand external = external("fly");
        manager.registerCommand(external);
        MainConfigHandler.getConfig().forceCommandPriorityEnable.value = true;
        platform.registerCommandContributor("owner", () -> platform.registerCommand(root("fly")));
        assertTrue(platform.ownsRegisteredCommandRoot("fly"));
        assertEquals("", manager.wrappers.get("mod:fly").getPermission());
        toggles.setEnabled("fly", false);
        platform.refreshRegisteredCommandContributors();
        assertSame(external, manager.getCommands().get("fly"));
        assertEquals(ForgeCommand.class.getName(), manager.wrappers.get("mod:fly").getPermission());
        manager.forward("mod:fly", player(false));
        verify(external, never()).processCommand(any(), any());
    }

    @Test void tpClaimsPrimaryBukkitRootAndReleasesItOnDisableWithoutGrantingForeignPermissions() {
        SimulatedCrucible manager = hybrid();
        Object vanilla = new Object();
        manager.primary.knownCommands.put("tp", vanilla);
        manager.primary.knownCommands.put("bukkit:tp", vanilla);
        EntityPlayerMP player = player(false);
        assertTrue(permissions.addPermissionToPlayer(player.getUniqueID(), "paradigm.tp", false));
        MainConfigHandler.getConfig().forceCommandPriorityEnable.value = true;
        platform.registerCommandContributor("tp-owner", () -> platform.registerCommand(root("tp")));
        assertFalse(manager.primary.knownCommands.containsKey("tp"));
        assertSame(vanilla, manager.primary.knownCommands.get("bukkit:tp"));
        manager.forward("tp", player);
        assertEquals(1, executions.get());
        assertTrue(permissions.addPermissionToPlayer(player.getUniqueID(), "paradigm.tp", true));
        manager.forward("tp", player);
        assertEquals(1, executions.get());
        toggles.setEnabled("tp", false);
        platform.refreshRegisteredCommandContributors();
        assertSame(vanilla, manager.primary.knownCommands.get("tp"));
        assertEquals(ForgeCommand.class.getName(), manager.wrappers.get("tp").getPermission());
    }

    @Test void tpPreservesPrimaryBukkitRootWithoutPriorityAndPreservesALaterReplacement() {
        SimulatedCrucible manager = hybrid();
        Object vanilla = new Object();
        manager.primary.knownCommands.put("tp", vanilla);
        platform.registerCommand(root("tp"));
        assertSame(vanilla, manager.primary.knownCommands.get("tp"));
        assertFalse(platform.ownsRegisteredCommandRoot("tp"));
        MainConfigHandler.getConfig().forceCommandPriorityEnable.value = true;
        platform.registerCommand(root("tp"));
        Object later = new Object();
        manager.primary.knownCommands.put("tp", later);
        platform.unregisterCommandRoot("tp");
        assertSame(later, manager.primary.knownCommands.get("tp"));
    }

    @Test void shutdownRestoresPrimaryBukkitRoot() {
        SimulatedCrucible manager = hybrid();
        Object vanilla = new Object();
        manager.primary.knownCommands.put("tp", vanilla);
        MainConfigHandler.getConfig().forceCommandPriorityEnable.value = true;
        platform.registerCommand(root("tp"));
        platform.clear();
        assertSame(vanilla, manager.primary.knownCommands.get("tp"));
    }

    @Test void tpWithoutForcedPriorityPreservesVanillaOwnership() {
        SimulatedCrucible manager = hybrid();
        ICommand vanilla = external("tp");
        manager.registerCommand(vanilla);
        platform.registerCommand(root("tp"));
        assertSame(vanilla, manager.getCommands().get("tp"));
        assertFalse(platform.ownsRegisteredCommandRoot("tp"));
    }

    @Test void tpPriorityAndDisableRestoreVanillaCommandWithoutLeavingAnOpenGate() {
        SimulatedCrucible manager = hybrid();
        ICommand external = external("tp");
        manager.registerCommand(external);
        MainConfigHandler.getConfig().forceCommandPriorityEnable.value = true;
        platform.registerCommandContributor("owner", () -> platform.registerCommand(root("tp")));
        assertTrue(platform.ownsRegisteredCommandRoot("tp"));
        assertEquals("", manager.wrappers.get("mod:tp").getPermission());
        toggles.setEnabled("tp", false);
        platform.refreshRegisteredCommandContributors();
        assertSame(external, manager.getCommands().get("tp"));
        assertEquals(ForgeCommand.class.getName(), manager.wrappers.get("mod:tp").getPermission());
        manager.forward("mod:tp", player(false));
        verify(external, never()).processCommand(any(), any());
    }

    @Test void failedGateRestorationPreventsNativeOwnershipHandoff() {
        SimulatedCrucible manager = hybrid();
        platform.registerCommand(root("fly"));
        ICommand command = (ICommand) manager.getCommands().get("fly");
        manager.wrappers.get("fly").failRestore = true;
        assertThrows(IllegalStateException.class, () -> platform.unregisterCommandRoot("fly"));
        assertSame(command, manager.getCommands().get("fly"));
        manager.wrappers.get("fly").failRestore = false;
    }

    @Test void lateNativeReplacementCannotInheritTheUnrestrictedBukkitGate() {
        SimulatedCrucible manager = hybrid();
        platform.registerCommand(root("fly"));
        ICommand external = external("fly");
        manager.registerCommand(external);
        EntityPlayerMP player = player(false);
        manager.forward("fly", player);
        verify(external, never()).processCommand(any(), any());
        when(external.canCommandSenderUseCommand(player)).thenReturn(true);
        manager.forward("fly", player);
        verify(external).processCommand(eq(player), any());
    }

    @Test void laterPluginPermissionChangesArePreserved() {
        SimulatedCrucible manager = hybrid();
        platform.registerCommand(root("fly"));
        ModCustomCommand wrapper = manager.wrappers.get("fly");
        wrapper.setPermission("plugin.explicit.gate");
        assertTrue(platform.unregisterCommandRoot("fly"));
        assertEquals("plugin.explicit.gate", wrapper.getPermission());
    }

    @Test void lateAliasReplacementCannotInheritAnOpenGateEither() {
        SimulatedCrucible manager = hybrid();
        platform.registerCommand(root("fly"));
        ICommand external = external("other-root");
        manager.getCommands().put("fly", external);
        manager.forward("fly", player(false));
        verify(external, never()).processCommand(any(), any());
    }

    @Test void legacyPlaytimeUsesNativeHybridRootAndRechecksOperatorAndStoragePermission() {
        SimulatedCrucible manager = hybrid();
        Services services = mock(Services.class);
        when(services.getPlatformAdapter()).thenReturn(platform);
        when(services.getPermissionsHandler()).thenReturn(permissions);
        var importer = new eu.avalanche7.paradigm.modules.playtime.LegacyPlaytimeCommand(services);
        platform.registerCommandContributor(importer, importer::register);
        ICommand command = (ICommand) manager.getCommands().get("paradigm");
        assertNotNull(command);
        assertEquals("", manager.wrappers.get("paradigm").getPermission());
        EntityPlayerMP player = player(false);
        assertFalse(command.addTabCompletionOptions(player, new String[]{""}).contains("importplaytime"));
        assertTrue(permissions.addPermissionToPlayer(player.getUniqueID(), "paradigm.storage.manage", false));
        assertFalse(command.addTabCompletionOptions(player, new String[]{""}).contains("importplaytime"));
        when(player.canCommandSenderUseCommand(2, "paradigm")).thenReturn(true);
        assertTrue(command.addTabCompletionOptions(player, new String[]{""}).contains("importplaytime"));
        assertEquals(List.of("confirm"), command.addTabCompletionOptions(player, new String[]{"importplaytime", ""}));
        assertTrue(permissions.addPermissionToPlayer(player.getUniqueID(), "paradigm.storage.manage", true));
        assertFalse(command.addTabCompletionOptions(player, new String[]{""}).contains("importplaytime"));
        manager.forward("paradigm", player, "importplaytime", "confirm");
        verify(services, never()).getStorageService();
        assertTrue(command.addTabCompletionOptions((MinecraftServer) platform.getMinecraftServer(), new String[]{""}).contains("importplaytime"));
        platform.refreshRegisteredCommandContributors();
        command = (ICommand) manager.getCommands().get("paradigm");
        assertEquals(List.of("confirm"), command.addTabCompletionOptions((MinecraftServer) platform.getMinecraftServer(), new String[]{"importplaytime", ""}));
    }

    @Test void playtimeUsesLocalTotalsAndReservesNetworkWithoutLosingPlayerLookupOrPermissions() {
        SimulatedCrucible manager = hybrid();
        Services services = mock(Services.class);
        var storage = mock(eu.avalanche7.paradigm.storage.StorageService.class);
        var profiles = mock(eu.avalanche7.paradigm.modules.profile.PlayerProfileService.class);
        var repo = mock(eu.avalanche7.paradigm.storage.repository.PlayerRepository.class);
        var playtime = mock(eu.avalanche7.paradigm.modules.playtime.PlaytimeService.class);
        var messenger = mock(eu.avalanche7.paradigm.core.PlayerMessenger.class);
        when(services.getPlatformAdapter()).thenReturn(platform);
        when(services.getPermissionsHandler()).thenReturn(permissions);
        when(services.getCommandToggleStore()).thenReturn(toggles);
        when(services.getStorageService()).thenReturn(storage);
        when(services.getPlayerProfileService()).thenReturn(profiles);
        when(services.getPlaytimeService()).thenReturn(playtime);
        when(services.getPlayerMessenger()).thenReturn(messenger);
        when(storage.context()).thenReturn(new eu.avalanche7.paradigm.storage.identity.StorageContext(new eu.avalanche7.paradigm.storage.identity.ServerIdentity("test", "gtnh", "GTNH")));
        when(storage.players()).thenReturn(repo);
        EntityPlayerMP player = player(true);
        String uuid = player.getUniqueID().toString();
        var profile = new eu.avalanche7.paradigm.storage.model.StoredPlayerProfile(uuid, "Player", 100, 200, 3_600_000);
        var state = new eu.avalanche7.paradigm.storage.model.StoredPlayerProfileState(profile, Map.of(), 3_540_000,
                Map.of("gtnh", new eu.avalanche7.paradigm.storage.model.StoredServerPlaytime(uuid, "gtnh", 60_000, 100, 200)), Map.of());
        when(profiles.findByNameOrUuid(anyString())).thenReturn(java.util.Optional.of(profile));
        when(repo.readPlaytime(uuid)).thenReturn(state);
        when(playtime.onlinePlaytimeMs(any())).thenReturn(60_000L);
        when(playtime.sessionMs(any())).thenReturn(10_000L);
        when(playtime.isPlaytimeLoaded(anyString())).thenReturn(true);
        when(playtime.recoveryStatus(any())).thenReturn("available");
        List<String> messages = new ArrayList<>();
        doAnswer(call -> {
            String rendered = call.getArgument(3);
            String[] tokens = (String[]) call.getRawArguments()[4];
            for (int i = 0; i < tokens.length; i += 2) rendered = rendered.replace(tokens[i], tokens[i + 1]);
            messages.add(rendered); return null;
        }).when(messenger).sendDecorated(any(), anyString(), anyString(), anyString(), any(String[].class));
        doAnswer(call -> {
            ((java.util.function.Consumer<Object>) call.getArgument(3)).accept(((java.util.function.Supplier<?>) call.getArgument(1)).get());
            return null;
        }).when(storage).runAsync(anyString(), any(), any(), any(), any());
        var captured = spy(platform);
        when(services.getPlatformAdapter()).thenReturn(captured);
        new eu.avalanche7.paradigm.modules.playtime.PlaytimeModule().registerCommands(manager, null, services);
        var commandTree = org.mockito.ArgumentCaptor.forClass(eu.avalanche7.paradigm.platform.Interfaces.ICommandBuilder.class);
        verify(captured, times(2)).registerCommand(commandTree.capture());
        var childNames = new java.util.HashSet<String>();
        for (var child : ((ForgeCommandBuilder) commandTree.getValue()).children()) {
            String name = child.literalName() != null ? child.literalName() : child.argumentName();
            assertTrue(childNames.add(name), "Brigadier sibling names must be unique: " + name);
        }
        assertTrue(childNames.containsAll(List.of("network", "player", "target")));
        manager.forward("playtime", player);
        assertTrue(messages.get(0).contains("this server"));
        manager.forward("playtime", player, "network");
        assertTrue(messages.get(1).contains("across the network"));
        assertTrue(messages.get(1).contains("unattributed legacy"));
        verify(profiles).findByNameOrUuid(uuid);
        manager.forward("playtime", player, "player", "network");
        verify(profiles).findByNameOrUuid("network");
        assertTrue(messages.get(2).contains("this server"));
        manager.forward("playtime", player, "SomePlayer");
        verify(profiles).findByNameOrUuid("SomePlayer");
        int before = messages.size();
        assertTrue(permissions.addPermissionToPlayer(player.getUniqueID(), "paradigm.playtime.others", true));
        manager.forward("playtime", player, "network", "OtherPlayer");
        manager.forward("playtime", player, "SomePlayer");
        assertEquals(before, messages.size());
        manager.forward("playtime", player, "network");
        assertEquals(before + 1, messages.size());
        when(playtime.isPlaytimeLoaded(anyString())).thenReturn(false);
        when(playtime.recoveryStatus(any())).thenReturn("sql_unavailable");
        when(playtime.recoverySummary(any())).thenReturn("sql_unavailable. Startup journal verification is incomplete.");
        manager.forward("playtime", player);
        assertTrue(messages.get(messages.size() - 1).contains("Historical playtime is unavailable"));
        manager.forward("playtime", player, "status");
        assertTrue(messages.get(messages.size() - 1).contains("sql_unavailable"));
        int afterStatus = messages.size();
        assertTrue(permissions.addPermissionToPlayer(player.getUniqueID(), "paradigm.playtime", true));
        manager.forward("playtime", player);
        manager.forward("playtime", player, "network");
        assertEquals(afterStatus, messages.size());
    }

    @Test void explicitSubcommandGrantOnlyDelegatesThatPathAndKeepsModChecks() {
        SimulatedCrucible manager = hybrid();
        EntityPlayerMP player = player(false);
        TeamTree team = new TeamTree("team", List.of("t"));
        ICommand create = external("create");
        when(create.getCommandAliases()).thenReturn(List.of("new"));
        when(create.canCommandSenderUseCommand(player)).thenReturn(true);
        ICommand kick = external("kick");
        team.addSubcommand(create);
        team.addSubcommand(kick);
        manager.registerCommand(team, "serverutils.command.team.CmdTeam");

        manager.forward("team", player, "create", "Example");
        verify(create, never()).processCommand(any(), any(String[].class));
        assertTrue(permissions.addPermissionToGroup("default", "command.team.create", false));
        manager.forward("team", player, "create", "Example");
        manager.forward("t", player, "new", "Example");
        manager.forward("mod:t", player, "new", "Example");
        manager.forward("team", player, "kick", "Example");
        verify(create, times(3)).processCommand(eq(player), any(String[].class));
        verify(kick, never()).processCommand(any(), any(String[].class));
        assertEquals("serverutils.command.team.CmdTeam", manager.wrappers.get("team").getPermission());

        assertTrue(permissions.addPermissionToPlayer(player.getUniqueID(), "command.team.kick", false));
        manager.forward("team", player, "kick", "Example");
        verify(kick).canCommandSenderUseCommand(player);
        verify(kick, never()).processCommand(any(), any(String[].class));
    }

    @Test void canonicalDeniesApplyToOperatorsThroughBothKindsOfAliases() {
        SimulatedCrucible manager = hybrid();
        EntityPlayerMP op = player(true);
        TeamTree team = new TeamTree("team", List.of("t"));
        TeamTree settings = new TeamTree("settings", List.of("s"));
        ICommand edit = external("edit");
        when(edit.getCommandAliases()).thenReturn(List.of("e"));
        when(edit.canCommandSenderUseCommand(op)).thenReturn(true);
        settings.addSubcommand(edit);
        team.addSubcommand(settings);
        manager.registerCommand(team);
        assertTrue(permissions.addPermissionToPlayer(op.getUniqueID(), "command.team.settings.edit", true));
        manager.forward("team", op, "settings", "edit");
        manager.forward("t", op, "s", "e");
        manager.forward("mod:t", op, "s", "e");
        manager.executeCommand(op, "t s e");
        verify(edit, never()).processCommand(any(), any(String[].class));
    }

    @Test void undefinedAndOriginalWrapperPermissionsFollowTheConfiguredMode() {
        SimulatedCrucible manager = hybrid();
        ICommand external = external("ordinary");
        manager.registerCommand(external, "mod.original");
        EntityPlayerMP player = player(false);
        manager.nativePermission = true;
        manager.forward("ordinary", player);
        verify(external).processCommand(eq(player), any(String[].class));

        MainConfigHandler.getConfig().externalCommandPermissionMode.value = "strict";
        manager.forward("ordinary", player);
        verify(external, times(1)).processCommand(eq(player), any(String[].class));
        EntityPlayerMP op = player(true);
        manager.forward("ordinary", op);
        verify(external).processCommand(eq(op), any(String[].class));

        manager.nativePermission = false;
        when(external.canCommandSenderUseCommand(player)).thenReturn(true);
        assertTrue(permissions.addPermissionToPlayer(player.getUniqueID(), "mod.original", false));
        manager.forward("ordinary", player);
        verify(external, times(2)).processCommand(eq(player), any(String[].class));

        assertTrue(permissions.addPermissionToPlayer(op.getUniqueID(), "mod.original", true));
        manager.forward("ordinary", op);
        verify(external, times(1)).processCommand(eq(op), any(String[].class));
        MainConfigHandler.getConfig().externalCommandPermissionsEnable.value = false;
        manager.forward("ordinary", op);
        verify(external, times(2)).processCommand(eq(op), any(String[].class));
    }

    @Test void directPlayerExecutionUsesTheNormalRouteAndConsoleStillRuns() {
        SimulatedCrucible manager = hybrid();
        ICommand restricted = external("restricted");
        manager.registerCommand(restricted);
        EntityPlayerMP player = player(false);
        var connection = mock(NetHandlerPlayServer.class,
                withSettings().extraInterfaces(PlayerCommandAccess.class));
        player.playerNetServerHandler = connection;
        doAnswer(call -> {
            manager.forward("restricted", player);
            return null;
        }).when((PlayerCommandAccess) connection).paradigm$handleSlashCommand("/restricted");

        platform.executeCommandAs(new MinecraftCommandSource(player), "/restricted");
        verify((PlayerCommandAccess) connection).paradigm$handleSlashCommand("/restricted");
        verify(restricted, never()).processCommand(any(), any(String[].class));
        manager.executeCommand(player, "restricted");
        verify(restricted, never()).processCommand(any(), any(String[].class));
        platform.executeCommandAsConsole("restricted");
        verify(restricted).processCommand(eq((MinecraftServer) platform.getMinecraftServer()), any(String[].class));
    }

    @Test void nestedNativeExecutionAndRewrittenArgumentsCannotReuseAWrapperGrant() {
        SimulatedCrucible manager = hybrid();
        EntityPlayerMP player = player(false);
        TeamTree team = new TeamTree("team", List.of());
        ICommand create = external("create");
        when(create.canCommandSenderUseCommand(player)).thenReturn(true);
        ICommand kick = external("kick");
        when(kick.canCommandSenderUseCommand(player)).thenReturn(true);
        team.addSubcommand(create);
        team.addSubcommand(kick);
        manager.registerCommand(team);
        assertTrue(permissions.addPermissionToPlayer(player.getUniqueID(), "command.team.create", false));

        ICommand restricted = external("restricted");
        manager.registerCommand(restricted);
        doAnswer(call -> manager.executeCommand(player, "restricted"))
                .when(create).processCommand(eq(player), any(String[].class));
        manager.forward("team", player, "create");
        verify(create).processCommand(eq(player), any(String[].class));
        verify(restricted, never()).processCommand(any(), any(String[].class));

        manager.events.onPlayerCommand(event -> manager.currentEvent.parameters[0] = "kick");
        manager.forward("team", player, "create");
        verify(kick, never()).processCommand(any(), any(String[].class));
        verify(create, times(1)).processCommand(eq(player), any(String[].class));
    }

    @Test void pureForgeUsesTheNativeManagerAndCanonicalPermissionGuard() {
        CommandHandler manager = bind(spy(new CommandHandler()));
        ICommand external = external("test-legacy");
        when(external.getCommandAliases()).thenReturn(List.of("test-alias"));
        manager.registerCommand(external);
        EntityPlayerMP player = player(false);

        platform.executeCommandAs(new MinecraftCommandSource(player), "test-alias");
        verify(manager).executeCommand(player, "test-alias");
        verify(external, never()).processCommand(any(), any(String[].class));
        assertTrue(permissions.addPermissionToPlayer(player.getUniqueID(), "command.test-legacy", true));
        assertTrue(CrucibleCommandBridge.post(platform, manager,
                new CommandEvent(external, player, new String[0]), () -> fail("Denied commands must not post events")));
        assertFalse(platform.isCrucibleCommandManager());
    }

    @Test void commandReplacementCannotUseThePreviousWrappersGrant() {
        SimulatedCrucible manager = hybrid();
        EntityPlayerMP player = player(false);
        ICommand first = external("collision");
        manager.registerCommand(first, "mod.old");
        assertTrue(permissions.addPermissionToPlayer(player.getUniqueID(), "mod.old", false));
        when(first.canCommandSenderUseCommand(player)).thenReturn(true);
        manager.forward("collision", player);
        verify(first).processCommand(eq(player), any(String[].class));

        ICommand replacement = external("collision");
        when(replacement.canCommandSenderUseCommand(player)).thenReturn(true);
        manager.registerCommand(replacement, "mod.new");
        manager.forward("collision", player);
        manager.forward("mod:collision", player);
        verify(replacement, never()).processCommand(any(), any(String[].class));
        assertTrue(permissions.addPermissionToPlayer(player.getUniqueID(), "mod.new", false));
        manager.forward("mod:collision", player);
        verify(replacement).processCommand(eq(player), any(String[].class));
    }

    @Test void staleSubcommandAliasesAndChangedBukkitGatesCannotInheritGrants() {
        SimulatedCrucible manager = hybrid();
        EntityPlayerMP player = player(false);
        TeamTree team = new TeamTree("team", List.of());
        ICommand original = external("create");
        when(original.getCommandAliases()).thenReturn(List.of("old"));
        when(original.canCommandSenderUseCommand(player)).thenReturn(true);
        team.addSubcommand(original);
        manager.registerCommand(team);
        assertTrue(permissions.addPermissionToPlayer(player.getUniqueID(), "command.team.create", false));

        ICommand replacement = external("create");
        when(replacement.canCommandSenderUseCommand(player)).thenReturn(true);
        team.addSubcommand(replacement);
        manager.forward("team", player, "old");
        verify(original, never()).processCommand(any(), any(String[].class));
        manager.forward("team", player, "create");
        verify(replacement).processCommand(eq(player), any(String[].class));

        manager.wrappers.get("team").setPermission("plugin.override");
        manager.forward("team", player, "create");
        verify(replacement, times(1)).processCommand(eq(player), any(String[].class));
    }

    @Test void strictModeRequiresAuthorizationForOpaqueCommandNames() {
        SimulatedCrucible manager = hybrid();
        EntityPlayerMP player = player(false);
        ICommand command = external("tools.v1");
        manager.registerCommand(command, "tools.v1.permission");
        manager.nativePermission = true;
        MainConfigHandler.getConfig().externalCommandPermissionMode.value = "strict";

        manager.forward("tools.v1", player);
        verify(command, never()).processCommand(any(), any(String[].class));
        assertTrue(permissions.addPermissionToPlayer(player.getUniqueID(), "tools.v1.permission", true));
        manager.forward("tools.v1", player);
        verify(command, never()).processCommand(any(), any(String[].class));
        assertTrue(permissions.removePermissionFromPlayer(player.getUniqueID(), "tools.v1.permission"));
        assertTrue(permissions.addPermissionToPlayer(player.getUniqueID(), "tools.v1.permission", false));
        manager.forward("tools.v1", player);
        verify(command).processCommand(eq(player), any(String[].class));
    }

    @Test void caseCollidingRootsCannotShareAnExplicitGrant() {
        SimulatedCrucible manager = hybrid();
        EntityPlayerMP player = player(false);
        ICommand lower = external("casecommand");
        ICommand upper = external("CaseCommand");
        when(lower.canCommandSenderUseCommand(player)).thenReturn(true);
        when(upper.canCommandSenderUseCommand(player)).thenReturn(true);
        manager.registerCommand(lower);
        manager.registerCommand(upper);
        assertTrue(permissions.addPermissionToPlayer(player.getUniqueID(), "command.casecommand", false));
        manager.forward("casecommand", player);
        manager.forward("CaseCommand", player);
        verify(lower).processCommand(eq(player), any(String[].class));
        verify(upper, never()).processCommand(any(), any(String[].class));
    }

    @Test void aSlashPrefixedWrapperCannotAuthorizeADifferentNativeCommand() {
        SimulatedCrucible manager = hybrid();
        EntityPlayerMP player = player(false);
        ICommand wrapped = external("/tools");
        ICommand nativeTarget = external("tools");
        when(nativeTarget.canCommandSenderUseCommand(player)).thenReturn(true);
        manager.registerCommand(wrapped, "mod.slash");
        manager.registerCommand(nativeTarget, "mod.tools");
        assertTrue(permissions.addPermissionToPlayer(player.getUniqueID(), "mod.slash", false));

        manager.forward("/tools", player);
        verify(nativeTarget, never()).processCommand(any(), any(String[].class));
    }

    @Test void claimedBareRootKeepsALaterBukkitPermissionOverride() {
        SimulatedCrucible manager = hybrid();
        EntityPlayerMP player = player(false);
        manager.registerCommand(external("tp"), "external.tp");
        MainConfigHandler.getConfig().forceCommandPriorityEnable.value = true;
        platform.registerCommand(root("tp"));
        assertTrue(permissions.addPermissionToPlayer(player.getUniqueID(), "paradigm.tp", false));

        manager.forward("tp", player);
        assertEquals(1, executions.get());
        manager.wrappers.get("tp").setPermission("plugin.tp");
        manager.forward("tp", player);
        assertEquals(1, executions.get());
    }

    @Test void discoveredAnonymousClassNodeCanBeAssignedAndEvaluatedLiterally() {
        SimulatedCrucible manager = hybrid();
        var command = external("tokens");
        manager.registerCommand(command, "network.arcturus.tokens.forge17.ArcturusTokensMod$1");
        assertTrue(platform.discoverCommandPermissions(permissions, platform.getMinecraftServer()) > 0);
        String node = "network.arcturus.tokens.forge17.arcturustokensmod$1";
        var discovered = permissions.listDiscoveredPermissionNodes(node, 10).get(0);
        assertEquals(node, discovered.node);

        EntityPlayerMP player = player(false);
        MainConfigHandler.getConfig().externalCommandPermissionMode.value = "strict";
        when(command.canCommandSenderUseCommand(player)).thenReturn(true);
        manager.forward("tokens", player, "arbitrary");
        verify(command, never()).processCommand(eq(player), any(String[].class));

        Services services = mock(Services.class);
        when(services.getPermissionsHandler()).thenReturn(permissions);
        var admin = new PermissionAdminService(services, null);
        var actor = new DashboardPrincipal(null, "Console", true);
        var request = new PermissionMutationRequest();
        request.action = "user_permission_add";
        request.user = player.getUniqueID().toString();
        request.permission = discovered.node;
        assertTrue(admin.mutate(actor, request).applied());
        assertEquals(Boolean.TRUE, permissions.queryDefinedPermission(player.getUniqueID(), node));
        assertNull(permissions.queryDefinedPermission(player.getUniqueID(), node.replace("$", "")));
        manager.forward("tokens", player, "arbitrary");
        verify(command).processCommand(eq(player), any(String[].class));
        assertTrue(permissions.listDiscoveredPermissionNodes("command.tokens.arbitrary", 10).isEmpty());

        request.action = "user_permission_remove";
        assertTrue(admin.mutate(actor, request).applied());
        assertNull(permissions.queryDefinedPermission(player.getUniqueID(), node));
    }

    @Test void discoveryUsesCanonicalPathsAndRefreshesOnlyChangedNodes() {
        SimulatedCrucible manager = hybrid();
        TeamTree team = new TeamTree("team", List.of("t"));
        ICommand create = external("create");
        when(create.getCommandAliases()).thenReturn(List.of("new"));
        team.addSubcommand(create);
        manager.registerCommand(team, "serverutils.command.team.CmdTeam");
        manager.registerCommand(external("opaque"));

        assertTrue(platform.discoverCommandPermissions(permissions, platform.getMinecraftServer()) > 0);
        var nodes = permissions.listDiscoveredPermissionNodes("", 1000).stream()
                .collect(java.util.stream.Collectors.toMap(node -> node.node, node -> node));
        assertTrue(nodes.get("command.team").description.contains("aliases: mod:team, t"));
        assertTrue(nodes.get("command.team.create").description.contains("aliases: new"));
        assertTrue(nodes.containsKey("serverutils.command.team.cmdteam"));
        assertFalse(nodes.containsKey("command.team.new"));
        assertFalse(nodes.keySet().stream().anyMatch(node -> node.startsWith("command.opaque.")));
        assertEquals(0, platform.discoverCommandPermissions(permissions, platform.getMinecraftServer()));

        manager.getCommands().remove("team");
        manager.getCommands().remove("t");
        assertTrue(platform.discoverCommandPermissions(permissions, platform.getMinecraftServer()) > 0);
        assertTrue(permissions.listDiscoveredPermissionNodes("command.team", 100).isEmpty());
    }

    private static final class TeamTree extends CommandTreeBase {
        private final String name;
        private final List<String> aliases;
        TeamTree(String name, List<String> aliases) {
            this.name = name;
            this.aliases = aliases;
        }
        @Override public String getCommandName() { return name; }
        @Override public List<String> getCommandAliases() { return aliases; }
        @Override public String getCommandUsage(ICommandSender sender) { return name; }
        @Override public int getRequiredPermissionLevel() { return 0; }
    }

    private ForgeCommandBuilder root(String name) {
        return new ForgeCommandBuilder().literal(name)
                .requires(source -> toggles.isEnabled(name) && source.getPlayer() != null
                        && permissions.hasPermission(source.getPlayer(), "paradigm." + name, 2))
                .executes(ctx -> executions.incrementAndGet());
    }

    private EntityPlayerMP player(boolean op) {
        EntityPlayerMP player = mock(EntityPlayerMP.class);
        when(player.getUniqueID()).thenReturn(UUID.randomUUID());
        when(player.getCommandSenderName()).thenReturn("Player");
        when(player.canCommandSenderUseCommand(eq(0), anyString())).thenReturn(true);
        when(player.canCommandSenderUseCommand(2, "paradigm")).thenReturn(op);
        return player;
    }

    private ICommand external(String name) {
        ICommand command = mock(ICommand.class);
        when(command.getCommandName()).thenReturn(name);
        when(command.getCommandAliases()).thenReturn(List.of());
        return command;
    }

    private <T extends CommandHandler> T bind(T manager) {
        MinecraftServer server = mock(MinecraftServer.class);
        when(server.getCommandManager()).thenReturn(manager);
        ServerConfigurationManager players = mock(ServerConfigurationManager.class);
        ReflectionHelper.setPrivateValue(ServerConfigurationManager.class, players, new ArrayList<>(), "playerEntityList");
        when(server.getConfigurationManager()).thenReturn(players);
        platform.setMinecraftServer(server);
        return manager;
    }

    private SimulatedCrucible hybrid() {
        SimulatedCrucible manager = bind(new SimulatedCrucible());
        manager.platform = platform;
        manager.events = platform.events();
        Bukkit.server = manager;
        return manager;
    }

    /** Mirrors class-name registration, Bukkit collisions, and forwarding without the native gate. */
    public static final class SimulatedCrucible extends CommandHandler {
        final PrimaryBukkitCommandMap primary = new PrimaryBukkitCommandMap();
        public PrimaryBukkitCommandMap getCommandMap() { return primary; }
        final Map<String, ModCustomCommand> wrappers = new LinkedHashMap<>();
        int registrations;
        boolean failOpen;
        boolean bridge = true;
        boolean nativePermission;
        PlatformAdapterImpl platform;
        MinecraftEventSystem events;
        CommandEvent currentEvent;

        public BukkitCommandMap getCraftCommandMap() { return new BukkitCommandMap(wrappers); }

        @Override public ICommand registerCommand(ICommand command) {
            return registerCommand(command, command.getClass().getName());
        }

        public ICommand registerCommand(ICommand command, String permission) {
            registrations++;
            ModCustomCommand wrapper = new ModCustomCommand(command.getCommandName(), permission);
            wrapper.failOpen = failOpen;
            String name = command.getCommandName();
            wrappers.putIfAbsent(name, wrapper);
            wrappers.put("mod:" + name, wrapper);
            if (command.getCommandAliases() != null) {
                for (Object alias : command.getCommandAliases()) {
                    wrappers.putIfAbsent((String) alias, wrapper);
                    wrappers.put("mod:" + alias, wrapper);
                }
            }
            CrucibleCommandBridge.bind(wrapper, command, permission, "mod");
            return super.registerCommand(command);
        }

        void forward(String label, EntityPlayerMP player, String... args) {
            if (primary.knownCommands.containsKey(label)) return;
            ModCustomCommand wrapper = wrappers.get(label);
            if (wrapper == null) return;
            String suffix = args.length == 0 ? "" : " " + String.join(" ", args);
            String nativeLabel = label.contains(":") ? wrapper.getName() : label;
            java.util.function.BooleanSupplier gate = () -> wrapper.getPermission().isEmpty()
                    || nativePermission || player.canCommandSenderUseCommand(2, "paradigm");
            if (bridge && !CrucibleCommandBridge.testWrapper(platform, this, wrapper, player, wrapper.getName(),
                    wrapper.getPermission(), label + suffix, gate)) return;
            if (!bridge && !gate.getAsBoolean()) return;
            executeCommand(player, nativeLabel + suffix);
        }

        @Override
        public int executeCommand(ICommandSender sender, String line) {
            String nativeLine = line.trim();
            if (nativeLine.startsWith("/")) nativeLine = nativeLine.substring(1);
            String[] tokens = nativeLine.split(" ");
            ICommand command = (ICommand) getCommands().get(tokens[0]);
            if (command == null) return 0;
            String[] args = Arrays.copyOfRange(tokens, 1, tokens.length);
            CrucibleCommandBridge.bindInvocation(this, sender, command, args);
            CommandEvent event = new CommandEvent(command, sender, args) {
                @Override public boolean isCancelable() { return true; }
            };
            currentEvent = event;
            if (bridge && CrucibleCommandBridge.post(platform, this, event, () -> {
                if (events != null) events.command(event);
                return event.isCanceled();
            })) return 0;
            command.processCommand(sender, args);
            return 1;
        }
    }

    public static final class PrimaryBukkitCommandMap {
        final Map<String, Object> knownCommands = new LinkedHashMap<>();
    }

    public static final class BukkitCommandMap {
        private final Map<String, ModCustomCommand> wrappers;

        BukkitCommandMap(Map<String, ModCustomCommand> wrappers) { this.wrappers = wrappers; }
        public Collection<ModCustomCommand> getCommands() { return new ArrayList<>(wrappers.values()); }
    }
}
