package eu.avalanche7.paradigm.modules.commands;

import eu.avalanche7.paradigm.core.ParadigmModule;
import eu.avalanche7.paradigm.core.Services;
import eu.avalanche7.paradigm.platform.Interfaces.ICommandBuilder;
import eu.avalanche7.paradigm.platform.Interfaces.IPlayer;
import eu.avalanche7.paradigm.utils.PermissionsHandler;

import java.lang.reflect.Field;
import java.util.Locale;

public class SpeedCommand implements ParadigmModule {
    private Services services;

    @Override
    public String getName() {
        return "Speed";
    }

    @Override
    public boolean isEnabled(Services services) {
        return services == null
                || services.getMainConfig() == null
                || Boolean.TRUE.equals(services.getMainConfig().speedCommandEnable.value);
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
                .literal("speed")
                .requires(src -> services.getCommandToggleStore().isEnabled("speed")
                        && src.getPlayer() != null
                        && services.getPermissionsHandler().hasPermission(src.getPlayer(), PermissionsHandler.SPEED_PERMISSION, PermissionsHandler.SPEED_PERMISSION_LEVEL))
                .then(services.getPlatformAdapter().createCommandBuilder()
                        .argument("level", ICommandBuilder.ArgumentType.INTEGER)
                        .executes(ctx -> applySpeed(ctx.getSource().getPlayer(), ctx.getSource().getPlayer(), ctx.getIntArgument("level")))
                        .then(services.getPlatformAdapter().createCommandBuilder()
                                .argument("player", ICommandBuilder.ArgumentType.PLAYER)
                                .executes(ctx -> applySpeed(ctx.getSource().getPlayer(), ctx.getPlayerArgument("player"), ctx.getIntArgument("level")))));
        services.getPlatformAdapter().registerCommand(cmd);
    }

    @Override
    public void registerEventListeners(Object eventBus, Services services) {
    }

    private int applySpeed(IPlayer actor, IPlayer target, int level) {
        if (target == null) {
            return 0;
        }
        int clamped = Math.max(0, Math.min(level, 10));
        if (!setPlayerSpeed(target, clamped)) {
            send(actor, "utility.speed_fail", "Could not set speed for {player}.", "{player}", target.getName());
            return 0;
        }

        if (clamped == 0) {
            send(actor, "utility.speed_cleared", "Speed cleared for {player}.", "{player}", target.getName());
        } else {
            send(actor, "utility.speed_set", "Set speed {level} for {player}.", "{level}", String.valueOf(clamped), "{player}", target.getName());
        }
        return 1;
    }

    private boolean setPlayerSpeed(IPlayer target, int level) {
        if (target == null || target.getOriginalPlayer() == null) {
            return false;
        }

        Object abilities = getAbilities(target.getOriginalPlayer());
        if (abilities == null) {
            return false;
        }

        float walkSpeed = level <= 0 ? 0.1f : (0.1f * level);
        float flySpeed = level <= 0 ? 0.05f : (0.05f * level);

        boolean walkSet = setSpeedValue(abilities, walkSpeed,
                new String[]{"setWalkSpeed", "setWalkingSpeed"},
                new String[]{"walkSpeed", "walkingSpeed"});
        boolean movementAttributeSet = setMovementSpeedAttribute(target, walkSpeed);
        boolean flySet = setSpeedValue(abilities, flySpeed,
                new String[]{"setFlySpeed", "setFlyingSpeed"},
                new String[]{"flySpeed", "flyingSpeed"});

        invokeNoArg(target.getOriginalPlayer(), "onUpdateAbilities");
        invokeNoArg(target.getOriginalPlayer(), "updateAbilities");
        invokeNoArg(target.getOriginalPlayer(), "sendAbilitiesUpdate");

        return walkSet || movementAttributeSet || flySet;
    }

    private boolean setMovementSpeedAttribute(IPlayer target, float walkSpeed) {
        if (target == null || target.getName() == null || target.getName().isBlank()) {
            return false;
        }
        String value = String.format(Locale.US, "%.4f", walkSpeed);
        services.getPlatformAdapter().executeCommandAsConsole(
                "attribute " + target.getName() + " minecraft:generic.movement_speed base set " + value
        );
        return true;
    }

    private Object getAbilities(Object playerHandle) {
        Object abilities = invokeNoArg(playerHandle, "getAbilities");
        if (abilities != null) {
            return abilities;
        }
        return readField(playerHandle, "abilities");
    }

    private boolean setSpeedValue(Object abilities, float value, String[] methodNames, String[] fieldNames) {
        boolean changed = false;
        for (String methodName : methodNames) {
            try {
                var method = abilities.getClass().getMethod(methodName, float.class);
                method.invoke(abilities, value);
                changed = true;
                break;
            } catch (Throwable ignored) {
            }
        }

        for (String fieldName : fieldNames) {
            try {
                Field field = abilities.getClass().getDeclaredField(fieldName);
                field.setAccessible(true);
                field.setFloat(abilities, value);
                changed = true;
            } catch (Throwable ignored) {
            }
        }
        return changed;
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

