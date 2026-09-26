package eu.avalanche7.paradigm.platform;

import java.util.Map;

import eu.avalanche7.paradigm.platform.Interfaces.ICommandContext;
import eu.avalanche7.paradigm.platform.Interfaces.ICommandSource;
import eu.avalanche7.paradigm.platform.Interfaces.IPlayer;

public final class ForgeCommandContext implements ICommandContext {
    private final ICommandSource source;
    private final Map<String, Object> arguments;
    private final String input;

    public ForgeCommandContext(ICommandSource source, Map<String, Object> arguments, String input) {
        this.source = source;
        this.arguments = Map.copyOf(arguments);
        this.input = input;
    }

    @Override
    public ICommandSource getSource() {
        return source;
    }

    @Override
    public String getStringArgument(String name) {
        Object value = arguments.get(name);
        return value instanceof String ? (String) value : "";
    }

    @Override
    public int getIntArgument(String name) {
        Object value = arguments.get(name);
        return value instanceof Integer ? (Integer) value : 0;
    }

    @Override
    public boolean getBooleanArgument(String name) {
        Object value = arguments.get(name);
        return value instanceof Boolean && (Boolean) value;
    }

    @Override
    public IPlayer getPlayerArgument(String name) {
        Object value = arguments.get(name);
        return value instanceof IPlayer ? (IPlayer) value : null;
    }

    @Override
    public Object getOriginalContext() {
        return this;
    }

    @Override
    public String getInput() {
        return input;
    }
}
