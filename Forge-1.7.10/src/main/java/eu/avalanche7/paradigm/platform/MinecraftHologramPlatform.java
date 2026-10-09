package eu.avalanche7.paradigm.platform;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import net.minecraft.entity.passive.EntityBat;
import net.minecraft.entity.passive.EntityHorse;
import net.minecraft.entity.player.EntityPlayerMP;
import net.minecraft.entity.projectile.EntityWitherSkull;
import net.minecraft.network.Packet;
import net.minecraft.network.play.server.S0EPacketSpawnObject;
import net.minecraft.network.play.server.S0FPacketSpawnMob;
import net.minecraft.network.play.server.S13PacketDestroyEntities;
import net.minecraft.network.play.server.S18PacketEntityTeleport;
import net.minecraft.network.play.server.S1BPacketEntityAttach;
import net.minecraft.network.play.server.S1CPacketEntityMetadata;
import net.minecraft.util.Vec3;
import net.minecraft.world.WorldServer;
import net.minecraftforge.common.DimensionManager;

import eu.avalanche7.paradigm.platform.Interfaces.IHologramPlatform;
import eu.avalanche7.paradigm.platform.Interfaces.IPlayer;

public final class MinecraftHologramPlatform implements IHologramPlatform {
    private static final int HORSE_AGE = -1700000;
    private static volatile MinecraftHologramPlatform current;
    private final PlatformAdapterImpl adapter;
    private final Map<String, Line> lines = new LinkedHashMap<>();
    private final Map<String, Interaction> interactions = new LinkedHashMap<>();
    private final Map<Integer, String> ownedIds = new HashMap<>();
    private InteractionHandler handler;
    private final Map<String, Set<Line>> interactionLines = new HashMap<>();
    private int probeOffset;
    private int interactionOffset;
    private long lastTick;

    public MinecraftHologramPlatform(PlatformAdapterImpl adapter) { this.adapter = adapter; }
    public static MinecraftHologramPlatform current() { return current; }
    public void bind() { current = this; }

    private WorldServer world(String dimension) {
        if (adapter.getMinecraftServer() == null) return null;
        try { return DimensionManager.getWorld(Integer.parseInt(dimension)); }
        catch (NumberFormatException failure) { return null; }
    }

    @Override public boolean isChunkLoaded(Location location) {
        WorldServer world = location != null ? world(location.dimension()) : null;
        return world != null && finite(location) && world.getChunkProvider().chunkExists((int) Math.floor(location.x()) >> 4, (int) Math.floor(location.z()) >> 4);
    }

    public static boolean finite(Location location) {
        return location != null && Double.isFinite(location.x()) && Double.isFinite(location.y()) && Double.isFinite(location.z())
                && Math.abs(location.x()) < 30_000_000 && Math.abs(location.z()) < 30_000_000 && Math.abs(location.y()) < 30_000_000;
    }

    @Override public boolean isEntityLoaded(String runtimeId) {
        Line line = lines.get(runtimeId);
        if (line != null) {
            boolean loaded = isChunkLoaded(line.request.location());
            if (!loaded) removeLine(runtimeId);
            return loaded;
        }
        Interaction interaction = interactions.get(runtimeId);
        if (interaction == null) return false;
        boolean loaded = isChunkLoaded(interaction.request.location());
        if (!loaded) removeInteraction(runtimeId);
        return loaded;
    }

    @Override public String upsertLine(LineRequest request, String runtimeId) { return upsert(request, null, runtimeId); }
    @Override public String upsertViewerLine(LineRequest request, IPlayer viewer, String runtimeId) {
        if (viewer == null || !(viewer.getOriginalPlayer() instanceof EntityPlayerMP player)) return null;
        return upsert(request, player, runtimeId);
    }

    private String upsert(LineRequest request, EntityPlayerMP target, String runtimeId) {
        if (!adapter.isServerThread() || request == null || request.ownershipKey() == null || !isChunkLoaded(request.location())) return null;
        UUID viewer = target != null ? target.getUniqueID() : null;
        if (target != null && !eligible(target, request.location(), Math.min(24, request.viewDistance()))) {
            Line previous = lines.get(runtimeId);
            if (previous != null && viewer.equals(previous.viewer)) removeLine(runtimeId);
            return null;
        }
        Line line = lines.get(runtimeId);
        if (line == null && viewer == null && players().stream().noneMatch(player -> eligible(player, request.location(), Math.min(24, request.viewDistance())))) return null;
        if (line == null || !line.request.ownershipKey().equals(request.ownershipKey()) || line.target != target
                || !line.request.location().dimension().equals(request.location().dimension())) {
            if (line != null && line.request.ownershipKey().equals(request.ownershipKey())) removeLine(runtimeId);
            line = new Line(request, target, world(request.location().dimension()));
            lines.put(line.id, line);
            String key = interactionKey(request.ownershipKey());
            if (key != null) interactionLines.computeIfAbsent(key, ignored -> new HashSet<>()).add(line);
            for (int id : line.ids()) ownedIds.put(id, line.id);
        }
        String text = MinecraftMenuPlatform.legacyLimit(MinecraftMenuPlatform.text(request.text()).replace('\n', ' '), 64);
        boolean changed = !text.equals(line.text);
        boolean moved = !line.request.location().equals(request.location());
        line.request = request;
        line.text = text;
        line.horse.setCustomNameTag(text);
        line.position();
        if (target != null) sync(line, target, changed, moved);
        else for (EntityPlayerMP player : players()) sync(line, player, changed, moved);
        return line.id;
    }

    @Override public void removeLine(String runtimeId) {
        if (!adapter.isServerThread()) { adapter.executeOnServerThread(() -> removeLine(runtimeId)); return; }
        Line line = lines.remove(runtimeId);
        if (line == null) { removeInteraction(runtimeId); return; }
        for (EntityPlayerMP player : line.sent.values()) send(player, new S13PacketDestroyEntities(line.ids()));
        for (int id : line.ids()) ownedIds.remove(id);
        String key = interactionKey(line.request.ownershipKey());
        Set<Line> owned = interactionLines.get(key);
        if (owned != null) { owned.remove(line); if (owned.isEmpty()) interactionLines.remove(key); }
        line.sent.clear();
    }

    @Override public String upsertInteraction(InteractionRequest request, String runtimeId) {
        if (!adapter.isServerThread() || request == null || request.ownershipKey() == null || !Double.isFinite(request.width()) || request.width() <= 0
                || !Double.isFinite(request.height()) || request.height() <= 0 || !isChunkLoaded(request.location())) return null;
        Interaction interaction = interactions.get(runtimeId);
        if (interaction == null || !interaction.request.ownershipKey().equals(request.ownershipKey())
                || !interaction.request.location().dimension().equals(request.location().dimension())) {
            if (interaction != null && interaction.request.ownershipKey().equals(request.ownershipKey())) removeInteraction(runtimeId);
            interaction = new Interaction(request, world(request.location().dimension()));
            interactions.put(interaction.id, interaction);
            ownedIds.put(interaction.bat.getEntityId(), interaction.id);
        }
        boolean moved = !interaction.request.location().equals(request.location());
        interaction.request = request;
        interaction.position();
        for (EntityPlayerMP player : players()) sync(interaction, player, moved);
        return interaction.id;
    }

    @Override public void removeInteraction(String runtimeId) {
        if (!adapter.isServerThread()) { adapter.executeOnServerThread(() -> removeInteraction(runtimeId)); return; }
        Interaction interaction = interactions.remove(runtimeId);
        if (interaction == null) return;
        for (EntityPlayerMP player : interaction.sent.values()) send(player, new S13PacketDestroyEntities(interaction.bat.getEntityId()));
        ownedIds.remove(interaction.bat.getEntityId());
        interaction.sent.clear();
    }

    @Override public void setInteractionHandler(InteractionHandler handler) { this.handler = handler; }

    public boolean interact(EntityPlayerMP player, int entityId, boolean attack) {
        String runtimeId = ownedIds.get(entityId);
        if (runtimeId == null) return false;
        Interaction interaction = interactions.get(runtimeId);
        if (!adapter.isServerThread() || handler == null || interaction == null || interaction.sent.get(player.getUniqueID()) != player
                || !eligible(player, interaction.request.location(), 6) || !hasVisibleLine(interaction, player)) return true;
        double y = interaction.bat.posY + interaction.bat.height * 0.5;
        if (player.getDistanceSq(interaction.bat.posX, y, interaction.bat.posZ) > 36) return true;
        Vec3 eyes = Vec3.createVectorHelper(player.posX, player.posY + player.getEyeHeight(), player.posZ);
        if (player.worldObj.rayTraceBlocks(eyes, Vec3.createVectorHelper(interaction.bat.posX, y, interaction.bat.posZ)) != null) return true;
        handler.onInteraction(interaction.request.ownershipKey(), new MinecraftPlayer(player), attack);
        return true;
    }

    @Override public boolean setViewerVisible(String runtimeId, IPlayer player, boolean visible) {
        Line line = lines.get(runtimeId);
        if (!adapter.isServerThread() || line == null || player == null || !(player.getOriginalPlayer() instanceof EntityPlayerMP viewer)) return false;
        if (visible) line.excluded.remove(viewer.getUniqueID()); else line.excluded.add(viewer.getUniqueID());
        sync(line, viewer, false, false);
        return true;
    }

    @Override public WorldState worldState(String dimension) {
        WorldServer world = world(dimension);
        return world != null ? new WorldState(world.getWorldTime(), world.isThundering() ? "thunder" : world.isRaining() ? "rain" : "clear") : null;
    }

    @Override public Capabilities capabilities() { return new Capabilities(false, false, false, false, false, false, false, false, false, true, true, true); }

    @Override public void removeUnknownOwnedLines(Set<String> validOwnershipKeys) {
        for (Line line : List.copyOf(lines.values())) {
            String key = line.request.ownershipKey();
            String base = key.contains(":viewer:") ? key.substring(0, key.indexOf(":viewer:")) : key;
            if (!validOwnershipKeys.contains(base)) removeLine(line.id);
        }
        for (Interaction interaction : List.copyOf(interactions.values())) if (!validOwnershipKeys.contains(interaction.request.ownershipKey())) removeInteraction(interaction.id);
    }

    public void tick() {
        long now = System.nanoTime();
        if (now - lastTick < 1_000_000_000L) return;
        lastTick = now;
        List<Line> snapshot = new ArrayList<>(lines.values());
        for (int i = 0; i < Math.min(128, snapshot.size()); i++) {
            Line line = snapshot.get(Math.floorMod(probeOffset++, snapshot.size()));
            if (line.target != null) sync(line, line.target, false, false);
            else for (EntityPlayerMP player : players()) sync(line, player, false, false);
        }
        List<Interaction> targets = new ArrayList<>(interactions.values());
        for (int i = 0; i < Math.min(128, targets.size()); i++) {
            Interaction target = targets.get(Math.floorMod(interactionOffset++, targets.size()));
            for (EntityPlayerMP player : players()) sync(target, player, false);
        }
    }

    public void disconnected(EntityPlayerMP player) {
        for (Line line : List.copyOf(lines.values())) {
            if (player.getUniqueID().equals(line.viewer)) removeLine(line.id);
            else line.sent.remove(player.getUniqueID(), player);
        }
        for (Interaction interaction : interactions.values()) interaction.sent.remove(player.getUniqueID(), player);
    }

    public void transferred(EntityPlayerMP player) {
        for (Line line : List.copyOf(lines.values())) {
            if (player.getUniqueID().equals(line.viewer) && line.target != player) { removeLine(line.id); continue; }
            if (line.sent.remove(player.getUniqueID()) != null) send(player, new S13PacketDestroyEntities(line.ids()));
            sync(line, player, false, false);
        }
        for (Interaction interaction : interactions.values()) {
            if (interaction.sent.remove(player.getUniqueID()) != null) send(player, new S13PacketDestroyEntities(interaction.bat.getEntityId()));
            sync(interaction, player, false);
        }
    }

    public void clear() { removeUnknownOwnedLines(Set.of()); handler = null; if (current == this) current = null; }

    private void sync(Line line, EntityPlayerMP player, boolean changed, boolean moved) {
        UUID uuid = player.getUniqueID();
        boolean visible = (line.viewer == null || line.viewer.equals(uuid)) && !line.excluded.contains(uuid)
                && !line.text.isEmpty() && eligible(player, line.request.location(), Math.min(24, line.request.viewDistance()))
                && player.getDistanceSq(line.horse.posX, line.horse.posY, line.horse.posZ) < 4096;
        EntityPlayerMP previous = line.sent.get(uuid);
        if (!visible) {
            if (previous != null) { send(player, new S13PacketDestroyEntities(line.ids())); line.sent.remove(uuid); }
        } else if (previous != player) {
            send(player, new S0EPacketSpawnObject(line.skull, 66));
            send(player, new S0FPacketSpawnMob(line.horse));
            send(player, new S1BPacketEntityAttach(0, line.horse, line.skull));
            line.sent.put(uuid, player);
        } else {
            if (changed) send(player, new S1CPacketEntityMetadata(line.horse.getEntityId(), line.horse.getDataWatcher(), true));
            if (moved) { send(player, new S18PacketEntityTeleport(line.skull)); send(player, new S18PacketEntityTeleport(line.horse)); }
        }
    }

    private boolean hasVisibleLine(Interaction interaction, EntityPlayerMP player) {
        Set<Line> owned = interactionLines.get(interaction.request.ownershipKey());
        return owned != null && owned.stream().anyMatch(line -> line.sent.get(player.getUniqueID()) == player);
    }

    private static String interactionKey(String ownershipKey) {
        if (!ownershipKey.startsWith("line:")) return null;
        int end = ownershipKey.indexOf(':', 5);
        return end > 5 ? "interaction:" + ownershipKey.substring(5, end) : null;
    }

    private void sync(Interaction interaction, EntityPlayerMP player, boolean moved) {
        boolean visible = eligible(player, interaction.request.location(), 24) && hasVisibleLine(interaction, player);
        EntityPlayerMP previous = interaction.sent.get(player.getUniqueID());
        if (!visible) {
            if (previous != null) { send(player, new S13PacketDestroyEntities(interaction.bat.getEntityId())); interaction.sent.remove(player.getUniqueID()); }
        } else if (previous != player) {
            send(player, new S0FPacketSpawnMob(interaction.bat));
            interaction.sent.put(player.getUniqueID(), player);
        } else if (moved) send(player, new S18PacketEntityTeleport(interaction.bat));
    }

    private boolean eligible(EntityPlayerMP player, Location location, double distance) {
        return player != null && Integer.toString(player.dimension).equals(location.dimension()) && isChunkLoaded(location)
                && player.getDistanceSq(location.x(), location.y(), location.z()) <= distance * distance;
    }

    private List<EntityPlayerMP> players() { return adapter.getOnlinePlayers().stream().map(player -> (EntityPlayerMP) player.getOriginalPlayer()).toList(); }
    private static void send(EntityPlayerMP player, Packet packet) { if (player.playerNetServerHandler != null) player.playerNetServerHandler.sendPacket(packet); }

    private static final class Line {
        final String id = "legacy-line:" + UUID.randomUUID();
        final EntityHorse horse;
        final EntityWitherSkull skull;
        final UUID viewer;
        final EntityPlayerMP target;
        final Map<UUID, EntityPlayerMP> sent = new HashMap<>();
        final Set<UUID> excluded = new HashSet<>();
        LineRequest request;
        String text = "";
        Line(LineRequest request, EntityPlayerMP target, WorldServer world) {
            this.request = request; this.target = target; this.viewer = target != null ? target.getUniqueID() : null;
            horse = new EntityHorse(world); skull = new EntityWitherSkull(world);
            horse.setGrowingAge(HORSE_AGE); horse.setAlwaysRenderNameTag(true);
            horse.mountEntity(skull);
            position();
        }
        void position() {
            Location location = request.location();
            skull.setPosition(location.x(), location.y() - horse.height - 0.5 - skull.getMountedYOffset() - horse.getYOffset(), location.z());
            skull.updateRiderPosition();
        }
        int[] ids() { return new int[] {horse.getEntityId(), skull.getEntityId()}; }
    }

    private static final class Interaction {
        final String id = "legacy-interaction:" + UUID.randomUUID();
        final EntityBat bat;
        final Map<UUID, EntityPlayerMP> sent = new HashMap<>();
        InteractionRequest request;
        Interaction(InteractionRequest request, WorldServer world) {
            this.request = request; bat = new EntityBat(world); bat.setInvisible(true); bat.setIsBatHanging(true); position();
        }
        void position() { Location location = request.location(); bat.setPosition(location.x(), Math.floor(location.y()) + 1 - bat.height, location.z()); }
    }
}
