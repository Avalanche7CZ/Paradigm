package eu.avalanche7.paradigm.modules;

import eu.avalanche7.paradigm.core.ParadigmModule;
import eu.avalanche7.paradigm.core.Services;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.MutableComponent;
import net.minecraft.server.level.ServerPlayer;
import net.minecraftforge.event.entity.player.PlayerEvent;
import net.minecraftforge.event.server.ServerStartingEvent;
import net.minecraftforge.event.server.ServerStoppingEvent;
import net.minecraftforge.eventbus.api.IEventBus;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.event.lifecycle.FMLCommonSetupEvent;
import com.mojang.brigadier.CommandDispatcher;
import net.minecraft.commands.CommandSourceStack;

import java.util.List;

public class MOTD implements ParadigmModule {

    private static final String NAME = "MOTD";
    private Services services;

    @Override
    public String getName() {
        return NAME;
    }

    @Override
    public boolean isEnabled(Services services) {
        return services.getMainConfig().motdEnable.get();
    }

    @Override
    public void onLoad(FMLCommonSetupEvent event, Services services, IEventBus modEventBus) {
        this.services = services;
        if (services != null && services.getDebugLogger() != null) {
            services.getDebugLogger().debugLog(NAME + " module loaded.");
        }
    }

    @Override
    public void onServerStarting(ServerStartingEvent event, Services services) {
        if (services != null && services.getDebugLogger() != null) {
            services.getDebugLogger().debugLog(NAME + " module: Server starting.");
        }
    }

    @Override
    public void onEnable(Services services) {
        if (services != null && services.getDebugLogger() != null) {
            services.getDebugLogger().debugLog(NAME + " module enabled.");
        }
    }

    @Override
    public void onDisable(Services services) {
        if (services != null && services.getDebugLogger() != null) {
            services.getDebugLogger().debugLog(NAME + " module disabled.");
        }
    }

    @Override
    public void onServerStopping(ServerStoppingEvent event, Services services) {
        if (services != null && services.getDebugLogger() != null) {
            services.getDebugLogger().debugLog(NAME + " module: Server stopping.");
        }
    }

    @Override
    public void registerCommands(CommandDispatcher<CommandSourceStack> dispatcher, Services services) {
    }

    @Override
    public void registerEventListeners(IEventBus forgeEventBus, Services services) {
        forgeEventBus.register(this);
    }

    @SubscribeEvent
    public void onPlayerJoin(PlayerEvent.PlayerLoggedInEvent event) {
        if (this.services == null || !isEnabled(this.services) || !(event.getEntity() instanceof ServerPlayer)) {
            return;
        }
        ServerPlayer player = (ServerPlayer) event.getEntity();
        Component motdMessage = createMOTDMessage(player, this.services);
        player.sendSystemMessage(motdMessage);
        this.services.getDebugLogger().debugLog("Sent MOTD to " + player.getName().getString());
    }

    private Component createMOTDMessage(ServerPlayer player, Services services) {
        if (services == null || services.getMotdConfig() == null) {
            if(services != null && services.getDebugLogger() != null) {
                services.getDebugLogger().debugLog("MOTDModule: Services or MOTDConfig is null in createMOTDMessage.");
            }
            return Component.empty();
        }
        List<String> lines = services.getMotdConfig().motdLines;
        if (lines == null || lines.isEmpty()) {
            return Component.empty();
        }
        MutableComponent motdMessage = Component.literal("");
        for (String line : lines) {
            if (services.getMessageParser() != null) {
                motdMessage.append(services.getMessageParser().parseMessage(line, player)).append(Component.literal("\n"));
            } else {
                if(services.getDebugLogger() != null) {
                    services.getDebugLogger().debugLog("MOTDModule: MessageParser is null in createMOTDMessage loop.");
                }
                motdMessage.append(Component.literal(line)).append(Component.literal("\n"));
            }
        }
        return motdMessage;
    }
}
