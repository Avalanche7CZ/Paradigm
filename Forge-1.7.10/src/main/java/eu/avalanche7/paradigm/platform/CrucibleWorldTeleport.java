package eu.avalanche7.paradigm.platform;

import java.lang.reflect.Constructor;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;

import net.minecraft.entity.player.EntityPlayerMP;
import net.minecraft.world.WorldServer;
import net.minecraftforge.common.DimensionManager;

import eu.avalanche7.paradigm.data.PlayerDataStore.StoredLocation;

final class CrucibleWorldTeleport {
    private static final Method BUKKIT_DIMENSION = findDimensionTest();

    private CrucibleWorldTeleport() {}

    static boolean isBukkitDimension(int dimension) {
        return BUKKIT_DIMENSION != null && invoke(BUKKIT_DIMENSION, null, dimension);
    }

    static boolean teleport(EntityPlayerMP player, WorldServer world, StoredLocation target) {
        try {
            Class<?> worldType = Class.forName("org.bukkit.World");
            Class<?> locationType = Class.forName("org.bukkit.Location");
            Class<?> playerType = Class.forName("org.bukkit.entity.Player");
            Constructor<?> constructor = locationType.getConstructor(
                    worldType, double.class, double.class, double.class, float.class, float.class);
            Object bukkitWorld = WorldServer.class.getMethod("getWorld").invoke(world);
            Object bukkitPlayer = EntityPlayerMP.class.getMethod("getBukkitEntity").invoke(player);
            Object location = constructor.newInstance(bukkitWorld, target.getX(), target.getY(), target.getZ(),
                    target.getYaw(), target.getPitch());
            return invoke(playerType.getMethod("teleport", locationType), bukkitPlayer, location)
                    && player.worldObj == world && player.dimension == world.provider.dimensionId;
        } catch (ReflectiveOperationException failure) {
            throw new IllegalStateException("Crucible's Bukkit world teleport API is unavailable", failure);
        }
    }

    private static Method findDimensionTest() {
        try {
            return DimensionManager.class.getMethod("isBukkitDimension", int.class);
        } catch (NoSuchMethodException stockForge) {
            return null;
        }
    }

    private static boolean invoke(Method method, Object target, Object argument) {
        try {
            return Boolean.TRUE.equals(method.invoke(target, argument));
        } catch (IllegalAccessException | InvocationTargetException failure) {
            throw new IllegalStateException("Crucible's Bukkit world teleport API failed", failure);
        }
    }
}
