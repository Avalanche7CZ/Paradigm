package eu.avalanche7.paradigm.utils;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import eu.avalanche7.paradigm.core.Services;

public final class ServerStatusDiagnostics {
    private static final Logger LOGGER = LoggerFactory.getLogger("eu.avalanche7.paradigm.server-status");

    private ServerStatusDiagnostics() {
    }

    public static void received(Services services, String target, String remoteAddress, boolean enabled, int motdCount) {
        debug(services, "Server status [" + target + "]: request received from " + safe(remoteAddress)
                + "; customMotdEnabled=" + enabled + "; configuredMotds=" + Math.max(0, motdCount) + ".");
    }

    public static void servicesUnavailable(String target) {
        LOGGER.debug("[Paradigm] Server status [{}]: request reached the mixin before shared services were available; vanilla processing will continue.",
                target);
    }

    public static void constructed(Services services, String target, String remoteAddress) {
        debug(services, "Server status [" + target + "]: custom response constructed for " + safe(remoteAddress) + ".");
    }

    public static void queued(Services services, String target, String remoteAddress) {
        debug(services, "Server status [" + target + "]: custom response queued for " + safe(remoteAddress) + ".");
    }

    public static void sent(Services services, String target, String remoteAddress) {
        debug(services, "Server status [" + target + "]: custom response write completed for " + safe(remoteAddress) + ".");
    }

    public static void vanillaFallback(Services services, String target, String remoteAddress, String reason) {
        debug(services, "Server status [" + target + "]: falling back to vanilla for " + safe(remoteAddress)
                + "; reason=" + safe(reason) + ".");
    }

    public static void customizationFailed(Services services, String target, String remoteAddress, Throwable failure) {
        if (services != null && services.getLogger() != null) {
            services.getLogger().warn("[Paradigm] Server status [{}]: custom response failed for {}; vanilla processing will continue.",
                    target, safe(remoteAddress), failure);
        }
    }

    public static void sendFailed(Services services, String target, String remoteAddress, Throwable failure) {
        if (services != null && services.getLogger() != null) {
            if (failure != null) {
                services.getLogger().warn("[Paradigm] Server status [{}]: custom response write failed for {}.",
                        target, safe(remoteAddress), failure);
            } else {
                services.getLogger().warn("[Paradigm] Server status [{}]: custom response write failed for {}; attempting vanilla response.",
                        target, safe(remoteAddress));
            }
        }
    }

    private static void debug(Services services, String message) {
        if (services != null && services.getDebugLogger() != null) {
            services.getDebugLogger().debugLog(message);
        }
    }

    private static String safe(String value) {
        return value == null || value.isBlank() ? "unknown" : value;
    }
}
