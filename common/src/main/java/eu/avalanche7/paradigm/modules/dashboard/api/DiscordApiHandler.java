package eu.avalanche7.paradigm.modules.dashboard.api;

import java.util.LinkedHashMap;
import java.util.Map;

import eu.avalanche7.paradigm.configs.DiscordConfigHandler;
import eu.avalanche7.paradigm.modules.audit.AuditActionType;
import eu.avalanche7.paradigm.modules.audit.AuditResult;
import eu.avalanche7.paradigm.modules.dashboard.DashboardJson;
import eu.avalanche7.paradigm.modules.dashboard.DashboardRequestContext;
import eu.avalanche7.paradigm.modules.dashboard.DashboardResponse;
import eu.avalanche7.paradigm.modules.dashboard.DashboardService;
import eu.avalanche7.paradigm.modules.discord.DiscordConnectionStatus;
import eu.avalanche7.paradigm.modules.discord.DiscordDestination;
import eu.avalanche7.paradigm.modules.discord.DiscordSecrets;
import eu.avalanche7.paradigm.modules.discord.DiscordService;

public class DiscordApiHandler {
    private final DashboardService dashboard;

    public DiscordApiHandler(DashboardService dashboard) {
        this.dashboard = dashboard;
    }

    public DashboardResponse status(DashboardRequestContext ctx) {
        DiscordService discord = dashboard.services().getDiscordService();
        DiscordConnectionStatus status = discord.status();
        DiscordConfigHandler.Config config = discord.config();

        Map<String, Object> view = new LinkedHashMap<>();
        view.put("enabled", status.enabled());
        view.put("state", status.state().name());
        view.put("summary", status.summary());
        view.put("inboundCapability", status.inboundCapability().name());
        view.put("inboundRelayBroken", status.inboundRelayBroken());
        view.put("botUsername", status.botUsername() != null ? status.botUsername() : "");
        view.put("botTokenSet", status.tokenConfigured());

        view.put("botTokenMasked", DiscordSecrets.mask(status.tokenConfigured() ? "configured" : null));
        view.put("guildId", config != null ? nullToEmpty(config.guildId.get()) : "");
        view.put("chatChannelId", config != null ? nullToEmpty(config.chatChannelId.get()) : "");
        view.put("moderationChannelId", config != null ? nullToEmpty(config.moderationChannelId.get()) : "");
        view.put("notificationChannelId", config != null ? nullToEmpty(config.notificationChannelId.get()) : "");
        view.put("minecraftToDiscordEnabled", status.minecraftToDiscordEnabled());
        view.put("discordToMinecraftEnabled", status.discordToMinecraftEnabled());
        view.put("connectedSinceMs", status.connectedSinceMs());
        view.put("lastHeartbeatAckMs", status.lastHeartbeatAckMs());
        view.put("heartbeatOutstanding", status.heartbeatOutstanding());
        view.put("queueDepth", status.queueDepth());
        view.put("sentCount", status.sentCount());
        view.put("droppedCount", status.droppedCount());
        view.put("failedCount", status.failedCount());
        view.put("lastError", status.lastError() != null ? status.lastError() : "");
        view.put("warnings", status.warnings());
        return DashboardResponse.apiOk(view);
    }

    public DashboardResponse saveToken(DashboardRequestContext ctx) {
        TokenRequest request = DashboardJson.fromJson(ctx.bodyReader(), TokenRequest.class);
        String submitted = request != null && request.botToken != null ? request.botToken.trim() : "";
        boolean clear = request != null && Boolean.TRUE.equals(request.clear);

        DiscordConfigHandler.Config config = dashboard.services().getDiscordService().config();
        if (config == null) {
            return DashboardResponse.apiError(503, "unavailable", "Discord configuration is not loaded.");
        }

        if (clear) {
            config.botToken.value = "";
        } else if (submitted.isEmpty()) {
            return DashboardResponse.apiOk(Map.of("botTokenSet", DiscordSecrets.isPresent(config.botToken.get()),
                    "changed", false));
        } else {
            if (submitted.length() < 20 || submitted.length() > 200 || submitted.chars().anyMatch(Character::isWhitespace)) {
                return DashboardResponse.apiError(400, "validation_failed", "That does not look like a Discord bot token.");
            }
            config.botToken.value = submitted;
        }

        DiscordConfigHandler.persistConfig();
        dashboard.services().getDiscordService().reload();

        dashboard.audit().dashboard(ctx.principal(), AuditActionType.DISCORD_CHANGE, AuditResult.SUCCESS,
                clear ? "Discord bot token cleared." : "Discord bot token replaced.",
                Map.of("tokenReplaced", String.valueOf(!clear), "tokenCleared", String.valueOf(clear)));
        return DashboardResponse.apiOk(Map.of("botTokenSet", DiscordSecrets.isPresent(config.botToken.get()),
                "changed", true));
    }

    public DashboardResponse test(DashboardRequestContext ctx) {
        TestRequest request = DashboardJson.fromJson(ctx.bodyReader(), TestRequest.class);
        String raw = request != null && request.destination != null ? request.destination : "chat";
        DiscordDestination destination = DiscordDestination.parse(raw);
        if (destination == null) {
            return DashboardResponse.apiError(400, "validation_failed",
                    "Unknown destination. Use chat, moderation or notifications.");
        }

        DiscordService discord = dashboard.services().getDiscordService();
        if (!discord.isEnabled()) {
            return DashboardResponse.apiError(409, "discord_disabled",
                    "Discord integration is disabled or incompletely configured.");
        }
        boolean queued = discord.sendTest(destination);
        dashboard.audit().dashboard(ctx.principal(), AuditActionType.DISCORD_TEST,
                queued ? AuditResult.SUCCESS : AuditResult.FAILED, "Discord test message requested.",
                Map.of("destination", destination.name()));
        if (!queued) {
            return DashboardResponse.apiError(503, "queue_unavailable",
                    "Could not queue the test message; the outbound queue is closed or full.");
        }
        return DashboardResponse.apiOk(Map.of("queued", true, "destination", destination.name()));
    }

    public DashboardResponse reconnect(DashboardRequestContext ctx) {
        DiscordService discord = dashboard.services().getDiscordService();
        if (!discord.isEnabled()) {
            return DashboardResponse.apiError(409, "discord_disabled",
                    "Discord integration is disabled or incompletely configured.");
        }
        discord.reconnect();
        dashboard.audit().dashboard(ctx.principal(), AuditActionType.DISCORD_CHANGE, AuditResult.SUCCESS,
                "Discord reconnect requested.", Map.of("action", "reconnect"));
        return DashboardResponse.apiOk(Map.of("reconnecting", true));
    }

    private static String nullToEmpty(String value) {
        return value != null ? value.trim() : "";
    }

    public static final class TokenRequest {
        public String botToken = "";
        public Boolean clear = Boolean.FALSE;
    }

    public static final class TestRequest {
        public String destination = "chat";
    }
}
