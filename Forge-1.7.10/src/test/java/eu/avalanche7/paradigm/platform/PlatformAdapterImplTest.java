package eu.avalanche7.paradigm.platform;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.*;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import cpw.mods.fml.relauncher.ReflectionHelper;
import net.minecraft.command.CommandHandler;
import net.minecraft.command.ICommand;
import net.minecraft.entity.player.EntityPlayerMP;
import net.minecraft.event.HoverEvent;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.management.ServerConfigurationManager;
import net.minecraft.util.ChatComponentText;
import net.minecraft.util.ChatStyle;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import eu.avalanche7.paradigm.core.CommandAccess;
import eu.avalanche7.paradigm.core.PlayerMessenger;
import eu.avalanche7.paradigm.core.Services;
import eu.avalanche7.paradigm.modules.commands.admin.SudoCommand;
import eu.avalanche7.paradigm.modules.permissions.PermissionsHandler;
import eu.avalanche7.paradigm.utils.CommandToggleStore;
import eu.avalanche7.paradigm.utils.DebugLogger;
import eu.avalanche7.paradigm.utils.Placeholders;
import eu.avalanche7.paradigm.utils.TaskScheduler;

class PlatformAdapterImplTest {
    private final ForgeConfig config = new ForgeConfig(Path.of("test-config"));
    private final DebugLogger logger = new DebugLogger(null);
    private final PlatformAdapterImpl adapter =
            new PlatformAdapterImpl(null, new Placeholders(), new TaskScheduler(logger), logger, config);
    private MinecraftServer server;
    private ServerConfigurationManager players;
    private CommandHandler commands;
    private EntityPlayerMP online;

    @AfterEach
    void cleanupLookupFixture() {
        if (players != null) players.playerEntityList.clear();
        adapter.clear();
    }

    @Test
    void nativeLookupResolvesTheOnlinePlayerWithTheExistingWrapper() {
        bindOnlinePlayer();
        var resolved = adapter.getPlayerByName("Avalanche7CZ");
        assertInstanceOf(MinecraftPlayer.class, resolved);
        assertSame(online, resolved.getOriginalPlayer());
        verify(players).func_152612_a("Avalanche7CZ");
    }

    @Test
    void nativeLookupIsAlreadyCaseInsensitiveAndExact() {
        bindOnlinePlayer();
        for (String name : List.of("Avalanche7CZ", "avalanche7cz", "AVALANCHE7CZ")) {
            assertSame(online, adapter.getPlayerByName(name).getOriginalPlayer());
        }
        for (String name : List.of("Avalanche", "Avalanche7C", "Avalanche7CZ-extra", "Unknown", "")) {
            assertNull(adapter.getPlayerByName(name));
        }
    }

    @Test
    void offlinePlayersAndAnUnboundServerAreNotResolved() {
        assertNull(adapter.getPlayerByName("Avalanche7CZ"));
        bindOnlinePlayer();
        players.playerEntityList.clear();
        assertNull(adapter.getPlayerByName("Avalanche7CZ"));
    }

    @Test
    void nativeLookupRemainsAuthoritative() {
        bindOnlinePlayer();
        EntityPlayerMP nativeResult = mock(EntityPlayerMP.class);
        doReturn(nativeResult).when(players).func_152612_a("Avalanche7CZ");
        assertSame(nativeResult, adapter.getPlayerByName("Avalanche7CZ").getOriginalPlayer());
    }

    @Test
    void realSudoConsolePathResolvesThePlayerAndDispatchesAsThatPlayer() {
        bindOnlinePlayer();
        PlayerMessenger messenger = registerSudo();
        doReturn(1).when(commands).executeCommand(online, "spawn");
        ICommand sudo = (ICommand) commands.getCommands().get("sudo");
        assertTrue(sudo.canCommandSenderUseCommand(server));
        sudo.processCommand(server, new String[] {"Avalanche7CZ", "spawn"});
        verify(commands).executeCommand(online, "spawn");
        verify(messenger).logToConsole("Admin", "Executed command as {player}.", "{player}", "Avalanche7CZ");
    }

    @Test
    void anInjectedNativeMissReproducesTheReportedParseErrorDespiteAnOnlineListEntry() {
        bindOnlinePlayer();
        PlayerMessenger messenger = registerSudo();
        // Deliberate failure injection, not evidence that Crucible's native method behaves this way.
        doReturn(null).when(players).func_152612_a("Avalanche7CZ");
        assertEquals(1, adapter.getOnlinePlayers().size());
        assertNull(adapter.getPlayerByName("Avalanche7CZ"));
        ICommand sudo = (ICommand) commands.getCommands().get("sudo");
        sudo.processCommand(server, new String[] {"Avalanche7CZ", "spawn"});
        verify(server).addChatMessage(argThat(component -> component.getUnformattedText()
                .equals("§cInvalid or incomplete command: sudo Avalanche7CZ spawn")));
        verify(commands, never()).executeCommand(any(), anyString());
        verifyNoInteractions(messenger);
    }

    private void bindOnlinePlayer() {
        server = mock(MinecraftServer.class);
        // Execute the actual Forge lookup body, without bootstrapping an entire Minecraft server.
        players = mock(ServerConfigurationManager.class, CALLS_REAL_METHODS);
        online = mock(EntityPlayerMP.class);
        when(online.getCommandSenderName()).thenReturn("Avalanche7CZ");
        when(online.getUniqueID()).thenReturn(UUID.randomUUID());
        ReflectionHelper.setPrivateValue(ServerConfigurationManager.class, players,
                new ArrayList<>(List.of(online)), "playerEntityList");
        commands = spy(new CommandHandler());
        when(server.getConfigurationManager()).thenReturn(players);
        when(server.getCommandManager()).thenReturn(commands);
        adapter.setMinecraftServer(server);
    }

    private PlayerMessenger registerSudo() {
        Services services = mock(Services.class);
        CommandToggleStore toggles = mock(CommandToggleStore.class);
        when(toggles.isEnabled("sudo")).thenReturn(true);
        when(services.getCommandAccess()).thenReturn(new CommandAccess(mock(PermissionsHandler.class), toggles));
        when(services.getPlatformAdapter()).thenReturn(adapter);
        PlayerMessenger messenger = mock(PlayerMessenger.class);
        when(services.getPlayerMessenger()).thenReturn(messenger);
        new SudoCommand().registerCommands(commands, null, services);
        return messenger;
    }

    @Test
    void hoverStyleAcceptsPlainTextAndNativeComponents() {
        ChatStyle base = new ChatStyle();
        assertHoverText("plain", adapter.createStyleWithHoverEvent(base, "plain"));
        assertHoverText("native", adapter.createStyleWithHoverEvent(base, new ChatComponentText("native")));
        assertHoverText("wrapped", adapter.createStyleWithHoverEvent(base, new MinecraftComponent("wrapped")));
        assertHoverText("42", adapter.createStyleWithHoverEvent(base, 42));
        assertEquals(null, base.getChatHoverEvent());
    }

    @Test
    void unsupportedCommandTreeSynchronizationIsInert() {
        assertDoesNotThrow(adapter::rewireCommandTreePermissions);
        assertDoesNotThrow(() -> adapter.refreshPlayerCommandTree(null));
        assertDoesNotThrow(adapter::refreshAllPlayerCommandTrees);
        assertDoesNotThrow(() -> adapter.resetPlayerListState(null));
        assertEquals(Path.of("test-config"), adapter.getConfig().getConfigDirectory());
    }

    private static void assertHoverText(String expected, Object style) {
        HoverEvent hover = ((ChatStyle) style).getChatHoverEvent();
        assertEquals(HoverEvent.Action.SHOW_TEXT, hover.getAction());
        assertEquals(expected, hover.getValue().getUnformattedText());
    }
}
