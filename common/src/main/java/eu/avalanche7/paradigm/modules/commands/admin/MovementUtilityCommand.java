package eu.avalanche7.paradigm.modules.commands.admin;

import eu.avalanche7.paradigm.core.Services;
import eu.avalanche7.paradigm.platform.Interfaces.ICommandBuilder;
import eu.avalanche7.paradigm.platform.Interfaces.ICommandSource;
import eu.avalanche7.paradigm.platform.Interfaces.IPlayer;
import eu.avalanche7.paradigm.modules.permissions.PermissionsHandler;

public class MovementUtilityCommand extends AbstractAdminCommand {
    @Override
    public String getName() {
        return "MovementUtility";
    }

    @Override
    public void registerCommands(Object dispatcher, Object registryAccess, Services services) {
        this.services = services;
        registerTop();
        registerJump();
    }

    private void registerTop() {
        ICommandBuilder cmd = builder()
                .literal("top")
                .requires(src -> allowed(src, "top", PermissionsHandler.TOP_PERMISSION, PermissionsHandler.TOP_PERMISSION_LEVEL)
                        && src.getPlayer() != null)
                .executes(ctx -> top(ctx.getSource()));
        services.getPlatformAdapter().registerCommand(cmd);
    }

    private void registerJump() {
        ICommandBuilder cmd = builder()
                .literal("jump")
                .requires(src -> allowed(src, "jump", PermissionsHandler.JUMP_PERMISSION, PermissionsHandler.JUMP_PERMISSION_LEVEL)
                        && src.getPlayer() != null)
                .executes(ctx -> jump(ctx.getSource(), 8))
                .then(builder()
                        .argument("distance", ICommandBuilder.ArgumentType.INTEGER)
                        .executes(ctx -> jump(ctx.getSource(), ctx.getIntArgument("distance"))));
        services.getPlatformAdapter().registerCommand(cmd);
    }

    private int top(ICommandSource source) {
        IPlayer player = source.getPlayer();
        if (player == null || player.getX() == null || player.getZ() == null) {
            send(source, "admin.player_only", "This command can only be used by a player.");
            return 0;
        }
        Integer height = services.getPlatformAdapter().getHighestBlockY(player);
        if (height == null) {
            send(source, "admin.top_fail", "Could not find top block at your position.");
            return 0;
        }
        services.getPlatformAdapter().teleportPlayer(player, player.getX(), height + 1.0, player.getZ());
        send(source, "admin.top_ok", "Teleported to top block.");
        return 1;
    }

    private int jump(ICommandSource source, int distance) {
        IPlayer player = source.getPlayer();
        if (player == null) {
            send(source, "admin.player_only", "This command can only be used by a player.");
            return 0;
        }
        int clamped = Math.max(1, Math.min(distance, 128));
        if (!services.getPlatformAdapter().jumpPlayerForward(player, clamped)) {
            send(source, "admin.jump_fail", "Could not jump forward.");
            return 0;
        }
        send(source, "admin.jump_ok", "Jumped {distance} blocks forward.", "{distance}", String.valueOf(clamped));
        return 1;
    }

}
