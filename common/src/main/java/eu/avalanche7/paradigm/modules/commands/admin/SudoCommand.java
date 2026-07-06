package eu.avalanche7.paradigm.modules.commands.admin;

import eu.avalanche7.paradigm.core.Services;
import eu.avalanche7.paradigm.platform.Interfaces.ICommandBuilder;
import eu.avalanche7.paradigm.platform.Interfaces.ICommandSource;
import eu.avalanche7.paradigm.platform.Interfaces.IPlayer;
import eu.avalanche7.paradigm.utils.PermissionsHandler;

public class SudoCommand extends AbstractAdminCommand {
    @Override
    public String getName() {
        return "Sudo";
    }

    @Override
    public void registerCommands(Object dispatcher, Object registryAccess, Services services) {
        this.services = services;
        ICommandBuilder cmd = builder()
                .literal("sudo")
                .requires(src -> allowed(src, "sudo", PermissionsHandler.SUDO_PERMISSION, PermissionsHandler.SUDO_PERMISSION_LEVEL))
                .then(builder()
                        .argument("player", ICommandBuilder.ArgumentType.PLAYER)
                        .then(builder()
                                .argument("command", ICommandBuilder.ArgumentType.GREEDY_STRING)
                                .executes(ctx -> sudo(ctx.getSource(), ctx.getPlayerArgument("player"), ctx.getStringArgument("command")))));
        services.getPlatformAdapter().registerCommand(cmd);
    }

    private int sudo(ICommandSource source, IPlayer target, String commandRaw) {
        if (target == null) {
            send(source, "admin.player_not_found", "Player not found.");
            return 0;
        }
        String command = commandRaw != null ? commandRaw.trim() : "";
        while (command.startsWith("/")) {
            command = command.substring(1).trim();
        }
        if (command.isBlank()) {
            send(source, "admin.sudo_missing", "Command is missing.");
            return 0;
        }
        services.getPlatformAdapter().executeCommandAsConsole("execute as " + target.getName() + " at " + target.getName() + " run " + command);
        send(source, "admin.sudo_ok", "Executed command as {player}.", "{player}", target.getName());
        return 1;
    }
}
