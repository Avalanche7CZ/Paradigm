package eu.avalanche7.paradigm.modules;

import eu.avalanche7.paradigm.core.ParadigmModule;
import eu.avalanche7.paradigm.core.Services;

public class StorageLifecycle implements ParadigmModule {
    @Override public String getName() { return "StorageLifecycle"; }
    @Override public boolean isEnabled(Services services) { return true; }
    @Override
    public void onServerStarting(Object event, Services services) {
        var configSync = services.getManagedConfigSyncService();
        configSync.reconcileOnStartup();
        services.getServerHeartbeatPublisher().start();
        configSync.start();
        services.getPermissionsHandler().startDefinitionSync(services.getTaskScheduler());
    }

}
