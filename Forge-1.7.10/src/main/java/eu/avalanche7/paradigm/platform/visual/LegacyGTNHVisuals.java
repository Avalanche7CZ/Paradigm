package eu.avalanche7.paradigm.platform.visual;

import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.nio.charset.StandardCharsets;

import net.minecraft.entity.player.EntityPlayerMP;
import net.minecraft.util.ChatComponentText;
import net.minecraft.util.IChatComponent;
import org.apache.logging.log4j.LogManager;

final class LegacyGTNHVisuals {
    private Method title, subtitle, times, clear;
    private boolean initialized, titlesUnavailable, actionbarUnavailable;

    synchronized boolean supportsTitles(EntityPlayerMP player) {
        if (!connected(player) || !LegacyClientCapabilities.titles(player.playerNetServerHandler.netManager) || titlesUnavailable) return false;
        if (!initialized) {
            initialized = true;
            try {
                Class<?> api = Class.forName("com.gtnewhorizon.gtnhlib.network.TitlePacketHandler");
                title = api.getMethod("sendTitle", EntityPlayerMP.class, IChatComponent.class);
                subtitle = api.getMethod("sendSubtitle", EntityPlayerMP.class, IChatComponent.class);
                times = api.getMethod("sendTimes", EntityPlayerMP.class, int.class, int.class, int.class);
                clear = api.getMethod("sendClear", EntityPlayerMP.class);
            } catch (ClassNotFoundException | NoSuchMethodException | LinkageError unavailable) {
                unavailable(true, unavailable);
            }
        }
        return !titlesUnavailable;
    }

    boolean title(EntityPlayerMP player, IChatComponent heading, IChatComponent subheading) {
        if (!supportsTitles(player) || !encodable(heading) || !encodable(subheading)) return false;
        return invoke(clear, player) && invoke(times, player, 10, 70, 20)
                && invoke(subtitle, player, text(subheading)) && invoke(title, player, text(heading));
    }

    boolean subtitle(EntityPlayerMP player, IChatComponent message) {
        return supportsTitles(player) && encodable(message) && invoke(subtitle, player, text(message));
    }

    void clear(EntityPlayerMP player) { if (supportsTitles(player)) invoke(clear, player); }

    boolean actionbar(EntityPlayerMP player, IChatComponent message, int duration) {
        if (!connected(player) || actionbarUnavailable || !LegacyClientCapabilities.actionbar(player.playerNetServerHandler.netManager) || !encodable(message)) return false;
        try { LegacyGTNHActionbar.send(player, text(message), duration); return true; }
        catch (LinkageError unavailable) { unavailable(false, unavailable); return false; }
    }

    private boolean invoke(Method method, Object... arguments) {
        try { method.invoke(null, arguments); return true; }
        catch (IllegalAccessException unavailable) { unavailable(true, unavailable); return false; }
        catch (InvocationTargetException failure) {
            Throwable cause = failure.getCause();
            if (cause instanceof LinkageError) { unavailable(true, cause); return false; }
            if (cause instanceof RuntimeException runtime) throw runtime;
            if (cause instanceof Error error) throw error;
            throw new IllegalStateException("GTNHLib title API failed", cause);
        }
    }

    private synchronized void unavailable(boolean titles, Throwable cause) {
        if (titles) titlesUnavailable = true; else actionbarUnavailable = true;
        LogManager.getLogger("Paradigm").warn("Optional GTNHLib {} API is incompatible; using chat fallback", titles ? "title" : "actionbar", cause);
    }

    private static boolean encodable(IChatComponent message) {
        return message == null || IChatComponent.Serializer.func_150696_a(message).getBytes(StandardCharsets.UTF_8).length <= 30000;
    }
    private static IChatComponent text(IChatComponent message) { return message != null ? message.createCopy() : new ChatComponentText(""); }
    static boolean connected(EntityPlayerMP player) {
        return player != null && player.playerNetServerHandler != null && player.playerNetServerHandler.netManager != null
                && player.playerNetServerHandler.netManager.isChannelOpen() && player.playerNetServerHandler.playerEntity == player;
    }
}
