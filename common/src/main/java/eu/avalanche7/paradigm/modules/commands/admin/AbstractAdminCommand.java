package eu.avalanche7.paradigm.modules.commands.admin;

import eu.avalanche7.paradigm.core.ParadigmModule;
import eu.avalanche7.paradigm.core.Services;
import eu.avalanche7.paradigm.modules.commands.shared.CommandMessages;
import eu.avalanche7.paradigm.platform.Interfaces.ICommandBuilder;
import eu.avalanche7.paradigm.platform.Interfaces.ICommandSource;
import eu.avalanche7.paradigm.platform.Interfaces.IPlayer;

public abstract class AbstractAdminCommand implements ParadigmModule {
    protected Services services;

    @Override
    public boolean isEnabled(Services services) {
        return services == null
                || services.getMainConfig() == null
                || Boolean.TRUE.equals(services.getMainConfig().adminUtilityCommandsEnable.value);
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
    public void registerEventListeners(Object eventBus, Services services) {
    }

    protected ICommandBuilder builder() {
        return services.getPlatformAdapter().createCommandBuilder();
    }

    protected boolean allowed(ICommandSource source, String root, String permission, int level) {
        if (services == null || services.getCommandToggleStore() == null || !services.getCommandToggleStore().isEnabled(root)) {
            return false;
        }
        if (source == null) {
            return false;
        }
        if (source.isConsole()) {
            return true;
        }
        IPlayer player = source.getPlayer();
        return player != null && services.getPermissionsHandler().hasPermission(player, permission, level);
    }

    protected boolean canTargetOther(IPlayer actor, IPlayer target, String permission, int level) {
        if (actor == null || target == null || actor.getUUID() == null || actor.getUUID().equals(target.getUUID())) {
            return true;
        }
        return services.getPermissionsHandler().hasPermission(actor, permission, level);
    }

    protected void send(ICommandSource source, String key, String fallback, String... placeholders) {
        CommandMessages.source(services, source, "Admin", key, fallback, placeholders);
    }

    protected void send(IPlayer player, String key, String fallback, String... placeholders) {
        CommandMessages.send(services, player, "Admin", key, fallback, placeholders);
    }
}
