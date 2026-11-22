package eu.avalanche7.paradigm.platform;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.Queue;
import java.util.UUID;
import java.util.function.Consumer;
import java.util.function.Supplier;

import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.exceptions.CommandSyntaxException;
import com.mojang.brigadier.suggestion.Suggestion;
import net.minecraft.SharedConstants;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.server.Bootstrap;
import net.minecraft.server.level.ServerPlayer;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.ArgumentCaptor;

import eu.avalanche7.paradigm.core.Services;
import eu.avalanche7.paradigm.data.PlayerDataStore;
import eu.avalanche7.paradigm.modules.commands.HomeCommand;
import eu.avalanche7.paradigm.modules.permissions.ParadigmPermissions;
import eu.avalanche7.paradigm.modules.permissions.PermissionDefinition;
import eu.avalanche7.paradigm.modules.permissions.PermissionsHandler;
import eu.avalanche7.paradigm.modules.profile.PlayerProfileService;
import eu.avalanche7.paradigm.platform.Interfaces.IComponent;
import eu.avalanche7.paradigm.platform.Interfaces.IPlayer;
import eu.avalanche7.paradigm.storage.StorageService;
import eu.avalanche7.paradigm.storage.model.StoredHome;
import eu.avalanche7.paradigm.storage.model.StoredLocation;
import eu.avalanche7.paradigm.storage.model.StoredPlayerProfile;
import eu.avalanche7.paradigm.storage.repository.PlayerRepository;
import eu.avalanche7.paradigm.utils.CommandToggleStore;
import eu.avalanche7.paradigm.utils.Lang;
import eu.avalanche7.paradigm.utils.MessageParser;
import eu.avalanche7.paradigm.utils.Placeholders;

class HomeCommandBrigadierTest {
    private static final String OWNER = "steve-id";
    private static final StoredLocation OWN_LOCATION = new StoredLocation("minecraft:overworld", 10, 70, 20, 0, 0);
    private static final StoredLocation OTHER_LOCATION = new StoredLocation("minecraft:overworld", 100, 70, 200, 0, 0);

    private final CommandDispatcher<CommandSourceStack> dispatcher = new CommandDispatcher<>();
    private final CommandSourceStack source = mock(CommandSourceStack.class);
    private final PlatformAdapterImpl platform = spy(new PlatformAdapterImpl(null, new Placeholders(), null, null));
    private final PlayerRepository players = mock(PlayerRepository.class);
    private final PlayerProfileService profiles = mock(PlayerProfileService.class);
    private final PermissionsHandler permissions = mock(PermissionsHandler.class);
    private final Queue<Runnable> pendingStorage = new ArrayDeque<>();
    private final List<String> messages = new ArrayList<>();
    private final String visitorId = UUID.randomUUID().toString();
    private final StoredHome ownHome = new StoredHome(visitorId, "base", OWN_LOCATION, 1, 1);
    private MinecraftPlayer visitor;
    private boolean others = true;

    @BeforeAll
    static void bootstrap() {
        SharedConstants.tryDetectVersion();
        Bootstrap.bootStrap();
    }

    @BeforeEach
    @SuppressWarnings("unchecked")
    void registerCommands() {
        var player = mock(ServerPlayer.class);
        when(player.getUUID()).thenReturn(UUID.fromString(visitorId));
        when(source.getEntity()).thenReturn(player);
        visitor = new MinecraftPlayer(player);
        platform.setCommandDispatcher(dispatcher);
        doReturn(visitor).when(platform).getPlayerByUuid(visitorId);
        doReturn(null).when(platform).getPlayerByName(anyString());
        doReturn(List.of("Steve")).when(platform).getVisiblePlayerNames(any());
        doReturn(Optional.empty()).when(platform).getPlayerLocation(any());
        doReturn(true).when(platform).teleportPlayer(any(), any(PlayerDataStore.StoredLocation.class));
        doNothing().when(platform).sendSystemMessage(any(), any(IComponent.class));

        var services = mock(Services.class);
        var storage = mock(StorageService.class);
        var toggles = mock(CommandToggleStore.class);
        var parser = mock(MessageParser.class);
        when(services.getPlatformAdapter()).thenReturn(platform);
        when(services.getStorageService()).thenReturn(storage);
        when(services.getCommandToggleStore()).thenReturn(toggles);
        when(services.getPermissionsHandler()).thenReturn(permissions);
        when(services.getPlayerProfileService()).thenReturn(profiles);
        when(services.getLang()).thenReturn(mock(Lang.class));
        when(services.getMessageParser()).thenReturn(parser);
        when(services.moduleActivationGuard(any())).thenReturn(() -> true);
        when(toggles.isEnabled(anyString())).thenReturn(true);
        when(permissions.hasPermission(any(IPlayer.class), any(PermissionDefinition.class)))
                .thenAnswer(call -> call.getArgument(1) != ParadigmPermissions.HOME_OTHERS || others);
        when(parser.parseMessage(anyString(), any())).thenAnswer(call -> {
            messages.add(call.getArgument(0));
            return mock(IComponent.class);
        });
        when(storage.players()).thenReturn(players);
        doAnswer(call -> {
            Supplier<?> work = call.getArgument(1);
            Consumer<Object> onSuccess = call.getArgument(3);
            pendingStorage.add(() -> onSuccess.accept(work.get()));
            return null;
        }).when(storage).runAsync(anyString(), any(), any(), any(), any());
        when(profiles.findByNameOrUuid("Steve")).thenReturn(Optional.of(new StoredPlayerProfile(OWNER, "Steve", 1, 1, 0)));
        when(players.listHomes(OWNER)).thenReturn(List.of(new StoredHome(OWNER, "base", OTHER_LOCATION, 1, 1)));
        when(players.listHomes(visitorId)).thenReturn(List.of(ownHome));
        when(players.getHome(visitorId, "base")).thenReturn(Optional.of(ownHome));

        var command = new HomeCommand();
        command.onLoad(null, services, null);
        command.registerCommands(dispatcher, null, services);
    }

    @ParameterizedTest
    @ValueSource(strings = {"home", "home base"})
    void ownHomeUsesTheVisitorsStorage(String input) throws Exception {
        executeAndComplete(input);
        assertTeleportedTo(10);
        verifyNoInteractions(profiles);
    }

    @Test
    void defaultListsMultipleOwnHomes() throws Exception {
        when(players.listHomes(visitorId)).thenReturn(List.of(ownHome, new StoredHome(visitorId, "mine", OWN_LOCATION, 1, 1)));
        executeAndComplete("home");
        assertMessage("Homes (2):");
        verifyNoTeleport();
    }

    @Test
    void trailingColonListsTheOfflinePlayersSingleHome() throws Exception {
        assertEquals(1, dispatcher.execute("home Steve:", source));
        verifyNoInteractions(profiles);
        completeStorage();
        verify(profiles).findByNameOrUuid("Steve");
        assertMessage("Homes of Steve: base");
        verifyNoTeleport();
    }

    @ParameterizedTest
    @ValueSource(strings = {"home Steve:base", "home Steve:BASE"})
    void namedOfflineHomeTeleportsOnlyTheVisitor(String input) throws Exception {
        assertEquals(1, dispatcher.execute(input, source));
        verifyNoInteractions(profiles);
        verifyNoTeleport();
        completeStorage();
        verify(profiles).findByNameOrUuid("Steve");
        assertTeleportedTo(100);
    }

    @Test
    void unknownPlayerDoesNotFallBackToTheVisitorsHome() throws Exception {
        executeAndComplete("home Unknown:base");
        verify(profiles).findByNameOrUuid("Unknown");
        verifyNoInteractions(players);
        assertMessage("That player has no stored profile.");
        verifyNoTeleport();
    }

    @ParameterizedTest
    @ValueSource(strings = {"base other", " base", "base ", "  ", "Steve :base", "Steve: base", "Steve:base extra",
            ":base", "Steve::base", "@a:base"})
    void invalidArgumentsNeverReachStorage(String argument) throws Exception {
        assertEquals(0, dispatcher.execute("home " + argument, source));
        assertTrue(pendingStorage.isEmpty());
        verifyNoInteractions(profiles, players);
        verifyNoTeleport();
    }

    @Test
    void permissionDenialStopsLookupAndCompletion() throws Exception {
        others = false;
        assertEquals(0, dispatcher.execute("home Steve:", source));
        assertEquals(0, dispatcher.execute("home Steve:base", source));
        assertMessage("You do not have permission to visit other players' homes.");
        assertTrue(pendingStorage.isEmpty());
        assertFalse(suggestions("home St").contains("Steve:"));
        assertTrue(suggestions("home Steve:").isEmpty());
        completeStorage();
        verifyNoInteractions(profiles);
        verifyNoTeleport();
    }

    @Test
    void permissionRevokedDuringLookupPreventsTeleport() throws Exception {
        assertEquals(1, dispatcher.execute("home Steve:base", source));
        others = false;
        completeStorage();
        assertTrue(messages.isEmpty());
        verifyNoTeleport();
    }

    @ParameterizedTest
    @CsvSource({
            "home set base, set", "sethome base, set",
            "home delete base, delete", "home del base, delete", "delhome base, delete",
            "home list, list", "homes, list", "home back, back", "back, back"
    })
    void existingLiteralsAndStandaloneCommandsDispatch(String input, String operation) throws Exception {
        if (operation.equals("set")) {
            doReturn(Optional.of(new PlayerDataStore.StoredLocation("minecraft:overworld", 10, 70, 20, 0, 0)))
                    .when(platform).getPlayerLocation(any());
        }
        executeAndComplete(input);
        switch (operation) {
            case "set" -> verify(players).saveHome(argThat(home -> home.name().equals("base")));
            case "delete" -> verify(players).deleteHome(visitorId, "base");
            case "list" -> assertMessage("Homes (1):");
            case "back" -> verify(players).getBackLocation(visitorId);
            default -> fail("Unexpected operation " + operation);
        }
    }

    @Test
    void deniedLiteralAndExtraArgumentsDoNotFallThroughToHomeTeleport() {
        when(permissions.hasPermission(any(IPlayer.class), eq(ParadigmPermissions.HOME_SET))).thenReturn(false);
        assertThrows(CommandSyntaxException.class, () -> dispatcher.execute("home set base", source));
        assertThrows(CommandSyntaxException.class, () -> dispatcher.execute("home list extra", source));
        assertTrue(pendingStorage.isEmpty());
        verifyNoTeleport();
    }

    @Test
    void tabCompletionPreservesTheFullColonArgument() {
        assertEquals(List.of("Steve:"), suggestions("home St"));
        completeStorage();
        assertEquals(List.of("base"), suggestions("home bas"));
        assertTrue(suggestions("home Steve:").isEmpty());
        completeStorage();
        assertEquals(List.of("Steve:base"), suggestions("home Steve:"));
        assertEquals(List.of("home Steve:base"), dispatcher.getCompletionSuggestions(dispatcher.parse("home Steve:BA", source))
                .join().getList().stream().map(suggestion -> suggestion.apply("home Steve:BA")).toList());
        others = false;
        assertTrue(suggestions("home Steve:").isEmpty());
    }

    private List<String> suggestions(String input) {
        return dispatcher.getCompletionSuggestions(dispatcher.parse(input, source)).join()
                .getList().stream().map(Suggestion::getText).toList();
    }

    private void executeAndComplete(String input) throws CommandSyntaxException {
        assertEquals(1, dispatcher.execute(input, source));
        completeStorage();
    }

    private void completeStorage() {
        while (!pendingStorage.isEmpty()) {
            pendingStorage.remove().run();
        }
    }

    private void assertTeleportedTo(double x) {
        var destination = ArgumentCaptor.forClass(PlayerDataStore.StoredLocation.class);
        verify(platform).teleportPlayer(same(visitor), destination.capture());
        assertEquals(x, destination.getValue().getX());
    }

    private void verifyNoTeleport() {
        verify(platform, never()).teleportPlayer(any(), any(PlayerDataStore.StoredLocation.class));
    }

    private void assertMessage(String text) {
        assertTrue(messages.stream().anyMatch(message -> message.contains(text)), messages::toString);
    }
}
