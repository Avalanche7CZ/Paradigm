package eu.avalanche7.paradigm.modules.dashboard;

import com.sun.net.httpserver.HttpExchange;
import eu.avalanche7.paradigm.modules.dashboard.auth.DashboardPrincipal;
import eu.avalanche7.paradigm.modules.dashboard.auth.DashboardSession;

import java.io.InputStreamReader;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

public class DashboardRequestContext {
    private final HttpExchange exchange;
    private final DashboardPrincipal principal;
    private final DashboardSession session;

    public DashboardRequestContext(HttpExchange exchange, DashboardPrincipal principal) {
        this(exchange, principal, null);
    }

    public DashboardRequestContext(HttpExchange exchange, DashboardPrincipal principal, DashboardSession session) {
        this.exchange = exchange;
        this.principal = principal;
        this.session = session;
    }

    public String method() {
        return exchange.getRequestMethod();
    }

    public String path() {
        return exchange.getRequestURI().getPath();
    }

    public Map<String, String> query() {
        Map<String, String> result = new LinkedHashMap<>();
        String raw = exchange.getRequestURI().getRawQuery();
        if (raw == null || raw.isBlank()) {
            return result;
        }
        for (String part : raw.split("&")) {
            int eq = part.indexOf('=');
            String key = decode(eq >= 0 ? part.substring(0, eq) : part);
            String value = decode(eq >= 0 ? part.substring(eq + 1) : "");
            result.put(key, value);
        }
        return result;
    }

    public InputStreamReader bodyReader() {
        return new InputStreamReader(exchange.getRequestBody(), StandardCharsets.UTF_8);
    }

    public DashboardPrincipal principal() {
        return principal;
    }

    public DashboardSession session() {
        return session;
    }

    public String header(String name) {
        if (name == null) {
            return null;
        }
        return exchange.getRequestHeaders().getFirst(name);
    }

    public String cookie(String name) {
        List<String> cookies = exchange.getRequestHeaders().get("Cookie");
        if (cookies == null || name == null) {
            return null;
        }
        for (String header : cookies) {
            for (String part : header.split(";")) {
                String[] pieces = part.trim().split("=", 2);
                if (pieces.length == 2 && name.equals(pieces[0])) {
                    return pieces[1];
                }
            }
        }
        return null;
    }

    private static String decode(String value) {
        return URLDecoder.decode(value != null ? value : "", StandardCharsets.UTF_8);
    }
}
