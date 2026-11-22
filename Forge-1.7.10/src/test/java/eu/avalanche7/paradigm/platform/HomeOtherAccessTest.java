package eu.avalanche7.paradigm.platform;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import java.util.*;
import java.util.function.*;

import net.minecraft.entity.player.EntityPlayerMP;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import eu.avalanche7.paradigm.core.Services;
import eu.avalanche7.paradigm.data.PlayerDataStore;
import eu.avalanche7.paradigm.modules.commands.HomeCommand;
import eu.avalanche7.paradigm.modules.permissions.*;
import eu.avalanche7.paradigm.modules.profile.PlayerProfileService;
import eu.avalanche7.paradigm.platform.Interfaces.*;
import eu.avalanche7.paradigm.storage.StorageService;
import eu.avalanche7.paradigm.storage.model.*;
import eu.avalanche7.paradigm.storage.repository.PlayerRepository;
import eu.avalanche7.paradigm.utils.*;

class HomeOtherAccessTest {
    final Services services = mock(Services.class);
    final PlatformAdapterImpl platform = mock(PlatformAdapterImpl.class);
    final StorageService storage = mock(StorageService.class);
    final PlayerRepository players = mock(PlayerRepository.class);
    final PermissionsHandler permissions = mock(PermissionsHandler.class);
    final PlayerProfileService profiles = mock(PlayerProfileService.class);
    final IPlayer actor = mock(IPlayer.class);
    final EntityPlayerMP sender = mock(EntityPlayerMP.class);
    final String actorId = UUID.randomUUID().toString();
    final List<ForgeCommandBuilder> roots = new ArrayList<>();
    final List<Runnable> callbacks = new ArrayList<>();
    final List<String> messages = new ArrayList<>();
    final StoredHome home = new StoredHome("owner-id", "base", new StoredLocation("0", 100, 70, 200, 0, 0), 1, 1);
    boolean others = true;
    boolean enabled = true;
    boolean defer = false;

    @BeforeEach void setup() {
        when(services.moduleActivationGuard(any())).thenReturn(() -> enabled);
        when(services.getPlatformAdapter()).thenReturn(platform);
        when(services.getStorageService()).thenReturn(storage);
        when(services.getPermissionsHandler()).thenReturn(permissions);
        when(services.getPlayerProfileService()).thenReturn(profiles);
        when(storage.players()).thenReturn(players);
        when(actor.getUUID()).thenReturn(actorId);
        when(actor.getOriginalPlayer()).thenReturn(sender);
        when(sender.getUniqueID()).thenReturn(UUID.fromString(actorId));
        when(platform.getPlayerByUuid(actorId)).thenReturn(actor);
        when(platform.getPlayerLocation(actor)).thenReturn(Optional.empty());
        when(platform.teleportPlayer(eq(actor), any(PlayerDataStore.StoredLocation.class))).thenReturn(true);
        var toggles = mock(CommandToggleStore.class);
        when(services.getCommandToggleStore()).thenReturn(toggles);
        when(toggles.isEnabled(anyString())).thenAnswer(i -> enabled);
        when(permissions.hasPermission(any(IPlayer.class), any(PermissionDefinition.class))).thenAnswer(i -> i.getArgument(1) != ParadigmPermissions.HOME_OTHERS || others);
        when(profiles.findByNameOrUuid(anyString())).thenReturn(Optional.of(new StoredPlayerProfile("owner-id", "Steve", 1, 1, 0)));
        when(players.listHomes("owner-id")).thenReturn(List.of(home));
        var lang = mock(Lang.class);
        when(services.getLang()).thenReturn(lang);
        when(lang.getTranslation(anyString())).thenAnswer(i -> i.getArgument(0));
        var parser = mock(MessageParser.class);
        when(services.getMessageParser()).thenReturn(parser);
        when(parser.parseMessage(anyString(), any(IPlayer.class))).thenAnswer(i -> { messages.add(i.getArgument(0)); return mock(IComponent.class); });
        when(platform.createCommandBuilder()).thenAnswer(i -> new ForgeCommandBuilder());
        doAnswer(i -> { roots.add(i.getArgument(0)); return null; }).when(platform).registerCommand(any());
        doAnswer(i -> {
            Object result = ((Supplier<?>) i.getArgument(1)).get();
            Runnable callback = () -> ((Consumer<Object>) i.getArgument(3)).accept(result);
            if (defer) callbacks.add(callback); else callback.run();
            return null;
        }).when(storage).runAsync(anyString(), any(), any(), any(), any());
        HomeCommand command = new HomeCommand();
        command.onLoad(null, services, null);
        command.registerCommands(null, null, services);
    }
    ForgeCommand homeCommand() {
        var root = roots.stream().filter(r -> r.literalName().equals("home")).findFirst().orElseThrow();
        return new ForgeCommand(root, platform);
    }

    void run(String input) {
        homeCommand().processCommand(sender, input.split(" "));
    }

    @Test void offlineListWithOneHomeDoesNotTeleport() throws Exception {
        run("Steve:");
        verify(profiles).findByNameOrUuid("Steve");
        verify(platform, never()).teleportPlayer(any(), any(PlayerDataStore.StoredLocation.class));
        assertTrue(messages.stream().anyMatch(m -> m.contains("Steve") && m.contains("base")));
    }
    @Test void offlineNamedHomeTeleportsOnlySender() throws Exception {
        run("Steve:BASE");
        var destination = org.mockito.ArgumentCaptor.forClass(PlayerDataStore.StoredLocation.class);
        verify(platform).teleportPlayer(eq(actor), destination.capture());
        assertEquals(100, destination.getValue().getX());
        verify(players, never()).saveHome(any());
    }
    @Test void denyBeforeAnyForeignLookup() throws Exception {
        others = false;
        run("Steve:"); run("sTeVe:base");
        verifyNoInteractions(profiles, players);
    }
    @Test void revokeBeforeAsyncResultDoesNotDiscloseOrTeleport() throws Exception {
        defer = true; run("Steve:"); run("Steve:base"); others = false;
        callbacks.forEach(Runnable::run);
        assertTrue(messages.isEmpty());
        verify(platform, never()).teleportPlayer(any(), any(PlayerDataStore.StoredLocation.class));
    }
    @Test void disabledAfterReadDoesNotDisclose() throws Exception {
        defer = true; run("Steve:"); enabled = false; callbacks.forEach(Runnable::run);
        assertTrue(messages.isEmpty());
    }
    @Test void unknownEmptyAndMalformedTargetsDoNotTeleport() throws Exception {
        when(profiles.findByNameOrUuid("Unknown")).thenReturn(Optional.empty());
        when(players.listHomes("owner-id")).thenReturn(List.of());
        run("Unknown:"); run("Steve:"); run("Steve:missing");
        run(":base"); run("Steve:base:other"); run("@a:base");
        verify(platform, never()).teleportPlayer(any(), any(PlayerDataStore.StoredLocation.class));
    }
    @Test void ownNamedHomesKeepTheirOwner() throws Exception {
        when(players.getHome(actorId, "base")).thenReturn(Optional.of(home));
        run("base");
        verify(players).getHome(actorId, "base");
        verifyNoInteractions(profiles);
        verify(platform).teleportPlayer(eq(actor), any(PlayerDataStore.StoredLocation.class));
    }
    @Test void tabCompletionRequiresPrivilegeAndSupportsOfflineHomes() {
        when(platform.getVisiblePlayerNames(any(IPlayer.class))).thenReturn(List.of("Steve"));
        var command = homeCommand();
        assertTrue(command.addTabCompletionOptions(sender, new String[]{"St"}).contains("Steve:"));
        assertEquals(List.of("Steve:base"), command.addTabCompletionOptions(sender, new String[]{"Steve:"}));
        others = false;
        assertFalse(command.addTabCompletionOptions(sender, new String[]{"Steve:"}).contains("Steve:base"));
    }
}
