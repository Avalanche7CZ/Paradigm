package eu.avalanche7.paradigm.platform.Interfaces;

import org.jetbrains.annotations.Nullable;

import java.util.Optional;

/**
 * Platform-agnostic command execution context.
 */
public interface ICommandContext {

    /**
     * Get the command source (player or console).
     */
    ICommandSource getSource();

    /**
     * Get a string argument by name.
     */
    String getStringArgument(String name);

    /**
     * Get an integer argument by name.
     */
    int getIntArgument(String name);

    /**
     * Get a boolean argument by name.
     */
    boolean getBooleanArgument(String name);

    /**
     * Get a player argument by name.
     */
    @Nullable
    IPlayer getPlayerArgument(String name);

    /**
     * Optional variants (prefer these in new code).
     */
    default Optional<String> getStringArgumentOpt(String name) {
        String v = getStringArgument(name);
        return (v == null || v.isEmpty()) ? Optional.empty() : Optional.of(v);
    }

    default Optional<Integer> getIntArgumentOpt(String name) {
        return Optional.of(getIntArgument(name));
    }

    default Optional<Boolean> getBooleanArgumentOpt(String name) {
        return Optional.of(getBooleanArgument(name));
    }

    default Optional<IPlayer> getPlayerArgumentOpt(String name) {
        return Optional.ofNullable(getPlayerArgument(name));
    }

    default String requireString(String name) {
        return getStringArgumentOpt(name)
                .orElseThrow(() -> new IllegalArgumentException("Missing argument: " + name));
    }

    default int requireInt(String name) {
        return getIntArgumentOpt(name)
                .orElseThrow(() -> new IllegalArgumentException("Missing argument: " + name));
    }

    default boolean requireBoolean(String name) {
        return getBooleanArgumentOpt(name)
                .orElseThrow(() -> new IllegalArgumentException("Missing argument: " + name));
    }

    default IPlayer requirePlayer(String name) {
        return getPlayerArgumentOpt(name)
                .orElseThrow(() -> new IllegalArgumentException("Missing/invalid player argument: " + name));
    }

    Object getOriginalContext();
}
