package eu.avalanche7.paradigm.modules.commands.admin;

import eu.avalanche7.paradigm.core.Services;
import eu.avalanche7.paradigm.platform.Interfaces.ICommandBuilder;
import eu.avalanche7.paradigm.platform.Interfaces.ICommandSource;
import eu.avalanche7.paradigm.platform.Interfaces.IPlayer;
import eu.avalanche7.paradigm.modules.permissions.PermissionsHandler;

public class RepairCommand extends AbstractAdminCommand {
    @Override
    public String getName() {
        return "Repair";
    }

    @Override
    public void registerCommands(Object dispatcher, Object registryAccess, Services services) {
        this.services = services;
        ICommandBuilder root = builder()
                .literal("repair")
                .requires(src -> allowed(src, "repair", PermissionsHandler.REPAIR_PERMISSION, PermissionsHandler.REPAIR_PERMISSION_LEVEL))
                .executes(ctx -> repair(ctx.getSource(), ctx.getSource().getPlayer(), false))
                .then(builder()
                        .literal("all")
                        .executes(ctx -> repair(ctx.getSource(), ctx.getSource().getPlayer(), true))
                        .then(builder()
                                .argument("player", ICommandBuilder.ArgumentType.PLAYER)
                                .executes(ctx -> repair(ctx.getSource(), ctx.getPlayerArgument("player"), true))))
                .then(builder()
                        .argument("player", ICommandBuilder.ArgumentType.PLAYER)
                        .executes(ctx -> repair(ctx.getSource(), ctx.getPlayerArgument("player"), false)));
        services.getPlatformAdapter().registerCommand(root);
    }

    private int repair(ICommandSource source, IPlayer target, boolean all) {
        if (target == null) {
            send(source, "admin.player_not_found", "Player not found.");
            return 0;
        }
        if (!canTargetOther(source.getPlayer(), target, PermissionsHandler.REPAIR_OTHERS_PERMISSION, PermissionsHandler.REPAIR_OTHERS_PERMISSION_LEVEL)) {
            send(source, "admin.no_permission_others", "You do not have permission to affect other players.");
            return 0;
        }
        int changed = services.getPlatformAdapter().repairPlayerItems(target, all);
        send(source, "admin.repair_ok", "Repaired {count} item stack(s) for {player}.",
                "{count}", String.valueOf(changed), "{player}", target.getName());
        return changed > 0 ? 1 : 0;
    }
}
