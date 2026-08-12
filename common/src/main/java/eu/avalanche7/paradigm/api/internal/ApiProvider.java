package eu.avalanche7.paradigm.api.internal;

import java.util.Set;

import eu.avalanche7.paradigm.api.ApiCapability;
import eu.avalanche7.paradigm.api.MessageService;
import eu.avalanche7.paradigm.api.PermissionService;
import eu.avalanche7.paradigm.api.PlaceholderService;

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
