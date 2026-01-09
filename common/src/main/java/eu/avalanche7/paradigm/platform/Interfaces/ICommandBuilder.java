package eu.avalanche7.paradigm.platform.Interfaces;

import java.util.List;
import java.util.function.Predicate;

/**
 * Platform-agnostic command builder interface.
 * Wraps Brigadier command building functionality.
 */
public interface ICommandBuilder {

    /**
     * Create a literal command node.
     */
    ICommandBuilder literal(String name);

    /**
     * Create an argument.
     */
    ICommandBuilder argument(String name, ArgumentType type);

    /**
     * Add a requirement/permission check.
     */
    ICommandBuilder requires(Predicate<ICommandSource> requirement);

    /**
     * Add command execution logic.
     */
    ICommandBuilder executes(CommandExecutor executor);

    /**
     * Add suggestions/completions for the current argument node.
     * No-op for literal nodes.
     */
    default ICommandBuilder suggests(SuggestionProvider provider) {
        return this;
    }

    /**
     * Convenience: static suggestions list.
     */
    default ICommandBuilder suggests(List<String> suggestions) {
        return suggests((ctx, input) -> suggestions);
    }

    /**
     * Add a child/subcommand.
     */
    ICommandBuilder then(ICommandBuilder child);

    /**
     * Get the underlying platform-specific command object.
     */
    Object build();

    /**
     * Argument types for commands.
     */
    enum ArgumentType {
        STRING,
        GREEDY_STRING,
        INTEGER,
        BOOLEAN,
        PLAYER,
        WORD
    }

    /**
     * Command executor functional interface.
     */
    @FunctionalInterface
    interface CommandExecutor {
        int execute(ICommandContext context) throws Exception;
    }

    /**
     * Suggestion contract (simple, platform-agnostic).
     */
    @FunctionalInterface
    interface SuggestionProvider {
        /**
         * @param context command context (may be "partial", depending on platform)
         * @param input current user input token
         */
        List<String> getSuggestions(ICommandContext context, String input);
    }
}
