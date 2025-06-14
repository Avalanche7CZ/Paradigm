package eu.avalanche7.paradigm.utils;

import eu.avalanche7.paradigm.configs.MainConfigHandler;
import org.apache.logging.log4j.Logger;

public class DebugLogger {
    private final Logger logger;
    private final MainConfigHandler.Config mainConfig;

    public DebugLogger(Logger logger, MainConfigHandler.Config mainConfig) {
        this.logger = logger;
        this.mainConfig = mainConfig;
    }

    public void debugLog(String message) {
        if (mainConfig != null && mainConfig.debugEnable.value) {
            logger.info("[DEBUG] " + message);
        }
    }

    public void debugLog(String message, Exception e) {
        if (mainConfig != null && mainConfig.debugEnable.value) {
            logger.error("[DEBUG] " + message, e);
        }
    }

    public void debugLog(String message, Object... args) {
        if (mainConfig != null && mainConfig.debugEnable.value) {
            logger.info("[DEBUG] " + message, args);
        }
    }
}
