package eu.avalanche7.paradigm.modules.actions;

import org.jetbrains.annotations.Nullable;

import eu.avalanche7.paradigm.data.CustomCommand;

public final class ConditionRegistry extends TypeRegistry<ConditionRegistry.Entry> {

    @FunctionalInterface
    public interface Predicate {
        boolean test(CustomCommand.Condition condition, ActionContext context);
    }

    public record Entry(String type, Predicate predicate, boolean requiresPlayer) {
    }

    public ConditionRegistry() {
        super(Entry::type);
    }

    public void register(String type, boolean requiresPlayer, Predicate predicate, String... aliases) {
        put(type, predicate != null ? new Entry(normalize(type), predicate, requiresPlayer) : null, aliases,
                "Condition type and predicate are required.");
    }

    @Nullable
    public Entry get(String type) {
        return entry(type);
    }
}
