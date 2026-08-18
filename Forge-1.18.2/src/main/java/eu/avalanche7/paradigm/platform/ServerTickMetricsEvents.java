package eu.avalanche7.paradigm.platform;

import eu.avalanche7.paradigm.Paradigm;
import eu.avalanche7.paradigm.metrics.ServerTickMetrics;
import net.minecraftforge.event.TickEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;

@Mod.EventBusSubscriber(modid = Paradigm.MOD_ID)
public final class ServerTickMetricsEvents {
    private ServerTickMetricsEvents() {
    }

    @SubscribeEvent
    public static void onServerTick(TickEvent.ServerTickEvent event) {
        if (event.phase == TickEvent.Phase.START) {
            ServerTickMetrics.onTickStart();
        } else if (event.phase == TickEvent.Phase.END) {
            ServerTickMetrics.onTickEnd();
        }
    }
}
