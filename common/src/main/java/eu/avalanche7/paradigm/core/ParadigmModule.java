package eu.avalanche7.paradigm.core;

import com.mojang.brigadier.CommandDispatcher;
import net.minecraft.command.CommandRegistryAccess;
import net.minecraft.server.command.ServerCommandSource;

public interface ParadigmModule {
    String getName();

    boolean isEnabled(Services services);

    void onLoad(Object event, Services services, Object modEventBus);

    void onServerStarting(Object event, Services services);

    void onEnable(Services services);

    void onDisable(Services services);

    void onServerStopping(Object event, Services services);

    void registerCommands(CommandDispatcher<ServerCommandSource> dispatcher, CommandRegistryAccess registryAccess, Services services);

    void registerEventListeners(Object eventBus, Services services);
}