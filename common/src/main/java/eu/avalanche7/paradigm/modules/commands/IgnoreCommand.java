package eu.avalanche7.paradigm.modules.commands;

import eu.avalanche7.paradigm.core.ParadigmModule;
import eu.avalanche7.paradigm.core.Services;
import eu.avalanche7.paradigm.platform.Interfaces.ICommandBuilder;
import eu.avalanche7.paradigm.platform.Interfaces.IPlayer;
import eu.avalanche7.paradigm.utils.PermissionsHandler;

public class IgnoreCommand implements ParadigmModule {
    private Services services;

    @Override
    public String getName() {
        return "Ignore";
    }

    @Override
    public boolean isEnabled(Services services) {
        return services == null
                || services.getMainConfig() == null
                || Boolean.TRUE.equals(services.getMainConfig().ignoreCommandsEnable.value);
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
        registerIgnore();
        registerUnignore();
    }

    @Override
    public void registerEventListeners(Object eventBus, Services services) {
    }

    private void registerIgnore() {
        ICommandBuilder cmd = services.getPlatformAdapter().createCommandBuilder()
                .literal("ignore")
                .requires(src -> services.getCommandToggleStore().isEnabled("ignore")
                        && src.getPlayer() != null
                        && services.getPermissionsHandler().hasPermission(src.getPlayer(), PermissionsHandler.IGNORE_PERMISSION, PermissionsHandler.IGNORE_PERMISSION_LEVEL))
                .then(services.getPlatformAdapter().createCommandBuilder()
                        .argument("player", ICommandBuilder.ArgumentType.PLAYER)
                        .executes(ctx -> {
                            IPlayer owner = ctx.getSource().getPlayer();
                            IPlayer target = ctx.getPlayerArgument("player");
                            if (owner == null || target == null) {
                                return 0;
                            }
                            if (owner.getUUID() != null && owner.getUUID().equals(target.getUUID())) {
                                send(owner, "ignore.self", "You cannot ignore yourself.");
                                return 0;
                            }
                            boolean changed = services.getPlayerDataStore().addIgnoredPlayer(owner.getUUID(), target.getUUID());
                            if (!changed) {
                                send(owner, "ignore.already", "You already ignore {player}.", "{player}", target.getName());
                                return 0;
                            }
                            send(owner, "ignore.added", "You now ignore {player}.", "{player}", target.getName());
                            return 1;
                        }));
        services.getPlatformAdapter().registerCommand(cmd);
    }

    private void registerUnignore() {
        ICommandBuilder cmd = services.getPlatformAdapter().createCommandBuilder()
                .literal("unignore")
                .requires(src -> services.getCommandToggleStore().isEnabled("unignore")
                        && src.getPlayer() != null
                        && services.getPermissionsHandler().hasPermission(src.getPlayer(), PermissionsHandler.IGNORE_PERMISSION, PermissionsHandler.IGNORE_PERMISSION_LEVEL))
                .then(services.getPlatformAdapter().createCommandBuilder()
                        .argument("player", ICommandBuilder.ArgumentType.PLAYER)
                        .executes(ctx -> {
                            IPlayer owner = ctx.getSource().getPlayer();
                            IPlayer target = ctx.getPlayerArgument("player");
                            if (owner == null || target == null) {
                                return 0;
                            }
                            boolean changed = services.getPlayerDataStore().removeIgnoredPlayer(owner.getUUID(), target.getUUID());
                            if (!changed) {
                                send(owner, "ignore.not_found", "You do not ignore {player}.", "{player}", target.getName());
                                return 0;
                            }
                            send(owner, "ignore.removed", "You no longer ignore {player}.", "{player}", target.getName());
                            return 1;
                        }));
        services.getPlatformAdapter().registerCommand(cmd);
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

