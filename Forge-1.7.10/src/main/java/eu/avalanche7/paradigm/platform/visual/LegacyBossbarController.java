package eu.avalanche7.paradigm.platform.visual;

import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.function.BiConsumer;

import net.minecraft.entity.Entity;
import net.minecraft.entity.EntityTrackerEntry;
import net.minecraft.entity.boss.EntityDragon;
import net.minecraft.entity.boss.IBossDisplayData;
import net.minecraft.entity.player.EntityPlayerMP;
import net.minecraft.network.play.server.S0FPacketSpawnMob;
import net.minecraft.network.play.server.S13PacketDestroyEntities;
import net.minecraft.network.play.server.S18PacketEntityTeleport;
import net.minecraft.network.play.server.S1CPacketEntityMetadata;
import net.minecraft.server.MinecraftServer;
import net.minecraft.util.IChatComponent;
import net.minecraft.world.World;
import net.minecraft.world.WorldServer;

import eu.avalanche7.paradigm.mixin.EntityTrackerAccess;
import eu.avalanche7.paradigm.platform.MinecraftComponent;

public final class LegacyBossbarController {
    private final MinecraftServer server;
    private final BiConsumer<EntityPlayerMP, IChatComponent> fallback;
    private final Map<UUID, Session> sessions = new HashMap<>();
    private final Set<Entity> realBosses = new HashSet<>();
    private Bar restart;
    private long ticks;

    LegacyBossbarController(MinecraftServer server, BiConsumer<EntityPlayerMP, IChatComponent> fallback) {
        this.server = server;
        this.fallback = fallback;
        if (server.worldServers != null) for (WorldServer world : server.worldServers) {
            if (world != null && world.loadedEntityList != null) for (Object entity : world.loadedEntityList) entityJoined((Entity) entity);
        }
    }

    public void entityJoined(Entity entity) { if (entity instanceof IBossDisplayData) realBosses.add(entity); }
    public void worldUnloaded(World world) { realBosses.removeIf(entity -> entity.worldObj == world); }

    public void timed(EntityPlayerMP player, IChatComponent message, float progress, int duration) {
        if (!LegacyGTNHVisuals.connected(player) || duration <= 0) return;
        session(player).timed = bar(message, progress, ticks + Math.min((long) duration * 20, Integer.MAX_VALUE));
        refresh(session(player));
    }

    public void persistent(EntityPlayerMP player, IChatComponent message) {
        if (!LegacyGTNHVisuals.connected(player)) return;
        session(player).persistent = bar(message, 1, Long.MAX_VALUE);
        refresh(session(player));
    }

    public void removePersistent(EntityPlayerMP player) {
        Session session = matching(player);
        if (session != null) { session.persistent = null; refresh(session); }
    }

    public void restart(IChatComponent message, float progress) {
        restart = bar(message, progress, Long.MAX_VALUE);
        for (EntityPlayerMP player : players()) refresh(session(player));
    }

    public void removeRestart() {
        restart = null;
        for (Session session : List.copyOf(sessions.values())) refresh(session);
    }

    public void transferred(EntityPlayerMP player) {
        Session session = sessions.get(player.getUniqueID());
        if (session != null) { destroy(session); session.player = player; session.rendered = null; }
    }

    public void disconnected(EntityPlayerMP player) {
        Session session = matching(player);
        if (session != null) { destroy(session); sessions.remove(player.getUniqueID()); }
    }

    public void tick() {
        ticks++;
        if (ticks % 10 != 0) return;
        realBosses.removeIf(entity -> entity.isDead);
        if (restart != null) for (EntityPlayerMP player : players()) session(player);
        for (Session session : List.copyOf(sessions.values())) {
            if (!LegacyGTNHVisuals.connected(session.player)) { sessions.remove(session.player.getUniqueID()); continue; }
            refresh(session);
            if (session.persistent == null && session.timed == null && restart == null) sessions.remove(session.player.getUniqueID());
        }
    }

    public void clear() {
        for (Session session : sessions.values()) destroy(session);
        sessions.clear(); realBosses.clear(); restart = null;
    }

    private void refresh(Session session) {
        if (session.timed != null && session.timed.expires <= ticks) session.timed = null;
        Bar selected = restart != null ? restart : session.timed != null ? session.timed : session.persistent;
        if (selected == null || !LegacyGTNHVisuals.connected(session.player)) { destroy(session); return; }
        EntityPlayerMP player = session.player;
        if (!LegacyClientCapabilities.bossbar(player.playerNetServerHandler.netManager) || realBossTracked(player)) {
            destroy(session);
            if (session.fallback == null || !session.fallback.text.equals(selected.text) && ticks - session.fallbackAt >= (restart != null ? 600 : 100)) {
                fallback.accept(player, selected.message.createCopy()); session.fallback = selected; session.fallbackAt = ticks;
            }
            return;
        }
        if (session.dragon != null && session.dragon.worldObj != player.worldObj) destroy(session);
        if (session.dragon == null) {
            session.dragon = new EntityDragon(player.worldObj);
            apply(session.dragon, selected);
            position(session);
            player.playerNetServerHandler.sendPacket(new S0FPacketSpawnMob(session.dragon));
            session.rendered = selected;
        } else {
            if (!selected.text.equals(session.rendered.text) || selected.progress != session.rendered.progress) {
                apply(session.dragon, selected);
                player.playerNetServerHandler.sendPacket(new S1CPacketEntityMetadata(session.dragon.getEntityId(), session.dragon.getDataWatcher(), true));
                session.rendered = selected;
            }
            position(session);
            player.playerNetServerHandler.sendPacket(new S18PacketEntityTeleport(session.dragon));
        }
    }

    private boolean realBossTracked(EntityPlayerMP player) {
        if (!(player.worldObj instanceof WorldServer world) || !(world.getEntityTracker() instanceof EntityTrackerAccess tracker)) return false;
        for (Entity boss : realBosses) {
            if (boss.worldObj != world || boss.isDead) continue;
            EntityTrackerEntry entry = (EntityTrackerEntry) tracker.paradigm$trackedEntities().lookup(boss.getEntityId());
            if (entry != null && entry.trackingPlayers.contains(player)) return true;
        }
        return false;
    }

    private static void apply(EntityDragon dragon, Bar bar) {
        dragon.setCustomNameTag(bar.text);
        dragon.setHealth(Math.max(0.0001f, bar.progress * 200));
    }

    private static void position(Session session) {
        EntityPlayerMP player = session.player;
        session.dragon.setPosition(player.posX, player.posY - 640, player.posZ);
        session.dragon.motionX = session.dragon.motionY = session.dragon.motionZ = 0;
    }

    private void destroy(Session session) {
        if (session.dragon == null) return;
        if (LegacyGTNHVisuals.connected(session.player)) session.player.playerNetServerHandler.sendPacket(new S13PacketDestroyEntities(session.dragon.getEntityId()));
        session.dragon = null; session.rendered = null;
    }

    private Session session(EntityPlayerMP player) {
        Session previous = sessions.get(player.getUniqueID());
        if (previous != null && previous.player != player) transferred(player);
        return sessions.computeIfAbsent(player.getUniqueID(), ignored -> new Session(player));
    }

    private Session matching(EntityPlayerMP player) {
        Session session = player != null ? sessions.get(player.getUniqueID()) : null;
        return session != null && session.player == player ? session : null;
    }

    private static Bar bar(IChatComponent message, float progress, long expires) {
        IChatComponent text = message != null ? message.createCopy() : new net.minecraft.util.ChatComponentText("");
        String name = new MinecraftComponent(text).toLegacyText();
        if (name.length() > 256) name = name.substring(0, name.charAt(255) == '§' ? 255 : 256);
        return new Bar(text, name, Float.isFinite(progress) ? Math.max(0, Math.min(1, progress)) : 0, expires);
    }

    @SuppressWarnings("unchecked")
    private List<EntityPlayerMP> players() { return List.copyOf(server.getConfigurationManager().playerEntityList); }
    private record Bar(IChatComponent message, String text, float progress, long expires) {}
    private static final class Session {
        EntityPlayerMP player;
        Bar persistent, timed, rendered, fallback;
        EntityDragon dragon;
        long fallbackAt;
        Session(EntityPlayerMP player) { this.player = player; }
    }
}
