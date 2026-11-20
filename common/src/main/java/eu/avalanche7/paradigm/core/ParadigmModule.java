package eu.avalanche7.paradigm.core;

import eu.avalanche7.paradigm.platform.Interfaces.IEventSystem;

public interface ParadigmModule {
    String getName();

    boolean isEnabled(Services services);

    default void onLoad(Object event, Services services, Object modEventBus) {
    }

    default void onServerStarting(Object event, Services services) {
    }

    default void onEnable(Services services) {
    }

    default void onDisable(Services services) {
    }

    default void onServerStopping(Object event, Services services) {
    }

    default void onServerStopped(Object event, Services services) {
    }

    default void registerCommands(Object dispatcher, Object registryAccess, Services services) {
    }

    default void registerEventListeners(Object eventBus, Services services) {
    }

    default IEventSystem moduleEvents(Services services) {
        return ModuleEventListeners.gated(services, this);
    }

    default IEventSystem lifecycleEvents(Services services) {
        return ModuleEventListeners.raw(services);
    }
}
