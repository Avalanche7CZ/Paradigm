package eu.avalanche7.paradigm.modules.chat;

public enum ChatRoute {
    PUBLIC,
    STAFF,
    GROUP;

    public static ChatRoute resolve(boolean staffMode, boolean groupMode) {
        if (staffMode) return STAFF;
        if (groupMode) return GROUP;
        return PUBLIC;
    }
}
