package eu.avalanche7.paradigm.modules.commands.moderation;

import eu.avalanche7.paradigm.core.Services;
import eu.avalanche7.paradigm.platform.Interfaces.ICommandBuilder;
import eu.avalanche7.paradigm.platform.Interfaces.ICommandSource;
import eu.avalanche7.paradigm.platform.Interfaces.IPlayer;
import eu.avalanche7.paradigm.modules.permissions.PermissionsHandler;

public class KickCommand extends AbstractModerationCommand {
    @Override
    public String getName() {
        return "Kick";
    }

    @Override
    public void registerCommands(Object dispatcher, Object registryAccess, Services services) {
        this.services = services;
        ICommandBuilder cmd = builder()
                .literal("kick")
                .requires(src -> allowed(src, "kick", PermissionsHandler.KICK_PERMISSION, PermissionsHandler.KICK_PERMISSION_LEVEL))
                .then(builder()
                        .argument("player", ICommandBuilder.ArgumentType.PLAYER)
                        .executes(ctx -> kick(ctx.getSource(), ctx.getPlayerArgument("player"), null))
                        .then(builder()
                                .argument("reason", ICommandBuilder.ArgumentType.GREEDY_STRING)
                                .executes(ctx -> kick(ctx.getSource(), ctx.getPlayerArgument("player"), ctx.getStringArgument("reason")))));
        services.getPlatformAdapter().registerCommand(cmd);
    }

    private int kick(ICommandSource source, IPlayer target, String rawReason) {
        if (target == null) {
            send(source, "moderation.player_not_found", "Player not found.");
            return 0;
        }
        String reason = reason(rawReason);
        services.getPlatformAdapter().executeCommandAsConsole("kick " + target.getName() + " " + reason);
        send(source, "moderation.kick_ok", "Kicked {player}.", "{player}", target.getName());
        return 1;
    }
}
