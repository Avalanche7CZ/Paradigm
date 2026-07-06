package eu.avalanche7.paradigm.modules.commands.admin;

import eu.avalanche7.paradigm.core.Services;
import eu.avalanche7.paradigm.modules.commands.shared.PlayerReflection;
import eu.avalanche7.paradigm.platform.Interfaces.ICommandBuilder;
import eu.avalanche7.paradigm.platform.Interfaces.ICommandSource;
import eu.avalanche7.paradigm.platform.Interfaces.IPlayer;
import eu.avalanche7.paradigm.utils.PermissionsHandler;

import java.lang.reflect.Method;
import java.util.Locale;

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
        Integer height = highestBlockY(player);
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
        services.getPlatformAdapter().executeCommandAsConsole("execute as " + player.getName() + " at " + player.getName() + " run tp " + player.getName() + " ^ ^ ^" + clamped);
        send(source, "admin.jump_ok", "Jumped {distance} blocks forward.", "{distance}", String.valueOf(clamped));
        return 1;
    }

    private Integer highestBlockY(IPlayer player) {
        Object handle = player != null ? player.getOriginalPlayer() : null;
        Object world = PlayerReflection.invokeNoArg(handle, "serverLevel", "getCommandSenderWorld", "getWorld", "level");
        if (world == null) {
            world = PlayerReflection.readField(handle, "level", "world");
        }
        if (world == null || player.getX() == null || player.getZ() == null) {
            return null;
        }
        int x = (int) Math.floor(player.getX());
        int z = (int) Math.floor(player.getZ());
        for (Method method : world.getClass().getMethods()) {
            if (!method.getName().equals("getHeight") || method.getParameterCount() != 3) {
                continue;
            }
            Class<?> type = method.getParameterTypes()[0];
            if (!type.isEnum()) {
                continue;
            }
            Object heightmap = enumConstant(type, "MOTION_BLOCKING_NO_LEAVES", "MOTION_BLOCKING");
            if (heightmap == null) {
                continue;
            }
            try {
                method.setAccessible(true);
                Object value = method.invoke(world, heightmap, x, z);
                if (value instanceof Number n) {
                    return n.intValue();
                }
            } catch (Throwable ignored) {
            }
        }
        return null;
    }

    @SuppressWarnings({"unchecked", "rawtypes"})
    private Object enumConstant(Class<?> type, String... names) {
        for (String wanted : names) {
            for (Object value : type.getEnumConstants()) {
                if (value instanceof Enum<?> e && e.name().equalsIgnoreCase(wanted)) {
                    return Enum.valueOf((Class<? extends Enum>) type.asSubclass(Enum.class), e.name().toUpperCase(Locale.ROOT));
                }
            }
        }
        return null;
    }
}
