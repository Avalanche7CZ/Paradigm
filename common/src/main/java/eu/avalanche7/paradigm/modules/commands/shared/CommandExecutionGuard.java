package eu.avalanche7.paradigm.modules.commands.shared;

import eu.avalanche7.paradigm.ParadigmAPI;
import eu.avalanche7.paradigm.core.Services;
import eu.avalanche7.paradigm.platform.Interfaces.ICommandBuilder;
import eu.avalanche7.paradigm.platform.Interfaces.ICommandContext;
import eu.avalanche7.paradigm.platform.Interfaces.IPlatformAdapter;

/** Shared exception boundary used by every loader's Brigadier adapter. */
public final class CommandExecutionGuard {
    private CommandExecutionGuard() {
    }

    public static int execute(ICommandBuilder.CommandExecutor executor, ICommandContext context) {
        try {
            return executor.execute(context);
        } catch (Throwable failure) {
            Services services = ParadigmAPI.getServices();
            IPlatformAdapter platform = services != null ? services.getPlatformAdapter() : null;
            String input = context != null ? context.getInput() : "";
            String root = rootOf(input);
            String loader = platform != null ? platform.getLoaderName() : "unavailable";
            String minecraft = platform != null ? platform.getMinecraftVersion() : "unavailable";

            if (services != null && services.getLogger() != null) {
                services.getLogger().error(
                        "Paradigm command failed: root={} loader={} minecraft={} input={}",
                        root, loader, minecraft, input, failure
                );
            }

            if (platform != null && context != null && context.getSource() != null) {
                String message = "Command failed. Check the server log for details.";
                if (services.getLang() != null) {
                    String translated = services.getLang().getTranslation("command.execution_failed");
                    if (translated != null && !translated.equals("command.execution_failed")) message = translated;
                }
                platform.sendFailure(context.getSource(), platform.createLiteralComponent(message));
            }
            return 0;
        }
    }

    static String rootOf(String input) {
        if (input == null) return "unknown";
        String normalized = input.stripLeading();
        if (normalized.startsWith("/")) normalized = normalized.substring(1);
        int separator = normalized.indexOf(' ');
        String root = separator >= 0 ? normalized.substring(0, separator) : normalized;
        return root.isBlank() ? "unknown" : root;
    }
}
