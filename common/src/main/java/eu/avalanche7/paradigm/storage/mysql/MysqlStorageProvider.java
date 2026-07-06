package eu.avalanche7.paradigm.storage.mysql;

import eu.avalanche7.paradigm.storage.StorageConfig;
import eu.avalanche7.paradigm.storage.StorageProviderType;
import eu.avalanche7.paradigm.storage.identity.ServerIdentityService;
import eu.avalanche7.paradigm.storage.identity.StorageContext;
import eu.avalanche7.paradigm.storage.runtime.RuntimeJdbcDriverProvider;
import eu.avalanche7.paradigm.storage.sql.SqlStorageProvider;
import org.slf4j.Logger;

public class MysqlStorageProvider extends SqlStorageProvider {
    public MysqlStorageProvider(StorageConfig config, StorageContext context, ServerIdentityService identityService, Logger logger, RuntimeJdbcDriverProvider runtimeDrivers) {
        super(StorageProviderType.MYSQL, config, new MysqlDialect(), context, identityService, logger, runtimeDrivers);
    }
}
