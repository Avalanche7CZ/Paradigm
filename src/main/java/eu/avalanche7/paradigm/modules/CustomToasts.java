package eu.avalanche7.paradigm.modules;

import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.suggestion.SuggestionProvider;
import eu.avalanche7.paradigm.configs.ToastConfigHandler;
import eu.avalanche7.paradigm.core.ParadigmModule;
import eu.avalanche7.paradigm.core.Services;
import eu.avalanche7.paradigm.platform.Interfaces.IPlatformAdapter;
import net.minecraft.command.CommandRegistryAccess;
import net.minecraft.command.argument.EntityArgumentType;
import net.minecraft.server.command.CommandManager;
import net.minecraft.server.command.ServerCommandSource;
import net.minecraft.server.network.ServerPlayerEntity;

public class CustomToasts implements ParadigmModule {

    private IPlatformAdapter platform;

    @Override
    public String getName() {
        return "CustomToasts";
    }

    @Override
    public boolean isEnabled(Services services) {
        return true;
    }

    @Override
    public void onLoad(Object event, Services services, Object modEventBus) {
        this.platform = services.getPlatformAdapter();
    }

    @Override
    public void onServerStarting(Object event, Services services) {}

    @Override
    public void onEnable(Services services) {}

    @Override
    public void onDisable(Services services) {}

    @Override
    public void onServerStopping(Object event, Services services) {}

    @Override
    public void registerEventListeners(Object eventBus, Services services) {}

    @Override
    public void registerCommands(CommandDispatcher<ServerCommandSource> dispatcher, CommandRegistryAccess registryAccess, Services services) {
        SuggestionProvider<ServerCommandSource> toastIdProvider = (context, builder) -> {
            ToastConfigHandler.TOASTS.keySet().forEach(builder::suggest);
            return builder.buildFuture();
        };

        dispatcher.register(CommandManager.literal("paradigm")
                .then(CommandManager.literal("toast")
                        .requires(source -> source.hasPermissionLevel(2))
                        .then(CommandManager.literal("show")
                                .then(CommandManager.argument("player", EntityArgumentType.player())
                                        .then(CommandManager.argument("toast_id", StringArgumentType.string()).suggests(toastIdProvider)
                                                .executes(context -> {
                                                    ServerPlayerEntity player = EntityArgumentType.getPlayer(context, "player");
                                                    String toastId = StringArgumentType.getString(context, "toast_id");

                                                    boolean success = services.getCustomToastManager().showToast(player, toastId, services);
                                                    if (success) {
                                                        platform.sendSuccess(context.getSource(), platform.createLiteralComponent("Toast '" + toastId + "' sent to " + platform.getPlayerName(player)), true);
                                                    } else {
                                                        platform.sendFailure(context.getSource(), platform.createLiteralComponent("Toast with ID '" + toastId + "' not found in toasts.json."));
                                                    }
                                                    return success ? 1 : 0;
                                                })
                                        )
                                )
                        )
                )
        );
    }
}