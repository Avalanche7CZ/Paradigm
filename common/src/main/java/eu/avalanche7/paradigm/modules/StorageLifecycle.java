package eu.avalanche7.paradigm.modules;

import eu.avalanche7.paradigm.core.ParadigmModule;
import eu.avalanche7.paradigm.core.Services;

public class StorageLifecycle implements ParadigmModule {
    @Override public String getName() { return "StorageLifecycle"; }
    @Override public boolean isEnabled(Services services) { return true; }
    @Override public void onLoad(Object event, Services services, Object modEventBus) {}
    @Override public void onServerStarting(Object event, Services services) {}
    @Override public void onEnable(Services services) {}
    @Override public void onDisable(Services services) {}
    @Override public void registerCommands(Object dispatcher, Object registryAccess, Services services) {}
    @Override public void registerEventListeners(Object eventBus, Services services) {}

    @Override
    public void onServerStopping(Object event, Services services) {
        if (services != null && services.getStorageService() != null) {
            services.getStorageService().close();
        }
    }
}
