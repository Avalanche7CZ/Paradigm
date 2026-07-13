package eu.avalanche7.paradigm.modules.chat;

/** Routing decision for a player-originated chat message. */
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
