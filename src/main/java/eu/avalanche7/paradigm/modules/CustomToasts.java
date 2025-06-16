package eu.avalanche7.paradigm.modules;

import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.suggestion.SuggestionProvider;
import eu.avalanche7.paradigm.configs.ToastConfigHandler;
import eu.avalanche7.paradigm.core.ParadigmModule;
import eu.avalanche7.paradigm.core.Services;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.commands.SharedSuggestionProvider;
import net.minecraft.commands.arguments.EntityArgument;
import net.minecraft.server.level.ServerPlayer;
import net.minecraftforge.event.server.ServerStartingEvent;
import net.minecraftforge.event.server.ServerStoppingEvent;
import net.minecraftforge.eventbus.api.IEventBus;
import net.minecraftforge.fml.event.lifecycle.FMLCommonSetupEvent;

public class CustomToasts implements ParadigmModule {

    @Override
    public String getName() {
        return "CustomToasts";
    }

    @Override
    public boolean isEnabled(Services services) {
        return true;
    }

    @Override
    public void onLoad(FMLCommonSetupEvent event, Services services, IEventBus modEventBus) {}

    @Override
    public void onServerStarting(ServerStartingEvent event, Services services) {}

    @Override
    public void onEnable(Services services) {}

    @Override
    public void onDisable(Services services) {}

    @Override
    public void onServerStopping(ServerStoppingEvent event, Services services) {}

    @Override
    public void registerEventListeners(IEventBus forgeEventBus, Services services) {}

    @Override
    public void registerCommands(CommandDispatcher<CommandSourceStack> dispatcher, Services services) {
        SuggestionProvider<CommandSourceStack> toastIdProvider = (context, builder) ->
                SharedSuggestionProvider.suggest(ToastConfigHandler.TOASTS.keySet(), builder);

        dispatcher.register(Commands.literal("paradigm")
                .then(Commands.literal("toast")
                        .requires(source -> source.hasPermission(2))
                        .then(Commands.literal("show")
                                .then(Commands.argument("player", EntityArgument.player())
                                        .then(Commands.argument("toast_id", StringArgumentType.string()).suggests(toastIdProvider)
                                                .executes(context -> {
                                                    ServerPlayer player = EntityArgument.getPlayer(context, "player");
                                                    String toastId = StringArgumentType.getString(context, "toast_id");

                                                    boolean success = services.getCustomToastManager().showToast(player, toastId);
                                                    if (success) {
                                                        context.getSource().sendSuccess(() -> services.getPlatformAdapter().createLiteralComponent("Toast '" + toastId + "' sent to " + player.getName().getString()), true);
                                                    } else {
                                                        context.getSource().sendFailure(services.getPlatformAdapter().createLiteralComponent("Toast with ID '" + toastId + "' not found in toasts.json."));
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