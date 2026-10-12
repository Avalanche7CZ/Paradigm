package eu.avalanche7.paradigm.platform;

import eu.avalanche7.paradigm.Paradigm;
import eu.avalanche7.paradigm.metrics.ServerTickMetrics;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.tick.ServerTickEvent;

@EventBusSubscriber(modid = Paradigm.MOD_ID)
public final class ServerTickMetricsEvents {
    private ServerTickMetricsEvents() {
    }

    @SubscribeEvent
    public static void onServerTickStart(ServerTickEvent.Pre event) {
        ServerTickMetrics.onTickStart();
    }

    @SubscribeEvent
    public static void onServerTickEnd(ServerTickEvent.Post event) {
        ServerTickMetrics.onTickEnd();
    }
}
