package eu.avalanche7.paradigm.core;

import net.minecraft.command.ICommand;
import net.minecraftforge.fml.common.event.FMLPreInitializationEvent;
import net.minecraftforge.fml.common.event.FMLServerStartingEvent;
import net.minecraftforge.fml.common.event.FMLServerStoppingEvent;

/**
 * Defines the contract for all modules in the Paradigm system for Minecraft 1.12.2.
 */
public interface ParadigmModule {

    String getName();

    boolean isEnabled(Services services);

    /**
     * Called during Forge's Pre-Initialization phase.
     */
    void onLoad(FMLPreInitializationEvent event, Services services);

    /**
     * Called when the server is starting. Use this to register commands.
     */
    void onServerStarting(FMLServerStartingEvent event, Services services);

    /**
     * Called after onLoad and when the server is available.
     */
    void onEnable(Services services);

    /**
     * Called when the module is being disabled during server shutdown.
     */
    void onDisable(Services services);

    /**
     * Called when the server is stopping.
     */
    void onServerStopping(FMLServerStoppingEvent event, Services services);

    /**
     * Returns the command instance for this module, to be registered by the main mod class.
     * @return An ICommand instance, or null if this module has no command.
     */
    ICommand getCommand();

    /**
     * Used to register any event listeners (e.g., to MinecraftForge.EVENT_BUS).
     */
    void registerEventListeners(Services services);
}
