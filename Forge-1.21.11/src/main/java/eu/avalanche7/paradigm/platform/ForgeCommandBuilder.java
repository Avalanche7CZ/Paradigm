package eu.avalanche7.paradigm.platform;

import com.mojang.brigadier.arguments.BoolArgumentType;
import com.mojang.brigadier.arguments.IntegerArgumentType;
import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.builder.LiteralArgumentBuilder;
import com.mojang.brigadier.builder.RequiredArgumentBuilder;
import eu.avalanche7.paradigm.platform.Interfaces.ICommandBuilder;
import eu.avalanche7.paradigm.platform.Interfaces.ICommandContext;
import eu.avalanche7.paradigm.platform.Interfaces.ICommandSource;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.commands.SharedSuggestionProvider;
import net.minecraft.commands.arguments.EntityArgument;

import java.util.List;
import java.util.function.Predicate;

public class ForgeCommandBuilder implements ICommandBuilder {

    private Object currentBuilder; // Can be LiteralArgumentBuilder or RequiredArgumentBuilder

    public ForgeCommandBuilder() {
    }

    private ForgeCommandBuilder(Object builder) {
        this.currentBuilder = builder;
    }

    @Override
    public ICommandBuilder literal(String name) {
        this.currentBuilder = Commands.literal(name);
        return this;
    }

    @Override
    public ICommandBuilder argument(String name, ArgumentType type) {
        RequiredArgumentBuilder<CommandSourceStack, ?> arg = switch (type) {
            case STRING -> Commands.argument(name, StringArgumentType.word());
            case GREEDY_STRING -> Commands.argument(name, StringArgumentType.greedyString());
            case INTEGER -> Commands.argument(name, IntegerArgumentType.integer());
            case BOOLEAN -> Commands.argument(name, BoolArgumentType.bool());
            case PLAYER -> Commands.argument(name, EntityArgument.player());
            case WORD -> Commands.argument(name, StringArgumentType.word());
        };
        this.currentBuilder = arg;
        return this;
    }

    @Override
    public ICommandBuilder requires(Predicate<ICommandSource> requirement) {
        if (currentBuilder instanceof LiteralArgumentBuilder<?> lit) {
            @SuppressWarnings("unchecked")
            LiteralArgumentBuilder<CommandSourceStack> builder = (LiteralArgumentBuilder<CommandSourceStack>) lit;
            this.currentBuilder = builder.requires(source -> requirement.test(MinecraftCommandSource.of(source)));
        } else if (currentBuilder instanceof RequiredArgumentBuilder<?, ?> req) {
            @SuppressWarnings("unchecked")
            RequiredArgumentBuilder<CommandSourceStack, ?> builder = (RequiredArgumentBuilder<CommandSourceStack, ?>) req;
            this.currentBuilder = builder.requires(source -> requirement.test(MinecraftCommandSource.of(source)));
        }
        return this;
    }

    @Override
    public ICommandBuilder executes(CommandExecutor executor) {
        if (currentBuilder instanceof LiteralArgumentBuilder<?> lit) {
            @SuppressWarnings("unchecked")
            LiteralArgumentBuilder<CommandSourceStack> builder = (LiteralArgumentBuilder<CommandSourceStack>) lit;
            this.currentBuilder = builder.executes(ctx -> {
                try {
                    return executor.execute(new ForgeCommandContext(ctx));
                } catch (Exception e) {
                    e.printStackTrace();
                    return 0;
                }
            });
        } else if (currentBuilder instanceof RequiredArgumentBuilder<?, ?> req) {
            @SuppressWarnings("unchecked")
            RequiredArgumentBuilder<CommandSourceStack, ?> builder = (RequiredArgumentBuilder<CommandSourceStack, ?>) req;
            this.currentBuilder = builder.executes(ctx -> {
                try {
                    return executor.execute(new ForgeCommandContext(ctx));
                } catch (Exception e) {
                    e.printStackTrace();
                    return 0;
                }
            });
        }
        return this;
    }

    @Override
    public ICommandBuilder suggests(SuggestionProvider provider) {
        if (currentBuilder instanceof RequiredArgumentBuilder<?, ?> req) {
            @SuppressWarnings("unchecked")
            RequiredArgumentBuilder<CommandSourceStack, ?> builder = (RequiredArgumentBuilder<CommandSourceStack, ?>) req;
            this.currentBuilder = builder.suggests((ctx, b) -> {
                ICommandContext pc = new ForgeCommandContext(ctx);
                String remaining = b.getRemaining();
                List<String> s = provider.getSuggestions(pc, remaining != null ? remaining : "");
                if (s != null) {
                    return SharedSuggestionProvider.suggest(s, b);
                }
                return b.buildFuture();
            });
        }
        return this;
    }

    @Override
    public ICommandBuilder then(ICommandBuilder child) {
        Object childBuilder = child.build();

        if (currentBuilder instanceof LiteralArgumentBuilder<?> lit) {
            @SuppressWarnings("unchecked")
            LiteralArgumentBuilder<CommandSourceStack> builder = (LiteralArgumentBuilder<CommandSourceStack>) lit;
            if (childBuilder instanceof LiteralArgumentBuilder<?> childLit) {
                @SuppressWarnings("unchecked")
                LiteralArgumentBuilder<CommandSourceStack> childBuilder2 = (LiteralArgumentBuilder<CommandSourceStack>) childLit;
                this.currentBuilder = builder.then(childBuilder2);
            } else if (childBuilder instanceof RequiredArgumentBuilder<?, ?> childReq) {
                @SuppressWarnings("unchecked")
                RequiredArgumentBuilder<CommandSourceStack, ?> childBuilder2 = (RequiredArgumentBuilder<CommandSourceStack, ?>) childReq;
                this.currentBuilder = builder.then(childBuilder2);
            }
        } else if (currentBuilder instanceof RequiredArgumentBuilder<?, ?> req) {
            @SuppressWarnings("unchecked")
            RequiredArgumentBuilder<CommandSourceStack, ?> builder = (RequiredArgumentBuilder<CommandSourceStack, ?>) req;
            if (childBuilder instanceof LiteralArgumentBuilder<?> childLit) {
                @SuppressWarnings("unchecked")
                LiteralArgumentBuilder<CommandSourceStack> childBuilder2 = (LiteralArgumentBuilder<CommandSourceStack>) childLit;
                this.currentBuilder = builder.then(childBuilder2);
            } else if (childBuilder instanceof RequiredArgumentBuilder<?, ?> childReq) {
                @SuppressWarnings("unchecked")
                RequiredArgumentBuilder<CommandSourceStack, ?> childBuilder2 = (RequiredArgumentBuilder<CommandSourceStack, ?>) childReq;
                this.currentBuilder = builder.then(childBuilder2);
            }
        }

        return this;
    }

    @Override
    public Object build() {
        return currentBuilder;
    }
}
