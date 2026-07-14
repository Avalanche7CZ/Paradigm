package eu.avalanche7.paradigm.api.internal;

import eu.avalanche7.paradigm.api.ApiCapability;
import eu.avalanche7.paradigm.api.MessageService;
import eu.avalanche7.paradigm.api.PermissionService;
import eu.avalanche7.paradigm.api.PlaceholderService;

import java.util.Set;

public interface ApiProvider extends AutoCloseable {
    boolean available();
    String modVersion();
    Set<ApiCapability> capabilities();
    PermissionService permissions();
    MessageService messages();
    PlaceholderService placeholders();
    String resolveExternalPlaceholders(String text, java.util.UUID playerUuid);

    @Override
    void close();
}
