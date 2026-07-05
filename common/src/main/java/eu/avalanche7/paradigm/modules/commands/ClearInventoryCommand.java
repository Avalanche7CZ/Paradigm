package eu.avalanche7.paradigm.modules.commands;

import eu.avalanche7.paradigm.core.ParadigmModule;
import eu.avalanche7.paradigm.core.Services;
import eu.avalanche7.paradigm.platform.Interfaces.ICommandBuilder;
import eu.avalanche7.paradigm.platform.Interfaces.IPlayer;
import eu.avalanche7.paradigm.utils.PermissionsHandler;

public class ClearInventoryCommand implements ParadigmModule {
    private Services services;

    @Override
    public String getName() {
        return "ClearInventory";
    }

    @Override
    public boolean isEnabled(Services services) {
        return services == null
                || services.getMainConfig() == null
                || Boolean.TRUE.equals(services.getMainConfig().clearInventoryCommandEnable.value);
    }

    @Override
    public void onLoad(Object event, Services services, Object modEventBus) {
        this.services = services;
    }

    @Override
    public void onServerStarting(Object event, Services services) {
    }

    @Override
    public void onEnable(Services services) {
    }

    @Override
    public void onDisable(Services services) {
    }

    @Override
    public void onServerStopping(Object event, Services services) {
    }

    @Override
    public void registerCommands(Object dispatcher, Object registryAccess, Services services) {
        registerLiteral("clearinv");
        registerLiteral("ci");
    }

    @Override
    public void registerEventListeners(Object eventBus, Services services) {
    }

    private void registerLiteral(String literal) {
        ICommandBuilder cmd = services.getPlatformAdapter().createCommandBuilder()
                .literal(literal)
                .requires(src -> services.getCommandToggleStore().isEnabled("clearinv")
                        && src.getPlayer() != null
                        && services.getPermissionsHandler().hasPermission(src.getPlayer(), PermissionsHandler.CLEARINV_PERMISSION, PermissionsHandler.CLEARINV_PERMISSION_LEVEL))
                .executes(ctx -> clearInventory(ctx.getSource().getPlayer(), ctx.getSource().getPlayer()))
                .then(services.getPlatformAdapter().createCommandBuilder()
                        .argument("player", ICommandBuilder.ArgumentType.PLAYER)
                        .executes(ctx -> clearInventory(ctx.getSource().getPlayer(), ctx.getPlayerArgument("player"))));
        services.getPlatformAdapter().registerCommand(cmd);
    }

    private int clearInventory(IPlayer actor, IPlayer target) {
        if (actor == null || target == null) {
            return 0;
        }
        if (!canTargetOther(actor, target)) {
            send(actor, "utility.no_permission_others", "You do not have permission to affect other players.");
            return 0;
        }
        if (!clearInventoryNative(target)) {
            send(actor, "utility.clearinv_fail", "Could not clear inventory for {player}.", "{player}", target.getName());
            return 0;
        }
        send(actor, "utility.clearinv_ok", "Cleared inventory for {player}.", "{player}", target.getName());
        return 1;
    }

    private boolean clearInventoryNative(IPlayer target) {
        Object handle = target != null ? target.getOriginalPlayer() : null;
        Object inventory = invokeNoArg(handle, "getInventory");
        if (inventory == null) {
            inventory = readField(handle, "inventory");
        }
        if (inventory == null) {
            return false;
        }
        return invokeNoArgBool(inventory, "clearContent", "clear");
    }

    private Object invokeNoArg(Object target, String methodName) {
        if (target == null) {
            return null;
        }
        try {
            var method = target.getClass().getMethod(methodName);
            method.setAccessible(true);
            return method.invoke(target);
        } catch (Throwable ignored) {
            return null;
        }
    }

    private boolean invokeNoArgBool(Object target, String... methodNames) {
        if (target == null) {
            return false;
        }
        for (String methodName : methodNames) {
            try {
                var method = target.getClass().getMethod(methodName);
                method.setAccessible(true);
                method.invoke(target);
                return true;
            } catch (Throwable ignored) {
            }
        }
        return false;
    }

    private Object readField(Object target, String fieldName) {
        if (target == null) {
            return null;
        }
        try {
            var field = target.getClass().getDeclaredField(fieldName);
            field.setAccessible(true);
            return field.get(target);
        } catch (Throwable ignored) {
            return null;
        }
    }

    private boolean canTargetOther(IPlayer actor, IPlayer target) {
        if (actor == null || target == null || actor.getUUID() == null || actor.getUUID().equals(target.getUUID())) {
            return true;
        }
        return services.getPermissionsHandler().hasPermission(actor, PermissionsHandler.CLEARINV_OTHERS_PERMISSION, PermissionsHandler.CLEARINV_OTHERS_PERMISSION_LEVEL);
    }

    private void send(IPlayer player, String key, String fallback, String... placeholders) {
        if (player == null) {
            return;
        }
        String raw = services.getLang().getTranslation(key);
        if (raw == null || raw.equals(key)) {
            raw = fallback;
        }
        for (int i = 0; i + 1 < placeholders.length; i += 2) {
            raw = raw.replace(placeholders[i], placeholders[i + 1]);
        }
        String decorated = "<color:#22D3EE><bold>[Utility]</bold></color> <color:#E5E7EB>" + raw + "</color>";
        services.getPlatformAdapter().sendSystemMessage(player, services.getMessageParser().parseMessage(decorated, player));
    }
}
