package eu.avalanche7.paradigm.platform;

import eu.avalanche7.paradigm.metrics.ServerTickMetrics;
import net.minecraftforge.event.TickEvent;

public final class ServerTickMetricsEvents {
    private static boolean registered;

    private ServerTickMetricsEvents() {
    }

    public static synchronized void register() {
        if (registered) return;
        registered = true;
        TickEvent.ServerTickEvent.Pre.BUS.addListener(event -> ServerTickMetrics.onTickStart());
        TickEvent.ServerTickEvent.Post.BUS.addListener(event -> ServerTickMetrics.onTickEnd());
    }
}
