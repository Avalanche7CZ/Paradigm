package eu.avalanche7.paradigm.modules.dashboard.api;

import com.google.gson.JsonObject;
import eu.avalanche7.paradigm.modules.audit.AuditActionType;
import eu.avalanche7.paradigm.modules.audit.AuditResult;
import eu.avalanche7.paradigm.modules.dashboard.DashboardRequestContext;
import eu.avalanche7.paradigm.modules.dashboard.DashboardResponse;
import eu.avalanche7.paradigm.modules.dashboard.DashboardService;
import eu.avalanche7.paradigm.modules.dashboard.auth.DashboardAuthService;

import java.net.URI;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.Map;

public class AuthApiHandler {
    private static final String COOKIE_NAME = "PARADIGM_DASHBOARD_SESSION";

    private final DashboardService dashboard;

    public AuthApiHandler(DashboardService dashboard) {
        this.dashboard = dashboard;
    }

    public DashboardResponse status(DashboardRequestContext ctx) {
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("authenticated", ctx.principal() != null);
        data.put("requireLogin", dashboard.config().requireLogin);
        data.put("running", dashboard.running());
        data.put("principal", ctx.principal());
        data.put("csrfToken", ctx.session() != null ? ctx.session().csrfToken() : null);
        return DashboardResponse.apiOk(data);
    }

    public DashboardResponse login(DashboardRequestContext ctx) {
        JsonObject body;
        try {
            body = com.google.gson.JsonParser.parseReader(ctx.bodyReader()).getAsJsonObject();
        } catch (Throwable ignored) {
            body = new JsonObject();
        }
        String token = body.has("token") ? body.get("token").getAsString() : ctx.query().get("token");
        token = extractToken(token);
        DashboardAuthService.IssuedSession issued = dashboard.auth().consumeLoginToken(token, dashboard.config());
        if (issued == null) {
            dashboard.audit().dashboard(null, AuditActionType.DASHBOARD_LOGIN, AuditResult.DENIED, "Dashboard login failed.", Map.of("reason", "invalid_or_expired_token"));
            return DashboardResponse.apiError(401, "invalid_request", "Login token is invalid, expired, or already used.");
        }
        dashboard.audit().dashboard(issued.session().principal(), AuditActionType.DASHBOARD_LOGIN, AuditResult.SUCCESS, "Dashboard login succeeded.", Map.of());
        String secure = dashboard.baseUrl().startsWith("https://") ? "; Secure" : "";
        String cookie = COOKIE_NAME + "=" + issued.token() + "; Path=/; HttpOnly; SameSite=Lax; Max-Age=" + Math.max(60, dashboard.config().sessionMinutes * 60) + secure;
        return DashboardResponse.bytes(
                200,
                "application/json; charset=utf-8",
                eu.avalanche7.paradigm.modules.dashboard.DashboardJson.toJson(new DashboardResponse.ApiEnvelope(true, Map.of("principal", issued.session().principal(), "csrfToken", issued.csrfToken()), null, java.util.List.of())).getBytes(java.nio.charset.StandardCharsets.UTF_8),
                Map.of("Set-Cookie", cookie, "Cache-Control", "no-store")
        );
    }

    public DashboardResponse logout(DashboardRequestContext ctx) {
        dashboard.auth().logout(ctx.cookie(COOKIE_NAME));
        dashboard.audit().dashboard(ctx.principal(), AuditActionType.DASHBOARD_LOGOUT, AuditResult.SUCCESS, "Dashboard logout.", Map.of());
        return DashboardResponse.bytes(
                200,
                "application/json; charset=utf-8",
                eu.avalanche7.paradigm.modules.dashboard.DashboardJson.toJson(new DashboardResponse.ApiEnvelope(true, Map.of("loggedOut", true), null, java.util.List.of())).getBytes(java.nio.charset.StandardCharsets.UTF_8),
                Map.of("Set-Cookie", COOKIE_NAME + "=; Path=/; HttpOnly; SameSite=Lax; Max-Age=0", "Cache-Control", "no-store")
        );
    }

    public static String cookieName() {
        return COOKIE_NAME;
    }

    private static String extractToken(String input) {
        if (input == null) {
            return null;
        }
        String trimmed = input.trim();
        if (!trimmed.contains("token=")) {
            return trimmed;
        }
        try {
            String query = URI.create(trimmed).getRawQuery();
            if (query == null) {
                int idx = trimmed.indexOf('?');
                query = idx >= 0 ? trimmed.substring(idx + 1) : trimmed;
            }
            for (String part : query.split("&")) {
                int eq = part.indexOf('=');
                if (eq > 0 && "token".equals(part.substring(0, eq))) {
                    return URLDecoder.decode(part.substring(eq + 1), StandardCharsets.UTF_8);
                }
            }
        } catch (Throwable ignored) {
        }
        return trimmed;
    }
}
