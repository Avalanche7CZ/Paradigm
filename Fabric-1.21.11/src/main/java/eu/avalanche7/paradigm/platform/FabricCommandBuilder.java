package eu.avalanche7.paradigm.platform;

import com.mojang.brigadier.arguments.BoolArgumentType;
import com.mojang.brigadier.arguments.IntegerArgumentType;
import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.builder.LiteralArgumentBuilder;
import com.mojang.brigadier.builder.RequiredArgumentBuilder;
import eu.avalanche7.paradigm.platform.Interfaces.ICommandBuilder;
import eu.avalanche7.paradigm.platform.Interfaces.ICommandContext;
import eu.avalanche7.paradigm.platform.Interfaces.ICommandSource;
import net.minecraft.command.CommandSource;
import net.minecraft.command.argument.EntityArgumentType;
import net.minecraft.server.command.CommandManager;
import net.minecraft.server.command.ServerCommandSource;

import java.util.List;
import java.util.function.Predicate;

public class FabricCommandBuilder implements ICommandBuilder {

    private Object currentBuilder; // Can be LiteralArgumentBuilder or RequiredArgumentBuilder

    public FabricCommandBuilder() {
    }

    private FabricCommandBuilder(Object builder) {
        this.currentBuilder = builder;
    }

    @Override
    public ICommandBuilder literal(String name) {
        this.currentBuilder = CommandManager.literal(name);
        return this;
    }

    @Override
    public ICommandBuilder argument(String name, ArgumentType type) {
        RequiredArgumentBuilder<ServerCommandSource, ?> arg = switch (type) {
            case STRING -> CommandManager.argument(name, StringArgumentType.word());
            case GREEDY_STRING -> CommandManager.argument(name, StringArgumentType.greedyString());
            case INTEGER -> CommandManager.argument(name, IntegerArgumentType.integer());
            case BOOLEAN -> CommandManager.argument(name, BoolArgumentType.bool());
            case PLAYER -> CommandManager.argument(name, EntityArgumentType.player());
            case WORD -> CommandManager.argument(name, StringArgumentType.word());
        };
        this.currentBuilder = arg;
        return this;
    }

    @Override
    public ICommandBuilder requires(Predicate<ICommandSource> requirement) {
        if (currentBuilder instanceof LiteralArgumentBuilder<?> lit) {
            @SuppressWarnings("unchecked")
            LiteralArgumentBuilder<ServerCommandSource> builder = (LiteralArgumentBuilder<ServerCommandSource>) lit;
            this.currentBuilder = builder.requires(source -> requirement.test(MinecraftCommandSource.of(source)));
        } else if (currentBuilder instanceof RequiredArgumentBuilder<?, ?> req) {
            @SuppressWarnings("unchecked")
            RequiredArgumentBuilder<ServerCommandSource, ?> builder = (RequiredArgumentBuilder<ServerCommandSource, ?>) req;
            this.currentBuilder = builder.requires(source -> requirement.test(MinecraftCommandSource.of(source)));
        }
        return this;
    }

    @Override
    public ICommandBuilder executes(CommandExecutor executor) {
        if (currentBuilder instanceof LiteralArgumentBuilder<?> lit) {
            @SuppressWarnings("unchecked")
            LiteralArgumentBuilder<ServerCommandSource> builder = (LiteralArgumentBuilder<ServerCommandSource>) lit;
            this.currentBuilder = builder.executes(ctx -> {
                try {
                    return executor.execute(new FabricCommandContext(ctx));
                } catch (Exception e) {
                    e.printStackTrace();
                    return 0;
                }
            });
        } else if (currentBuilder instanceof RequiredArgumentBuilder<?, ?> req) {
            @SuppressWarnings("unchecked")
            RequiredArgumentBuilder<ServerCommandSource, ?> builder = (RequiredArgumentBuilder<ServerCommandSource, ?>) req;
            this.currentBuilder = builder.executes(ctx -> {
                try {
                    return executor.execute(new FabricCommandContext(ctx));
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
            RequiredArgumentBuilder<ServerCommandSource, ?> builder = (RequiredArgumentBuilder<ServerCommandSource, ?>) req;
            this.currentBuilder = builder.suggests((ctx, b) -> {
                ICommandContext pc = new FabricCommandContext(ctx);
                String remaining = b.getRemaining();
                List<String> s = provider.getSuggestions(pc, remaining != null ? remaining : "");
                if (s != null) {
                    return CommandSource.suggestMatching(s, b);
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
            LiteralArgumentBuilder<ServerCommandSource> builder = (LiteralArgumentBuilder<ServerCommandSource>) lit;
            if (childBuilder instanceof LiteralArgumentBuilder<?> childLit) {
                @SuppressWarnings("unchecked")
                LiteralArgumentBuilder<ServerCommandSource> childBuilder2 = (LiteralArgumentBuilder<ServerCommandSource>) childLit;
                this.currentBuilder = builder.then(childBuilder2);
            } else if (childBuilder instanceof RequiredArgumentBuilder<?, ?> childReq) {
                @SuppressWarnings("unchecked")
                RequiredArgumentBuilder<ServerCommandSource, ?> childBuilder2 = (RequiredArgumentBuilder<ServerCommandSource, ?>) childReq;
                this.currentBuilder = builder.then(childBuilder2);
            }
        } else if (currentBuilder instanceof RequiredArgumentBuilder<?, ?> req) {
            @SuppressWarnings("unchecked")
            RequiredArgumentBuilder<ServerCommandSource, ?> builder = (RequiredArgumentBuilder<ServerCommandSource, ?>) req;
            if (childBuilder instanceof LiteralArgumentBuilder<?> childLit) {
                @SuppressWarnings("unchecked")
                LiteralArgumentBuilder<ServerCommandSource> childBuilder2 = (LiteralArgumentBuilder<ServerCommandSource>) childLit;
                this.currentBuilder = builder.then(childBuilder2);
            } else if (childBuilder instanceof RequiredArgumentBuilder<?, ?> childReq) {
                @SuppressWarnings("unchecked")
                RequiredArgumentBuilder<ServerCommandSource, ?> childBuilder2 = (RequiredArgumentBuilder<ServerCommandSource, ?>) childReq;
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
