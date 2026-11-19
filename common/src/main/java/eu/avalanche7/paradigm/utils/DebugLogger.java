package eu.avalanche7.paradigm.utils;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import eu.avalanche7.paradigm.configs.MainConfigHandler;

public class DebugLogger {
    private static final Logger LOG = LoggerFactory.getLogger("eu.avalanche7.paradigm");
    private final java.util.function.Supplier<MainConfigHandler.Config> mainConfig;
    private static boolean isDebugConfigured = false;

    public DebugLogger(MainConfigHandler.Config mainConfig) {
        this.mainConfig = mainConfig != null && MainConfigHandler.handler() != null && MainConfigHandler.getConfig() == mainConfig
                ? MainConfigHandler::getConfig : () -> mainConfig;
        configureDebugLevel();
    }

    private void configureDebugLevel() {
        if (!isDebugConfigured && mainConfig.get() != null) {
            boolean debugEnabledInConfig = isEnabled();

            if (debugEnabledInConfig) {
                LOG.info("[Paradigm] Debug logging is ENABLED. Verbose logs will now be shown.");
            } else {
                LOG.info("[Paradigm] Debug logging is DISABLED. To see verbose logs, enable 'debugEnable' in the main config.");
            }
            isDebugConfigured = true;
        }
    }

    public boolean isEnabled() {
        MainConfigHandler.Config config = mainConfig.get();
        return config != null && Boolean.TRUE.equals(config.debugEnable.value);
    }

    public void debugLog(String message) {
        if (isEnabled()) {
            LOG.info("[Paradigm-Debug] " + message);
        }
    }

    public void debugLog(String message, Throwable failure) {
        if (isEnabled()) {
            LOG.info("[Paradigm-Debug] " + message, failure);
        }
    }

    public void debugLog(String message, Object... args) {
        if (isEnabled()) {
            LOG.info("[Paradigm-Debug] " + message, args);
        }
    }
}
