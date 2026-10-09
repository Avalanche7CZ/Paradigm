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
    private final Map<UUID, String> persistent = new HashMap<>();
    private long restartFeedback;
    private boolean restartActive;
    private int ticks;
    private String shutdownReason;

    public LegacyVisualController(MinecraftServer server) {
        this.server = server;
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
        if (player == null || message == null || message.getUnformattedText().isBlank()) return;
        String text = IChatComponent.Serializer.func_150696_a(message);
        long now = System.nanoTime();
        Feedback previous = feedback.get(player.getUniqueID());
        if (previous != null && previous.text().equals(text) && now - previous.time() < 5_000_000_000L) return;
        player.addChatMessage(message);
        feedback.put(player.getUniqueID(), new Feedback(text, now));
    }

    public void title(EntityPlayerMP player, IChatComponent title, IChatComponent subtitle) {
        IChatComponent message = title.createCopy();
        if (subtitle != null && !subtitle.getUnformattedText().isBlank()) {
            if (!title.getUnformattedText().isBlank()) message.appendText("\n");
            message.appendSibling(subtitle.createCopy());
        }
        feedback(player, message);
    }

    public void actionbar(EntityPlayerMP player, IChatComponent message) {
        if (player == null) return;
        if (!LegacyClientCapabilities.actionbar(player.playerNetServerHandler.netManager)) { feedback(player, message); return; }
        String text = IChatComponent.Serializer.func_150696_a(message);
        long now = System.nanoTime();
        Feedback previous = actionbars.get(player.getUniqueID());
        if (previous != null && previous.text().equals(text) && now - previous.time() < 1_000_000_000L) return;
        LegacyGTNHActionbar.send(player, message, 60);
        actionbars.put(player.getUniqueID(), new Feedback(text, now));
    }

    public void persistent(EntityPlayerMP player, IChatComponent message) {
        if (player == null) return;
        String text = IChatComponent.Serializer.func_150696_a(message);
        if (!text.equals(persistent.put(player.getUniqueID(), text))) feedback(player, message);
    }

    public void removePersistent(EntityPlayerMP player) {
        if (player != null) persistent.remove(player.getUniqueID());
    }

    public void restart(IChatComponent message) {
        long now = System.nanoTime();
        if (!restartActive || now - restartFeedback >= 30_000_000_000L) {
            for (EntityPlayerMP player : players()) feedback(player, message);
            restartFeedback = now;
        }
        restartActive = true;
    }

    public void shutdown(String reason) { shutdownReason = reason; }
    public String shutdownReason(String original) { return shutdownReason != null ? shutdownReason : original; }

    public void removeRestart() { restartActive = false; }
    public void clearTitles(EntityPlayerMP player) {
        if (player != null) feedback.remove(player.getUniqueID());
    }

    public void connected(EntityPlayerMP player) { vanish.connected(player); }
    public void joined(EntityPlayerMP player) { tablist.joined(player); }
    public void transferred(EntityPlayerMP player) {
        clearTitles(player); removePersistent(player); vanish.transferred(player);
        if (actionbars.remove(player.getUniqueID()) != null && LegacyClientCapabilities.actionbar(player.playerNetServerHandler.netManager)) LegacyGTNHActionbar.send(player, new net.minecraft.util.ChatComponentText(""), 0);
    }

    public void disconnected(EntityPlayerMP player) {
        clearTitles(player);
        removePersistent(player);
        actionbars.remove(player.getUniqueID());
        tablist.disconnected(player);
        vanish.disconnected(player);
    }

    public void tick() { if (++ticks % 20 == 0) { tablist.tick(); vanish.tick(); } }

    public void clear() {
        vanish.clear();
        tablist.clear();
        feedback.clear();
        persistent.clear();
        for (EntityPlayerMP player : players()) {
            if (actionbars.containsKey(player.getUniqueID()) && LegacyClientCapabilities.actionbar(player.playerNetServerHandler.netManager)) LegacyGTNHActionbar.send(player, new net.minecraft.util.ChatComponentText(""), 0);
        }
        actionbars.clear();
        restartActive = false;
        if (current == this) current = null;
    }

    @SuppressWarnings("unchecked")
    private List<EntityPlayerMP> players() { return List.copyOf(server.getConfigurationManager().playerEntityList); }
    private record Feedback(String text, long time) {}
}
