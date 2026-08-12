package eu.avalanche7.paradigm.utils;

import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public final class ServerShutdownWatchdog {
    private static final Logger LOGGER = LoggerFactory.getLogger("eu.avalanche7.paradigm");
    private static final AtomicBoolean ARMED = new AtomicBoolean();

    private ServerShutdownWatchdog() {
    }

    public static void arm() {
        if (!ARMED.compareAndSet(false, true)) {
            return;
        }

        Thread watchdog = new Thread(() -> {
            try {
                TimeUnit.SECONDS.sleep(30);
                LOGGER.warn("[Paradigm] Graceful shutdown timed out after 30 seconds; forcing JVM exit with status 1.");
                System.exit(1);
            } catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt();
            }
        }, "Paradigm-Shutdown-Watchdog");
        watchdog.setDaemon(true);
        watchdog.start();
    }
}
