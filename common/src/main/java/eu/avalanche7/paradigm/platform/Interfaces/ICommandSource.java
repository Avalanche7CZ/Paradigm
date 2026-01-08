package eu.avalanche7.paradigm.platform.Interfaces;

import org.jetbrains.annotations.Nullable;

public interface ICommandSource {
    @Nullable
    IPlayer getPlayer();
    String getSourceName();
    boolean hasPermissionLevel(int level);
    boolean isConsole();
    Object getOriginalSource();
}
