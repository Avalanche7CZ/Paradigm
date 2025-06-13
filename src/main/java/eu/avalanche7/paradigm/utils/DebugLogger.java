package eu.avalanche7.paradigm.utils;

import eu.avalanche7.paradigm.configs.MainConfigHandler;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

public class DebugLogger {
    private static final Logger LOGGER = LogManager.getLogger();

    public static void debugLog(String message) {
        if (MainConfigHandler.DEBUG_ENABLE) {
            LOGGER.info(message);
        }
    }

    public static void debugLog(String message, Exception e) {
        if (MainConfigHandler.DEBUG_ENABLE) {
            LOGGER.error(message, e);
        }
    }

    public static void debugLog(String message, Object... args) {
        if (MainConfigHandler.DEBUG_ENABLE) {
            LOGGER.info(message, args);
        }
    }
}