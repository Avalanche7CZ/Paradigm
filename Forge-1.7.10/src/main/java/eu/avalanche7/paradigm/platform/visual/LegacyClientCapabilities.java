package eu.avalanche7.paradigm.platform.visual;

import java.util.Map;

import cpw.mods.fml.common.Loader;
import io.netty.util.AttributeKey;
import net.minecraft.network.NetworkManager;

public final class LegacyClientCapabilities {
    private static final AttributeKey<Boolean> ACTIONBAR = new AttributeKey<>("paradigm:gtnh-actionbar");
    private LegacyClientCapabilities() {}

    public static boolean compatibleActionbar(String serverVersion, String clientVersion) {
        return "0.7.10".equals(serverVersion) && "0.7.10".equals(clientVersion);
    }

    public static void record(NetworkManager manager, Map<String, String> remoteMods) {
        var mod = Loader.instance().getIndexedModList().get("gtnhlib");
        manager.channel().attr(ACTIONBAR).set(compatibleActionbar(mod != null ? mod.getVersion() : null, remoteMods.get("gtnhlib")));
    }

    public static boolean actionbar(NetworkManager manager) {
        return manager != null && manager.channel() != null && Boolean.TRUE.equals(manager.channel().attr(ACTIONBAR).get());
    }
}
