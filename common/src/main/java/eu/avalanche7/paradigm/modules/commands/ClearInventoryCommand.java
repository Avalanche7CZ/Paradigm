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
        services.getPlatformAdapter().executeCommandAsConsole("clear " + target.getName());
        send(actor, "utility.clearinv_ok", "Cleared inventory for {player}.", "{player}", target.getName());
        return 1;
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

