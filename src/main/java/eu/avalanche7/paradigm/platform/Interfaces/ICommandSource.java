package eu.avalanche7.paradigm.platform.Interfaces;

import javax.annotation.Nullable;

/**
 * Platform-independent command source abstraction
 */
public interface ICommandSource {
    /**
     * Gets the player associated with this command source, if any
     * @return IPlayer if executed by a player, null if executed by console/other
     */
    @Nullable
    IPlayer getPlayer();

    /**
     * Gets the name of the command source (player name or "Console")
     * @return the display name of the command source
     */
    String getSourceName();

    /**
     * Checks if this command source has the specified permission level
     * @param level the minimum permission level required
     * @return true if the source has the permission level
     */
    boolean hasPermissionLevel(int level);

    /**
     * Checks if this is a console command source
     * @return true if executed by console
     */
    boolean isConsole();

    /**
     * Gets the original platform-specific command source for advanced operations
     * @return the underlying command source object
     */
    Object getOriginalSource();
}
