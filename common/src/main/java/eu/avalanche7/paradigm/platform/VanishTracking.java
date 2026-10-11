package eu.avalanche7.paradigm.platform;

import java.lang.ref.WeakReference;
import java.util.Map;
import java.util.WeakHashMap;

import eu.avalanche7.paradigm.ParadigmAPI;
import eu.avalanche7.paradigm.core.Services;
import eu.avalanche7.paradigm.platform.Interfaces.IVanishTracker;

public final class VanishTracking {
    private static final Map<Object, WeakReference<IVanishTracker>> trackers = new WeakHashMap<>();

    private VanishTracking() {}

    public static synchronized void bind(Object entity, IVanishTracker tracker) { trackers.put(entity, new WeakReference<>(tracker)); }
    public static synchronized void unbind(Object entity, IVanishTracker tracker) {
        WeakReference<IVanishTracker> current = trackers.get(entity);
        if (current != null && current.get() == tracker) trackers.remove(entity);
    }
    public static void refresh(Object viewer, Object target) {
        IVanishTracker tracker;
        synchronized (VanishTracking.class) {
            WeakReference<IVanishTracker> reference = trackers.get(target);
            tracker = reference == null ? null : reference.get();
        }
        if (tracker != null) tracker.paradigm$refreshVisibility(viewer);
    }
    public static boolean canSee(Object viewer, Object target) {
        Services services = ParadigmAPI.getServices();
        return services == null || services.getVanishService().canSee(services.getPlatformAdapter().wrapPlayer(viewer), services.getPlatformAdapter().wrapPlayer(target));
    }
    public static boolean seesHidden(Object viewer) {
        Services services = ParadigmAPI.getServices();
        return viewer != null && services != null && services.getPermissionsHandler() != null
                && services.getPermissionsHandler().hasPermission(services.getPlatformAdapter().wrapPlayer(viewer), eu.avalanche7.paradigm.modules.permissions.ParadigmPermissions.VANISH_SEE);
    }
    public static boolean hidden(String uuid) {
        Services services = ParadigmAPI.getServices();
        return services != null && services.getVanishService().isHidden(uuid);
    }
}
