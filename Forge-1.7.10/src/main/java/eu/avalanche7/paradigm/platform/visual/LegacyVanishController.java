package eu.avalanche7.paradigm.platform.visual;

import java.util.List;
import java.util.Map;
import java.util.UUID;

import net.minecraft.entity.Entity;
import net.minecraft.entity.EntityTrackerEntry;
import net.minecraft.entity.player.EntityPlayerMP;
import net.minecraft.network.play.server.S38PacketPlayerListItem;
import net.minecraft.server.MinecraftServer;
import net.minecraft.world.WorldServer;

import eu.avalanche7.paradigm.mixin.EntityTrackerAccess;

public final class LegacyVanishController {
    private final MinecraftServer server;
    private final Map<UUID, Admission> admission = new java.util.concurrent.ConcurrentHashMap<>();
    private final Map<UUID, Reveal> reveals = new java.util.concurrent.ConcurrentHashMap<>();
    private final Map<UUID, EntityPlayerMP> hidden = new java.util.concurrent.ConcurrentHashMap<>();

    public LegacyVanishController(MinecraftServer server) { this.server = server; }

    public void prepare(UUID uuid, boolean enabled) { admission.put(uuid, new Admission(enabled, System.nanoTime())); }

    public void connected(EntityPlayerMP player) {
        Admission state = admission.remove(player.getUniqueID());
        if (state != null && state.enabled()) hidden.put(player.getUniqueID(), player);
        else hidden.remove(player.getUniqueID());
    }

    public boolean visible(Entity entity, EntityPlayerMP viewer) {
        return entity == viewer || !(entity instanceof EntityPlayerMP player) || player.getUniqueID() == null || (!hidden.containsKey(player.getUniqueID()) || viewer != null && eu.avalanche7.paradigm.platform.VanishTracking.seesHidden(viewer));
    }

    public boolean listed(String name, EntityPlayerMP viewer) {
        for (EntityPlayerMP subject : hidden.values()) {
            if (subject != viewer && subject.getCommandSenderName().equals(name)) return visible(subject, viewer);
        }
        return true;
    }

    public boolean set(EntityPlayerMP player, boolean enabled) {
        if (player == null) return false;
        UUID uuid = player.getUniqueID();
        if (enabled == hidden.containsKey(uuid)) {
            if (enabled) hidden.put(uuid, player);
            return true;
        }
        if (enabled) {
            hidden.put(uuid, player);
            reveals.remove(uuid);
        } else {
            hidden.remove(uuid);
            transferred(player);
        }
        for (EntityPlayerMP viewer : players()) refresh(viewer, player);
        return true;
    }

    public void refresh(EntityPlayerMP viewer, EntityPlayerMP player) {
        if (viewer == player) return;
        boolean visible = visible(player, viewer);
        viewer.playerNetServerHandler.sendPacket(new S38PacketPlayerListItem(player.getCommandSenderName(), visible, player.ping));
        EntityTrackerEntry entry = entry(player);
        if (entry == null) return;
        if (!visible && entry.trackingPlayers.contains(viewer)) {
            entry.removeFromWatchingList(viewer);
            net.minecraftforge.event.ForgeEventFactory.onStopEntityTracking(player, viewer);
        } else if (visible && viewer.worldObj == player.worldObj) entry.tryStartWachingThis(viewer);
    }

    public void disconnected(EntityPlayerMP player) {
        hidden.remove(player.getUniqueID(), player);
        admission.remove(player.getUniqueID());
        reveals.remove(player.getUniqueID());
        var services = eu.avalanche7.paradigm.ParadigmAPI.getServices();
        if (services != null) services.getVanishService().left(new eu.avalanche7.paradigm.platform.MinecraftPlayer(player));
    }

    public void clear() {
        for (EntityPlayerMP player : List.copyOf(hidden.values())) set(player, false);
        hidden.clear();
        admission.clear();
        reveals.clear();
    }

    public void transferred(EntityPlayerMP player) {
        if (!hidden.containsKey(player.getUniqueID())) reveals.put(player.getUniqueID(), new Reveal(player, System.nanoTime()));
    }

    public void tick() {
        long now = System.nanoTime();
        admission.values().removeIf(state -> now - state.created() > 180_000_000_000L);
        reveals.values().removeIf(state -> now - state.created() > 15_000_000_000L);
        for (Reveal reveal : reveals.values()) {
            EntityPlayerMP subject = reveal.player();
            EntityTrackerEntry entry = entry(subject);
            if (entry == null || hidden.containsKey(subject.getUniqueID())) continue;
            for (EntityPlayerMP viewer : players()) {
                if (viewer != subject && viewer.worldObj == subject.worldObj && !entry.trackingPlayers.contains(viewer)) entry.tryStartWachingThis(viewer);
            }
        }
    }

    private record Reveal(EntityPlayerMP player, long created) {}

    private record Admission(boolean enabled, long created) {}

    private EntityTrackerEntry entry(EntityPlayerMP player) {
        if (!(player.worldObj instanceof WorldServer world)) return null;
        return (EntityTrackerEntry) ((EntityTrackerAccess) world.getEntityTracker()).paradigm$trackedEntities().lookup(player.getEntityId());
    }

    @SuppressWarnings("unchecked")
    private List<EntityPlayerMP> players() { return List.copyOf(server.getConfigurationManager().playerEntityList); }
}
