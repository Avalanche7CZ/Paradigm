package eu.avalanche7.paradigm.core;

import com.mojang.brigadier.CommandDispatcher;
import net.neoforged.neoforge.event.server.ServerStartingEvent;
import net.neoforged.neoforge.event.server.ServerStoppingEvent;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.fml.event.lifecycle.FMLCommonSetupEvent;

public interface ParadigmModule {
    String getName();
    void onLoad(FMLCommonSetupEvent event, Services services, IEventBus modEventBus);
    void onServerStarting(ServerStartingEvent event, Services services);
    void onEnable(Services services);
    void onDisable(Services services);
    void onServerStopping(ServerStoppingEvent event, Services services);
    void registerCommands(CommandDispatcher<?> dispatcher, Services services);
    void registerEventListeners(IEventBus forgeEventBus, Services services);
    boolean isEnabled(Services services);
}
