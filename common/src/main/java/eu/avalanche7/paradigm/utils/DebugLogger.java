package eu.avalanche7.paradigm.utils;

import eu.avalanche7.paradigm.configs.MainConfigHandler;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class DebugLogger {
    private static final Logger LOG = LoggerFactory.getLogger("eu.avalanche7.paradigm");
    private final MainConfigHandler.Config mainConfig;
    private static boolean isDebugConfigured = false;

    public DebugLogger(MainConfigHandler.Config mainConfig) {
        this.mainConfig = mainConfig;
        configureDebugLevel();
    }

    private void configureDebugLevel() {
        if (!isDebugConfigured && mainConfig != null) {
            boolean debugEnabledInConfig = mainConfig.debugEnable.value;

            if (debugEnabledInConfig) {
                LOG.info("[Paradigm] Debug logging is ENABLED. Verbose logs will now be shown.");
            } else {
                LOG.info("[Paradigm] Debug logging is DISABLED. To see verbose logs, enable 'debugEnable' in the main config.");
            }
            isDebugConfigured = true;
        }
    }

    public void debugLog(String message) {
        if (mainConfig != null && mainConfig.debugEnable.value) {
            LOG.debug("[Paradigm-Debug] " + message);
        }
    }

    public void debugLog(String message, Exception e) {
        if (mainConfig != null && mainConfig.debugEnable.value) {
            LOG.warn("[Paradigm-Debug] " + message, e);
        }
    }

    public void debugLog(String message, Object... args) {
        if (mainConfig != null && mainConfig.debugEnable.value) {
            LOG.debug("[Paradigm-Debug] " + message, args);
        }
    }
}