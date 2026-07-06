package eu.avalanche7.paradigm.storage.identity;

import eu.avalanche7.paradigm.storage.StorageConfig;
import eu.avalanche7.paradigm.storage.repository.ServerRepository;
import org.slf4j.Logger;

import java.util.Locale;
import java.util.regex.Pattern;

public class ServerIdentityService {
    private static final Pattern SAFE_ID = Pattern.compile("[A-Za-z0-9_.:-]{1,64}");

    private final Logger logger;
    private final ServerIdentity identity;

    public ServerIdentityService(Logger logger, StorageConfig config) {
        this.logger = logger;
        this.identity = new ServerIdentity(
                normalizeId(config != null ? config.networkId : null, "default", "networkId"),
                normalizeId(config != null ? config.serverId : null, "default", "serverId"),
                normalizeName(config != null ? config.serverName : null)
        );
    }

    public ServerIdentity current() {
        return identity;
    }

    public boolean registerWith(ServerRepository repository) {
        if (repository == null) {
            return false;
        }
        try {
            repository.registerServer(identity);
            repository.updateLastSeen(identity);
            return true;
        } catch (Throwable t) {
            if (logger != null) {
                logger.warn("Paradigm storage: failed to register server identity {}: {}", identity.serverId(), t.getMessage());
            }
            return false;
        }
    }

    private String normalizeId(String raw, String fallback, String label) {
        String value = raw != null ? raw.trim() : "";
        if (value.isBlank()) {
            return fallback;
        }
        if (!SAFE_ID.matcher(value).matches()) {
            if (logger != null) {
                logger.warn("Paradigm storage: invalid {}, falling back to '{}'.", label, fallback);
            }
            return fallback;
        }
        return value.toLowerCase(Locale.ROOT);
    }

    private String normalizeName(String raw) {
        String value = raw != null ? raw.trim() : "";
        return value.isBlank() ? "Default Server" : value;
    }
}
