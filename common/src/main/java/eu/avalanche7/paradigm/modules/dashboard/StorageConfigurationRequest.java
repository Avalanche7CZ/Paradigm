package eu.avalanche7.paradigm.modules.dashboard;

public class StorageConfigurationRequest {
    public String provider;
    public Boolean fallbackToJsonOnSqlFailure;
    public String networkId;
    public String serverId;
    public String serverName;
    public String sqlitePath;
    public String sqlHost;
    public Integer sqlPort;
    public String sqlDatabase;
    public String sqlUsername;
    public String sqlPassword;
    public String sqlPasswordEnv;
    public Integer sqlPoolSize;
    public Boolean sqlSsl;
}
