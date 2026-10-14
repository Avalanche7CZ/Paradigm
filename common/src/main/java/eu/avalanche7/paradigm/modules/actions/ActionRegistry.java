package eu.avalanche7.paradigm.modules.actions;

import org.jetbrains.annotations.Nullable;

import eu.avalanche7.paradigm.data.CustomCommand;

public final class ActionRegistry extends TypeRegistry<ActionRegistry.Entry> {

    @FunctionalInterface
    public interface Handler {
        void execute(CustomCommand.Action action, ActionContext context);
    }

    public record Entry(String type, Handler handler, boolean workflow) {
        public Entry(String type, Handler handler) { this(type, handler, false); }
    }

    public ActionRegistry() {
        super(Entry::type);
    }

    public void register(String type, Handler handler, String... aliases) {
        put(type, handler != null ? new Entry(normalize(type), handler) : null, aliases,
                "Action type and handler are required.");
    }

    void registerWorkflow(String type) {
        put(type, new Entry(normalize(type), null, true), null, "Workflow type is required");
    }

    public boolean isWorkflow(String type) {
        Entry entry = entry(type);
        return entry != null && entry.workflow();
    }

    @Nullable
    public Handler get(String type) {
        Entry entry = entry(type);
        return entry != null ? entry.handler() : null;
    }

    @Nullable
    public String canonicalType(String type) {
        Entry entry = entry(type);
        return entry != null ? entry.type() : null;
    }
}
