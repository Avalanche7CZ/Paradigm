package eu.avalanche7.paradigm.platform;

import eu.avalanche7.paradigm.platform.Interfaces.IHologramPlatform;
import eu.avalanche7.paradigm.platform.Interfaces.IPlayer;
import eu.avalanche7.paradigm.mixin.InteractionAccessor;
import eu.avalanche7.paradigm.mixin.TextDisplayAccessor;
import eu.avalanche7.paradigm.mixin.DisplayAccessor;
import com.mojang.math.Transformation;
import org.joml.Quaternionf;
import org.joml.Vector3f;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.Registries;
import net.minecraft.network.chat.Component;
import net.minecraft.network.protocol.game.ClientboundAddEntityPacket;
import net.minecraft.network.protocol.game.ClientboundRemoveEntitiesPacket;
import net.minecraft.network.protocol.game.ClientboundSetEntityDataPacket;
import net.minecraft.resources.ResourceKey;
import net.minecraft.resources.Identifier;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.Display;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.Interaction;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.Level;

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
        ServerLevel world = resolveWorld(location.dimension());
        return world != null && world.hasChunkAt(BlockPos.containing(location.x(), location.y(), location.z()));
    }

    @Override
    public boolean isEntityLoaded(String runtimeId) {
        ViewerEntity viewerEntity = viewerEntities.get(runtimeId);
        UUID entityId = parseRuntimeId(runtimeId);
        MinecraftServer server = server();
        if (viewerEntity != null) {
            return server != null && server.getPlayerList().getPlayer(viewerEntity.playerId()) != null;
        }
        if (entityId == null || server == null) {
            return false;
        }
        for (ServerLevel world : server.getAllLevels()) {
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
        ServerLevel world = resolveWorld(dimension);
        if (world == null) return null;
        return new WorldState(world.getDayTime(), world.isThundering() ? "thunder" : world.isRaining() ? "rain" : "clear");
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
        textDisplay.setText(nativeText);
        applyDisplay(textDisplay, request);
        return textDisplay.getStringUUID();
    }

    @Override
    public String upsertViewerLine(LineRequest request, IPlayer viewer, String runtimeId) {
        if (!(viewer.getOriginalPlayer() instanceof ServerPlayer player)) return null;
        ServerLevel world = resolveWorld(request.location().dimension());
        if (world == null || !isChunkLoaded(request.location())) return null;
        removeLine(runtimeId);

        Display.TextDisplay display = new Display.TextDisplay(EntityType.TEXT_DISPLAY, world);
        display.setPos(request.location().x(), request.location().y(), request.location().z());
        Object originalText = request.text().getOriginalText();
        display.setText(originalText instanceof Component component ? component : Component.literal(request.text().getRawText()));
        applyDisplay(display, request);

        player.connection.send(new ClientboundAddEntityPacket(display.getId(), display.getUUID(), display.getX(), display.getY(), display.getZ(),
                display.getXRot(), display.getYRot(), EntityType.TEXT_DISPLAY, 0, display.getDeltaMovement(), display.getYHeadRot()));
        var values = display.getEntityData().getNonDefaultValues();
        if (values != null && !values.isEmpty()) {
            player.connection.send(new ClientboundSetEntityDataPacket(display.getId(), values));
        }
        String viewerRuntimeId = "viewer:" + UUID.randomUUID();
        viewerEntities.put(viewerRuntimeId, new ViewerEntity(player.getUUID(), display.getId()));
        return viewerRuntimeId;
    }

    @Override
    public String upsertInteraction(InteractionRequest request, String runtimeId) {
        ServerLevel world = resolveWorld(request.location().dimension());
        if (world == null || !isChunkLoaded(request.location())) return null;
        Entity owned = findOwnedEntity(world, parseRuntimeId(runtimeId), request.ownershipKey());
        Interaction interaction;
        if (owned instanceof Interaction existing) interaction = existing;
        else {
            if (owned != null) owned.discard();
            interaction = new Interaction(EntityType.INTERACTION, world);
            interaction.addTag(OWNER_TAG);
            interaction.addTag(KEY_TAG_PREFIX + request.ownershipKey());
            world.addFreshEntity(interaction);
        }
        interaction.setPos(request.location().x(), request.location().y(), request.location().z());
        InteractionAccessor accessor = (InteractionAccessor) interaction;
        accessor.paradigm$setWidth((float) request.width());
        accessor.paradigm$setHeight((float) request.height());
        return interaction.getStringUUID();
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
            ServerPlayer viewer = viewerServer != null ? viewerServer.getPlayerList().getPlayer(viewerEntity.playerId()) : null;
            if (viewer != null) viewer.connection.send(new ClientboundRemoveEntitiesPacket(viewerEntity.entityId()));
            return;
        }
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

        applyDisplay(display, request);

        world.addFreshEntity(display);
        return display;
    }

    @Override
    public Capabilities capabilities() {
        return new Capabilities(true, true, true, true, true, true, true, true, true, true, true, false);
    }

    private void applyDisplay(Display.TextDisplay display, LineRequest request) {
        display.setBillboardConstraints(switch (request.display().billboard) {
            case "fixed" -> Display.BillboardConstraints.FIXED;
            case "vertical" -> Display.BillboardConstraints.VERTICAL;
            case "horizontal" -> Display.BillboardConstraints.HORIZONTAL;
            default -> Display.BillboardConstraints.CENTER;
        });
        display.setViewRange(toNativeViewRange(request.viewDistance()));
        float scale = (float) request.display().scale;
        ((DisplayAccessor) display).paradigm$setTransformation(new Transformation(new Vector3f(), new Quaternionf(), new Vector3f(scale, scale, scale), new Quaternionf()));
        display.setBackgroundColor(request.display().backgroundArgb());
        TextDisplayAccessor text = (TextDisplayAccessor) display;
        text.paradigm$setLineWidth(request.display().maxLineWidth);
        text.paradigm$setTextOpacity((byte) request.display().textOpacityByte());
        byte flags = (byte) ((request.display().textShadow ? 1 : 0) | (request.display().seeThrough ? 2 : 0));
        if ("left".equals(request.display().alignment)) flags |= 8;
        if ("right".equals(request.display().alignment)) flags |= 16;
        text.paradigm$setFlags(flags);
    }

    private void dispatchInteraction(Entity entity) {
        if (!(entity instanceof Interaction interaction) || interactionHandler == null) return;
        String key = ownershipKey(entity);
        if (key == null) return;
        if (interaction.getLastAttacker() instanceof ServerPlayer player) {
            interaction.discard();
            interactionHandler.onInteraction(key, adapter.wrapPlayer(player), true);
            return;
        }
        if (interaction.getTarget() instanceof ServerPlayer player) {
            interaction.discard();
            interactionHandler.onInteraction(key, adapter.wrapPlayer(player), false);
        }
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
        Identifier dimensionId = Identifier.tryParse(dimension);
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

    private record ViewerEntity(UUID playerId, int entityId) {
    }
}
