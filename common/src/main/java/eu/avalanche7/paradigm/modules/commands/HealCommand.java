package eu.avalanche7.paradigm.modules.commands;

import eu.avalanche7.paradigm.core.ParadigmModule;
import eu.avalanche7.paradigm.core.Services;
import eu.avalanche7.paradigm.platform.Interfaces.ICommandBuilder;
import eu.avalanche7.paradigm.platform.Interfaces.IPlayer;
import eu.avalanche7.paradigm.utils.PermissionsHandler;

public class HealCommand implements ParadigmModule {
    private Services services;

    @Override
    public String getName() {
        return "Heal";
    }

    @Override
    public boolean isEnabled(Services services) {
        return services == null
                || services.getMainConfig() == null
                || Boolean.TRUE.equals(services.getMainConfig().healCommandEnable.value);
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
                .literal("heal")
                .requires(src -> services.getCommandToggleStore().isEnabled("heal")
                        && src.getPlayer() != null
                        && services.getPermissionsHandler().hasPermission(src.getPlayer(), PermissionsHandler.HEAL_PERMISSION, PermissionsHandler.HEAL_PERMISSION_LEVEL))
                .executes(ctx -> applyHeal(ctx.getSource().getPlayer(), ctx.getSource().getPlayer()))
                .then(services.getPlatformAdapter().createCommandBuilder()
                        .argument("player", ICommandBuilder.ArgumentType.PLAYER)
                        .executes(ctx -> applyHeal(ctx.getSource().getPlayer(), ctx.getPlayerArgument("player"))));
        services.getPlatformAdapter().registerCommand(cmd);
    }

    @Override
    public void registerEventListeners(Object eventBus, Services services) {
    }

    private int applyHeal(IPlayer actor, IPlayer target) {
        if (target == null) {
            return 0;
        }
        if (!healToMaxHealth(target)) {
            send(actor, "utility.heal_fail", "Could not restore full HP for {player}.", "{player}", target.getName());
            return 0;
        }
        send(actor, "utility.heal_ok", "Healed {player}.", "{player}", target.getName());
        return 1;
    }

    private boolean healToMaxHealth(IPlayer target) {
        if (target == null) {
            return false;
        }

        Float maxHealth = null;
        if (target.getMaxHealth() != null) {
            maxHealth = target.getMaxHealth().floatValue();
        }

        Object original = target.getOriginalPlayer();
        if (original == null) {
            return false;
        }

        if (maxHealth == null || maxHealth <= 0.0f) {
            maxHealth = invokeFloatNoArg(original, "getMaxHealth");
        }
        if (maxHealth == null || maxHealth <= 0.0f) {
            return false;
        }

        return invokeSetHealth(original, maxHealth);
    }

    private Float invokeFloatNoArg(Object handle, String method) {
        try {
            var m = handle.getClass().getMethod(method);
            Object value = m.invoke(handle);
            if (value instanceof Number n) {
                return n.floatValue();
            }
        } catch (Throwable ignored) {
        }
        return null;
    }

    private boolean invokeSetHealth(Object handle, float health) {
        try {
            var m = handle.getClass().getMethod("setHealth", float.class);
            m.invoke(handle, health);
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

