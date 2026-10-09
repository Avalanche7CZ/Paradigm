package eu.avalanche7.paradigm.platform.visual;

import java.util.Map;
import java.util.regex.Pattern;

import cpw.mods.fml.common.Loader;
import io.netty.util.AttributeKey;
import net.minecraft.network.NetworkManager;

public final class LegacyClientCapabilities {
    private static final AttributeKey<Capabilities> VISUALS = new AttributeKey<>("paradigm:gtnh-visuals");
    private static final AttributeKey<Integer> VIEW_DISTANCE = new AttributeKey<>("paradigm:view-distance");
    private static final Pattern VERSION = Pattern.compile("(0|[1-9]\\d*)\\.(0|[1-9]\\d*)\\.(0|[1-9]\\d*)(?:\\+([0-9A-Za-z-]+(?:\\.[0-9A-Za-z-]+)*))?");
    private LegacyClientCapabilities() {}

    public static boolean compatibleActionbar(String serverVersion, String clientVersion) {
        Version server = Version.parse(serverVersion), client = Version.parse(clientVersion);
        if (server == null || client == null) return false;
        if (server.equals(client)) return supportedRelease(server);
        return endpoint(server) && endpoint(client);
    }

    public static boolean compatibleTitles(String serverVersion, String clientVersion) {
        Version server = Version.parse(serverVersion), client = Version.parse(clientVersion);
        return server != null && server.equals(client) && supportedRelease(server) && server.compareTo(new Version(0, 9, 55)) >= 0;
    }

    private static boolean supportedRelease(Version version) {
        return version.compareTo(new Version(0, 7, 10)) >= 0 && version.compareTo(new Version(0, 11, 52)) <= 0;
    }

    private static boolean endpoint(Version version) {
        return version.equals(new Version(0, 7, 10)) || version.equals(new Version(0, 9, 55));
    }

    public static void record(NetworkManager manager, Map<String, String> remoteMods) {
        var mod = Loader.instance().getIndexedModList().get("gtnhlib");
        String server = mod != null ? mod.getVersion() : null;
        String client = remoteMods.get("gtnhlib");
        record(manager, server, client);
    }

    static void record(NetworkManager manager, String server, String client) {
        manager.channel().attr(VISUALS).set(new Capabilities(compatibleActionbar(server, client), compatibleTitles(server, client)));
    }

    public static boolean actionbar(NetworkManager manager) {
        Capabilities capabilities = capabilities(manager);
        return capabilities != null && capabilities.actionbar;
    }

    public static boolean titles(NetworkManager manager) {
        Capabilities capabilities = capabilities(manager);
        return capabilities != null && capabilities.titles;
    }

    public static void viewDistance(NetworkManager manager, int chunks) {
        if (manager != null && manager.channel() != null) manager.channel().attr(VIEW_DISTANCE).set(chunks);
    }

    public static boolean bossbar(NetworkManager manager) {
        Integer chunks = manager != null && manager.channel() != null ? manager.channel().attr(VIEW_DISTANCE).get() : null;
        return chunks != null && chunks >= 2 && chunks <= 16;
    }

    private static Capabilities capabilities(NetworkManager manager) {
        return manager != null && manager.channel() != null ? manager.channel().attr(VISUALS).get() : null;
    }

    private record Capabilities(boolean actionbar, boolean titles) {}
    private record Version(int major, int minor, int patch) implements Comparable<Version> {
        static Version parse(String value) {
            if (value == null) return null;
            var match = VERSION.matcher(value.trim());
            if (!match.matches()) return null;
            if (match.group(4) != null && match.group(4).toLowerCase(java.util.Locale.ROOT).matches(".*(?:dev|snapshot|dirty).*$")) return null;
            try { return new Version(Integer.parseInt(match.group(1)), Integer.parseInt(match.group(2)), Integer.parseInt(match.group(3))); }
            catch (NumberFormatException invalid) { return null; }
        }
        @Override public int compareTo(Version other) {
            int result = Integer.compare(major, other.major);
            if (result == 0) result = Integer.compare(minor, other.minor);
            return result == 0 ? Integer.compare(patch, other.patch) : result;
        }
    }
}
