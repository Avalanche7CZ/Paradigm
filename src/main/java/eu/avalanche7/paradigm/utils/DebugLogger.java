package eu.avalanche7.paradigm.utils;

import eu.avalanche7.paradigm.configs.MainConfigHandler;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class DebugLogger {
    private static final Logger SLF4J_LOGGER = LoggerFactory.getLogger("Paradigm");
    private final MainConfigHandler.Config mainConfig;

    public DebugLogger(MainConfigHandler.Config mainConfig) {
        this.mainConfig = mainConfig;
    }

    public void debugLog(String message) {
        if (mainConfig != null && mainConfig.debugEnable) {
            SLF4J_LOGGER.info(message);
        }
    }

    public void debugLog(String message, Exception e) {
        if (mainConfig != null && mainConfig.debugEnable) {
            SLF4J_LOGGER.error(message, e);
        }
    }

    public void debugLog(String message, Object... args) {
        if (mainConfig != null && mainConfig.debugEnable) {
            SLF4J_LOGGER.info(message, args);
        }
    }
}
