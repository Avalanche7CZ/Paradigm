package eu.avalanche7.paradigm.platform;

import eu.avalanche7.paradigm.platform.Interfaces.IHologramPlatform;
import net.minecraft.core.BlockPos;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.chat.Component;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.decoration.ArmorStand;

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
        ServerLevel world = resolveWorld(location.dimension());
        return world != null && world.hasChunkAt(new BlockPos(location.x(), location.y(), location.z()));
    }

    @Override
    public boolean isEntityLoaded(String runtimeId) {
        UUID entityId = parseRuntimeId(runtimeId);
        MinecraftServer server = server();
        if (entityId == null || server == null) {
            return false;
        }
        for (ServerLevel world : server.getAllLevels()) {
            if (world.getEntity(entityId) != null) {
                return true;
            }
        }
        return false;
    }

    @Override
    public String upsertLine(LineRequest request, String runtimeId) {
        ServerLevel world = resolveWorld(request.location().dimension());
        if (world == null || !isChunkLoaded(request.location())) {
            return null;
        }

        Entity ownedEntity = findOwnedEntity(world, parseRuntimeId(runtimeId), request.ownershipKey());
        ArmorStand armorStand;
        if (ownedEntity instanceof ArmorStand existingStand) {
            armorStand = existingStand;
        } else {
            if (ownedEntity != null) {
                ownedEntity.discard();
            }
            armorStand = spawnArmorStand(world, request);
        }

        Object originalText = request.text().getOriginalText();
        Component nativeText = originalText instanceof Component component
                ? component
                : Component.literal(request.text().getRawText());
        armorStand.setCustomName(nativeText);
        return armorStand.getStringUUID();
    }

    @Override
    public void removeLine(String runtimeId) {
        UUID entityId = parseRuntimeId(runtimeId);
        MinecraftServer server = server();
        if (entityId == null || server == null) {
            return;
        }
        for (ServerLevel world : server.getAllLevels()) {
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
        for (ServerLevel world : server.getAllLevels()) {
            for (Entity entity : world.getAllEntities()) {
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

    private ArmorStand spawnArmorStand(ServerLevel world, LineRequest request) {
        ArmorStand armorStand = new ArmorStand(EntityType.ARMOR_STAND, world);
        enableMarkerFlag(armorStand);
        armorStand.addTag(OWNER_TAG);
        armorStand.addTag(KEY_TAG_PREFIX + request.ownershipKey());
        armorStand.setPos(
                request.location().x(),
                request.location().y() - ARMOR_STAND_NAME_OFFSET,
                request.location().z());
        armorStand.setInvisible(true);
        armorStand.setNoGravity(true);
        armorStand.setInvulnerable(true);
        armorStand.setCustomNameVisible(true);
        world.addFreshEntity(armorStand);
        return armorStand;
    }

    private void enableMarkerFlag(ArmorStand armorStand) {
        CompoundTag entityData = new CompoundTag();
        armorStand.saveWithoutId(entityData);
        entityData.putBoolean("Marker", true);
        armorStand.load(entityData);
    }

    private Entity findOwnedEntity(ServerLevel world, UUID runtimeId, String ownershipKey) {
        String ownershipTag = KEY_TAG_PREFIX + ownershipKey;
        if (runtimeId != null) {
            Entity entity = world.getEntity(runtimeId);
            if (entity != null && entity.getTags().contains(ownershipTag)) {
                return entity;
            }
        }
        for (Entity entity : world.getAllEntities()) {
            if (entity.getTags().contains(ownershipTag)) {
                return entity;
            }
        }
        return null;
    }

    private boolean isOwned(Entity entity) {
        return entity != null && entity.getTags().contains(OWNER_TAG);
    }

    private String ownershipKey(Entity entity) {
        return entity.getTags().stream()
                .filter(tag -> tag.startsWith(KEY_TAG_PREFIX))
                .map(tag -> tag.substring(KEY_TAG_PREFIX.length()))
                .findFirst()
                .orElse(null);
    }

    private ServerLevel resolveWorld(String dimension) {
        MinecraftServer server = server();
        if (server == null) {
            return null;
        }
        for (ServerLevel world : server.getAllLevels()) {
            if (world.dimension().location().toString().equalsIgnoreCase(dimension)) {
                return world;
            }
        }
        return null;
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
