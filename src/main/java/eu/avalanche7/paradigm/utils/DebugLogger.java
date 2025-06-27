package eu.avalanche7.paradigm.utils;

import eu.avalanche7.paradigm.configs.MainConfigHandler;
import org.apache.logging.log4j.Level;
import org.apache.logging.log4j.core.config.Configurator;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.concurrent.atomic.AtomicBoolean;

public class DebugLogger {
    private static final Logger SLF4J_LOGGER = LoggerFactory.getLogger("eu.avalanche7.paradigm");
    private final MainConfigHandler.Config mainConfig;
    private static final AtomicBoolean hasLoggedStatus = new AtomicBoolean(false);

    public DebugLogger(MainConfigHandler.Config mainConfig) {
        this.mainConfig = mainConfig;
        if (!hasLoggedStatus.getAndSet(true)) {
            boolean debugEnabledInConfig = mainConfig != null && mainConfig.debugEnable.value;

            if (debugEnabledInConfig) {
                Configurator.setLevel("eu.avalanche7.paradigm", Level.DEBUG);
                SLF4J_LOGGER.info("[Paradigm] Debug logging is ENABLED. Verbose logs will now be shown.");
            } else {
                Configurator.setLevel("eu.avalanche7.paradigm", Level.INFO);
                SLF4J_LOGGER.info("[Paradigm] Debug logging is DISABLED. To see verbose logs, enable 'debugEnable' in the main config.");
            }
        }
    }

    public void debugLog(String message) {
        if (mainConfig != null && mainConfig.debugEnable.value) {
            SLF4J_LOGGER.debug("[Paradigm-Debug] " + message);
        }
    }
    public void debugLog(String message, Exception e) {
        if (mainConfig != null && mainConfig.debugEnable.value) {
            SLF4J_LOGGER.warn("[Paradigm-Debug] " + message, e);
        }
    }
    public void debugLog(String message, Object... args) {
        if (mainConfig != null && mainConfig.debugEnable.value) {
            SLF4J_LOGGER.debug("[Paradigm-Debug] " + message, args);
        }
    }
}