package eu.avalanche7.paradigm.modules.holograms;

import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;

import eu.avalanche7.paradigm.utils.TaskScheduler;

public final class HologramUpdateScheduler {
    private final TaskScheduler scheduler;
    private final Runnable update;
    private ScheduledFuture<?> task;

    public HologramUpdateScheduler(TaskScheduler scheduler, Runnable update) {
        this.scheduler = scheduler;
        this.update = update;
    }

    public void start() {
        if (task == null || task.isCancelled()) task = scheduler.scheduleAtFixedRate(update, 1, 1, TimeUnit.SECONDS);
    }

    public void stop() {
        if (task != null) task.cancel(false);
        task = null;
    }
}
