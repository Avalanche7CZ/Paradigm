package eu.avalanche7.paradigm.platform;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import java.util.List;
import java.util.UUID;

import com.google.gson.Gson;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import eu.avalanche7.paradigm.core.Services;
import eu.avalanche7.paradigm.data.CustomCommand;
import eu.avalanche7.paradigm.modules.actions.*;
import eu.avalanche7.paradigm.modules.permissions.PermissionsHandler;
import eu.avalanche7.paradigm.platform.Interfaces.IPlatformAdapter;
import eu.avalanche7.paradigm.platform.Interfaces.IPlayer;

class ItemActionDispatchTest {
    Services services; IPlatformAdapter platform; IPlayer player; PermissionsHandler permissions; InventoryConsumption consumption; ActionDispatcher dispatcher;
    final java.util.List<String> messages = new java.util.ArrayList<>();
    @BeforeEach void setup() {
        services = mock(Services.class); platform = mock(IPlatformAdapter.class); player = mock(IPlayer.class); permissions = mock(PermissionsHandler.class); consumption = mock(InventoryConsumption.class);
        when(services.getPlatformAdapter()).thenReturn(platform); when(services.getPermissionsHandler()).thenReturn(permissions);
        when(player.getUUID()).thenReturn("12345678-1234-4234-8234-123456789abc"); when(platform.getPlayerByUuid(anyString())).thenReturn(player);
        Object handle = new Object(); when(player.getOriginalPlayer()).thenReturn(handle);
        doAnswer(call -> { call.<Runnable>getArgument(0).run(); return null; }).when(platform).executeOnServerThread(any());
        when(platform.prepareItemConsumption(eq(player), anyList())).thenReturn(consumption);
        when(permissions.isInternalPermissionsEnabled()).thenReturn(true); when(permissions.listPermissionGroups()).thenReturn(List.of("member"));
        when(permissions.changeGlobalGroups(any(UUID.class), anyList(), any())).thenAnswer(call -> { call.<Runnable>getArgument(2).run(); return true; });
        var registry = new ActionRegistry(); BuiltinActions.register(registry, services); registry.register("message", (action, context) -> messages.addAll(action.getText()));
        dispatcher = new ActionDispatcher(services, registry, new ConditionRegistry());
    }
    CustomCommand.Action action(String json) { return new Gson().fromJson(json, CustomCommand.Action.class); }
    void execute(CustomCommand.Action action) { dispatcher.execute(List.of(action), ActionContext.builder(services).player(player).origin("test").build()); }
    CustomCommand.Action exchange() { return action("{\"type\":\"consume_items\",\"items\":[\"280:0 9\"],\"on_success\":[{\"type\":\"add_group\",\"group\":\"member\"},{\"type\":\"message\",\"text\":[\"success\"]}],\"on_failure\":[{\"type\":\"message\",\"text\":[\"failure\"]}]}"); }
    @Test void completeWorkflowConsumesAndGrantsExactlyOnceOnRepeatedClicks() {
        var exchange = exchange(); execute(exchange); execute(exchange);
        verify(consumption, times(1)).consume(); verify(permissions, times(1)).changeGlobalGroups(any(), anyList(), any()); assertEquals(List.of("success"), messages);
    }
    @Test void missingItemsAndInvalidGroupsNeverConsumeOrGrant() {
        when(platform.prepareItemConsumption(any(), anyList())).thenThrow(new SlotInventory.MissingItemsException("Missing")); execute(exchange());
        verify(consumption, never()).consume(); verify(permissions, never()).changeGlobalGroups(any(), anyList(), any()); assertEquals(List.of("failure"), messages);
    }
    @Test void invalidGroupIsRejectedBeforeMutation() {
        when(permissions.listPermissionGroups()).thenReturn(List.of()); execute(exchange());
        verify(consumption, never()).consume(); verify(permissions, never()).changeGlobalGroups(any(), anyList(), any()); assertEquals(List.of("failure"), messages);
    }
    @Test void failedPermissionPersistenceRestoresItemsAndNeverAnnouncesSuccess() {
        doAnswer(call -> { call.<Runnable>getArgument(2).run(); throw new IllegalStateException("Persistence failed"); }).when(permissions).changeGlobalGroups(any(), anyList(), any());
        execute(exchange()); verify(consumption).consume(); verify(consumption).restore(); assertEquals(List.of("failure"), messages);
    }
    @Test void unsupportedInventoryAndUnsafeStepsFailBeforeMutation() {
        when(platform.prepareItemConsumption(any(), anyList())).thenThrow(new UnsupportedOperationException("Unavailable")); execute(exchange());
        verify(permissions, never()).changeGlobalGroups(any(), anyList(), any()); verify(consumption, never()).consume();
        messages.clear(); var safeRegistry = new ActionRegistry(); BuiltinActions.register(safeRegistry, services); dispatcher = new ActionDispatcher(services, safeRegistry, new ConditionRegistry());
        execute(action("{\"type\":\"consume_items\",\"items\":[\"280:0 9\"],\"on_success\":[{\"type\":\"run_console\",\"commands\":[\"give item\"]}]}")); verify(consumption, never()).consume();
    }
    @Test void checkItemsSucceedsWithoutConsumption() {
        execute(action("{\"type\":\"check_items\",\"items\":[\"280:0 9\"],\"on_success\":[{\"type\":\"message\",\"text\":[\"checked\"]}]}"));
        verify(consumption, never()).consume();
        assertEquals(List.of("checked"), messages);
    }
    @Test void transactionValidatesNestedFailureFieldsBeforeConsumption() {
        execute(action("{\"type\":\"transaction\",\"actions\":[{\"type\":\"consume_items\",\"items\":[\"280:0 9\"],\"on_failure\":[{\"type\":\"message\",\"text\":[\"nested\"]}]}]}"));
        verify(consumption, never()).consume();
        verify(permissions, never()).changeGlobalGroups(any(), anyList(), any());
        assertTrue(messages.isEmpty());
    }
    @org.junit.jupiter.params.ParameterizedTest
    @org.junit.jupiter.params.provider.EnumSource(eu.avalanche7.paradigm.storage.StorageTransactionException.Outcome.class)
    void uncertainCommitRetainsItemsAndBlocksRetriesWithoutFailureRewards(eu.avalanche7.paradigm.storage.StorageTransactionException.Outcome outcome) {
        doAnswer(call -> { call.<Runnable>getArgument(2).run(); throw new eu.avalanche7.paradigm.storage.StorageTransactionException("Lost acknowledgement", new java.sql.SQLException("network"), outcome); }).when(permissions).changeGlobalGroups(any(), anyList(), any());
        execute(exchange()); execute(exchange());
        verify(consumption, times(1)).consume(); verify(consumption, never()).restore();
        verify(permissions, times(1)).changeGlobalGroups(any(), anyList(), any());
        assertTrue(dispatcher.requiresRecovery(player.getUUID())); assertTrue(messages.isEmpty());
        dispatcher.acknowledgeRecovery(player.getUUID()); assertFalse(dispatcher.requiresRecovery(player.getUUID()));
    }
    @Test void registryRemovalDisablesManagedActions() {
        dispatcher.actions().unregister("consume_items"); execute(exchange());
        verify(consumption, never()).consume(); verify(permissions, never()).changeGlobalGroups(any(), anyList(), any());
    }
    @Test void paddedActionTypeRunsWithoutRecursiveRedispatch() {
        execute(action("{\"type\":\" consume_items \",\"items\":[\"280:0 9\"]}"));
        verify(consumption).consume();
    }
    @Test void failedActionStopsUnconditionalFollowingSuccessMessage() {
        dispatcher.execute(List.of(action("{\"type\":\"check_items\",\"items\":[]}"), CustomCommand.Action.message(List.of("wrong success"))), ActionContext.builder(services).player(player).build());
        assertTrue(messages.isEmpty());
    }
}
