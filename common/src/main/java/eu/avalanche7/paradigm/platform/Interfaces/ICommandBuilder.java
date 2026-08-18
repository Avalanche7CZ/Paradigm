package eu.avalanche7.paradigm.platform.Interfaces;

import java.util.List;
import java.util.function.Predicate;

public interface ICommandBuilder {

    ICommandBuilder literal(String name);

    ICommandBuilder argument(String name, ArgumentType type);

    ICommandBuilder requires(Predicate<ICommandSource> requirement);

    ICommandBuilder executes(CommandExecutor executor);

    default ICommandBuilder suggests(SuggestionProvider provider) {
        return this;
    }

    default ICommandBuilder suggests(List<String> suggestions) {
        return suggests((ctx, input) -> suggestions);
    }

    ICommandBuilder then(ICommandBuilder child);

    Object build();

    enum ArgumentType {
        STRING,
        GREEDY_STRING,
        INTEGER,
        BOOLEAN,
        PLAYER,
        WORD
    }

    @FunctionalInterface
    interface CommandExecutor {
        int execute(ICommandContext context) throws Exception;
    }

    @FunctionalInterface
    interface SuggestionProvider {

        List<String> getSuggestions(ICommandContext context, String input);
    }
}
