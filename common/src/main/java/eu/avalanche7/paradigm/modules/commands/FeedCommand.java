package eu.avalanche7.paradigm.modules.commands;

import eu.avalanche7.paradigm.core.ParadigmModule;
import eu.avalanche7.paradigm.core.Services;
import eu.avalanche7.paradigm.platform.Interfaces.ICommandBuilder;
import eu.avalanche7.paradigm.platform.Interfaces.IPlayer;
import eu.avalanche7.paradigm.utils.PermissionsHandler;

public class FeedCommand implements ParadigmModule {
    private Services services;

    @Override
    public String getName() {
        return "Feed";
    }

    @Override
    public boolean isEnabled(Services services) {
        return services == null
                || services.getMainConfig() == null
                || Boolean.TRUE.equals(services.getMainConfig().feedCommandEnable.value);
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
                .literal("feed")
                .requires(src -> services.getCommandToggleStore().isEnabled("feed")
                        && src.getPlayer() != null
                        && services.getPermissionsHandler().hasPermission(src.getPlayer(), PermissionsHandler.FEED_PERMISSION, PermissionsHandler.FEED_PERMISSION_LEVEL))
                .executes(ctx -> applyFeed(ctx.getSource().getPlayer(), ctx.getSource().getPlayer()))
                .then(services.getPlatformAdapter().createCommandBuilder()
                        .argument("player", ICommandBuilder.ArgumentType.PLAYER)
                        .executes(ctx -> applyFeed(ctx.getSource().getPlayer(), ctx.getPlayerArgument("player"))));
        services.getPlatformAdapter().registerCommand(cmd);
    }

    @Override
    public void registerEventListeners(Object eventBus, Services services) {
    }

    private int applyFeed(IPlayer actor, IPlayer target) {
        if (target == null) {
            return 0;
        }
        if (!feedToMax(target)) {
            send(actor, "utility.feed_fail", "Could not restore food for {player}.", "{player}", target.getName());
            return 0;
        }
        send(actor, "utility.feed_ok", "Fed {player}.", "{player}", target.getName());
        return 1;
    }

    private boolean feedToMax(IPlayer target) {
        Object player = target.getOriginalPlayer();
        if (player == null) {
            return false;
        }

        Object foodData = invokeNoArg(player, "getFoodData", "getHungerManager", "getFoodStats");
        if (foodData == null) {
            return false;
        }

        boolean foodSet = invoke(foodData, "setFoodLevel", new Class<?>[]{int.class}, 20)
                || invoke(foodData, "setFood", new Class<?>[]{int.class}, 20);
        boolean saturationSet = invoke(foodData, "setSaturation", new Class<?>[]{float.class}, 20.0f)
                || invoke(foodData, "setSaturationLevel", new Class<?>[]{float.class}, 20.0f)
                || invoke(foodData, "setSaturationAmount", new Class<?>[]{float.class}, 20.0f);

        // Optional cleanup; ignore failures on older/newer mappings.
        invoke(foodData, "setExhaustion", new Class<?>[]{float.class}, 0.0f);
        invoke(foodData, "setExhaustionLevel", new Class<?>[]{float.class}, 0.0f);

        return foodSet || saturationSet;
    }

    private Object invokeNoArg(Object target, String... methods) {
        for (String method : methods) {
            try {
                var m = target.getClass().getMethod(method);
                return m.invoke(target);
            } catch (Throwable ignored) {
            }
        }
        return null;
    }

    private boolean invoke(Object target, String method, Class<?>[] signature, Object... args) {
        try {
            var m = target.getClass().getMethod(method, signature);
            m.invoke(target, args);
            return true;
        } catch (Throwable ignored) {
            return false;
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

