package eu.avalanche7.paradigm.modules.actions;

import org.jetbrains.annotations.Nullable;

import eu.avalanche7.paradigm.data.CustomCommand;

public final class ActionRegistry extends TypeRegistry<ActionRegistry.Entry> {

    @FunctionalInterface
    public interface Handler {
        void execute(CustomCommand.Action action, ActionContext context);
    }

    public record Entry(String type, Handler handler) {
    }

    public ActionRegistry() {
        super(Entry::type);
    }

    public void register(String type, Handler handler, String... aliases) {
        put(type, handler != null ? new Entry(normalize(type), handler) : null, aliases,
                "Action type and handler are required.");
    }

    @Nullable
    public Handler get(String type) {
        Entry entry = entry(type);
        return entry != null ? entry.handler() : null;
    }
}
