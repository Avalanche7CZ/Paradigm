package eu.avalanche7.paradigm.platform;

import com.mojang.brigadier.arguments.BoolArgumentType;
import com.mojang.brigadier.arguments.IntegerArgumentType;
import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.context.CommandContext;
import eu.avalanche7.paradigm.platform.Interfaces.ICommandContext;
import eu.avalanche7.paradigm.platform.Interfaces.ICommandSource;
import eu.avalanche7.paradigm.platform.Interfaces.IPlayer;
import net.minecraft.command.argument.EntityArgumentType;
import net.minecraft.server.command.ServerCommandSource;
import net.minecraft.server.network.ServerPlayerEntity;

public class FabricCommandContext implements ICommandContext {

    private final CommandContext<ServerCommandSource> context;
    private final ICommandSource source;

    public FabricCommandContext(CommandContext<ServerCommandSource> context) {
        this.context = context;
        this.source = MinecraftCommandSource.of(context.getSource());
    }

    @Override
    public ICommandSource getSource() {
        return source;
    }

    @Override
    public String getStringArgument(String name) {
        try {
            return StringArgumentType.getString(context, name);
        } catch (IllegalArgumentException e) {
            return "";
        }
    }

    @Override
    public int getIntArgument(String name) {
        try {
            return IntegerArgumentType.getInteger(context, name);
        } catch (IllegalArgumentException e) {
            return 0;
        }
    }

    @Override
    public boolean getBooleanArgument(String name) {
        try {
            return BoolArgumentType.getBool(context, name);
        } catch (IllegalArgumentException e) {
            return false;
        }
    }

    @Override
    public IPlayer getPlayerArgument(String name) {
        try {
            ServerPlayerEntity player = EntityArgumentType.getPlayer(context, name);
            return MinecraftPlayer.of(player);
        } catch (Exception e) {
            return null;
        }
    }

    @Override
    public Object getOriginalContext() {
        return context;
    }
}
