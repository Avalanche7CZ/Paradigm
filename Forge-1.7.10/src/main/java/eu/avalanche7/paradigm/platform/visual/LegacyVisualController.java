package eu.avalanche7.paradigm.platform.visual;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import net.minecraft.entity.player.EntityPlayerMP;
import net.minecraft.server.MinecraftServer;
import net.minecraft.util.IChatComponent;

public final class LegacyVisualController {
    private static volatile LegacyVisualController current;
    private final MinecraftServer server;
    private final LegacyVanishController vanish;
    private final LegacyTablistController tablist;
    private final Map<UUID, Feedback> feedback = new HashMap<>();
    private final Map<UUID, Feedback> actionbars = new HashMap<>();
    private final Map<UUID, Feedback> actionbarFallbacks = new HashMap<>();
    private final LegacyGTNHVisuals gtnh = new LegacyGTNHVisuals();
    private final LegacyBossbarController bossbars;
    private int ticks;
    private String shutdownReason;

    public LegacyVisualController(MinecraftServer server) {
        this.server = server;
        bossbars = new LegacyBossbarController(server, this::feedback);
        vanish = new LegacyVanishController(server);
        tablist = new LegacyTablistController(server, vanish);
    }

    public void bind() { current = this; }
    public static LegacyVisualController current() { return current; }
    public LegacyVanishController vanish() { return vanish; }
    public boolean setVanished(EntityPlayerMP player, boolean enabled) {
        if (player == null) return false;
        boolean previous = !vanish.visible(player, null);
        boolean result = vanish.set(player, enabled);
        if (previous != enabled) tablist.visibilityChanged(player);
        return result;
    }

    public LegacyTablistController tablist() { return tablist; }

    public void feedback(EntityPlayerMP player, IChatComponent message) {
        if (!LegacyGTNHVisuals.connected(player) || message == null || message.getUnformattedText().isBlank()) return;
        String text = IChatComponent.Serializer.func_150696_a(message);
        long now = System.nanoTime();
        Feedback previous = feedback.get(player.getUniqueID());
        if (previous != null && previous.text().equals(text) && now - previous.time() < 5_000_000_000L) return;
        player.addChatMessage(message);
        feedback.put(player.getUniqueID(), new Feedback(text, now));
    }

    public boolean supportsTitles(EntityPlayerMP player) { return gtnh.supportsTitles(player); }
    public LegacyBossbarController bossbars() { return bossbars; }

    public void title(EntityPlayerMP player, IChatComponent title, IChatComponent subtitle) {
        if (gtnh.title(player, title, subtitle)) return;
        IChatComponent message = title != null ? title.createCopy() : new net.minecraft.util.ChatComponentText("");
        if (subtitle != null && !subtitle.getUnformattedText().isBlank()) {
            if (!message.getUnformattedText().isBlank()) message.appendText("\n");
            message.appendSibling(subtitle.createCopy());
        }
        feedback(player, message);
    }

    public void subtitle(EntityPlayerMP player, IChatComponent subtitle) {
        if (!gtnh.subtitle(player, subtitle)) feedback(player, subtitle);
    }

    public void actionbar(EntityPlayerMP player, IChatComponent message) {
        if (!LegacyGTNHVisuals.connected(player) || message == null) return;
        String text = IChatComponent.Serializer.func_150696_a(message);
        long now = System.nanoTime();
        Feedback previous = actionbars.get(player.getUniqueID());
        if (previous != null && previous.text().equals(text) && now - previous.time() < 1_000_000_000L) return;
        if (!gtnh.actionbar(player, message, message.getUnformattedText().isEmpty() ? 0 : 60)) {
            if (message.getUnformattedText().isEmpty()) { actionbars.remove(player.getUniqueID()); actionbarFallbacks.remove(player.getUniqueID()); return; }
            Feedback last = actionbarFallbacks.get(player.getUniqueID());
            if (last == null || now - last.time() >= 5_000_000_000L) {
                feedback(player, message); actionbarFallbacks.put(player.getUniqueID(), new Feedback(text, now));
            }
            return;
        }
        actionbarFallbacks.remove(player.getUniqueID());
        actionbars.put(player.getUniqueID(), new Feedback(text, now));
    }

    public void timedBossbar(EntityPlayerMP player, IChatComponent message, float progress, int duration) { bossbars.timed(player, message, progress, duration); }
    public void persistent(EntityPlayerMP player, IChatComponent message) { bossbars.persistent(player, message); }
    public void removePersistent(EntityPlayerMP player) { bossbars.removePersistent(player); }
    public void restart(IChatComponent message) { restart(message, 1); }
    public void restart(IChatComponent message, float progress) { bossbars.restart(message, progress); }

    public void shutdown(String reason) { shutdownReason = reason; }
    public String shutdownReason(String original) { return shutdownReason != null ? shutdownReason : original; }

    public void removeRestart() { bossbars.removeRestart(); }
    public void clearTitles(EntityPlayerMP player) {
        gtnh.clear(player);
        if (player != null) feedback.remove(player.getUniqueID());
    }

    public void connected(EntityPlayerMP player) { vanish.connected(player); }
    public void joined(EntityPlayerMP player) { tablist.joined(player); }
    public void transferred(EntityPlayerMP player) {
        clearTitles(player); bossbars.transferred(player); vanish.transferred(player);
        actionbarFallbacks.remove(player.getUniqueID());
        if (actionbars.remove(player.getUniqueID()) != null && LegacyClientCapabilities.actionbar(player.playerNetServerHandler.netManager)) gtnh.actionbar(player, new net.minecraft.util.ChatComponentText(""), 0);
    }

    public void disconnected(EntityPlayerMP player) {
        clearTitles(player);
        bossbars.disconnected(player);
        actionbars.remove(player.getUniqueID());
        actionbarFallbacks.remove(player.getUniqueID());
        tablist.disconnected(player);
        vanish.disconnected(player);
    }

    public void tick() { bossbars.tick(); if (++ticks % 20 == 0) { tablist.tick(); vanish.tick(); } }

    public void clear() {
        vanish.clear();
        tablist.clear();
        feedback.clear();
        bossbars.clear();
        for (EntityPlayerMP player : players()) {
            if (actionbars.containsKey(player.getUniqueID()) && LegacyClientCapabilities.actionbar(player.playerNetServerHandler.netManager)) gtnh.actionbar(player, new net.minecraft.util.ChatComponentText(""), 0);
        }
        actionbars.clear();
        actionbarFallbacks.clear();
        for (EntityPlayerMP player : players()) gtnh.clear(player);
        if (current == this) current = null;
    }

    @SuppressWarnings("unchecked")
    private List<EntityPlayerMP> players() { return List.copyOf(server.getConfigurationManager().playerEntityList); }
    private record Feedback(String text, long time) {}
}
