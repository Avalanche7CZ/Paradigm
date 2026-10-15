package eu.avalanche7.paradigm.modules.actions;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import eu.avalanche7.paradigm.data.CustomCommand;
import eu.avalanche7.paradigm.platform.InventoryConsumption;
import eu.avalanche7.paradigm.platform.ItemRequirement;
import eu.avalanche7.paradigm.storage.StorageTransactionException;
import eu.avalanche7.paradigm.storage.repository.PermissionRepository.GroupChange;

public final class ItemActions {
    private ItemActions() {}
    public static List<ItemRequirement> requirements(List<String> items) {
        if (items == null || items.isEmpty() || items.size() > 128) throw new IllegalArgumentException("Expected 1 to 128 item requirements");
        return items.stream().map(ItemRequirement::parse).toList();
    }
    public static void validate(CustomCommand.Action action) {
        switch (action.getType()) {
            case "check_items", "consume_items" -> requirements(action.getItems());
            case "add_group", "remove_group" -> {
                if (action.getGroup() == null || action.getGroup().isBlank()) throw new IllegalArgumentException("Group must be a fixed configured name");
            }
            case "transaction" -> {
                if (action.getSteps().isEmpty()) throw new IllegalArgumentException("Transaction requires actions");
                List<CustomCommand.Action> steps = new ArrayList<>(action.getSteps()); steps.addAll(action.getOnSuccess());
                collect(steps, new ArrayList<>(), new ArrayList<>(), new ArrayList<>(), 0);
            }
            default -> { }
        }
    }
    public static void validateSequence(List<CustomCommand.Action> actions) {
        if (actions == null) return;
        for (int index = 0; index < actions.size(); index++) {
            var action = actions.get(index);
            if (action == null) continue;
            switch (action.getType()) {
                case "consume_items" -> {
                    var steps = new ArrayList<>(action.getOnSuccess());
                    steps.addAll(actions.subList(index + 1, actions.size()));
                    collect(steps, new ArrayList<>(), new ArrayList<>(), new ArrayList<>(), 0);
                }
                case "add_group", "remove_group" -> collect(List.of(action), new ArrayList<>(), new ArrayList<>(), new ArrayList<>(), 0, action);
                default -> { }
            }
        }
    }
    public static boolean execute(CustomCommand.Action action, List<CustomCommand.Action> tail, ActionContext context, ActionDispatcher dispatcher, int depth) {
        var services = context.services();
        var player = context.player();
        InventoryConsumption consumption = null;
        List<CustomCommand.Action> notifications = new ArrayList<>();
        try {
            if (player == null || !context.isAuthorized(player)) throw new IllegalStateException("A currently authorized player is required");
            if (!dispatcher.testAll(action.getConditions(), context)) throw new IllegalStateException("Required conditions failed");
            if ("check_items".equals(action.getType())) {
                java.util.Objects.requireNonNull(services.getPlatformAdapter().prepareItemConsumption(player, requirements(action.getItems())), "Inventory checks are unavailable");
                return dispatcher.executeNested(action.getOnSuccess(), context, depth + 1);
            }
            List<ItemRequirement> items = new ArrayList<>();
            List<GroupChange> groups = new ArrayList<>();
            List<CustomCommand.Action> steps = new ArrayList<>();
            switch (action.getType()) {
                case "consume_items" -> { items.addAll(requirements(action.getItems())); steps.addAll(action.getOnSuccess()); steps.addAll(tail); }
                case "transaction" -> { steps.addAll(action.getSteps()); steps.addAll(action.getOnSuccess()); }
                default -> steps.add(action);
            }
            collect(steps, items, groups, notifications, depth, action);
            if (items.isEmpty() && groups.isEmpty()) throw new IllegalArgumentException("Transaction requires an inventory or group operation");
            if (!items.isEmpty()) consumption = java.util.Objects.requireNonNull(services.getPlatformAdapter().prepareItemConsumption(player, items), "Inventory consumption is unavailable");
            var permissions = services.getPermissionsHandler();
            if (!groups.isEmpty()) {
                if (permissions == null || !permissions.isInternalPermissionsEnabled()) throw new IllegalStateException("Internal permissions are disabled");
                var known = permissions.listPermissionGroups();
                for (var group : groups) if (!known.contains(group.group())) throw new IllegalArgumentException("Unknown group: " + group.group());
            }
            InventoryConsumption prepared = consumption;
            Runnable effect = () -> { if (prepared != null) prepared.consume(); };
            if (groups.isEmpty()) effect.run();
            else if (!permissions.changeGlobalGroups(UUID.fromString(player.getUUID()), groups, effect))
                throw new IllegalStateException("Group already assigned, or requested removal does not exist");
        } catch (StorageTransactionException failure) {
            dispatcher.requireRecovery(player.getUUID());
            if (services.getLogger() != null) services.getLogger().error("Item workflow requires recovery for {}: SQL outcome {}; inventory retained without refund", player.getUUID(), failure.outcome(), failure);
            context.replyFailure("&cThis exchange needs administrator recovery. No automatic refund or retry was performed.");
            return false;
        } catch (RuntimeException failure) {
            if (consumption != null) {
                try { consumption.restore(); }
                catch (RuntimeException rollbackFailure) {
                    failure.addSuppressed(rollbackFailure);
                    if (services.getLogger() != null) services.getLogger().error("Item workflow rollback failed for {}; administrator recovery required", player.getUUID(), failure);
                    dispatcher.requireRecovery(player.getUUID());
                    context.replyFailure("&cInventory rollback failed; contact an administrator. No success actions were executed.");
                    return false;
                }
            }
            if (services.getLogger() != null) services.getLogger().warn("Item workflow failed for {} from {}; inventory restored",
                    player != null ? player.getUUID() : "no-player", context.origin(), failure);
            context.replyFailure("&cWorkflow failed: " + failure.getMessage());
            dispatcher.executeNested(action.getOnFailure(), context, depth + 1);
            return false;
        }
        if (!dispatcher.executeNested(notifications, context, depth + 1)) return false;
        return true;
    }

    private static void collect(List<CustomCommand.Action> steps, List<ItemRequirement> items, List<GroupChange> groups,
            List<CustomCommand.Action> notifications, int depth) {
        collect(steps, items, groups, notifications, depth, null);
    }

    private static void collect(List<CustomCommand.Action> steps, List<ItemRequirement> items, List<GroupChange> groups,
            List<CustomCommand.Action> notifications, int depth, CustomCommand.Action outer) {
        if (depth > ActionDispatcher.MAX_DEPTH) throw new IllegalArgumentException("Action nesting is too deep");
        for (var step : steps) {
            if (step == null) throw new IllegalArgumentException("Null transaction action");
            if (step != outer && (!step.getConditions().isEmpty() || !step.getOnFailure().isEmpty()))
                throw new IllegalArgumentException("Place transaction conditions and failure actions on the outer transaction or consume_items action");
            switch (step.getType()) {
                case "consume_items" -> { items.addAll(requirements(step.getItems())); collect(step.getOnSuccess(), items, groups, notifications, depth + 1); }
                case "add_group", "remove_group" -> {
                    String group = step.getGroup();
                    if (group == null || group.isBlank()) throw new IllegalArgumentException("Group must be a fixed configured name");
                    groups.add(new GroupChange(group, "add_group".equals(step.getType())));
                    collect(step.getOnSuccess(), items, groups, notifications, depth + 1);
                }
                case "message", "broadcast", "actionbar", "title", "sound", "open_menu" -> notifications.add(step);
                default -> throw new IllegalArgumentException("Action cannot participate in an inventory transaction: " + step.getType());
            }
        }
    }
}
