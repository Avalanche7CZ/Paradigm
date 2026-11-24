package eu.avalanche7.paradigm.platform;

import net.minecraft.entity.Entity;
import net.minecraft.entity.player.EntityPlayer;
import net.minecraft.entity.player.EntityPlayerMP;
import net.minecraft.inventory.IInventory;
import net.minecraft.world.World;

public final class VanishInteractions {
    private static final ThreadLocal<Boolean> SILENT_VIEWER = new ThreadLocal<>();

    private VanishInteractions() {}

    public static boolean hidden(Object entity) {
        return entity instanceof EntityPlayerMP player && player.getUniqueID() != null
                && VanishTracking.hidden(player.getUniqueID().toString());
    }

    public static boolean silentViewer() {
        return Boolean.TRUE.equals(SILENT_VIEWER.get());
    }

    public static void notifyInventory(IInventory inventory, EntityPlayer viewer, boolean opening) {
        Boolean previous = SILENT_VIEWER.get();
        SILENT_VIEWER.set(hidden(viewer));
        try {
            if (opening) inventory.openInventory();
            else inventory.closeInventory();
        } finally {
            if (previous == null) SILENT_VIEWER.remove();
            else SILENT_VIEWER.set(previous);
        }
    }

    public static EntityPlayer nearestVisiblePlayer(World world, Entity entity, double range) {
        EntityPlayer nearest = null;
        double distance = range * range;
        for (Object entry : world.playerEntities) {
            EntityPlayer player = (EntityPlayer) entry;
            double candidate = player.getDistanceSqToEntity(entity);
            if (candidate < distance && !hidden(player)) {
                distance = candidate;
                nearest = player;
            }
        }
        return nearest;
    }
}
