package eu.avalanche7.paradigm.platform;

import java.net.InetSocketAddress;
import java.net.SocketAddress;

import com.mojang.authlib.GameProfile;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import eu.avalanche7.paradigm.core.Services;

public final class MinecraftLoginHandler {
    private static final Logger LOGGER = LoggerFactory.getLogger(MinecraftLoginHandler.class);
    private static volatile Services services;

    private MinecraftLoginHandler() {
    }

    public static void bind(Services runtimeServices) {
        services = runtimeServices;
    }

    public static void clear() {
        services = null;
    }

    public static String rejection(SocketAddress address, GameProfile profile) {
        Services runtimeServices = services;
        if (runtimeServices == null || profile == null || profile.getId() == null) {
            return null;
        }
        var punishments = runtimeServices.getPunishmentService();
        var blocked = punishments.loginBlock(profile.getId().toString(), remoteAddress(address));
        if (blocked.isEmpty()) {
            return null;
        }
        var record = blocked.get();
        MinecraftComponent screen = (MinecraftComponent) punishments.banScreen().format(record);
        LOGGER.info("Paradigm rejected login before player creation: punishment={} type={}",
                record.punishmentId(), record.type());
        return screen.toLegacyText();
    }

    public static boolean vanishEnabled() {
        Services runtime = services;
        return runtime != null && new eu.avalanche7.paradigm.modules.commands.admin.VanishCommand().isEnabled(runtime);
    }

    public static java.util.concurrent.CompletableFuture<Boolean> loadVanish(GameProfile profile) {
        Services runtime = services;
        if (!vanishEnabled()) {
            return java.util.concurrent.CompletableFuture.completedFuture(false);
        }
        var result = new java.util.concurrent.CompletableFuture<Boolean>();
        runtime.getStorageService().runAsync("admin.vanish.admission",
                () -> runtime.getStorageService().adminState().isVanished(profile.getId().toString()),
                runtime.getTaskScheduler(), result::complete, result::completeExceptionally);
        return result;
    }

    public static String remoteAddress(SocketAddress address) {
        if (address instanceof InetSocketAddress inet && inet.getAddress() != null) {
            return inet.getAddress().getHostAddress();
        }
        return null;
    }
}
