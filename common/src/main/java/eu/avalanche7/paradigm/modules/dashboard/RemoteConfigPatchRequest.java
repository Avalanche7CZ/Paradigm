package eu.avalanche7.paradigm.modules.dashboard;

import java.util.List;

import eu.avalanche7.paradigm.configs.schema.ConfigPatchOperation;

public final class RemoteConfigPatchRequest {
    public String serverId = "";
    public String scope = "SERVER";
    public String section = "";
    public long expectedRevision = 0L;
    public List<ConfigPatchOperation> operations = List.of();
}
