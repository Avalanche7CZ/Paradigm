package eu.avalanche7.paradigm.modules.commands;

import eu.avalanche7.paradigm.core.ParadigmModule;
import eu.avalanche7.paradigm.core.Services;
import eu.avalanche7.paradigm.platform.Interfaces.ICommandBuilder;
import eu.avalanche7.paradigm.platform.Interfaces.IPlayer;
import eu.avalanche7.paradigm.utils.PermissionsHandler;

import java.lang.reflect.Field;

public class FlyCommand implements ParadigmModule {
    private Services services;

    @Override
    public String getName() {
        return "Fly";
    }

    @Override
    public boolean isEnabled(Services services) {
        return services == null
                || services.getMainConfig() == null
                || Boolean.TRUE.equals(services.getMainConfig().flyCommandEnable.value);
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
        ICommandBuilder cmd = services.getPlatformAdapter().createCommandBuilder()
                .literal("fly")
                .requires(src -> services.getCommandToggleStore().isEnabled("fly")
                        && src.getPlayer() != null
                        && services.getPermissionsHandler().hasPermission(src.getPlayer(), PermissionsHandler.FLY_PERMISSION, PermissionsHandler.FLY_PERMISSION_LEVEL))
                .executes(ctx -> toggleFly(ctx.getSource().getPlayer(), ctx.getSource().getPlayer()))
                .then(services.getPlatformAdapter().createCommandBuilder()
                        .argument("player", ICommandBuilder.ArgumentType.PLAYER)
                        .executes(ctx -> toggleFly(ctx.getSource().getPlayer(), ctx.getPlayerArgument("player"))));
        services.getPlatformAdapter().registerCommand(cmd);
    }

    @Override
    public void registerEventListeners(Object eventBus, Services services) {
    }

    private int toggleFly(IPlayer actor, IPlayer target) {
        if (actor == null || target == null || target.getOriginalPlayer() == null) {
            return 0;
        }

        Object abilities = getAbilities(target.getOriginalPlayer());
        if (abilities == null) {
            send(actor, "utility.fly_failed", "Could not toggle fly for {player}.", "{player}", target.getName());
            return 0;
        }

        boolean currentlyAllowed = readBooleanField(abilities, "mayfly", "mayFly", "allowFlying");
        boolean next = !currentlyAllowed;

        boolean mayflySet = writeBooleanField(abilities, next, "mayfly", "mayFly", "allowFlying");
        boolean flyingSet = writeBooleanField(abilities, next && readBooleanField(abilities, "flying", "isFlying"), "flying", "isFlying");
        if (!next) {
            // Always force-stop flying when disabling fly mode.
            flyingSet = writeBooleanField(abilities, false, "flying", "isFlying") || flyingSet;
        }
        if (!mayflySet && !flyingSet) {
            send(actor, "utility.fly_failed", "Could not toggle fly for {player}.", "{player}", target.getName());
            return 0;
        }

        invokeNoArg(target.getOriginalPlayer(), "onUpdateAbilities");
        invokeNoArg(target.getOriginalPlayer(), "updateAbilities");
        invokeNoArg(target.getOriginalPlayer(), "sendAbilitiesUpdate");

        send(actor, "utility.fly_toggled", "Fly for {player}: {state}", "{player}", target.getName(), "{state}", next ? "on" : "off");
        return 1;
    }

    private Object getAbilities(Object playerHandle) {
        if (playerHandle == null) {
            return null;
        }
        Object abilities = invokeNoArg(playerHandle, "getAbilities");
        if (abilities != null) {
            return abilities;
        }
        return readField(playerHandle, "abilities");
    }

    private Object invokeNoArg(Object target, String methodName) {
        try {
            var method = target.getClass().getMethod(methodName);
            return method.invoke(target);
        } catch (Throwable ignored) {
            return null;
        }
    }

    private Object readField(Object target, String fieldName) {
        try {
            Field field = target.getClass().getDeclaredField(fieldName);
            field.setAccessible(true);
            return field.get(target);
        } catch (Throwable ignored) {
            return null;
        }
    }

    private boolean readBooleanField(Object target, String... fieldNames) {
        for (String fieldName : fieldNames) {
            try {
                Field field = target.getClass().getDeclaredField(fieldName);
                field.setAccessible(true);
                Object value = field.get(target);
                if (value instanceof Boolean b) {
                    return b;
                }
            } catch (Throwable ignored) {
            }
        }
        return false;
    }

    private boolean writeBooleanField(Object target, boolean value, String... fieldNames) {
        boolean changed = false;
        for (String fieldName : fieldNames) {
            try {
                Field field = target.getClass().getDeclaredField(fieldName);
                field.setAccessible(true);
                field.setBoolean(target, value);
                changed = true;
            } catch (Throwable ignored) {
            }
        }
        return changed;
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

