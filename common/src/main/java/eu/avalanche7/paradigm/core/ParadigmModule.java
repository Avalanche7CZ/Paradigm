package eu.avalanche7.paradigm.core;

public interface ParadigmModule {
    String getName();

    boolean isEnabled(Services services);

    void onLoad(Object event, Services services, Object modEventBus);

    void onServerStarting(Object event, Services services);

    void onEnable(Services services);

    void onDisable(Services services);

    void onServerStopping(Object event, Services services);

    void registerCommands(Object dispatcher, Object registryAccess, Services services);

    void registerEventListeners(Object eventBus, Services services);
}

