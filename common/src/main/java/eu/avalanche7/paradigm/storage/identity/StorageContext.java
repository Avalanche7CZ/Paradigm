package eu.avalanche7.paradigm.storage.identity;

public record StorageContext(ServerIdentity serverIdentity) {
    public String serverId() {
        return serverIdentity != null ? serverIdentity.serverId() : "default";
    }

    public String networkId() {
        return serverIdentity != null ? serverIdentity.networkId() : "default";
    }
}
