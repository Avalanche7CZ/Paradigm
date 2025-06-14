package eu.avalanche7.paradigm.modules;

import eu.avalanche7.paradigm.core.ParadigmModule;
import eu.avalanche7.paradigm.core.Services;
import eu.avalanche7.paradigm.data.CustomCommand;
import net.minecraft.command.CommandBase;
import net.minecraft.command.CommandException;
import net.minecraft.command.ICommand;
import net.minecraft.command.ICommandSender;
import net.minecraft.entity.player.EntityPlayerMP;
import net.minecraft.server.MinecraftServer;
import net.minecraft.util.text.ITextComponent;
import net.minecraftforge.fml.common.event.FMLPreInitializationEvent;
import net.minecraftforge.fml.common.event.FMLServerStartingEvent;
import net.minecraftforge.fml.common.event.FMLServerStoppingEvent;

import java.io.File;
import java.util.ArrayList;
import java.util.List;

public class CommandManager implements ParadigmModule {

    private static final String NAME = "CustomCommands";
    private final List<ICommand> commandsToRegister = new ArrayList<>();
    private Services services;

    @Override
    public String getName() {
        return NAME;
    }

    @Override
    public boolean isEnabled(Services services) {
        return services.getMainConfig().commandManagerEnable.value;
    }

    @Override
    public void onLoad(FMLPreInitializationEvent event, Services services) {
        this.services = services;
        services.getDebugLogger().debugLog(NAME + " module loaded.");
        services.getCmConfig().init(new File(event.getModConfigurationDirectory(), "paradigm"));
        services.getCmConfig().loadCommands();

        for (CustomCommand cmd : services.getCmConfig().getLoadedCommands()) {
            commandsToRegister.add(new DynamicCommand(cmd, services));
        }
        commandsToRegister.add(new ReloadCommands(services));
    }

    @Override
    public void onServerStarting(FMLServerStartingEvent event, Services services) {
        services.getDebugLogger().debugLog(NAME + " module: Server starting.");
        for (ICommand cmd : commandsToRegister) {
            event.registerServerCommand(cmd);
        }
        services.getDebugLogger().debugLog("Registered " + commandsToRegister.size() + " custom commands.");
    }

    @Override
    public void onEnable(Services services) {
        services.getDebugLogger().debugLog(NAME + " module enabled.");
    }

    @Override
    public void onDisable(Services services) {
        services.getDebugLogger().debugLog(NAME + " module disabled.");
    }

    @Override
    public void onServerStopping(FMLServerStoppingEvent event, Services services) {
        services.getDebugLogger().debugLog(NAME + " module: Server stopping.");
    }

    @Override
    public ICommand getCommand() {
        return null; // Commands are handled dynamically by this module
    }

    @Override
    public void registerEventListeners(Services services) {
        // No event listeners for this module
    }

    private static class DynamicCommand extends CommandBase {
        private final CustomCommand customCommand;
        private final Services services;

        public DynamicCommand(CustomCommand customCommand, Services services) {
            this.customCommand = customCommand;
            this.services = services;
        }

        @Override
        public String getName() {
            return customCommand.getName();
        }

        @Override
        public String getUsage(ICommandSender sender) {
            return "/" + customCommand.getName();
        }

        @Override
        public int getRequiredPermissionLevel() {
            return customCommand.isRequirePermission() ? 2 : 0;
        }

        @Override
        public boolean checkPermission(MinecraftServer server, ICommandSender sender) {
            if (!customCommand.isRequirePermission()) return true;
            if (!(sender instanceof EntityPlayerMP)) return super.checkPermission(server, sender);

            EntityPlayerMP player = (EntityPlayerMP) sender;
            boolean hasPerm = services.getPermissionsHandler().hasPermission(player, customCommand.getPermission());
            if (!hasPerm) {
                String errorMsg = customCommand.getPermissionErrorMessage();
                if (errorMsg != null && !errorMsg.isEmpty()) {
                    player.sendMessage(services.getMessageParser().parseMessage(errorMsg, player));
                }
            }
            return hasPerm || super.checkPermission(server, sender);
        }

        @Override
        public void execute(MinecraftServer server, ICommandSender sender, String[] args) throws CommandException {
            executeCustomCommand(sender, server, customCommand, services);
        }

        private void executeCustomCommand(ICommandSender sender, MinecraftServer server, CustomCommand command, Services services) {
            EntityPlayerMP player = (sender instanceof EntityPlayerMP) ? (EntityPlayerMP) sender : null;

            for (CustomCommand.Action action : command.getActions()) {
                switch (action.getType().toLowerCase()) {
                    case "message":
                        if (action.getText() != null) {
                            for (String line : action.getText()) {
                                ITextComponent formattedMessage = services.getMessageParser().parseMessage(line, player);
                                sender.sendMessage(formattedMessage);
                            }
                        }
                        break;
                    case "teleport":
                        if (player != null && action.getX() != null && action.getY() != null && action.getZ() != null) {
                            player.setPositionAndUpdate(action.getX(), action.getY(), action.getZ());
                        } else if (player == null) {
                            sender.sendMessage(services.getMessageParser().parseMessage("&cTeleport action can only be performed by a player.", null));
                        } else {
                            sender.sendMessage(services.getMessageParser().parseMessage("&cInvalid teleport coordinates.", player));
                        }
                        break;
                    case "run_command":
                    case "runcmd":
                        if (action.getCommands() != null) {
                            for (String cmd : action.getCommands()) {
                                String processedCmd = (player != null) ? services.getPlaceholders().replacePlaceholders(cmd, player) : cmd;
                                server.getCommandManager().executeCommand(sender, processedCmd);
                            }
                        }
                        break;
                    case "run_console":
                        if (action.getCommands() != null) {
                            for (String cmd : action.getCommands()) {
                                String processedCmd = (player != null) ? services.getPlaceholders().replacePlaceholders(cmd, player) : cmd;
                                server.getCommandManager().executeCommand(server, processedCmd);
                            }
                        }
                        break;
                    default:
                        sender.sendMessage(services.getMessageParser().parseMessage("&cUnknown action type '" + action.getType() + "'.", player));
                }
            }
        }
    }

    private static class ReloadCommands extends CommandBase {
        private final Services services;

        public ReloadCommands(Services services) {
            this.services = services;
        }

        @Override
        public String getName() {
            return "paradigmcmdreload";
        }

        @Override
        public String getUsage(ICommandSender sender) {
            return "/paradigmcmdreload";
        }

        @Override
        public int getRequiredPermissionLevel() {
            return 2;
        }

        @Override
        public void execute(MinecraftServer server, ICommandSender sender, String[] args) {
            services.getCmConfig().reloadCommands();
            sender.sendMessage(services.getMessageParser().parseMessage("&aReloaded custom command actions. A server restart is required to register new commands.", null));
            services.getDebugLogger().debugLog("Custom commands reloaded via command.");
        }
    }
}
