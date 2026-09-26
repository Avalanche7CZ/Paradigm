package eu.avalanche7.paradigm.platform;

import net.minecraft.command.ICommandSender;
import net.minecraft.entity.player.EntityPlayerMP;
import net.minecraft.server.MinecraftServer;

import eu.avalanche7.paradigm.platform.Interfaces.ICommandSource;
import eu.avalanche7.paradigm.platform.Interfaces.IPlayer;

public final class MinecraftCommandSource implements ICommandSource {
    private final ICommandSender sender;

    public MinecraftCommandSource(ICommandSender sender) {
        this.sender = sender;
    }

    @Override
    public IPlayer getPlayer() {
        return sender instanceof EntityPlayerMP ? new MinecraftPlayer((EntityPlayerMP) sender)
                                                : null;
    }

    @Override
    public String getSourceName() {
        return sender.getCommandSenderName();
    }

    @Override
    public boolean hasPermissionLevel(int level) {
        return sender.canCommandSenderUseCommand(level, "paradigm");
    }

    @Override
    public boolean isConsole() {
        return sender instanceof MinecraftServer;
    }

    @Override
    public Object getOriginalSource() {
        return sender;
    }
}
