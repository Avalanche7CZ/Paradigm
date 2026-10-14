package eu.avalanche7.paradigm.modules.actions;

import java.util.List;

import eu.avalanche7.paradigm.core.Services;
import eu.avalanche7.paradigm.data.CustomCommand;

public final class ActionDispatcher {

    public static final int MAX_DEPTH = 16;

    private final Services services;
    private final ActionRegistry actions;
    private final ConditionRegistry conditions;
    private final java.util.Set<String> activePlayers = java.util.concurrent.ConcurrentHashMap.newKeySet();
    private final java.util.Map<String, Long> lastWorkflow = new java.util.concurrent.ConcurrentHashMap<>();
    private final java.util.Set<String> recoveryPlayers = java.util.concurrent.ConcurrentHashMap.newKeySet();

    public boolean requiresRecovery(String uuid) { return recoveryPlayers.contains(uuid); }
    public void acknowledgeRecovery(String uuid) { recoveryPlayers.remove(uuid); }
    void requireRecovery(String uuid) { recoveryPlayers.add(uuid); }

    public ActionDispatcher(Services services, ActionRegistry actions, ConditionRegistry conditions) {
        this.services = services;
        this.actions = actions;
        this.conditions = conditions;
    }

    public ActionRegistry actions() {
        return actions;
    }

    public ConditionRegistry conditions() {
        return conditions;
    }

    public void execute(List<CustomCommand.Action> list, ActionContext context) {
        if (context == null) return;
        if (!containsWorkflow(list, 0)) { executeNested(list, context, 0); return; }
        if (services == null || services.getPlatformAdapter() == null || context.player() == null) {
            context.replyFailure("&cItem workflows require a native player platform"); return;
        }
        String uuid = context.player().getUUID();
        if (uuid == null || uuid.isBlank()) { context.replyFailure("&cPlayer UUID is unavailable"); return; }
        if (requiresRecovery(uuid)) { context.replyFailure("&cA previous workflow needs administrator recovery before you can retry."); return; }
        String debounceKey = uuid + "|" + context.origin() + "|" + context.value("menu") + "|" + context.value("slot")
                + "|" + (list != null && !list.isEmpty() ? System.identityHashCode(list.get(0)) : 0);
        if (!activePlayers.add(uuid)) { context.replyFailure("&cAn item workflow is already running"); return; }
        try { Runnable workflow = () -> {
            try {
                var live = services.getPlatformAdapter().getPlayerByUuid(uuid);
                if (live == null || live.getOriginalPlayer() != context.player().getOriginalPlayer() || !context.isAuthorized(live)) return;
                long now = System.nanoTime();
                Long previous = lastWorkflow.get(debounceKey);
                if (previous != null && now - previous < 500_000_000L) return;
                lastWorkflow.put(debounceKey, now);
                executeNested(list, context.toBuilder().player(live).build(), 0);
                lastWorkflow.entrySet().removeIf(entry -> now - entry.getValue() > 60_000_000_000L);
            } finally { activePlayers.remove(uuid); }
        };
            if (services.getPlatformAdapter().isServerThread()) workflow.run();
            else services.getPlatformAdapter().executeOnServerThread(workflow);
        } catch (RuntimeException failure) {
            activePlayers.remove(uuid); context.replyFailure("&cWorkflow could not be scheduled: " + failure.getMessage());
        }
    }

    boolean executeNested(List<CustomCommand.Action> list, ActionContext context, int depth) {
        if (list == null || list.isEmpty() || context == null) {
            return true;
        }
        if (depth > MAX_DEPTH) {
            fail(context, "&cAction nesting is too deep; aborting.");
            return false;
        }
        for (int index = 0; index < list.size(); index++) {
            CustomCommand.Action action = list.get(index);
            if (action == null) {
                continue;
            }
            String type = actions.canonicalType(action.getType());
            if (actions.isWorkflow(type)) {
                boolean result = ItemActions.execute(action, "consume_items".equals(type) ? list.subList(index + 1, list.size()) : List.of(), context, this, depth);
                if (!result || "consume_items".equals(type)) return result;
                continue;
            }
            if (isConditional(action.getType())) {
                boolean passed = testAll(action.getConditions(), context);
                boolean completed = executeNested(passed
                        ? action.getOnSuccess() : action.getOnFailure(), context, depth + 1);
                if (!completed || !passed) return false;
                continue;
            }
            ActionRegistry.Handler handler = actions.get(action.getType());
            if (handler == null) {
                fail(context, "&cUnknown action type: " + action.getType());
                return false;
            }
            try {
                long failuresBefore = context.failureCount();
                handler.execute(action, context);
                if (context.failureCount() != failuresBefore) return false;
            } catch (RuntimeException failure) {
                if (services != null && services.getDebugLogger() != null) {
                    services.getDebugLogger().debugLog("Action '" + type + "' failed: " + failure.getMessage());
                }
                fail(context, "&cAction '" + type + "' failed: " + failure.getMessage());
                return false;
            }
            if ("await_input".equals(actions.canonicalType(type))) return false;
        }
        return true;
    }

    private boolean containsWorkflow(List<CustomCommand.Action> list, int depth) {
        if (list == null || depth > MAX_DEPTH) return false;
        for (var action : list) if (action != null && !"await_input".equals(actions.canonicalType(action.getType())) && (actions.isWorkflow(action.getType())
                || containsWorkflow(action.getOnSuccess(), depth + 1) || containsWorkflow(action.getOnFailure(), depth + 1))) return true;
        return false;
    }

    public boolean testAll(List<CustomCommand.Condition> list, ActionContext context) {
        if (list == null || list.isEmpty()) {
            return true;
        }
        for (CustomCommand.Condition condition : list) {
            if (!test(condition, context)) {
                return false;
            }
        }
        return true;
    }

    public boolean test(CustomCommand.Condition condition, ActionContext context) {
        if (condition == null || context == null) {
            return false;
        }
        ConditionRegistry.Entry entry = conditions.get(condition.getType());
        if (entry == null) {
            fail(context, "&cUnknown condition type: " + condition.getType());
            return false;
        }
        if (entry.requiresPlayer() && context.player() == null) {
            if (services != null && services.getDebugLogger() != null) {
                services.getDebugLogger().debugLog("Condition '" + condition.getType()
                        + "' requires a player but none is present. Failing condition.");
            }
            return false;
        }
        boolean result;
        try {
            result = entry.predicate().test(condition, context);
        } catch (RuntimeException failure) {
            if (services != null && services.getDebugLogger() != null) {
                services.getDebugLogger().debugLog("Condition '" + condition.getType() + "' failed: " + failure.getMessage());
            }
            return false;
        }
        return condition.isNegate() != result;
    }

    public static boolean isConditional(String type) {
        String normalized = TypeRegistry.normalize(type);
        return "conditional".equals(normalized);
    }

    private void fail(ActionContext context, String rawMessage) {
        context.replyFailure(rawMessage);
    }
}
