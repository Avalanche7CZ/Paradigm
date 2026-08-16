package eu.avalanche7.paradigm.modules.dashboard;

import java.io.FilterInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import com.sun.net.httpserver.HttpExchange;

import eu.avalanche7.paradigm.modules.dashboard.auth.DashboardPrincipal;
import eu.avalanche7.paradigm.modules.dashboard.auth.DashboardSession;

public class DashboardRequestContext {
    static final long MAX_BODY_BYTES = 16L * 1024L * 1024L;

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
        return new InputStreamReader(new LimitedInputStream(exchange.getRequestBody(), MAX_BODY_BYTES), StandardCharsets.UTF_8);
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

    static boolean bodyTooLarge(HttpExchange exchange) {
        if (exchange == null) {
            return false;
        }
        String raw = exchange.getRequestHeaders().getFirst("Content-Length");
        if (raw == null || raw.isBlank()) {
            return false;
        }
        try {
            return Long.parseLong(raw.trim()) > MAX_BODY_BYTES;
        } catch (NumberFormatException ignored) {
            return false;
        }
    }

    static boolean causedByPayloadTooLarge(Throwable throwable) {
        Throwable current = throwable;
        while (current != null) {
            if (current instanceof PayloadTooLargeException) {
                return true;
            }
            current = current.getCause();
        }
        return false;
    }

    private static String decode(String value) {
        return URLDecoder.decode(value != null ? value : "", StandardCharsets.UTF_8);
    }

    static final class PayloadTooLargeException extends IOException {
        private PayloadTooLargeException() {
            super("Dashboard request body exceeds " + MAX_BODY_BYTES + " bytes.");
        }
    }

    private static final class LimitedInputStream extends FilterInputStream {
        private long remaining;

        private LimitedInputStream(InputStream input, long maxBytes) {
            super(input);
            this.remaining = Math.max(0L, maxBytes);
        }

        @Override
        public int read() throws IOException {
            if (remaining == 0L) {
                if (super.read() == -1) {
                    return -1;
                }
                throw new PayloadTooLargeException();
            }
            int value = super.read();
            if (value != -1) {
                remaining--;
            }
            return value;
        }

        @Override
        public int read(byte[] buffer, int offset, int length) throws IOException {
            if (length == 0) {
                return 0;
            }
            if (remaining == 0L) {
                if (super.read() == -1) {
                    return -1;
                }
                throw new PayloadTooLargeException();
            }
            int boundedLength = (int) Math.min(length, remaining);
            int read = super.read(buffer, offset, boundedLength);
            if (read > 0) {
                remaining -= read;
            }
            return read;
        }
    }
}
