package eu.avalanche7.paradigm.platform;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.atomic.AtomicReference;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import eu.avalanche7.paradigm.core.Services;
import eu.avalanche7.paradigm.data.PlayerDataStore.StoredLocation;
import eu.avalanche7.paradigm.modules.commands.TpCommand;
import eu.avalanche7.paradigm.modules.commands.TpaCommand;
import eu.avalanche7.paradigm.modules.permissions.ParadigmPermissions;
import eu.avalanche7.paradigm.modules.permissions.PermissionDefinition;
import eu.avalanche7.paradigm.modules.permissions.PermissionsHandler;
import eu.avalanche7.paradigm.platform.Interfaces.*;
import eu.avalanche7.paradigm.storage.StorageService;
import eu.avalanche7.paradigm.storage.repository.PlayerRepository;
import eu.avalanche7.paradigm.utils.CommandToggleStore;
import eu.avalanche7.paradigm.utils.Lang;

class TpCommandTest {
    private final Services services = mock(Services.class);
    private final PlatformAdapterImpl platform = mock(PlatformAdapterImpl.class);
    private final PermissionsHandler permissions = mock(PermissionsHandler.class);
    private final ICommandSource source = mock(ICommandSource.class);
    private final IPlayer self = mock(IPlayer.class);
    private final IPlayer other = mock(IPlayer.class);
    private final Set<PermissionDefinition> grants = new HashSet<>();
    private final AtomicReference<ForgeCommandBuilder> root = new AtomicReference<>();

    @BeforeEach void setup() {
        when(services.getPlatformAdapter()).thenReturn(platform);
        when(services.getPermissionsHandler()).thenReturn(permissions);
        var toggles = mock(CommandToggleStore.class);
        when(services.getCommandToggleStore()).thenReturn(toggles);
        when(toggles.isEnabled("tp")).thenReturn(true);
        Lang lang = mock(Lang.class);
        when(services.getLang()).thenReturn(lang);
        when(lang.getTranslation(anyString())).thenAnswer(i -> i.getArgument(0));
        when(platform.createCommandBuilder()).thenAnswer(i -> new ForgeCommandBuilder());
        doAnswer(i -> { root.set((ForgeCommandBuilder) i.getArgument(0)); return null; }).when(platform).registerCommand(any());
        doAnswer(i -> { ((Runnable) i.getArgument(0)).run(); return null; }).when(platform).executeOnServerThread(any());
        when(source.getPlayer()).thenReturn(self);
        when(self.getUUID()).thenReturn("self-uuid");
        when(other.getUUID()).thenReturn("other-uuid");
        when(permissions.hasPermission(eq(self), any(PermissionDefinition.class))).thenAnswer(i -> grants.contains(i.getArgument(1)));
        when(platform.resolveTeleportPlayer(eq(source), anyString())).thenAnswer(i -> {
            String token = i.getArgument(1);
            return token.equalsIgnoreCase("Self") || token.equals("@s") ? self : token.equalsIgnoreCase("Other") || token.equals("@p") ? other : null;
        });
        when(platform.getPlayerLocation(any())).thenReturn(Optional.of(new StoredLocation("0", 10, 64, -10, 30, 20)));
        when(platform.teleportPlayer(any(), any(StoredLocation.class))).thenReturn(true);
        TpCommand command = new TpCommand();
        command.onLoad(null, services, null);
        command.registerCommands(null, null, services);
    }

    private void run(String args) throws Exception {
        root.get().children().get(0).executor().execute(new ForgeCommandContext(source, java.util.Map.of("arguments", args), "tp " + args));
    }
    private StoredLocation moved() {
        var location = org.mockito.ArgumentCaptor.forClass(StoredLocation.class);
        verify(platform).teleportPlayer(any(), location.capture());
        return location.getValue();
    }
    @Test void journeyMapExplicitSelfAndCapitalizationNeedOnlyCoordinatePermission() throws Exception {
        grants.add(ParadigmPermissions.TP);
        run("sElF 100 70 -200");
        StoredLocation result = moved();
        assertEquals(100.5, result.getX()); assertEquals(-199.5, result.getZ());
        assertEquals("0", result.getWorldId()); assertEquals(30, result.getYaw());
        verify(platform).teleportPlayer(eq(self), any(StoredLocation.class));
    }
    @Test void relativeCoordinatesAndRotationsUseMovingPlayersLocation() throws Exception {
        grants.add(ParadigmPermissions.TP);
        run("~ ~1 ~-2 ~10 ~");
        StoredLocation result = moved();
        assertEquals(10, result.getX()); assertEquals(65, result.getY()); assertEquals(-12, result.getZ());
        assertEquals(40, result.getYaw()); assertEquals(20, result.getPitch());
    }
    @Test void coordinateGrantCannotMoveOthersOrUseSelectors() throws Exception {
        grants.add(ParadigmPermissions.TP);
        for (String input : new String[]{"Other 1 2 3", "OTHER ~ ~ ~", "@s 1 2 3", "@p", "Self Other", "Other Self", "Other 1 2", "1 2 3 trailing", "NaN 1 2", "Infinity 1 2", "30000001 1 2", "1 2 3 4 5 6 7"}) run(input);
        verify(platform, never()).teleportPlayer(any(), any(StoredLocation.class));
    }
    @Test void playerGrantDoesNotGrantCoordinates() throws Exception {
        grants.add(ParadigmPermissions.TP_PLAYER);
        run("1 2 3"); verify(platform, never()).teleportPlayer(any(), any(StoredLocation.class));
        run("Other"); verify(platform).teleportPlayer(eq(self), any(StoredLocation.class));
    }
    @Test void privilegedMovingOtherDoesNotRequireSelfPermission() throws Exception {
        grants.add(ParadigmPermissions.TP_OTHERS);
        run("Other ~ ~ ~"); verify(platform).teleportPlayer(eq(other), any(StoredLocation.class));
    }
    @Test void consoleNeedsExplicitMovingPlayer() throws Exception {
        when(source.getPlayer()).thenReturn(null); when(source.isConsole()).thenReturn(true);
        run("~ ~ ~"); verify(platform, never()).teleportPlayer(any(), any(StoredLocation.class));
        run("Other ~ ~ ~"); verify(platform).teleportPlayer(eq(other), any(StoredLocation.class));
    }
    @Test void opPermissionsAllowSelectors() throws Exception {
        when(permissions.hasPermission(eq(self), any(PermissionDefinition.class))).thenReturn(true);
        run("@p Self"); verify(platform).teleportPlayer(eq(other), any(StoredLocation.class));
        assertEquals(2, ParadigmPermissions.TP.fallbackLevel());
    }
    @Test void permissionRevocationAndToggleAreCheckedAtExecution() throws Exception {
        run("Self 1 2 3");
        grants.add(ParadigmPermissions.TP);
        when(services.getCommandToggleStore().isEnabled("tp")).thenReturn(false);
        run("Self 1 2 3"); verify(platform, never()).teleportPlayer(any(), any(StoredLocation.class));
    }
    @Test void nativeOpLevelDoesNotOverridePermissionHandlerDenial() throws Exception {
        when(source.hasPermissionLevel(2)).thenReturn(true);
        assertFalse(root.get().requirement().test(source));
        run("Self 1 2 3");
        verify(platform, never()).teleportPlayer(any(), any(StoredLocation.class));
    }
    @Test void commandBlockRequiresNativePrivilege() throws Exception {
        when(source.getPlayer()).thenReturn(null);
        run("Other 1 2 3");
        verify(platform, never()).teleportPlayer(any(), any(StoredLocation.class));
        when(source.hasPermissionLevel(2)).thenReturn(true);
        run("Other 1 2 3");
        verify(platform).teleportPlayer(eq(other), any(StoredLocation.class));
    }

    @Test
    void tpAndTpaUseTheSameDestinationAndTpaCanRetryARejectedTransfer() throws Exception {
        var roots = new HashMap<String, ForgeCommandBuilder>();
        doAnswer(call -> {
            ForgeCommandBuilder command = call.getArgument(0);
            roots.put(command.literalName(), command);
            return null;
        }).when(platform).registerCommand(any());
        when(self.getName()).thenReturn("Self");
        when(other.getName()).thenReturn("Other");
        when(platform.getPlayerByUuid("self-uuid")).thenReturn(self);
        when(platform.getPlayerByUuid("other-uuid")).thenReturn(other);
        when(services.getCommandToggleStore().isEnabled("tpaccept")).thenReturn(true);
        when(services.moduleActivationGuard(any())).thenReturn(() -> true);
        when(permissions.hasPermission(other, ParadigmPermissions.TPA_ACCEPT)).thenReturn(true);
        var parser = mock(eu.avalanche7.paradigm.utils.MessageParser.class);
        when(services.getMessageParser()).thenReturn(parser);
        when(parser.parseMessage(anyString(), any(IPlayer.class))).thenAnswer(call -> new MinecraftComponent(call.getArgument(0, String.class)));
        var storage = mock(StorageService.class);
        var repository = mock(PlayerRepository.class);
        when(services.getStorageService()).thenReturn(storage);
        when(storage.players()).thenReturn(repository);
        when(repository.listIgnoredPlayers("other-uuid")).thenReturn(Set.of());
        doAnswer(call -> {
            call.getArgument(3, java.util.function.Consumer.class).accept(call.getArgument(1, java.util.function.Supplier.class).get());
            return null;
        }).when(storage).runAsync(anyString(), any(), any(), any(), any());

        var origin = new StoredLocation("0", 2, 65, 3, 20, -5);
        var destination = new StoredLocation("-1", 12, 80, 14, 90, 5);
        var position = new AtomicReference<>(origin);
        when(platform.getPlayerLocation(self)).thenAnswer(call -> Optional.of(position.get()));
        when(platform.getPlayerLocation(other)).thenReturn(Optional.of(destination));
        var rejected = new java.util.concurrent.atomic.AtomicBoolean(true);
        when(platform.teleportPlayer(eq(self), any(StoredLocation.class))).thenAnswer(call -> {
            if (rejected.get()) return false;
            position.set(call.getArgument(1));
            return true;
        });
        var sent = new java.util.ArrayList<String>();
        doAnswer(call -> {
            sent.add(call.getArgument(1, IComponent.class).getRawText());
            return null;
        }).when(platform).sendSystemMessage(eq(other), any(IComponent.class));

        var tpa = new TpaCommand();
        tpa.onLoad(null, services, null);
        tpa.registerCommands(null, null, services);
        assertEquals(1, roots.get("tpa").children().get(0).executor()
                .execute(new ForgeCommandContext(source, Map.of("player", other), "tpa Other")));
        var accepter = mock(ICommandSource.class);
        when(accepter.getPlayer()).thenReturn(other);
        var accept = new ForgeCommandContext(accepter, Map.of(), "tpaccept");
        assertEquals(0, roots.get("tpaccept").executor().execute(accept));
        assertSame(origin, position.get());
        assertTrue(sent.stream().anyMatch(line -> line.contains("Teleport failed.")));
        verify(repository, never()).setBackLocation(anyString(), any());

        rejected.set(false);
        assertEquals(1, roots.get("tpaccept").executor().execute(accept));
        assertSame(destination, position.get());
        assertTrue(sent.stream().anyMatch(line -> line.contains("Accepted request from Self")));
        assertEquals(0, roots.get("tpaccept").executor().execute(accept));

        position.set(origin);
        grants.add(ParadigmPermissions.TP_PLAYER);
        run("Other");
        assertSame(destination, position.get());
    }

}
