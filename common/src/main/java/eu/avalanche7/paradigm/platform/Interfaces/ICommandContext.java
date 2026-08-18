package eu.avalanche7.paradigm.platform.Interfaces;

import java.util.Optional;

import org.jetbrains.annotations.Nullable;

public interface ICommandContext {

    ICommandSource getSource();

    String getStringArgument(String name);

    int getIntArgument(String name);

    boolean getBooleanArgument(String name);

    @Nullable
    IPlayer getPlayerArgument(String name);

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

    default String getInput() {
        return "";
    }
}
