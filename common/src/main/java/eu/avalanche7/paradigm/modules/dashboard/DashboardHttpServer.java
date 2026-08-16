package eu.avalanche7.paradigm.modules.dashboard;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.URI;
import java.util.HashSet;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicLong;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;

public class DashboardHttpServer implements AutoCloseable {
    private final DashboardService dashboard;
    private final DashboardConfig config;
    private final DashboardRouter router;
    private final Map<String, Window> rateLimits = new ConcurrentHashMap<>();
    private final AtomicLong lastRateCleanupMinute = new AtomicLong(-1L);
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

        Set<String> trusted = trustedOrigins();
        String origin = exchange.getRequestHeaders().getFirst("Origin");
        if (origin != null && !origin.isBlank()) {
            String normalizedOrigin = normalizeOrigin(origin);
            return !normalizedOrigin.isBlank() && trusted.contains(normalizedOrigin);
        }

        String method = exchange.getRequestMethod();
        String referer = exchange.getRequestHeaders().getFirst("Referer");
        if (referer != null && !referer.isBlank() && isMutationMethod(method)) {
            String refererOrigin = normalizeOrigin(referer);
            return !refererOrigin.isBlank() && trusted.contains(refererOrigin);
        }

        return true;
    }

    private void applyCorsHeaders(HttpExchange exchange) {
        String origin = exchange.getRequestHeaders().getFirst("Origin");
        String normalizedOrigin = normalizeOrigin(origin);
        if (normalizedOrigin.isBlank() || !trustedOrigins().contains(normalizedOrigin)) {
            return;
        }
        exchange.getResponseHeaders().set("Access-Control-Allow-Origin", normalizedOrigin);
        exchange.getResponseHeaders().set("Access-Control-Allow-Credentials", "true");
        exchange.getResponseHeaders().set("Access-Control-Allow-Headers", "Content-Type, X-Paradigm-CSRF");
        exchange.getResponseHeaders().set("Access-Control-Allow-Methods", "GET,POST,PUT,PATCH,DELETE,OPTIONS");
        exchange.getResponseHeaders().add("Vary", "Origin");
    }

    private Set<String> trustedOrigins() {
        Set<String> origins = new HashSet<>();
        addOrigin(origins, config.localBaseUrl());
        addOrigin(origins, "http://127.0.0.1:" + config.port);
        addOrigin(origins, "http://localhost:" + config.port);
        addOrigin(origins, "http://[::1]:" + config.port);
        if (config.publicBaseUrl != null && !config.publicBaseUrl.isBlank()) {
            addOrigin(origins, config.publicBaseUrl);
        }
        if (config.allowedOrigins != null) {
            for (String origin : config.allowedOrigins) {
                addOrigin(origins, origin);
            }
        }
        return origins;
    }

    private static void addOrigin(Set<String> origins, String rawOrigin) {
        String normalized = normalizeOrigin(rawOrigin);
        if (!normalized.isBlank()) {
            origins.add(normalized);
        }
    }

    private static String normalizeOrigin(String origin) {
        if (origin == null || origin.isBlank()) {
            return "";
        }
        try {
            URI uri = URI.create(origin.trim());
            String scheme = uri.getScheme();
            String host = uri.getHost();
            if (scheme == null || host == null) {
                return "";
            }
            String normalizedHost = host.toLowerCase(Locale.ROOT);
            if (normalizedHost.indexOf(':') >= 0) {
                normalizedHost = "[" + normalizedHost + "]";
            }
            int port = uri.getPort();
            return scheme.toLowerCase(Locale.ROOT) + "://" + normalizedHost + (port >= 0 ? ":" + port : "");
        } catch (IllegalArgumentException ignored) {
            return "";
        }
    }

    private static boolean isMutationMethod(String method) {
        return "POST".equalsIgnoreCase(method)
                || "PUT".equalsIgnoreCase(method)
                || "PATCH".equalsIgnoreCase(method)
                || "DELETE".equalsIgnoreCase(method);
    }

    private boolean rateAllowed(HttpExchange exchange) {
        String remote = exchange.getRemoteAddress() != null && exchange.getRemoteAddress().getAddress() != null
                ? exchange.getRemoteAddress().getAddress().getHostAddress()
                : "unknown";
        long minute = System.currentTimeMillis() / 60_000L;
        cleanupRateLimits(minute);

        Window window = rateLimits.compute(remote,
                (key, old) -> old == null || old.minute != minute ? new Window(minute, 0) : old);
        synchronized (window) {
            window.count++;
            return window.count <= Math.max(10, config.rateLimitPerMinute);
        }
    }

    private void cleanupRateLimits(long minute) {
        long previous = lastRateCleanupMinute.get();
        if (previous == minute || !lastRateCleanupMinute.compareAndSet(previous, minute)) {
            return;
        }
        rateLimits.entrySet().removeIf(entry -> entry.getValue() == null || entry.getValue().minute < minute - 1L);
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
        rateLimits.clear();
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
