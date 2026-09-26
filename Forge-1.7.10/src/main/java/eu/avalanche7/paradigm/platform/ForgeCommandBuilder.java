package eu.avalanche7.paradigm.platform;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Predicate;

import eu.avalanche7.paradigm.platform.Interfaces.ICommandBuilder;
import eu.avalanche7.paradigm.platform.Interfaces.ICommandSource;

public final class ForgeCommandBuilder implements ICommandBuilder {
    private String literal;
    private String argument;
    private ArgumentType argumentType;
    private Predicate<ICommandSource> requirement = source -> true;
    private CommandExecutor executor;
    private SuggestionProvider suggestions;
    private final List<ForgeCommandBuilder> children = new ArrayList<>();

    @Override
    public ForgeCommandBuilder literal(String name) {
        literal = name;
        return this;
    }

    @Override
    public ForgeCommandBuilder argument(String name, ArgumentType type) {
        argument = name;
        argumentType = type;
        return this;
    }

    @Override
    public ForgeCommandBuilder requires(Predicate<ICommandSource> value) {
        requirement = value;
        return this;
    }

    @Override
    public ForgeCommandBuilder executes(CommandExecutor value) {
        executor = value;
        return this;
    }

    @Override
    public ForgeCommandBuilder suggests(SuggestionProvider value) {
        suggestions = value;
        return this;
    }

    @Override
    public ForgeCommandBuilder then(ICommandBuilder child) {
        if (!(child instanceof ForgeCommandBuilder nativeChild)) {
            throw new IllegalArgumentException("Expected a Forge 1.7.10 command child");
        }
        children.add(nativeChild);
        return this;
    }

    @Override
    public Object build() {
        return this;
    }

    public String literalName() {
        return literal;
    }

    public String argumentName() {
        return argument;
    }

    public ArgumentType argumentType() {
        return argumentType;
    }

    public Predicate<ICommandSource> requirement() {
        return requirement;
    }

    public CommandExecutor executor() {
        return executor;
    }

    public SuggestionProvider suggestions() {
        return suggestions;
    }

    public List<ForgeCommandBuilder> children() {
        return List.copyOf(children);
    }
}
