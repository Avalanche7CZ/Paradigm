package eu.avalanche7.paradigm.platform;

import eu.avalanche7.paradigm.mixin.DisplayEntityAccessor;
import eu.avalanche7.paradigm.mixin.TextDisplayEntityAccessor;
import eu.avalanche7.paradigm.platform.Interfaces.IHologramPlatform;
import net.minecraft.entity.Entity;
import net.minecraft.entity.EntityType;
import net.minecraft.entity.decoration.DisplayEntity;
import net.minecraft.registry.RegistryKey;
import net.minecraft.registry.RegistryKeys;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.text.Text;
import net.minecraft.util.Identifier;
import net.minecraft.util.math.ChunkPos;

import java.util.HashSet;
import java.util.Set;
import java.util.UUID;

public final class MinecraftHologramPlatform implements IHologramPlatform {
    private static final String KEY_TAG_PREFIX = OWNER_TAG + ":";

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
        return world.isChunkLoaded(ChunkPos.toLong(chunkX, chunkZ));
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
        DisplayEntity.TextDisplayEntity textDisplay;
        if (ownedEntity instanceof DisplayEntity.TextDisplayEntity existingDisplay) {
            textDisplay = existingDisplay;
        } else {
            if (ownedEntity != null) {
                ownedEntity.discard();
            }
            textDisplay = spawnTextDisplay(world, request);
        }

        Object originalText = request.text().getOriginalText();
        Text nativeText = originalText instanceof Text text
                ? text
                : Text.literal(request.text().getRawText());
        ((TextDisplayEntityAccessor) textDisplay).paradigm$setText(nativeText);
        return textDisplay.getUuidAsString();
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

    private DisplayEntity.TextDisplayEntity spawnTextDisplay(ServerWorld world, LineRequest request) {
        DisplayEntity.TextDisplayEntity display = new DisplayEntity.TextDisplayEntity(EntityType.TEXT_DISPLAY, world);
        display.addCommandTag(OWNER_TAG);
        display.addCommandTag(KEY_TAG_PREFIX + request.ownershipKey());
        display.setPosition(request.location().x(), request.location().y(), request.location().z());

        DisplayEntityAccessor displayAccessor = (DisplayEntityAccessor) display;
        displayAccessor.paradigm$setBillboardMode(DisplayEntity.BillboardMode.CENTER);
        displayAccessor.paradigm$setViewRange(toNativeViewRange(request.viewDistance()));
        ((TextDisplayEntityAccessor) display).paradigm$setBackground(0);

        world.spawnEntity(display);
        return display;
    }

    private Entity findOwnedEntity(ServerWorld world, UUID runtimeId, String ownershipKey) {
        String ownershipTag = KEY_TAG_PREFIX + ownershipKey;
        if (runtimeId != null) {
            Entity entity = world.getEntity(runtimeId);
            if (entity != null && entity.getCommandTags().contains(ownershipTag)) {
                return entity;
            }
        }

        for (Entity entity : world.iterateEntities()) {
            if (entity.getCommandTags().contains(ownershipTag)) {
                return entity;
            }
        }
        return null;
    }

    private boolean isOwned(Entity entity) {
        return entity != null && entity.getCommandTags().contains(OWNER_TAG);
    }

    private String ownershipKey(Entity entity) {
        return entity.getCommandTags().stream()
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
        return server.getWorld(RegistryKey.of(RegistryKeys.WORLD, dimensionId));
    }

    private MinecraftServer server() {
        Object server = adapter.getMinecraftServer();
        return server instanceof MinecraftServer minecraftServer ? minecraftServer : null;
    }

    private static float toNativeViewRange(double blocks) {
        return (float) Math.max(0.01D, blocks / 64.0D);
    }

    private static UUID parseRuntimeId(String runtimeId) {
        try {
            return runtimeId != null ? UUID.fromString(runtimeId) : null;
        } catch (IllegalArgumentException ignored) {
            return null;
        }
    }
}
