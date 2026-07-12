package eu.avalanche7.paradigm.modules.dashboard;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public class DashboardHttpServer implements AutoCloseable {
    private final DashboardService dashboard;
    private final DashboardConfig config;
    private final DashboardRouter router;
    private final Map<String, Window> rateLimits = new ConcurrentHashMap<>();
    private HttpServer server;
    private ExecutorService httpExecutor;
    private volatile boolean running;

    public DashboardHttpServer(DashboardService dashboard, DashboardConfig config) {
        this.dashboard = dashboard;
        this.config = config;
        this.router = new DashboardRouter(dashboard);
    }

    public boolean start() {
        try {
            InetSocketAddress address = new InetSocketAddress(config.host, config.port);
            server = HttpServer.create(address, 32);
            server.createContext("/", this::handle);
            httpExecutor = Executors.newFixedThreadPool(4, r -> {
                Thread thread = new Thread(r, "Paradigm-Dashboard-HTTP");
                thread.setDaemon(true);
                return thread;
            });
            server.setExecutor(httpExecutor);
            server.start();
            running = true;
            if (dashboard.services().getLogger() != null) {
                dashboard.services().getLogger().info("Paradigm Dashboard started at {}", config.localBaseUrl());
            }
            return true;
        } catch (IOException | RuntimeException e) {
            running = false;
            if (dashboard.services().getLogger() != null) {
                dashboard.services().getLogger().warn("Paradigm Dashboard failed to start on {}:{}: {}", config.host, config.port, e.getMessage());
            }
            return false;
        }
    }

    public boolean running() {
        return running;
    }

    private void handle(HttpExchange exchange) throws IOException {
        DashboardResponse response;
        if (isApi(exchange) && "OPTIONS".equalsIgnoreCase(exchange.getRequestMethod())) {
            response = originAllowed(exchange)
                    ? DashboardResponse.bytes(204, "application/json; charset=utf-8", new byte[0], Map.of("Cache-Control", "no-store"))
                    : DashboardResponse.apiError(403, "permission_denied", "Dashboard origin is not allowed.");
        } else if (!originAllowed(exchange)) {
            response = DashboardResponse.apiError(403, "permission_denied", "Dashboard origin is not allowed.");
        } else if (!rateAllowed(exchange)) {
            response = DashboardResponse.apiError(429, "rate_limited", "Too many dashboard requests.");
        } else {
            response = router.route(exchange);
        }
        write(exchange, response);
    }

    private void write(HttpExchange exchange, DashboardResponse response) throws IOException {
        exchange.getResponseHeaders().set("X-Content-Type-Options", "nosniff");
        exchange.getResponseHeaders().set("Referrer-Policy", "no-referrer");
        exchange.getResponseHeaders().set("Content-Type", response.contentType());
        applyCorsHeaders(exchange);
        for (Map.Entry<String, String> header : response.headers().entrySet()) {
            exchange.getResponseHeaders().set(header.getKey(), header.getValue());
        }
        byte[] body = response.body();
        exchange.sendResponseHeaders(response.status(), body.length);
        try (var os = exchange.getResponseBody()) {
            os.write(body);
        }
    }

    private boolean isApi(HttpExchange exchange) {
        String path = exchange.getRequestURI() != null ? exchange.getRequestURI().getPath() : "";
        return path != null && path.startsWith("/api/");
    }

    private boolean originAllowed(HttpExchange exchange) {
        if (!isApi(exchange)) {
            return true;
        }
        String origin = exchange.getRequestHeaders().getFirst("Origin");
        if (origin != null && !origin.isBlank()) {
            return trustedOrigins().contains(normalizeOrigin(origin));
        }
        String method = exchange.getRequestMethod();
        String referer = exchange.getRequestHeaders().getFirst("Referer");
        if (referer != null && !referer.isBlank()
                && ("POST".equalsIgnoreCase(method) || "PUT".equalsIgnoreCase(method) || "PATCH".equalsIgnoreCase(method) || "DELETE".equalsIgnoreCase(method))) {
            return trustedOrigins().stream().anyMatch(originValue -> normalizeOrigin(referer).startsWith(originValue));
        }
        if (origin == null || origin.isBlank()) {
            return true;
        }
        return false;
    }

    private void applyCorsHeaders(HttpExchange exchange) {
        String origin = exchange.getRequestHeaders().getFirst("Origin");
        if (origin == null || origin.isBlank() || !trustedOrigins().contains(normalizeOrigin(origin))) {
            return;
        }
        exchange.getResponseHeaders().set("Access-Control-Allow-Origin", normalizeOrigin(origin));
        exchange.getResponseHeaders().set("Access-Control-Allow-Credentials", "true");
        exchange.getResponseHeaders().set("Access-Control-Allow-Headers", "Content-Type, X-Paradigm-CSRF");
        exchange.getResponseHeaders().set("Access-Control-Allow-Methods", "GET,POST,OPTIONS");
        exchange.getResponseHeaders().add("Vary", "Origin");
    }

    private Set<String> trustedOrigins() {
        Set<String> origins = new HashSet<>();
        origins.add(normalizeOrigin(config.localBaseUrl()));
        origins.add("http://127.0.0.1:" + config.port);
        origins.add("http://localhost:" + config.port);
        if (config.publicBaseUrl != null && !config.publicBaseUrl.isBlank()) {
            origins.add(normalizeOrigin(config.publicBaseUrl));
        }
        if (config.allowedOrigins != null) {
            for (String origin : config.allowedOrigins) {
                if (origin != null && !origin.isBlank()) {
                    origins.add(normalizeOrigin(origin));
                }
            }
        }
        return origins;
    }

    private static String normalizeOrigin(String origin) {
        return origin != null ? origin.trim().replaceAll("/+$", "") : "";
    }

    private boolean rateAllowed(HttpExchange exchange) {
        String remote = exchange.getRemoteAddress() != null ? exchange.getRemoteAddress().getAddress().getHostAddress() : "unknown";
        long minute = System.currentTimeMillis() / 60_000L;
        Window window = rateLimits.compute(remote, (key, old) -> old == null || old.minute != minute ? new Window(minute, 0) : old);
        synchronized (window) {
            window.count++;
            return window.count <= Math.max(10, config.rateLimitPerMinute);
        }
    }

    @Override
    public void close() {
        running = false;
        if (server != null) {
            server.stop(1);
            server = null;
        }
        if (httpExecutor != null) {
            httpExecutor.shutdownNow();
            httpExecutor = null;
        }
    }

    private static final class Window {
        private final long minute;
        private int count;

        private Window(long minute, int count) {
            this.minute = minute;
            this.count = count;
        }
    }
}
