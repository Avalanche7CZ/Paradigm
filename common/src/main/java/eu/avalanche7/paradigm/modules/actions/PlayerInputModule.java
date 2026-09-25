package eu.avalanche7.paradigm.modules.actions;

import eu.avalanche7.paradigm.core.ParadigmModule;
import eu.avalanche7.paradigm.core.Services;
import eu.avalanche7.paradigm.platform.Interfaces.ICommandBuilder;
import eu.avalanche7.paradigm.platform.Interfaces.IEventSystem;
import eu.avalanche7.paradigm.platform.Interfaces.IPlayer;

public final class PlayerInputModule implements ParadigmModule {

    @Override public String getName() { return "PlayerInput"; }
    @Override public boolean isEnabled(Services services) { return true; }
    @Override public void onLoad(Object event, Services services, Object modEventBus) { }
    @Override public void onServerStarting(Object event, Services services) { }
    @Override public void onEnable(Services services) { services.getPlayerInputService().activate(); }
    @Override public void onDisable(Services services) { services.getPlayerInputService().clear(); }
    @Override public void onServerStopping(Object event, Services services) { services.getPlayerInputService().shutdown(); }

    @Override
    public void registerCommands(Object dispatcher, Object registryAccess, Services services) {
        ICommandBuilder command = services.getPlatformAdapter().createCommandBuilder()
                .literal("paradigminput")
                .requires(source -> source.getPlayer() != null
                        && services.getCommandToggleStore().isEnabled("paradigminput"))
                .then(services.getPlatformAdapter().createCommandBuilder()
                        .literal("cancel")
                        .executes(context -> cancel(services, context.getSource().requirePlayer(), null))
                        .then(services.getPlatformAdapter().createCommandBuilder()
                                .argument("token", ICommandBuilder.ArgumentType.WORD)
                                .executes(context -> cancel(services, context.getSource().requirePlayer(),
                                        context.getStringArgument("token")))));
        services.getPlatformAdapter().registerCommand(command);
    }

    private static int cancel(Services services, IPlayer player, String rawToken) {
        java.util.UUID token = null;
        if (rawToken != null) {
            try {
                token = java.util.UUID.fromString(rawToken);
            } catch (IllegalArgumentException invalid) {
                return 0;
            }
        }
        if (!services.getPlayerInputService().cancel(player, token)) {
            services.getPlatformAdapter().sendSystemMessage(player,
                    services.getMessageParser().parseMessage("&eThat input prompt is no longer active.", player));
        }
        return 1;
    }

    @Override
    public void registerEventListeners(Object eventBus, Services services) {
        IEventSystem events = lifecycleEvents(services);
        if (events == null) return;
        events.onPlayerChat(services.getPlayerInputService()::onChat);
        events.onPlayerLeave(event -> services.getPlayerInputService().disconnect(event.getPlayer()));
    }
}
