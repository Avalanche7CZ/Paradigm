package eu.avalanche7.paradigm.platform;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.function.Consumer;

import com.mojang.authlib.GameProfile;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.slf4j.LoggerFactory;

import eu.avalanche7.paradigm.configs.MOTDConfigHandler;
import eu.avalanche7.paradigm.configs.MainConfigHandler;
import eu.avalanche7.paradigm.core.CommandAccess;
import eu.avalanche7.paradigm.core.Services;
import eu.avalanche7.paradigm.modules.Announcements;
import eu.avalanche7.paradigm.modules.Restart;
import eu.avalanche7.paradigm.modules.chat.MOTD;
import eu.avalanche7.paradigm.modules.commands.admin.VanishCommand;
import eu.avalanche7.paradigm.modules.permissions.PermissionsHandler;
import eu.avalanche7.paradigm.modules.tab.Tablist;
import eu.avalanche7.paradigm.platform.Interfaces.ICommandSource;
import eu.avalanche7.paradigm.platform.Interfaces.IPlatformAdapter;
import eu.avalanche7.paradigm.platform.Interfaces.IPlayer;
import eu.avalanche7.paradigm.storage.StorageService;
import eu.avalanche7.paradigm.storage.repository.AdminStateRepository;
import eu.avalanche7.paradigm.utils.CommandToggleStore;
import eu.avalanche7.paradigm.utils.DebugLogger;
import eu.avalanche7.paradigm.utils.MessageParser;
import eu.avalanche7.paradigm.utils.TaskScheduler;

class VisualModulesTest {
    @TempDir Path directory;
    Services services;
    IPlatformAdapter platform;
    IPlayer player;
    StorageService storage;
    AdminStateRepository state;
    List<ForgeCommandBuilder> commands;
    PermissionsHandler permissions;
    CommandToggleStore toggles;

    @BeforeEach void setup() {
        MainConfigHandler.init(new ForgeConfig(directory), new DebugLogger(null));
        MainConfigHandler.getConfig().adminUtilityCommandsEnable.value = true;
        services = mock(Services.class);
        platform = mock(IPlatformAdapter.class);
        player = mock(IPlayer.class);
        when(player.getUUID()).thenReturn(UUID.randomUUID().toString());
        when(player.getName()).thenReturn("Proof");
        when(platform.getOnlinePlayers()).thenReturn(List.of(player));
        when(platform.getPlayerByUuid(player.getUUID())).thenReturn(player);
        when(platform.createLiteralComponent(anyString())).thenAnswer(i -> new MinecraftComponent(i.getArgument(0, String.class)));
        when(platform.createComponentFromLiteral(anyString())).thenAnswer(i -> new MinecraftComponent(i.getArgument(0, String.class)));
        when(platform.createEmptyComponent()).thenAnswer(i -> new MinecraftComponent(""));
        when(platform.createCommandBuilder()).thenAnswer(i -> new ForgeCommandBuilder());
        when(services.getPlatformAdapter()).thenReturn(platform);
        when(services.getMainConfig()).thenReturn(MainConfigHandler.getConfig());
        when(services.getDebugLogger()).thenReturn(new DebugLogger(null));
        when(services.getLogger()).thenReturn(LoggerFactory.getLogger("visual-modules"));
        when(services.getTaskScheduler()).thenReturn(mock(TaskScheduler.class));
        MessageParser parser = mock(MessageParser.class);
        when(parser.parseMessage(anyString(), any())).thenAnswer(i -> new MinecraftComponent(i.getArgument(0, String.class).replace("{player}", "Proof")));
        when(services.getMessageParser()).thenReturn(parser);
        permissions = new PermissionsHandler(LoggerFactory.getLogger("visual-permissions"), null, new DebugLogger(null), platform, null, null) {
            @Override public boolean hasPermission(IPlayer player, String permission) { return false; }
            @Override public boolean hasPermission(IPlayer player, eu.avalanche7.paradigm.modules.permissions.PermissionDefinition permission) { return false; }
        };
        when(services.getPermissionsHandler()).thenReturn(permissions);
        toggles = mock(CommandToggleStore.class);
        when(toggles.isEnabled(anyString())).thenReturn(true);
        when(services.getCommandToggleStore()).thenReturn(toggles);
        when(services.getCommandAccess()).thenReturn(new CommandAccess(permissions, toggles));
        storage = mock(StorageService.class);
        state = mock(AdminStateRepository.class);
        when(storage.adminState()).thenReturn(state);
        when(services.getStorageService()).thenReturn(storage);
        var vanish = new eu.avalanche7.paradigm.modules.vanish.VanishService(services);
        when(services.getVanishService()).thenReturn(vanish);
        when(platform.setPlayerVanished(any(), anyBoolean())).thenReturn(true);
        vanish.enable();
        org.mockito.Mockito.clearInvocations(storage);
        commands = new ArrayList<>();
        doAnswer(i -> { commands.add((ForgeCommandBuilder) i.getArgument(0)); return null; }).when(platform).registerCommand(any());
    }

    @Test void motdDeliversOneMultilineComponentPerJoinAndRespectsDisable() {
        var config = new MOTDConfigHandler.Config();
        config.motdLines = List.of("Hello {player}", "Second line");
        when(services.getMotdConfig()).thenReturn(config);
        MainConfigHandler.getConfig().motdEnable.value = true;
        MOTD module = new MOTD();
        module.onLoad(null, services, null);
        module.onPlayerJoin(player);
        verify(platform).sendSystemMessage(eq(player), argThat(message -> message.getRawText().equals("Hello Proof\nSecond line")));
        module.onPlayerJoin(player);
        verify(platform, times(2)).sendSystemMessage(eq(player), any());
        MainConfigHandler.getConfig().motdEnable.value = false;
        module.onPlayerJoin(player);
        verify(platform, times(2)).sendSystemMessage(eq(player), any());
    }

    @Test void serverListMotdIsIndependentOfJoinToggleAndCopiesNativeStatus() {
        var config = new MOTDConfigHandler.Config();
        config.motds.value = List.of(new MOTDConfigHandler.ServerListMOTD("missing", "First", "Second",
                new MOTDConfigHandler.PlayerCountDisplay(null, "Hover one\nHover two", 80, true)));
        when(services.getMotdConfig()).thenReturn(config);
        MainConfigHandler.getConfig().motdEnable.value = false;
        var original = new net.minecraft.network.ServerStatusResponse();
        original.func_151315_a(new net.minecraft.util.ChatComponentText("Original"));
        original.func_151321_a(new net.minecraft.network.ServerStatusResponse.MinecraftProtocolVersionIdentifier("1.7.10", 5));
        original.func_151319_a(new net.minecraft.network.ServerStatusResponse.PlayerCountData(20, 3));
        original.func_151320_a("existing icon");
        var result = eu.avalanche7.paradigm.platform.visual.LegacyServerStatus.customize(original, services);
        assertNotSame(original, result);
        assertEquals("First\nSecond", result.func_151317_a().getUnformattedText());
        assertEquals("Original", original.func_151317_a().getUnformattedText());
        assertSame(original.func_151322_c(), result.func_151322_c());
        assertEquals(80, result.func_151318_b().func_151332_a());
        assertEquals(3, result.func_151318_b().func_151333_b());
        assertEquals("Hover two§r", result.func_151318_b().func_151331_c()[1].getName());
        assertEquals("existing icon", result.func_151316_d());
        original.func_151319_a(null);
        var initial = eu.avalanche7.paradigm.platform.visual.LegacyServerStatus.customize(original, services);
        assertEquals(80, initial.func_151318_b().func_151332_a());
        assertEquals(0, initial.func_151318_b().func_151333_b());
        config.serverlistMotdEnabled.value = false;
        assertSame(original, eu.avalanche7.paradigm.platform.visual.LegacyServerStatus.customize(original, services));
        config.serverlistMotdEnabled.value = true;
        config.motds.value = List.of();
        assertSame(original, eu.avalanche7.paradigm.platform.visual.LegacyServerStatus.customize(original, services));
    }

    @Test void invalidServerListFormattingRetainsNativeStatus() {
        var config = new MOTDConfigHandler.Config();
        when(services.getMotdConfig()).thenReturn(config);
        when(services.getMessageParser().parseMessage(anyString(), any())).thenThrow(new IllegalArgumentException("bad formatting"));
        var original = new net.minecraft.network.ServerStatusResponse();
        assertSame(original, eu.avalanche7.paradigm.platform.visual.LegacyServerStatus.customize(original, services));
    }

    @Test void titleCommandSplitsOnlyTheLiteralDoublePipeDelimiter() {
        Announcements module = new Announcements();
        module.onLoad(null, services, null);
        ICommandSource source = mock(ICommandSource.class);
        assertEquals(1, module.broadcastTitleCmd(new ForgeCommandContext(source,
                Map.of("titleAndSubtitle", "First title || Second subtitle"), "test")));
        verify(platform).sendTitle(eq(player), argThat(c -> c.getRawText().equals("First title")), argThat(c -> c.getRawText().equals("Second subtitle")));
    }

    @Test void tabManagementUsesPermissionDenialEvenForOp() {
        ICommandSource source = mock(ICommandSource.class);
        when(source.getPlayer()).thenReturn(player);
        when(source.hasPermissionLevel(2)).thenReturn(true);
        ForgeCommandBuilder branch = (ForgeCommandBuilder) new Tablist().buildCommandBranch(platform, services);
        assertFalse(branch.requirement().test(source));
        when(source.isConsole()).thenReturn(true);
        assertTrue(branch.requirement().test(source));
    }

    @Test void restartManagementUsesPermissionDenialEvenForOpAndRespectsToggle() {
        when(services.canDispatchModuleEvents(any())).thenReturn(true);
        Restart module = new Restart();
        module.onLoad(null, services, null);
        module.registerCommands(null, null, services);
        ICommandSource source = mock(ICommandSource.class);
        when(source.getPlayer()).thenReturn(player);
        when(source.hasPermissionLevel(2)).thenReturn(true);
        assertFalse(commands.get(0).requirement().test(source));
        when(source.isConsole()).thenReturn(true);
        assertTrue(commands.get(0).requirement().test(source));
        when(toggles.isEnabled("restart")).thenReturn(false);
        assertFalse(commands.get(0).requirement().test(source));
    }

    @Test void admissionLoadWaitsForStorageCallbackAndPropagatesFailure() {
        MinecraftLoginHandler.bind(services);
        try {
            GameProfile profile = new GameProfile(UUID.fromString(player.getUUID()), "Proof");
            var result = MinecraftLoginHandler.loadVanish(profile);
            assertFalse(result.isDone());
            var success = org.mockito.ArgumentCaptor.forClass(Consumer.class);
            var failure = org.mockito.ArgumentCaptor.forClass(Consumer.class);
            verify(storage).runAsync(eq("vanish.admission"), any(), any(), success.capture(), failure.capture());
            failure.getValue().accept(new IllegalStateException("storage unavailable"));
            assertTrue(result.isCompletedExceptionally());
        } finally { MinecraftLoginHandler.clear(); }
    }

    @Test void disablingModuleWhileAdmissionIsPendingMakesRestorationIneligible() {
        MinecraftLoginHandler.bind(services);
        try {
            assertTrue(MinecraftLoginHandler.vanishEnabled());
            MainConfigHandler.getConfig().adminUtilityCommandsEnable.value = false;
            services.getVanishService().disable();
            assertFalse(MinecraftLoginHandler.vanishEnabled());
            assertFalse(MinecraftLoginHandler.loadVanish(new GameProfile(UUID.fromString(player.getUUID()), "Proof")).join().hidden());
            verifyNoInteractions(storage);
        } finally { MinecraftLoginHandler.clear(); }
    }

    @Test void disablingVanishRestoresVisibilityWithoutClearingDurableState() {
        services.getVanishService().toggle(player, () -> true);
        new VanishCommand().onDisable(services);
        verify(platform).setPlayerVanished(player, false);
        verifyNoInteractions(state);
    }

    @Test void pendingVanishEnableCallbackCannotHideAfterModuleDisable() {
        VanishCommand module = new VanishCommand();
        services.getVanishService().disable();
        module.onEnable(services);
        MainConfigHandler.getConfig().adminUtilityCommandsEnable.value = false;
        module.onDisable(services);
        var callback = org.mockito.ArgumentCaptor.forClass(Consumer.class);
        verify(storage).runAsync(eq("vanish.restore"), any(), any(), callback.capture(), any());
        callback.getValue().accept(true);
        verify(platform, never()).setPlayerVanished(any(), anyBoolean());
    }
}
