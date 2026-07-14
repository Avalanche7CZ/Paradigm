package eu.avalanche7.paradigm.api;

import eu.avalanche7.paradigm.api.internal.ApiProviderRegistry;

import java.util.Set;

/**
 * Stable server-side integration facade for optional Paradigm companion mods.
 * API version changes only when these public contracts change incompatibly.
 */
public final class ParadigmAPI {
    public static final int API_VERSION = 1;

    private ParadigmAPI() {
    }

    public static boolean isAvailable() {
        return ApiProviderRegistry.provider().available();
    }

    public static String modVersion() {
        return ApiProviderRegistry.provider().modVersion();
    }

    public static int apiVersion() {
        return API_VERSION;
    }

    public static Set<ApiCapability> capabilities() {
        return ApiProviderRegistry.provider().capabilities();
    }

    public static PermissionService permissions() {
        return ApiProviderRegistry.provider().permissions();
    }

    public static MessageService messages() {
        return ApiProviderRegistry.provider().messages();
    }

    public static PlaceholderService placeholders() {
        return ApiProviderRegistry.provider().placeholders();
    }
}
