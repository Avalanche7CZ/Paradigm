package eu.avalanche7.paradigm.platform;

import com.mojang.brigadier.context.CommandContext;
import eu.avalanche7.paradigm.platform.Interfaces.ICommandContext;
import eu.avalanche7.paradigm.platform.Interfaces.ICommandSource;
import eu.avalanche7.paradigm.platform.Interfaces.IPlayer;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.arguments.EntityArgument;

public class NeoForgeCommandContext implements ICommandContext {

    private final CommandContext<CommandSourceStack> ctx;

    public NeoForgeCommandContext(CommandContext<CommandSourceStack> ctx) {
        this.ctx = ctx;
    }

    @Override
    public ICommandSource getSource() {
        return MinecraftCommandSource.of(ctx.getSource());
    }

    @Override
    public String getStringArgument(String name) {
        return ctx.getArgument(name, String.class);
    }

    @Override
    public int getIntArgument(String name) {
        return ctx.getArgument(name, Integer.class);
    }

    @Override
    public boolean getBooleanArgument(String name) {
        return ctx.getArgument(name, Boolean.class);
    }

    @Override
    public IPlayer getPlayerArgument(String name) {
        try {
            return MinecraftPlayer.of(EntityArgument.getPlayer(ctx, name));
        } catch (Throwable t) {
            return null;
        }
    }

    @Override
    public Object getOriginalContext() {
        return ctx;
    }
}
