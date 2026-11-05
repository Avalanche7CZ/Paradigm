package eu.avalanche7.paradigm.modules.commands;

import eu.avalanche7.paradigm.core.ParadigmModule;
import eu.avalanche7.paradigm.core.Services;
import eu.avalanche7.paradigm.data.PlayerDataStore.StoredLocation;
import eu.avalanche7.paradigm.modules.permissions.ParadigmPermissions;
import eu.avalanche7.paradigm.modules.permissions.PermissionDefinition;
import eu.avalanche7.paradigm.platform.Interfaces.ICommandBuilder;
import eu.avalanche7.paradigm.platform.Interfaces.ICommandSource;
import eu.avalanche7.paradigm.platform.Interfaces.IPlayer;

public final class TpCommand implements ParadigmModule {
    private Services services;

    @Override public String getName() { return "Tp"; }
    @Override public boolean isEnabled(Services services) {
        return services == null || services.getMainConfig() == null || Boolean.TRUE.equals(services.getMainConfig().tpCommandEnable.value);
    }
    @Override public void onLoad(Object event, Services services, Object bus) { this.services = services; }
    @Override public void onServerStarting(Object event, Services services) { }
    @Override public void onEnable(Services services) { }
    @Override public void onDisable(Services services) { }
    @Override public void onServerStopping(Object event, Services services) { }
    @Override public void registerEventListeners(Object bus, Services services) { }

    @Override public void registerCommands(Object dispatcher, Object registry, Services services) {
        var platform = services.getPlatformAdapter();
        platform.registerCommand(platform.createCommandBuilder().literal("tp")
                .requires(this::canUse)
                .then(platform.createCommandBuilder().argument("arguments", ICommandBuilder.ArgumentType.GREEDY_STRING)
                        .suggests((ctx, input) -> platform.getVisiblePlayerNames(ctx.getSource().getPlayer()))
                        .executes(ctx -> {
                            if (platform.isServerThread()) return execute(ctx.getSource(), ctx.getStringArgument("arguments"));
                            platform.executeOnServerThread(() -> execute(ctx.getSource(), ctx.getStringArgument("arguments")));
                            return 1;
                        })));
    }

    private boolean allowed(ICommandSource source, PermissionDefinition permission) {
        return source.getPlayer() != null
                ? services.getPermissionsHandler().hasPermission(source.getPlayer(), permission)
                : source.isConsole() || source.hasPermissionLevel(2);
    }

    private boolean canUse(ICommandSource source) {
        return isEnabled(services) && services.getCommandToggleStore().isEnabled("tp")
                && (allowed(source, ParadigmPermissions.TP) || allowed(source, ParadigmPermissions.TP_PLAYER)
                || allowed(source, ParadigmPermissions.TP_OTHERS));
    }

    int execute(ICommandSource source, String input) {
        if (!canUse(source)) return fail(source, "denied");
        String[] args = input.trim().split("\\s+");
        int count = args.length;
        boolean coordinates = count == 3 || count == 4 || count == 5 || count == 6;
        if (!coordinates && count != 1 && count != 2) return fail(source, "usage");
        boolean explicitTarget = coordinates ? count == 4 || count == 6 : count == 2;
        int offset = explicitTarget ? 1 : 0;
        for (String arg : args) {
            if (arg.startsWith("@") && !allowed(source, ParadigmPermissions.TP_OTHERS)) return fail(source, "denied");
        }
        var platform = services.getPlatformAdapter();
        IPlayer moving = explicitTarget ? platform.resolveTeleportPlayer(source, args[0]) : source.getPlayer();
        if (moving == null) return fail(source, "player_missing");
        boolean self = source.getPlayer() != null && source.getPlayer().getUUID() != null
                && source.getPlayer().getUUID().equals(moving.getUUID());
        PermissionDefinition required = !self ? ParadigmPermissions.TP_OTHERS
                : coordinates ? ParadigmPermissions.TP : ParadigmPermissions.TP_PLAYER;
        if (!allowed(source, required)) return fail(source, "denied");
        StoredLocation location;
        if (coordinates) {
            StoredLocation origin = platform.getPlayerLocation(moving).orElse(null);
            if (origin == null) return fail(source, "failed");
            try {
                double x = coordinate(args[offset], origin.getX(), true);
                double y = coordinate(args[offset + 1], origin.getY(), false);
                double z = coordinate(args[offset + 2], origin.getZ(), true);
                float yaw = origin.getYaw();
                float pitch = origin.getPitch();
                if (count - offset == 5) {
                    yaw = rotation(args[offset + 3], yaw);
                    pitch = rotation(args[offset + 4], pitch);
                    if (pitch > 90 || pitch < -90) {
                        pitch = rotation(Double.toString(180 - pitch), 0);
                        yaw = rotation(Double.toString(yaw + 180), 0);
                    }
                }
                location = new StoredLocation(origin.getWorldId(), x, y, z, yaw, pitch);
            } catch (IllegalArgumentException invalid) {
                return fail(source, "usage");
            }
        } else {
            IPlayer destination = platform.resolveTeleportPlayer(source, args[offset]);
            if (destination == null) return fail(source, "player_missing");
            location = platform.getPlayerLocation(destination).orElse(null);
            if (location == null) return fail(source, "failed");
        }
        if (!platform.teleportPlayer(moving, location)) return fail(source, "failed");
        platform.sendSuccess(source, platform.createLiteralComponent(message("success")), false);
        return 1;
    }

    static double coordinate(String token, double base, boolean center) {
        boolean relative = token.startsWith("~");
        String value = relative ? token.substring(1) : token;
        if (value.isEmpty() && !relative) throw new IllegalArgumentException("Missing coordinate");
        double number = value.isEmpty() ? 0 : Double.parseDouble(value);
        double result = relative ? base + number : number + (center && !token.contains(".") ? 0.5 : 0);
        if (!Double.isFinite(number) || !Double.isFinite(result) || result < -30000000 || result > 30000000) {
            throw new IllegalArgumentException("Coordinate outside world bounds");
        }
        return result;
    }

    static float rotation(String token, float base) {
        boolean relative = token.startsWith("~");
        String value = relative ? token.substring(1) : token;
        double number = value.isEmpty() && relative ? 0 : Double.parseDouble(value);
        double result = relative ? base + number : number;
        if (!Double.isFinite(result)) throw new IllegalArgumentException("Invalid rotation");
        result = ((result % 360) + 540) % 360 - 180;
        return (float) result;
    }

    private String message(String key) { return services.getLang().getTranslation("tp." + key); }
    private int fail(ICommandSource source, String key) {
        services.getPlatformAdapter().sendFailure(source, services.getPlatformAdapter().createLiteralComponent(message(key)));
        return 0;
    }
}
