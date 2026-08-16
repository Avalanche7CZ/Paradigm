package eu.avalanche7.paradigm.modules.permissions;

public final class CommandPermissionElevation {

    private static final ThreadLocal<State> ACTIVE = new ThreadLocal<>();

    private CommandPermissionElevation() {
    }

    public static void begin(Object source, boolean allow) {
        ACTIVE.set(new State(source, allow));
    }

    public static void clear() {
        ACTIVE.remove();
    }

    public static boolean isElevated(Object source) {
        State state = ACTIVE.get();
        return state != null && state.allow && state.source == source;
    }

    public static boolean isDenied(Object source) {
        State state = ACTIVE.get();
        return state != null && !state.allow && state.source == source;
    }

    private record State(Object source, boolean allow) {
    }
}
