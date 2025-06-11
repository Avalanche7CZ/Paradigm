package eu.avalanche7.paradigm.utils;

import eu.avalanche7.paradigm.configs.MainConfigHandler;
import com.mojang.logging.LogUtils;
import org.slf4j.Logger;

public class DebugLogger {
    private static final Logger SLF4J_LOGGER = LogUtils.getLogger();
    private final MainConfigHandler.Config mainConfig;

    public DebugLogger(MainConfigHandler.Config mainConfig) {
        this.mainConfig = mainConfig;
    }

    public void debugLog(String message) {
        if (mainConfig != null && mainConfig.debugEnable.get()) {
            SLF4J_LOGGER.info(message);
        }
    }

    public void debugLog(String message, Exception e) {
        if (mainConfig != null && mainConfig.debugEnable.get()) {
            SLF4J_LOGGER.error(message, e);
        }
    }

    public void debugLog(String message, Object... args) {
        if (mainConfig != null && mainConfig.debugEnable.get()) {
            SLF4J_LOGGER.info(message, args);
        }
    }
}
