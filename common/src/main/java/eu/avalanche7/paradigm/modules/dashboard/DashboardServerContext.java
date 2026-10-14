package eu.avalanche7.paradigm.modules.dashboard;

public final class DashboardServerContext {
    private DashboardServerContext() {
    }

    public static boolean hostOnly(String path) {
        return path.equals("/api/overview") || path.startsWith("/api/storage/")
                || path.startsWith("/api/discord/") || path.startsWith("/api/menus")
                || path.startsWith("/api/holograms") || path.startsWith("/api/custom-commands")
                || path.startsWith("/api/moderation/") || path.equals("/api/config/patch")
                || path.equals("/api/config/apply");
    }
}
