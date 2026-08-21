package eu.avalanche7.paradigm.modules.actions;

import java.util.List;

import eu.avalanche7.paradigm.core.Services;
import eu.avalanche7.paradigm.data.CustomCommand;

public final class ActionDispatcher {

    public static final int MAX_DEPTH = 16;

    private final Services services;
    private final ActionRegistry actions;
    private final ConditionRegistry conditions;

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
        execute(list, context, 0);
    }

    private void execute(List<CustomCommand.Action> list, ActionContext context, int depth) {
        if (list == null || list.isEmpty() || context == null) {
            return;
        }
        if (depth > MAX_DEPTH) {
            fail(context, "&cAction nesting is too deep; aborting.");
            return;
        }
        for (CustomCommand.Action action : list) {
            if (action == null) {
                continue;
            }
            String type = action.getType();
            if (isConditional(type)) {
                if (testAll(action.getConditions(), context)) {
                    execute(action.getOnSuccess(), context, depth + 1);
                } else {
                    execute(action.getOnFailure(), context, depth + 1);
                }
                continue;
            }
            ActionRegistry.Handler handler = actions.get(type);
            if (handler == null) {
                fail(context, "&cUnknown action type: " + type);
                continue;
            }
            try {
                handler.execute(action, context);
            } catch (RuntimeException failure) {
                if (services != null && services.getDebugLogger() != null) {
                    services.getDebugLogger().debugLog("Action '" + type + "' failed: " + failure.getMessage());
                }
                fail(context, "&cAction '" + type + "' failed: " + failure.getMessage());
            }
        }
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
