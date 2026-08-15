package eu.avalanche7.paradigm.modules.discord.client;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.Test;

class DiscordRestClientRateLimitTest {
    @Test
    void managementRequestsObserveDiscordBucketHeaders() throws Exception {
        try (TestEndpoint endpoint = new TestEndpoint(200, "[]")) {
            endpoint.addHeader("X-RateLimit-Remaining", "0");
            endpoint.addHeader("X-RateLimit-Reset-After", "0.5");
            RecordingClock clock = new RecordingClock();
            DiscordRestClient client = endpoint.client(new DiscordRateLimiter(clock));

            client.listWebhooks("123");
            client.listWebhooks("123");

            assertEquals(List.of(500L), clock.sleeps);
        }
    }

    @Test
    void managementRequestsRecordDiscordRetryAfter() throws Exception {
        try (TestEndpoint endpoint = new TestEndpoint(429, "{\"retry_after\":0.75}")) {
            endpoint.addHeader("X-RateLimit-Global", "true");
            RecordingClock clock = new RecordingClock();
            DiscordRateLimiter limiter = new DiscordRateLimiter(clock);
            DiscordRestClient client = endpoint.client(limiter);

            assertThrows(DiscordRestClient.DiscordApiException.class, () -> client.listWebhooks("123"));

            assertEquals(1_750L, limiter.globalResumeAtMillis());
        }
    }

    private static final class RecordingClock implements DiscordRateLimiter.Clock {
        private final List<Long> sleeps = new ArrayList<>();
        private long now = 1_000L;

        @Override
        public long nowMillis() {
            return now;
        }

        @Override
        public void sleep(long millis) {
            sleeps.add(millis);
            now += millis;
        }
    }

    private static final class TestEndpoint implements AutoCloseable {
        private final HttpServer server;
        private final ExecutorService executor = Executors.newSingleThreadExecutor();
        private final List<Header> headers = new ArrayList<>();

        private TestEndpoint(int status, String body) throws IOException {
            server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
            server.createContext("/channels/123/webhooks", exchange -> respond(exchange, status, body));
            server.start();
        }

        private void addHeader(String name, String value) {
            headers.add(new Header(name, value));
        }

        private DiscordRestClient client(DiscordRateLimiter limiter) {
            return new DiscordRestClient(() -> "test-token", limiter, null, executor,
                    "http://127.0.0.1:" + server.getAddress().getPort());
        }

        private void respond(HttpExchange exchange, int status, String body) throws IOException {
            byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
            for (Header header : headers) {
                exchange.getResponseHeaders().add(header.name, header.value);
            }
            exchange.sendResponseHeaders(status, bytes.length);
            exchange.getResponseBody().write(bytes);
            exchange.close();
        }

        @Override
        public void close() {
            server.stop(0);
            executor.shutdownNow();
        }

        private record Header(String name, String value) {
        }
    }
}
