package eu.avalanche7.paradigm.platform;

import eu.avalanche7.paradigm.mixin.DisplayEntityAccessor;
import eu.avalanche7.paradigm.mixin.InteractionEntityAccessor;
import eu.avalanche7.paradigm.mixin.TextDisplayEntityAccessor;
import eu.avalanche7.paradigm.platform.Interfaces.IHologramPlatform;
import eu.avalanche7.paradigm.platform.Interfaces.IPlayer;
import net.minecraft.entity.Entity;
import net.minecraft.entity.EntityType;
import net.minecraft.entity.decoration.DisplayEntity;
import net.minecraft.entity.decoration.InteractionEntity;
import net.minecraft.network.packet.s2c.play.EntitiesDestroyS2CPacket;
import net.minecraft.network.packet.s2c.play.EntitySpawnS2CPacket;
import net.minecraft.network.packet.s2c.play.EntityTrackerUpdateS2CPacket;
import net.minecraft.registry.RegistryKey;
import net.minecraft.registry.RegistryKeys;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.text.Text;
import net.minecraft.util.Identifier;
import net.minecraft.util.math.ChunkPos;
import net.minecraft.util.math.AffineTransformation;
import org.joml.Quaternionf;
import org.joml.Vector3f;

import java.util.HashSet;
import java.util.HashMap;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

public final class MinecraftHologramPlatform implements IHologramPlatform {
    private static final String KEY_TAG_PREFIX = OWNER_TAG + ":";

    private final PlatformAdapterImpl adapter;
    private final Map<String, ViewerEntity> viewerEntities = new HashMap<>();
    private InteractionHandler interactionHandler;

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
        ViewerEntity viewerEntity = viewerEntities.get(runtimeId);
        UUID entityId = parseRuntimeId(runtimeId);
        MinecraftServer server = server();
        if (viewerEntity != null) {
            return server != null && server.getPlayerManager().getPlayer(viewerEntity.playerId()) != null;
        }
        if (entityId == null || server == null) {
            return false;
        }

        for (ServerWorld world : server.getWorlds()) {
            Entity entity = world.getEntity(entityId);
            if (entity != null) {
                dispatchInteraction(entity);
                return true;
            }
        }
        return false;
    }

    @Override
    public WorldState worldState(String dimension) {
        ServerWorld world = resolveWorld(dimension);
        if (world == null) return null;
        return new WorldState(world.getTimeOfDay(), world.isThundering() ? "thunder" : world.isRaining() ? "rain" : "clear");
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
        applyDisplay(textDisplay, request);
        return textDisplay.getUuidAsString();
    }

    @Override
    public String upsertViewerLine(LineRequest request, IPlayer viewer, String runtimeId) {
        if (!(viewer.getOriginalPlayer() instanceof ServerPlayerEntity player)) return null;
        ServerWorld world = resolveWorld(request.location().dimension());
        if (world == null || !isChunkLoaded(request.location())) return null;
        removeLine(runtimeId);

        DisplayEntity.TextDisplayEntity display = new DisplayEntity.TextDisplayEntity(EntityType.TEXT_DISPLAY, world);
        display.setPosition(request.location().x(), request.location().y(), request.location().z());
        Object originalText = request.text().getOriginalText();
        ((TextDisplayEntityAccessor) display).paradigm$setText(originalText instanceof Text text ? text : Text.literal(request.text().getRawText()));
        applyDisplay(display, request);

        player.networkHandler.sendPacket(new EntitySpawnS2CPacket(display.getId(), display.getUuid(), display.getX(), display.getY(), display.getZ(),
                display.getPitch(), display.getYaw(), EntityType.TEXT_DISPLAY, 0, display.getVelocity(), display.getHeadYaw()));
        var entries = display.getDataTracker().getChangedEntries();
        if (entries != null && !entries.isEmpty()) {
            player.networkHandler.sendPacket(new EntityTrackerUpdateS2CPacket(display.getId(), entries));
        }
        String viewerRuntimeId = "viewer:" + UUID.randomUUID();
        viewerEntities.put(viewerRuntimeId, new ViewerEntity(player.getUuid(), display.getId()));
        return viewerRuntimeId;
    }

    @Override
    public String upsertInteraction(InteractionRequest request, String runtimeId) {
        ServerWorld world = resolveWorld(request.location().dimension());
        if (world == null || !isChunkLoaded(request.location())) return null;
        Entity owned = findOwnedEntity(world, parseRuntimeId(runtimeId), request.ownershipKey());
        InteractionEntity interaction;
        if (owned instanceof InteractionEntity existing) interaction = existing;
        else {
            if (owned != null) owned.discard();
            interaction = new InteractionEntity(EntityType.INTERACTION, world);
            interaction.addCommandTag(OWNER_TAG);
            interaction.addCommandTag(KEY_TAG_PREFIX + request.ownershipKey());
            world.spawnEntity(interaction);
        }
        interaction.setPosition(request.location().x(), request.location().y(), request.location().z());
        InteractionEntityAccessor accessor = (InteractionEntityAccessor) interaction;
        accessor.paradigm$setInteractionWidth((float) request.width());
        accessor.paradigm$setInteractionHeight((float) request.height());
        return interaction.getUuidAsString();
    }

    @Override
    public void setInteractionHandler(InteractionHandler handler) {
        interactionHandler = handler;
    }

    @Override
    public void removeLine(String runtimeId) {
        ViewerEntity viewerEntity = viewerEntities.remove(runtimeId);
        if (viewerEntity != null) {
            MinecraftServer viewerServer = server();
            ServerPlayerEntity viewer = viewerServer != null ? viewerServer.getPlayerManager().getPlayer(viewerEntity.playerId()) : null;
            if (viewer != null) viewer.networkHandler.sendPacket(new EntitiesDestroyS2CPacket(viewerEntity.entityId()));
            return;
        }
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

        applyDisplay(display, request);

        world.spawnEntity(display);
        return display;
    }

    @Override
    public Capabilities capabilities() {
        return new Capabilities(true, true, true, true, true, true, true, true, true, true, true, false);
    }

    private void applyDisplay(DisplayEntity.TextDisplayEntity display, LineRequest request) {
        DisplayEntityAccessor accessor = (DisplayEntityAccessor) display;
        accessor.paradigm$setBillboardMode(switch (request.display().billboard) {
            case "fixed" -> DisplayEntity.BillboardMode.FIXED;
            case "vertical" -> DisplayEntity.BillboardMode.VERTICAL;
            case "horizontal" -> DisplayEntity.BillboardMode.HORIZONTAL;
            default -> DisplayEntity.BillboardMode.CENTER;
        });
        accessor.paradigm$setViewRange(toNativeViewRange(request.viewDistance()));
        float scale = (float) request.display().scale;
        accessor.paradigm$setTransformation(new AffineTransformation(new Vector3f(), new Quaternionf(), new Vector3f(scale, scale, scale), new Quaternionf()));
        TextDisplayEntityAccessor text = (TextDisplayEntityAccessor) display;
        text.paradigm$setBackground(request.display().backgroundArgb());
        text.paradigm$setLineWidth(request.display().maxLineWidth);
        text.paradigm$setTextOpacity((byte) request.display().textOpacityByte());
        byte flags = (byte) ((request.display().textShadow ? 1 : 0) | (request.display().seeThrough ? 2 : 0));
        if ("left".equals(request.display().alignment)) flags |= 8;
        if ("right".equals(request.display().alignment)) flags |= 16;
        text.paradigm$setDisplayFlags(flags);
    }

    private void dispatchInteraction(Entity entity) {
        if (!(entity instanceof InteractionEntity interaction) || interactionHandler == null) return;
        String key = ownershipKey(entity);
        if (key == null) return;
        if (interaction.getLastAttacker() instanceof ServerPlayerEntity player) {
            interaction.discard();
            interactionHandler.onInteraction(key, adapter.wrapPlayer(player), true);
            return;
        }
        if (interaction.getTarget() instanceof ServerPlayerEntity player) {
            interaction.discard();
            interactionHandler.onInteraction(key, adapter.wrapPlayer(player), false);
        }
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

    private record ViewerEntity(UUID playerId, int entityId) {
    }
}
