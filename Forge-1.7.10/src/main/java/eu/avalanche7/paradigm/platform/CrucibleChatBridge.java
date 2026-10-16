package eu.avalanche7.paradigm.platform;

import java.lang.reflect.Array;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.FutureTask;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.function.Supplier;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import eu.avalanche7.paradigm.Paradigm;
import eu.avalanche7.paradigm.core.Services;
import eu.avalanche7.paradigm.platform.Interfaces.IComponent;
import eu.avalanche7.paradigm.platform.Interfaces.IPlatformAdapter;

/** Optional string formatting at Bukkit's completed chat-event boundary; Bukkit still delivers. */
public final class CrucibleChatBridge {
    private static final Logger LOGGER = LoggerFactory.getLogger(CrucibleChatBridge.class);
    private static final String ASYNC = "org.bukkit.event.player.AsyncPlayerChatEvent";
    private static final String SYNC = "org.bukkit.event.player.PlayerChatEvent";

    private CrucibleChatBridge() {}

    public static boolean available() {
        try {
            Class.forName("org.bukkit.craftbukkit.v1_7_R4.CraftServer", false, CrucibleChatBridge.class.getClassLoader());
            return true;
        } catch (ClassNotFoundException absent) {
            return false;
        }
    }

    public static void afterChatEvent(Object event) {
        String type = event.getClass().getName();
        if (!ASYNC.equals(type) && !SYNC.equals(type)) return;
        Services services = Paradigm.getServices();
        if (services == null) return;
        if (services.getChatConfig() == null || !Boolean.TRUE.equals(services.getChatConfig().enableCustomChatFormat.value)) return;
        try {
            if (Boolean.TRUE.equals(event.getClass().getMethod("isCancelled").invoke(event))) return;
            if (ASYNC.equals(type)) {
                // Crucible will copy this event into PlayerChatEvent and run its handlers next.
                Class<?> sync = Class.forName(SYNC, false, event.getClass().getClassLoader());
                Object handlers = sync.getMethod("getHandlerList").invoke(null);
                if (Array.getLength(handlers.getClass().getMethod("getRegisteredListeners").invoke(handlers)) != 0) return;
            }
            onServerThread(services.getPlatformAdapter(), () -> {
                try {
                    Object player = event.getClass().getMethod("getPlayer").invoke(event);
                    Object handle = player.getClass().getMethod("getHandle").invoke(player);
                    if (!(handle instanceof net.minecraft.entity.player.EntityPlayerMP nativePlayer)) {
                        throw new IllegalStateException("Crucible chat sender is not a native player");
                    }
                    String message = (String) event.getClass().getMethod("getMessage").invoke(event);
                    IComponent formatted = services.getChatFormatter().format(new MinecraftPlayer(nativePlayer), message);
                    if (formatted != null) {
                        // String.format runs after this hook. User/template '%' must stay literal.
                        // A single line also avoids Crucible's lazy-recipient loop repeating multiline arrays.
                        String legacy = ((MinecraftComponent) formatted).toLegacyText()
                                .replace('\r', ' ').replace('\n', ' ').replace("%", "%%");
                        event.getClass().getMethod("setFormat", String.class).invoke(event, legacy);
                    }
                    return null;
                } catch (ReflectiveOperationException failure) {
                    throw new IllegalStateException("Could not format Crucible chat", failure);
                }
            });
        } catch (ReflectiveOperationException | RuntimeException failure) {
            // A failed formatter must not turn a moderated/private message into a raw broadcast.
            try {
                event.getClass().getMethod("setCancelled", boolean.class).invoke(event, true);
            } catch (ReflectiveOperationException cancellationFailure) {
                failure.addSuppressed(cancellationFailure);
                throw new IllegalStateException("Could not cancel failed Crucible chat", failure);
            }
            LOGGER.error("Crucible chat formatting failed; message cancelled", failure);
        }
    }

    static <T> T onServerThread(IPlatformAdapter platform, Supplier<T> action) {
        if (platform.isServerThread()) return action.get();
        FutureTask<T> task = new FutureTask<>(action::get);
        platform.executeOnServerThread(task);
        try {
            return task.get(5, TimeUnit.SECONDS);
        } catch (InterruptedException failure) {
            task.cancel(false);
            Thread.currentThread().interrupt();
            throw new IllegalStateException("Interrupted waiting for chat on the server thread", failure);
        } catch (ExecutionException | TimeoutException failure) {
            task.cancel(false);
            throw new IllegalStateException("Chat could not complete on the server thread", failure);
        }
    }
}
