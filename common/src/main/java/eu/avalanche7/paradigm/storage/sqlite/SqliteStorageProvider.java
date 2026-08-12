package eu.avalanche7.paradigm.storage.sqlite;

import org.slf4j.Logger;

import eu.avalanche7.paradigm.storage.StorageConfig;
import eu.avalanche7.paradigm.storage.StorageProviderType;
import eu.avalanche7.paradigm.storage.identity.ServerIdentityService;
import eu.avalanche7.paradigm.storage.identity.StorageContext;
import eu.avalanche7.paradigm.storage.runtime.RuntimeJdbcDriverProvider;
import eu.avalanche7.paradigm.storage.sql.SqlStorageProvider;

public class SqliteStorageProvider extends SqlStorageProvider {
    public SqliteStorageProvider(StorageConfig config, StorageContext context, ServerIdentityService identityService, Logger logger, RuntimeJdbcDriverProvider runtimeDrivers) {
        super(StorageProviderType.SQLITE, config, new SqliteDialect(), context, identityService, logger, runtimeDrivers);
    }
}
