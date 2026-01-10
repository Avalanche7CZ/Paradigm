package eu.avalanche7.paradigm.platform;

import com.mojang.brigadier.arguments.BoolArgumentType;
import com.mojang.brigadier.arguments.IntegerArgumentType;
import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.context.CommandContext;
import eu.avalanche7.paradigm.platform.Interfaces.ICommandContext;
import eu.avalanche7.paradigm.platform.Interfaces.ICommandSource;
import eu.avalanche7.paradigm.platform.Interfaces.IPlayer;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.arguments.EntityArgument;
import net.minecraft.server.level.ServerPlayer;

public class ForgeCommandContext implements ICommandContext {

    private final CommandContext<CommandSourceStack> context;
    private final ICommandSource source;

    public ForgeCommandContext(CommandContext<CommandSourceStack> context) {
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
            ServerPlayer player = EntityArgument.getPlayer(context, name);
            return new MinecraftPlayer(player);
        } catch (Exception e) {
            return null;
        }
    }

    @Override
    public Object getOriginalContext() {
        return context;
    }
}
