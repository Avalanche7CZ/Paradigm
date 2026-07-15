package eu.avalanche7.paradigm.platform;

import eu.avalanche7.paradigm.mixin.DisplayAccessor;
import eu.avalanche7.paradigm.mixin.TextDisplayAccessor;
import eu.avalanche7.paradigm.platform.Interfaces.IHologramPlatform;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.Registries;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceKey;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.Display;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.level.Level;

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
        ServerLevel world = resolveWorld(location.dimension());
        return world != null && world.hasChunkAt(BlockPos.containing(location.x(), location.y(), location.z()));
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
        Display.TextDisplay textDisplay;
        if (ownedEntity instanceof Display.TextDisplay existingDisplay) {
            textDisplay = existingDisplay;
        } else {
            if (ownedEntity != null) {
                ownedEntity.discard();
            }
            textDisplay = spawnTextDisplay(world, request);
        }

        Object originalText = request.text().getOriginalText();
        Component nativeText = originalText instanceof Component component
                ? component
                : Component.literal(request.text().getRawText());
        ((TextDisplayAccessor) textDisplay).paradigm$setText(nativeText);
        return textDisplay.getStringUUID();
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

    private Display.TextDisplay spawnTextDisplay(ServerLevel world, LineRequest request) {
        Display.TextDisplay display = new Display.TextDisplay(EntityType.TEXT_DISPLAY, world);
        display.addTag(OWNER_TAG);
        display.addTag(KEY_TAG_PREFIX + request.ownershipKey());
        display.setPos(request.location().x(), request.location().y(), request.location().z());

        DisplayAccessor displayAccessor = (DisplayAccessor) display;
        displayAccessor.paradigm$setBillboardConstraints(Display.BillboardConstraints.CENTER);
        displayAccessor.paradigm$setViewRange(toNativeViewRange(request.viewDistance()));
        ((TextDisplayAccessor) display).paradigm$setBackgroundColor(0);

        world.addFreshEntity(display);
        return display;
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
        ResourceLocation dimensionId = ResourceLocation.tryParse(dimension);
        if (server == null || dimensionId == null) {
            return null;
        }
        ResourceKey<Level> worldKey = ResourceKey.create(Registries.DIMENSION, dimensionId);
        return server.getLevel(worldKey);
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
