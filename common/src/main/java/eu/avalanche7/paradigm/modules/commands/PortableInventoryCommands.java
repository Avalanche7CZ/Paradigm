package eu.avalanche7.paradigm.modules.commands;

import eu.avalanche7.paradigm.core.ParadigmModule;
import eu.avalanche7.paradigm.core.Services;
import eu.avalanche7.paradigm.modules.commands.shared.CommandMessages;
import eu.avalanche7.paradigm.modules.permissions.ParadigmPermissions;
import eu.avalanche7.paradigm.modules.permissions.PermissionDefinition;
import eu.avalanche7.paradigm.platform.Interfaces.ICommandSource;

public final class PortableInventoryCommands implements ParadigmModule {
    @Override
    public String getName() {
        return "PortableInventories";
    }

    @Override
    public boolean isEnabled(Services services) {
        return true;
    }

    @Override
    public void registerCommands(Object dispatcher, Object registryAccess, Services services) {
        register(services, "workbench", "workbench", ParadigmPermissions.WORKBENCH);
        registerAlias(services, "craft", "workbench", ParadigmPermissions.WORKBENCH);
        register(services, "enderchest", "enderchest", ParadigmPermissions.ENDER_CHEST);
        registerAlias(services, "ec", "enderchest", ParadigmPermissions.ENDER_CHEST);
    }

    private void registerAlias(Services services, String alias, String command, PermissionDefinition permission) {
        var platform = services.getPlatformAdapter();
        if (!platform.hasRegisteredCommandRoot(alias) || platform.ownsRegisteredCommandRoot(alias)) {
            register(services, alias, command, permission);
        }
    }

    private void register(Services services, String literal, String command, PermissionDefinition permission) {
        services.getPlatformAdapter().registerCommand(services.getPlatformAdapter().createCommandBuilder()
                .literal(literal)
                .requires(source -> services.getCommandAccess().allowsPlayer(source, command, permission))
                .executes(context -> open(services, context.getSource(), command, permission)));
    }

    private int open(Services services, ICommandSource source, String command, PermissionDefinition permission) {
        if (!services.getCommandAccess().allowsPlayer(source, command, permission)) {
            return 0;
        }
        var player = source.getPlayer();
        var platform = services.getPlatformAdapter();
        boolean opened = "workbench".equals(command) ? platform.openWorkbench(player) : platform.openEnderChest(player);
        if (!opened) {
            CommandMessages.source(services, source, "Utility", "utility.inventory_open_failed",
                    "Could not open that inventory.");
        }
        return opened ? 1 : 0;
    }

    @Override public void onLoad(Object event, Services services, Object modEventBus) { }
    @Override public void onServerStarting(Object event, Services services) { }
    @Override public void onEnable(Services services) { }
    @Override public void onDisable(Services services) { }
    @Override public void onServerStopping(Object event, Services services) { }
    @Override public void registerEventListeners(Object eventBus, Services services) { }
}
