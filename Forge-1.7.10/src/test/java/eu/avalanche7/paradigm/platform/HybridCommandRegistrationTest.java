package eu.avalanche7.paradigm.platform;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;

import cpw.mods.fml.relauncher.ReflectionHelper;
import net.minecraft.command.CommandHandler;
import net.minecraft.command.ICommand;
import net.minecraft.entity.player.EntityPlayerMP;
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

import eu.avalanche7.paradigm.configs.MainConfigHandler;
import eu.avalanche7.paradigm.core.Services;
import eu.avalanche7.paradigm.modules.commands.FlyCommand;
import eu.avalanche7.paradigm.modules.commands.HomeCommand;
import eu.avalanche7.paradigm.modules.commands.shared.CommandCatalog;
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
        manager.events = platform.events();
        Bukkit.server = manager;
        return manager;
    }

    /** Mirrors class-name registration, Bukkit collisions, and forwarding without the native gate. */
    public static final class SimulatedCrucible extends CommandHandler {
        final Map<String, ModCustomCommand> wrappers = new LinkedHashMap<>();
        int registrations;
        boolean failOpen;
        MinecraftEventSystem events;

        public BukkitCommandMap getCraftCommandMap() { return new BukkitCommandMap(wrappers); }

        @Override public ICommand registerCommand(ICommand command) {
            registrations++;
            ModCustomCommand wrapper = new ModCustomCommand(command.getCommandName(), command.getClass().getName());
            wrapper.failOpen = failOpen;
            wrappers.putIfAbsent(command.getCommandName(), wrapper);
            wrappers.put("mod:" + command.getCommandName(), wrapper);
            return super.registerCommand(command);
        }

        void forward(String label, EntityPlayerMP player, String... args) {
            ModCustomCommand wrapper = wrappers.get(label);
            if (!wrapper.getPermission().isEmpty() && !player.canCommandSenderUseCommand(2, "paradigm")) return;
            ICommand command = (ICommand) getCommands().get(wrapper.getName());
            if (command != null) {
                // Forge transforms @Cancelable at runtime; plain JUnit does not run that transformer.
                CommandEvent event = new CommandEvent(command, player, args) {
                    @Override public boolean isCancelable() { return true; }
                };
                if (events != null) events.command(event);
                if (!event.isCanceled()) command.processCommand(player, args);
            }
        }
    }

    public static final class BukkitCommandMap {
        private final Map<String, ModCustomCommand> wrappers;

        BukkitCommandMap(Map<String, ModCustomCommand> wrappers) { this.wrappers = wrappers; }
        public Collection<ModCustomCommand> getCommands() { return new ArrayList<>(wrappers.values()); }
    }
}
