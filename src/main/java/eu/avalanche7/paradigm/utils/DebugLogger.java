package eu.avalanche7.paradigm.utils;

import eu.avalanche7.paradigm.configs.MainConfigHandler;
import com.mojang.logging.LogUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.apache.logging.log4j.Level;
import org.apache.logging.log4j.core.config.Configurator;

import java.util.concurrent.atomic.AtomicBoolean;

public class DebugLogger {
    private static final Logger SLF4J_LOGGER = LoggerFactory.getLogger(DebugLogger.class);
    private final MainConfigHandler.Config mainConfig;
    private static final AtomicBoolean hasLoggedStatus = new AtomicBoolean(false);

    public DebugLogger(MainConfigHandler.Config mainConfig) {
        this.mainConfig = mainConfig;
        boolean debugEnabledInConfig = mainConfig != null && mainConfig.debugEnable.get();
        SLF4J_LOGGER.info("[Paradigm-DebugLogger] Ensuring logger exists for dynamic level setting");
        if (!hasLoggedStatus.getAndSet(true)) {
            if (debugEnabledInConfig) {
                SLF4J_LOGGER.info("[Paradigm] Debug logging is ENABLED. Verbose logs will now be shown. (Check both console and logs/latest.log)");
                setParadigmLogLevel(DebugLogger.class.getName(), true);
            } else {
                SLF4J_LOGGER.info("[Paradigm] Debug logging is DISABLED. To see verbose logs, enable 'debugEnable' in the main config.");
                setParadigmLogLevel(DebugLogger.class.getName(), false);
                SLF4J_LOGGER.warn("[Paradigm] DebugLogger constructed with debug disabled! No debug logs will be shown.");
            }
        } else if (!debugEnabledInConfig) {
            SLF4J_LOGGER.warn("[Paradigm] DebugLogger constructed with debug disabled! No debug logs will be shown.");
        }
    }

    private void setParadigmLogLevel(String loggerName, boolean debug) {
        // Try Logback (Forge 1.20.1+)
        try {
            SLF4J_LOGGER.info("[Paradigm-DebugLogger] Trying Logback for logger: {} (debug={})", loggerName, debug);
            Class<?> loggerFactoryClass = Class.forName("org.slf4j.LoggerFactory");
            Object loggerContext = loggerFactoryClass.getMethod("getILoggerFactory").invoke(null);
            if (loggerContext.getClass().getName().equals("ch.qos.logback.classic.LoggerContext")) {
                Class<?> loggerClass = Class.forName("ch.qos.logback.classic.Logger");
                Class<?> levelClass = Class.forName("ch.qos.logback.classic.Level");
                Object level = debug ? levelClass.getField("DEBUG").get(null) : levelClass.getField("INFO").get(null);
                Object logger = loggerContext.getClass().getMethod("getLogger", String.class).invoke(loggerContext, loggerName);
                if (loggerClass.isInstance(logger)) {
                    loggerClass.getMethod("setLevel", levelClass).invoke(logger, level);
                    SLF4J_LOGGER.info("[Paradigm-DebugLogger] Logback logger '{}' level set to {}", loggerName, debug ? "DEBUG" : "INFO");
                    return;
                } else {
                    SLF4J_LOGGER.warn("[Paradigm-DebugLogger] Logger instance is not Logback Logger!");
                }
            } else {
                SLF4J_LOGGER.info("[Paradigm-DebugLogger] Not a Logback context, falling back to Log4j2.");
            }
        } catch (Throwable t) {
            SLF4J_LOGGER.info("[Paradigm-DebugLogger] Logback not available or failed: {}", t.toString());
        }
        // Try Log4j2 (Forge 1.18.2–1.19.2)
        try {
            SLF4J_LOGGER.info("[Paradigm-DebugLogger] Trying Log4j2 for logger: {} (debug={})", loggerName, debug);
            Class<?> logManagerClass = Class.forName("org.apache.logging.log4j.LogManager");
            Class<?> levelClass = Class.forName("org.apache.logging.log4j.Level");
            Object level = debug ? levelClass.getField("DEBUG").get(null) : levelClass.getField("INFO").get(null);
            Object ctx = logManagerClass.getMethod("getContext", boolean.class).invoke(null, false);
            Object config = ctx.getClass().getMethod("getConfiguration").invoke(ctx);
            java.util.Map loggers = (java.util.Map) config.getClass().getMethod("getLoggers").invoke(config);
            String currentLogger = loggerName;
            boolean set = false;
            while (currentLogger != null) {
                Object loggerConfig = loggers.get(currentLogger);
                if (loggerConfig != null) {
                    loggerConfig.getClass().getMethod("setLevel", levelClass).invoke(loggerConfig, level);
                    ctx.getClass().getMethod("updateLoggers").invoke(ctx);
                    SLF4J_LOGGER.info("[Paradigm-DebugLogger] Log4j2 logger '{}' level set to {}", currentLogger, debug ? "DEBUG" : "INFO");
                    set = true;
                    break;
                }
                int lastDot = currentLogger.lastIndexOf('.');
                if (lastDot > 0) {
                    currentLogger = currentLogger.substring(0, lastDot);
                } else if (!currentLogger.isEmpty()) {
                    currentLogger = ""; // Try root logger
                } else {
                    currentLogger = null;
                }
            }
            if (!set) {
                Object rootLoggerConfig = loggers.get("root");
                if (rootLoggerConfig != null) {
                    rootLoggerConfig.getClass().getMethod("setLevel", levelClass).invoke(rootLoggerConfig, level);
                    ctx.getClass().getMethod("updateLoggers").invoke(ctx);
                    SLF4J_LOGGER.info("[Paradigm-DebugLogger] Log4j2 root logger level set to {}", debug ? "DEBUG" : "INFO");
                    set = true;
                }
            }
            if (!set) {
                SLF4J_LOGGER.warn("[Paradigm-DebugLogger] Log4j2 logger '{}' and its parents not found in config map! (Did you define it in log4j2.xml?)", loggerName);
            }
            if (set) return;
        } catch (Throwable t) {
            SLF4J_LOGGER.error("[Paradigm-DebugLogger] Log4j2 log level set failed: {}", t.toString());
        }
        SLF4J_LOGGER.warn("[Paradigm-DebugLogger] Could not set log level for '{}'! No compatible logging backend found.", loggerName);
    }

    public void debugLog(String message) {
        if (mainConfig != null && mainConfig.debugEnable.get()) {
            SLF4J_LOGGER.info("[Paradigm-Debug] " + message);
        }
    }

    public void debugLog(String message, Exception e) {
        if (mainConfig != null && mainConfig.debugEnable.get()) {
            SLF4J_LOGGER.info("[Paradigm-Debug] " + message, e);
        }
    }

    public void debugLog(String message, Object... args) {
        if (mainConfig != null && mainConfig.debugEnable.get()) {
            SLF4J_LOGGER.info("[Paradigm-Debug] " + message, args);
        }
    }
}