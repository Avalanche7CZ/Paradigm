package eu.avalanche7.paradigm.platform.Interfaces;

import org.jetbrains.annotations.Nullable;

public interface ICommandSource {
    @Nullable
    IPlayer getPlayer();

    default boolean isPlayer() {
        return getPlayer() != null;
    }

    default IPlayer requirePlayer() {
        IPlayer p = getPlayer();
        if (p == null) {
            throw new IllegalStateException("This command can only be executed by a player.");
        }
        return p;
    }

    String getSourceName();
    boolean hasPermissionLevel(int level);
    boolean isConsole();
    Object getOriginalSource();
}
