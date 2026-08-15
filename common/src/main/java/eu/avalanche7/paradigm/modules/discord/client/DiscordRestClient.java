package eu.avalanche7.paradigm.modules.discord.client;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Executor;
import java.util.function.Supplier;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import eu.avalanche7.paradigm.modules.discord.DiscordSecrets;
import eu.avalanche7.paradigm.utils.DebugLogger;

public final class DiscordRestClient {
    public static final String API_BASE = "https://discord.com/api/v10";
    private static final Duration REQUEST_TIMEOUT = Duration.ofSeconds(15);
    private static final String USER_AGENT = "DiscordBot (https://github.com/Avalanche7CZ/Paradigm, 1.0)";

    private final Supplier<String> tokenSupplier;
    private final DiscordRateLimiter rateLimiter;
    private final DebugLogger debugLogger;
    private final HttpClient httpClient;
    private final String apiBase;

    public DiscordRestClient(Supplier<String> tokenSupplier, DiscordRateLimiter rateLimiter,
                             DebugLogger debugLogger, Executor executor) {
        this(tokenSupplier, rateLimiter, debugLogger, executor, API_BASE);
    }

    public DiscordRestClient(Supplier<String> tokenSupplier, DiscordRateLimiter rateLimiter,
                             DebugLogger debugLogger, Executor executor, String apiBase) {
        this.tokenSupplier = tokenSupplier;
        this.rateLimiter = rateLimiter;
        this.debugLogger = debugLogger;
        this.apiBase = apiBase;
        HttpClient.Builder builder = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(10))
                .followRedirects(HttpClient.Redirect.NEVER);
        if (executor != null) {
            builder.executor(executor);
        }
        this.httpClient = builder.build();
    }

    public HttpClient httpClient() {
        return httpClient;
    }

    public record Result(boolean success, int status, String errorMessage, boolean retryable) {
        public static Result ok(int status) {
            return new Result(true, status, null, false);
        }

        public static Result failure(int status, String message, boolean retryable) {
            return new Result(false, status, message, retryable);
        }
    }

    public record BotIdentity(String id, String username) {
    }

    public record Webhook(String id, String token, String applicationId, String name) {
    }

    public Result postMessage(String channelId, String payloadJson) {
        if (channelId == null || channelId.isBlank()) {
            return Result.failure(0, "No Discord channel configured for this destination.", false);
        }
        String token = tokenSupplier.get();
        if (!DiscordSecrets.isPresent(token)) {
            return Result.failure(0, "No Discord bot token configured.", false);
        }
        return post(channelId, apiBase + "/channels/" + channelId + "/messages", payloadJson, token);
    }

    public Result executeWebhook(String webhookId, String webhookToken, String payloadJson) {
        if (webhookId == null || webhookId.isBlank() || webhookToken == null || webhookToken.isBlank()) {
            return Result.failure(0, "No Discord webhook is available for this channel.", false);
        }
        return post(webhookId, apiBase + "/webhooks/" + webhookId + "/" + webhookToken + "?wait=true",
                payloadJson, null);
    }

    private Result post(String bucketKey, String uri, String payloadJson, String token) {
        try {
            rateLimiter.awaitSlot(bucketKey);
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            return Result.failure(0, "Interrupted while waiting for a Discord rate-limit slot.", false);
        }

        try {
            HttpRequest.Builder builder = HttpRequest.newBuilder()
                    .uri(URI.create(uri))
                    .header("Content-Type", "application/json")
                    .POST(HttpRequest.BodyPublishers.ofString(payloadJson, StandardCharsets.UTF_8));
            HttpRequest request = (token != null ? authorized(builder, token) : unauthorized(builder)).build();

            HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
            int status = response.statusCode();
            rateLimiter.observe(bucketKey, response.headers());

            if (status >= 200 && status < 300) {
                return Result.ok(status);
            }
            if (status == 429) {
                long retryAfter = retryAfterMillis(response.body());
                boolean global = response.headers().firstValue("x-ratelimit-global").isPresent();
                rateLimiter.observeRetryAfter(bucketKey, retryAfter, global);
                return Result.failure(status, "Rate limited by Discord; retrying in " + retryAfter + "ms.", true);
            }

            boolean retryable = status >= 500;
            return Result.failure(status, describe(status, response.body(), token), retryable);
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            return Result.failure(0, "Interrupted while sending a Discord message.", false);
        } catch (Exception failure) {
            String message = DiscordSecrets.redact(String.valueOf(failure.getMessage()), token);
            debug("REST send failed: " + message);
            return Result.failure(0, "Could not reach Discord: " + message, true);
        }
    }

    public List<Webhook> listWebhooks(String channelId) throws DiscordApiException {
        JsonArray body = getArray("/channels/" + channelId + "/webhooks");
        List<Webhook> webhooks = new ArrayList<>(body.size());
        for (JsonElement element : body) {
            if (element != null && element.isJsonObject()) {
                Webhook webhook = toWebhook(element.getAsJsonObject());
                if (webhook != null) {
                    webhooks.add(webhook);
                }
            }
        }
        return webhooks;
    }

    public Webhook createWebhook(String channelId, String name) throws DiscordApiException {
        JsonObject payload = new JsonObject();
        payload.addProperty("name", name);
        JsonObject body = postForObject("/channels/" + channelId + "/webhooks", payload.toString());
        Webhook webhook = toWebhook(body);
        if (webhook == null) {
            throw new DiscordApiException("Discord did not return a usable webhook.", false);
        }
        return webhook;
    }

    private static Webhook toWebhook(JsonObject json) {
        String id = DiscordJson.string(json, "id");
        if (id == null || id.isBlank()) {
            return null;
        }
        return new Webhook(id, DiscordJson.string(json, "token"),
                DiscordJson.string(json, "application_id"), DiscordJson.string(json, "name", ""));
    }

    public BotIdentity fetchBotIdentity() throws DiscordApiException {
        JsonObject body = get("/users/@me");
        String id = DiscordJson.string(body, "id");
        String username = DiscordJson.string(body, "username", "unknown");
        if (id == null) {
            throw new DiscordApiException("Discord did not return a bot identity.", false);
        }
        return new BotIdentity(id, username);
    }

    public String fetchGatewayUrl() throws DiscordApiException {
        JsonObject body = get("/gateway/bot");
        String url = DiscordJson.string(body, "url");
        if (url == null || url.isBlank()) {
            throw new DiscordApiException("Discord did not return a gateway URL.", true);
        }
        return url;
    }

    private JsonObject get(String path) throws DiscordApiException {
        return request(path, null, "GET").getAsJsonObject();
    }

    private JsonArray getArray(String path) throws DiscordApiException {
        JsonElement body = request(path, null, "GET");
        if (!body.isJsonArray()) {
            throw new DiscordApiException("Discord returned an unexpected response for " + path + ".", false);
        }
        return body.getAsJsonArray();
    }

    private JsonObject postForObject(String path, String payloadJson) throws DiscordApiException {
        JsonElement body = request(path, payloadJson, "POST");
        if (!body.isJsonObject()) {
            throw new DiscordApiException("Discord returned an unexpected response for " + path + ".", false);
        }
        return body.getAsJsonObject();
    }

    public JsonArray listGuildCommands(String guildId, String applicationId) throws DiscordApiException {
        return getArray("/applications/" + applicationId + "/guilds/" + guildId + "/commands");
    }

    public void upsertGuildCommand(String guildId, String applicationId, String commandJson) throws DiscordApiException {
        postForObject("/applications/" + applicationId + "/guilds/" + guildId + "/commands", commandJson);
    }

    public void deleteGuildCommand(String guildId, String applicationId, String commandId) throws DiscordApiException {
        request("/applications/" + applicationId + "/guilds/" + guildId + "/commands/" + commandId, null, "DELETE");
    }

    public Result respondToInteraction(String interactionId, String interactionToken, String payloadJson) {
        return post(interactionId, apiBase + "/interactions/" + interactionId + "/" + interactionToken + "/callback",
                payloadJson, null);
    }

    private JsonElement request(String path, String payloadJson, String method) throws DiscordApiException {
        String token = tokenSupplier.get();
        if (!DiscordSecrets.isPresent(token)) {
            throw new DiscordApiException("No Discord bot token configured.", false);
        }
        String bucketKey = "rest:" + method + ":" + path;
        try {
            rateLimiter.awaitSlot(bucketKey);
            HttpRequest.Builder builder = HttpRequest.newBuilder().uri(URI.create(apiBase + path));
            if ("DELETE".equals(method)) {
                builder.DELETE();
            } else if (payloadJson != null) {
                builder.header("Content-Type", "application/json")
                        .method(method, HttpRequest.BodyPublishers.ofString(payloadJson, StandardCharsets.UTF_8));
            } else {
                builder.GET();
            }
            HttpRequest request = authorized(builder, token).build();
            HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
            int status = response.statusCode();
            rateLimiter.observe(bucketKey, response.headers());
            if (status == 401 || status == 403) {
                throw new DiscordApiException("Discord rejected the request (HTTP " + status + ").", false, status);
            }
            if (status == 429) {
                long retryAfter = retryAfterMillis(response.body());
                boolean global = response.headers().firstValue("x-ratelimit-global").isPresent();
                rateLimiter.observeRetryAfter(bucketKey, retryAfter, global);
                throw new DiscordApiException("Rate limited by Discord; retrying in " + retryAfter + "ms.", true, status);
            }
            if (status < 200 || status >= 300) {
                throw new DiscordApiException(describe(status, response.body(), token),
                        status >= 500, status);
            }
            String body = response.body();
            return body == null || body.isBlank() ? com.google.gson.JsonNull.INSTANCE : JsonParser.parseString(body);
        } catch (DiscordApiException already) {
            throw already;
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            throw new DiscordApiException("Interrupted while contacting Discord.", false);
        } catch (Exception failure) {
            throw new DiscordApiException("Could not reach Discord: "
                    + DiscordSecrets.redact(String.valueOf(failure.getMessage()), token), true);
        }
    }

    private HttpRequest.Builder authorized(HttpRequest.Builder builder, String token) {
        return unauthorized(builder).header("Authorization", "Bot " + token);
    }

    private HttpRequest.Builder unauthorized(HttpRequest.Builder builder) {
        return builder
                .timeout(REQUEST_TIMEOUT)
                .header("User-Agent", USER_AGENT);
    }

    private static long retryAfterMillis(String body) {
        try {
            JsonObject json = JsonParser.parseString(body).getAsJsonObject();
            double seconds = DiscordJson.doubleValue(json, "retry_after", 1.0d);
            return (long) Math.ceil(seconds * 1000.0d);
        } catch (RuntimeException unparsable) {
            return 1000L;
        }
    }

    private static String describe(int status, String body, String token) {
        String message = null;
        try {
            JsonObject json = JsonParser.parseString(body).getAsJsonObject();
            message = DiscordJson.string(json, "message");
        } catch (RuntimeException unparsable) {
        }
        String detail = message != null && !message.isBlank() ? message : "HTTP " + status;
        return DiscordSecrets.redact("Discord rejected the request: " + detail + " (HTTP " + status + ")", token);
    }

    private void debug(String message) {
        if (debugLogger != null) {
            debugLogger.debugLog("[Discord] " + message);
        }
    }

    public static final class DiscordApiException extends Exception {
        private final boolean retryable;
        private final int status;

        public DiscordApiException(String message, boolean retryable) {
            this(message, retryable, 0);
        }

        public DiscordApiException(String message, boolean retryable, int status) {
            super(message);
            this.retryable = retryable;
            this.status = status;
        }

        public boolean isRetryable() {
            return retryable;
        }

        public int status() {
            return status;
        }
    }
}
