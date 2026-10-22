package eu.avalanche7.paradigm.storage.sql;

import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;

import eu.avalanche7.paradigm.storage.StorageConfig;
import eu.avalanche7.paradigm.storage.identity.ServerIdentityService;
import eu.avalanche7.paradigm.storage.identity.StorageContext;
import eu.avalanche7.paradigm.storage.mysql.MysqlStorageProvider;

@EnabledIfEnvironmentVariable(named = "PARADIGM_REGRESSION_MARIADB", matches = "true")
class MariaDbPermissionInstanceSyncTest extends PermissionInstanceSyncTest {
    @Override SqlStorageProvider provider(String server) {
        var config = new StorageConfig();
        config.provider = "mysql";
        config.sql.host = "127.0.0.1";
        config.sql.port = 33317;
        config.sql.database = "paradigm_regression";
        config.sql.username = "paradigm_test";
        config.sql.password = "disposable-regression";
        config.runtimeLibraries.enabled = false;
        config.serverId = server;
        config.networkId = "isolated";
        var identity = new ServerIdentityService(null, config);
        var provider = new MysqlStorageProvider(config, new StorageContext(identity.current()), identity, null, null);
        provider.initialize();
        return provider;
    }
}
