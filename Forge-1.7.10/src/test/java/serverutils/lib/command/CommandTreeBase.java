package serverutils.lib.command;

import java.util.Arrays;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.Map;

import net.minecraft.command.CommandBase;
import net.minecraft.command.ICommand;
import net.minecraft.command.ICommandSender;

public abstract class CommandTreeBase extends CommandBase {
    private final Map<String, ICommand> commands = new LinkedHashMap<>();
    private final Map<String, ICommand> aliases = new LinkedHashMap<>();

    public void addSubcommand(ICommand command) {
        commands.put(command.getCommandName(), command);
        if (command.getCommandAliases() != null) {
            for (Object alias : command.getCommandAliases()) aliases.put((String) alias, command);
        }
    }

    public Collection<ICommand> getSubCommands() {
        return commands.values();
    }

    public ICommand getSubCommand(String label) {
        return commands.getOrDefault(label, aliases.get(label));
    }

    @Override
    public void processCommand(ICommandSender sender, String[] args) {
        if (args.length == 0) return;
        ICommand command = getSubCommand(args[0]);
        if (command != null && command.canCommandSenderUseCommand(sender)) {
            command.processCommand(sender, Arrays.copyOfRange(args, 1, args.length));
        }
    }
}
