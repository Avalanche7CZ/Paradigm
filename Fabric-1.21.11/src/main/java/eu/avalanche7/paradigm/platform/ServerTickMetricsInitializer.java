package eu.avalanche7.paradigm.platform;

import eu.avalanche7.paradigm.metrics.ServerTickMetrics;
import net.fabricmc.api.DedicatedServerModInitializer;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents;

public final class ServerTickMetricsInitializer implements DedicatedServerModInitializer {
    @Override
    public void onInitializeServer() {
        ServerTickEvents.START_SERVER_TICK.register(server -> ServerTickMetrics.onTickStart());
        ServerTickEvents.END_SERVER_TICK.register(server -> ServerTickMetrics.onTickEnd());
    }
}
