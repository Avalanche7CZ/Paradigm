package eu.avalanche7.paradigm.platform;

import eu.avalanche7.paradigm.mixin.ArmorStandEntityAccessor;
import eu.avalanche7.paradigm.platform.Interfaces.IHologramPlatform;
import net.minecraft.entity.Entity;
import net.minecraft.entity.EntityType;
import net.minecraft.entity.decoration.ArmorStandEntity;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.text.Text;
import net.minecraft.util.Identifier;
import net.minecraft.util.registry.Registry;
import net.minecraft.util.registry.RegistryKey;
import net.minecraft.world.World;

import java.util.HashSet;
import java.util.Set;
import java.util.UUID;

public final class MinecraftHologramPlatform implements IHologramPlatform {
    private static final String KEY_TAG_PREFIX = OWNER_TAG + ":";
    private static final double ARMOR_STAND_NAME_OFFSET = 1.975D;

    private final PlatformAdapterImpl adapter;

    public MinecraftHologramPlatform(PlatformAdapterImpl adapter) {
        this.adapter = adapter;
    }

    @Override
    public boolean isChunkLoaded(Location location) {
        ServerWorld world = resolveWorld(location.dimension());
        if (world == null) {
            return false;
        }
        int chunkX = ((int) Math.floor(location.x())) >> 4;
        int chunkZ = ((int) Math.floor(location.z())) >> 4;
        return world.isChunkLoaded(chunkX, chunkZ);
    }

    @Override
    public boolean isEntityLoaded(String runtimeId) {
        UUID entityId = parseRuntimeId(runtimeId);
        MinecraftServer server = server();
        if (entityId == null || server == null) {
            return false;
        }
        for (ServerWorld world : server.getWorlds()) {
            if (world.getEntity(entityId) != null) {
                return true;
            }
        }
        return false;
    }

    @Override
    public String upsertLine(LineRequest request, String runtimeId) {
        ServerWorld world = resolveWorld(request.location().dimension());
        if (world == null || !isChunkLoaded(request.location())) {
            return null;
        }

        Entity ownedEntity = findOwnedEntity(world, parseRuntimeId(runtimeId), request.ownershipKey());
        ArmorStandEntity armorStand;
        if (ownedEntity instanceof ArmorStandEntity existingStand) {
            armorStand = existingStand;
        } else {
            if (ownedEntity != null) {
                ownedEntity.discard();
            }
            armorStand = spawnArmorStand(world, request);
        }

        Object originalText = request.text().getOriginalText();
        Text nativeText = originalText instanceof Text text
                ? text
                : Text.literal(request.text().getRawText());
        armorStand.setCustomName(nativeText);
        return armorStand.getUuidAsString();
    }

    @Override
    public void removeLine(String runtimeId) {
        UUID entityId = parseRuntimeId(runtimeId);
        MinecraftServer server = server();
        if (entityId == null || server == null) {
            return;
        }
        for (ServerWorld world : server.getWorlds()) {
            Entity entity = world.getEntity(entityId);
            if (isOwned(entity)) {
                entity.discard();
            }
        }
    }

    @Override
    public void removeUnknownOwnedLines(Set<String> validOwnershipKeys) {
        MinecraftServer server = server();
        if (server == null) {
            return;
        }

        Set<String> encounteredKeys = new HashSet<>();
        for (ServerWorld world : server.getWorlds()) {
            for (Entity entity : world.iterateEntities()) {
                if (!isOwned(entity)) {
                    continue;
                }
                String ownershipKey = ownershipKey(entity);
                boolean unknown = ownershipKey == null || !validOwnershipKeys.contains(ownershipKey);
                boolean duplicate = ownershipKey != null && !encounteredKeys.add(ownershipKey);
                if (unknown || duplicate) {
                    entity.discard();
                }
            }
        }
    }

    private ArmorStandEntity spawnArmorStand(ServerWorld world, LineRequest request) {
        ArmorStandEntity armorStand = new ArmorStandEntity(EntityType.ARMOR_STAND, world);
        armorStand.addScoreboardTag(OWNER_TAG);
        armorStand.addScoreboardTag(KEY_TAG_PREFIX + request.ownershipKey());
        armorStand.setPosition(
                request.location().x(),
                request.location().y() - ARMOR_STAND_NAME_OFFSET,
                request.location().z());
        armorStand.setInvisible(true);
        ((ArmorStandEntityAccessor) armorStand).paradigm$setMarker(true);
        armorStand.setNoGravity(true);
        armorStand.setInvulnerable(true);
        armorStand.setCustomNameVisible(true);
        world.spawnEntity(armorStand);
        return armorStand;
    }

    private Entity findOwnedEntity(ServerWorld world, UUID runtimeId, String ownershipKey) {
        String ownershipTag = KEY_TAG_PREFIX + ownershipKey;
        if (runtimeId != null) {
            Entity entity = world.getEntity(runtimeId);
            if (entity != null && entity.getScoreboardTags().contains(ownershipTag)) {
                return entity;
            }
        }
        for (Entity entity : world.iterateEntities()) {
            if (entity.getScoreboardTags().contains(ownershipTag)) {
                return entity;
            }
        }
        return null;
    }

    private boolean isOwned(Entity entity) {
        return entity != null && entity.getScoreboardTags().contains(OWNER_TAG);
    }

    private String ownershipKey(Entity entity) {
        return entity.getScoreboardTags().stream()
                .filter(tag -> tag.startsWith(KEY_TAG_PREFIX))
                .map(tag -> tag.substring(KEY_TAG_PREFIX.length()))
                .findFirst()
                .orElse(null);
    }

    private ServerWorld resolveWorld(String dimension) {
        MinecraftServer server = server();
        Identifier dimensionId = Identifier.tryParse(dimension);
        if (server == null || dimensionId == null) {
            return null;
        }
        RegistryKey<World> worldKey = RegistryKey.of(Registry.WORLD_KEY, dimensionId);
        return server.getWorld(worldKey);
    }

    private MinecraftServer server() {
        Object server = adapter.getMinecraftServer();
        return server instanceof MinecraftServer minecraftServer ? minecraftServer : null;
    }

    private static UUID parseRuntimeId(String runtimeId) {
        try {
            return runtimeId != null ? UUID.fromString(runtimeId) : null;
        } catch (IllegalArgumentException ignored) {
            return null;
        }
    }
}
