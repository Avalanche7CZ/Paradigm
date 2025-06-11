package eu.avalanche7.paradigm.core;

import com.mojang.brigadier.CommandDispatcher;
import net.minecraft.commands.CommandSourceStack;
import net.minecraftforge.event.server.ServerStartingEvent;
import net.minecraftforge.event.server.ServerStoppingEvent;
import net.minecraftforge.eventbus.api.IEventBus;
import net.minecraftforge.fml.event.lifecycle.FMLCommonSetupEvent;

public interface ParadigmModule {
    String getName();
    void onLoad(FMLCommonSetupEvent event, Services services, IEventBus modEventBus);
    void onServerStarting(ServerStartingEvent event, Services services);
    void onEnable(Services services);
    void onDisable(Services services);
    void onServerStopping(ServerStoppingEvent event, Services services);
    void registerCommands(CommandDispatcher<CommandSourceStack> dispatcher, Services services);
    void registerEventListeners(IEventBus forgeEventBus, Services services);
    boolean isEnabled(Services services);
}

