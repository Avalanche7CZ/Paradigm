package eu.avalanche7.paradigm.modules.discord;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.junit.jupiter.api.io.TempDir;

import eu.avalanche7.paradigm.configs.DiscordConfigHandler;
import eu.avalanche7.paradigm.modules.discord.client.DiscordRateLimiter;
import eu.avalanche7.paradigm.modules.discord.client.DiscordRestClient;
import eu.avalanche7.paradigm.modules.tickets.SqlTicketRepository;
import eu.avalanche7.paradigm.modules.tickets.Ticket;
import eu.avalanche7.paradigm.modules.tickets.TicketCreate;
import eu.avalanche7.paradigm.modules.tickets.TicketEvent;
import eu.avalanche7.paradigm.modules.tickets.TicketEventType;
import eu.avalanche7.paradigm.modules.tickets.TicketMutation;
import eu.avalanche7.paradigm.modules.tickets.TicketPriority;
import eu.avalanche7.paradigm.modules.tickets.TicketStatus;
import eu.avalanche7.paradigm.storage.StorageConfig;
import eu.avalanche7.paradigm.storage.StorageProviderType;
import eu.avalanche7.paradigm.storage.identity.ServerIdentity;
import eu.avalanche7.paradigm.storage.identity.StorageContext;
import eu.avalanche7.paradigm.storage.mysql.MysqlDialect;
import eu.avalanche7.paradigm.storage.sql.SqlConnectionProvider;
import eu.avalanche7.paradigm.storage.sql.SqlDialect;
import eu.avalanche7.paradigm.storage.sql.SqlExecutor;
import eu.avalanche7.paradigm.storage.sql.SqlMigrationRunner;
import eu.avalanche7.paradigm.storage.sqlite.SqliteDialect;

class DiscordTicketRoomsTest {
    private static final String GUILD = "100000000000000001";
    private static final String CATEGORY = "100000000000000002";
    private static final String BOT = "100000000000000004";
    private static final String STAFF = "100000000000000005";
    private static final String CREATOR = "100000000000000006";
    private static final String REPLACEMENT = "100000000000000007";
    private static final long VIEW = 1L << 10;
    private static final long SEND = 1L << 11;
    private static final DiscordTicketRooms.Settings SETTINGS =
            new DiscordTicketRooms.Settings(GUILD, CATEGORY, List.of(STAFF), "ticket-{ticket_key}", true);

    @TempDir Path directory;
    private final String network = UUID.randomUUID().toString();
    private final UUID player = UUID.randomUUID();
    private final long started = System.currentTimeMillis();
    private final List<String> warnings = new ArrayList<>();

    SqlDialect dialect() {
        return new SqliteDialect();
    }

    StorageConfig config() {
        StorageConfig config = new StorageConfig();
        config.runtimeLibraries.enabled = false;
        config.sqlite.path = directory.resolve("tickets.db").toString();
        return config;
    }

    @Test
    void crossServerOwnerCreatesOnlyNewLinkedTicketsWithPrivateInitialPermissions() throws Exception {
        try (var database = connect(); var satellite = connect(); var api = new FakeDiscord()) {
            var writer = repository(satellite, "satellite");
            var unlinkedPlayer = UUID.randomUUID();
            var owner = worker(database, api);
            var config = new DiscordConfigHandler.Config();
            config.enabled.value = true;
            config.linkingEnabled.value = true;
            config.linkingOwnerServerId.value = "owner";
            config.guildId.value = GUILD;
            var satelliteSettings = DiscordService.linkingSettings(config, StorageProviderType.MYSQL, "satellite");
            var ownerSettings = DiscordService.linkingSettings(config, StorageProviderType.MYSQL, "owner");
            link(links(satellite), player, CREATOR);
            create(writer, "T-2", player, started - 1);
            create(writer, "T-3", unlinkedPlayer, started + 2_000);
            create(writer, "T-1", player, started + 2_000);

            owner.sweep(SETTINGS, BOT, started, satelliteSettings::owner);
            assertEquals(0, api.creates);
            owner.sweep(SETTINGS, BOT, started, ownerSettings::owner);
            assertEquals(1, api.creates);
            assertEquals("ticket-t-1", api.initial.get("name").getAsString());
            assertEquals(CATEGORY, api.initial.get("parent_id").getAsString());
            assertEquals(4, api.initial.getAsJsonArray("permission_overwrites").size());
            assertEquals(VIEW, bit(api.initial, GUILD, "deny") & VIEW);
            assertEquals(0, bit(api.initial, GUILD, "allow") & VIEW);
            assertEquals(VIEW | SEND, bit(api.initial, CREATOR, "allow") & (VIEW | SEND));
            assertEquals(VIEW | SEND, bit(api.initial, STAFF, "allow") & (VIEW | SEND));
            assertEquals(VIEW | SEND, bit(api.initial, BOT, "allow") & (VIEW | SEND));
            String opening = api.opening.get("content").getAsString();
            assertTrue(opening.contains("T-1") && opening.contains("support") && opening.contains("Remote Hub"));
            assertTrue(opening.contains("Text replies sync with Minecraft."));
            assertTrue(opening.contains("Link your Minecraft account before replying here."));
            assertFalse(opening.contains("private ticket body"));
            link(links(satellite), unlinkedPlayer, REPLACEMENT);
            worker(database, api).sweep(SETTINGS, BOT, started + 10_000, ownerSettings::owner);
            assertEquals(1, api.creates);
            assertEquals(1, api.messages);
        }
    }

    @Test
    void uncertainCreationUsesMarkerAcrossRestartAndDoesNotUseTheChannelName() throws Exception {
        try (var database = connect(); var restarted = connect(); var api = new FakeDiscord()) {
            link(links(database), player, CREATOR);
            create(repository(database, "satellite"), "T-1", player, started + 2_000);
            api.createStatus = 503;
            worker(database, api).sweep(SETTINGS, BOT, started, () -> true);
            assertEquals(1, api.creates);
            assertEquals(0, api.messages);
            api.remote.addProperty("name", "renamed-by-staff");
            resetRetries(database);
            worker(restarted, api).sweep(SETTINGS, BOT, started + 3_000, () -> true);
            assertEquals(1, api.creates);
            assertEquals(1, api.lists);
            assertEquals(1, api.messages);
            assertTrue(api.paths.contains("PATCH /channels/" + api.channel));
        }
    }

    @Test
    void missingMarkerAfterUnknownOutcomeDoesNotTriggerAnotherCreate() throws Exception {
        try (var database = connect(); var api = new FakeDiscord()) {
            link(links(database), player, CREATOR);
            create(repository(database, "satellite"), "T-1", player, started + 2_000);
            api.createStatus = 503;
            var worker = worker(database, api);
            worker.sweep(SETTINGS, BOT, started, () -> true);
            api.remote.addProperty("topic", "unrelated channel");
            resetRetries(database);
            worker.sweep(SETTINGS, BOT, started, () -> true);
            assertEquals(1, api.creates);
            assertEquals(1, api.lists);
            assertEquals(0, api.messages);
            assertTrue(warnings.stream().anyMatch(warning -> warning.contains("reation")));
        }
    }

    @Test
    void closeDuringCreateReopenAndRelinkUseCurrentAuthorization() throws Exception {
        try (var database = connect(); var satellite = connect(); var api = new FakeDiscord()) {
            var links = links(satellite);
            link(links, player, CREATOR);
            var writer = repository(satellite, "satellite");
            Ticket ticket = create(writer, "T-1", player, started + 2_000);
            api.afterCreate = () -> status(writer, ticket, TicketStatus.CLOSED);
            var worker = worker(database, api);
            worker.sweep(SETTINGS, BOT, started, () -> true);
            assertEquals(0, bit(api.patched, CREATOR, "allow") & SEND);
            assertEquals(SEND, bit(api.patched, STAFF, "deny") & SEND);

            status(writer, ticket, TicketStatus.OPEN);
            worker.sweep(SETTINGS, BOT, started, () -> true);
            assertEquals(SEND, bit(api.patched, CREATOR, "allow") & SEND);
            assertEquals(SEND, bit(api.patched, STAFF, "allow") & SEND);

            assertTrue(links.unlink(player));
            link(links, player, REPLACEMENT);
            worker.sweep(SETTINGS, BOT, started, () -> true);
            int removal = api.paths.indexOf("DELETE /channels/" + api.channel + "/permissions/" + CREATOR);
            assertTrue(removal >= 0 && removal < api.paths.lastIndexOf("PATCH /channels/" + api.channel));
            assertFalse(contains(api.patched, CREATOR));
            assertEquals(VIEW, bit(api.patched, REPLACEMENT, "allow") & VIEW);
            assertTrue(links.unlink(player));
            worker.sweep(SETTINGS, BOT, started, () -> true);
            assertFalse(contains(api.patched, REPLACEMENT));
            assertEquals(VIEW, bit(api.patched, STAFF, "allow") & VIEW);
            assertEquals(1, api.creates);
        }
    }

    @Test
    void definitivelyRejectedCreateCanRetryWithoutChangingTheMinecraftTicket() throws Exception {
        try (var database = connect(); var api = new FakeDiscord()) {
            link(links(database), player, CREATOR);
            var writer = repository(database, "satellite");
            Ticket ticket = create(writer, "T-1", player, started + 2_000);
            api.createStatus = 403;
            var worker = worker(database, api);
            worker.sweep(SETTINGS, BOT, started, () -> true);
            assertEquals(1, api.creates);
            assertEquals(0, api.messages);
            assertEquals(TicketStatus.OPEN, writer.findById(network, ticket.ticketId()).orElseThrow().status());
            api.createStatus = 201;
            resetRetries(database);
            worker.sweep(SETTINGS, BOT, started, () -> true);
            assertEquals(2, api.creates);
            assertEquals(1, api.messages);
            assertEquals(0, api.lists);
        }
    }

    @Test
    void ticketConfigurationRejectsMissingCategoryAndEveryoneStaffRole() {
        var config = new DiscordConfigHandler.Config();
        assertFalse(config.ticketRoomsEnabled.get());
        DiscordConfigHandler.validateTicketRooms(config);
        config.enabled.value = true;
        config.linkingEnabled.value = true;
        config.guildId.value = GUILD;
        config.ticketRoomsEnabled.value = true;
        assertThrows(IllegalArgumentException.class, () -> DiscordConfigHandler.validateTicketRooms(config));
        config.ticketCategoryId.value = CATEGORY;
        config.ticketStaffRoleIds.value = List.of(GUILD);
        assertThrows(IllegalArgumentException.class, () -> DiscordConfigHandler.validateTicketRooms(config));
        config.ticketStaffRoleIds.value = List.of(STAFF);
        DiscordConfigHandler.validateTicketRooms(config);
    }

    @Test
    void namesUseOriginCategoryAndPlayerAndFallBackIfUnusable() {
        Ticket ticket = ticket("T-1", player, started);
        assertEquals("help-remote-hub-support-player-one-t-1",
                DiscordTicketRooms.name("Help_{server}-{category}-{player}-{ticket_key}", ticket, "Remote Hub"));
        assertEquals("ticket-t-1", DiscordTicketRooms.name("⚠{unknown}", ticket, "Remote Hub"));
        assertEquals("ticket-t-1", DiscordTicketRooms.name("🚀", ticket, "Remote Hub"));
        assertEquals("ticket-t-1", DiscordTicketRooms.name("x".repeat(101), ticket, "Remote Hub"));
    }

    private SqlConnectionProvider connect() {
        var connection = new SqlConnectionProvider(config(), dialect(), null);
        new SqlMigrationRunner(connection, null).runAvailableMigrations();
        return connection;
    }

    private SqlTicketRepository repository(SqlConnectionProvider connection, String server) {
        return new SqlTicketRepository(new SqlExecutor(connection), new StorageContext(new ServerIdentity(network, server, server)));
    }

    private DiscordTicketRooms worker(SqlConnectionProvider connection, FakeDiscord api) {
        return new DiscordTicketRooms(new DiscordTicketRoomStore(new SqlExecutor(connection), network, repository(connection, "owner")),
                links(connection), api.client, ticket -> "satellite".equals(ticket.originServerId()) ? "Remote Hub" : "Owner",
                warnings::add);
    }

    private DiscordAccountLinks links(SqlConnectionProvider connection) {
        return new DiscordAccountLinks(new SqlExecutor(connection), network);
    }

    private static void link(DiscordAccountLinks links, UUID player, String discordId) {
        assertEquals(DiscordAccountLinks.Claim.LINKED, links.claim(discordId, GUILD, links.issue(player, GUILD).orElseThrow()));
    }

    private Ticket ticket(String key, UUID creator, long time) {
        return new Ticket(UUID.randomUUID().toString(), key, network, "satellite", creator.toString(), "Player_One",
                "support", "private ticket body", TicketStatus.OPEN, TicketPriority.NORMAL, null, null,
                time, time, null, null, time, 0, Map.of());
    }

    private Ticket create(SqlTicketRepository repository, String key, UUID creator, long time) {
        Ticket ticket = ticket(key, creator, time);
        var event = new TicketEvent(UUID.randomUUID().toString(), ticket.ticketId(), network, key,
                TicketEventType.CREATED, ticket.creatorUuid(), ticket.creatorName(), ticket.originServerId(), null, null, time);
        assertTrue(repository.createTicket(new TicketCreate(ticket, null, event)).ok());
        return ticket;
    }

    private void status(SqlTicketRepository repository, Ticket ticket, TicketStatus state) {
        Ticket latest = repository.findById(network, ticket.ticketId()).orElseThrow();
        assertTrue(repository.applyMutation(TicketMutation.of(latest.toBuilder().status(state).build(),
                latest.revision(), null, List.of())).ok());
    }

    private void resetRetries(SqlConnectionProvider connection) {
        new SqlExecutor(connection).update("UPDATE discord_ticket_rooms SET retry_at_ms = 0 WHERE network_id = ?",
                stmt -> stmt.setString(1, network));
    }

    private static long bit(JsonObject payload, String id, String field) {
        for (var element : payload.getAsJsonArray("permission_overwrites")) {
            var overwrite = element.getAsJsonObject();
            if (id.equals(overwrite.get("id").getAsString())) {
                return overwrite.get(field).getAsLong();
            }
        }
        throw new AssertionError("Missing permission overwrite " + id);
    }

    private static boolean contains(JsonObject payload, String id) {
        for (var element : payload.getAsJsonArray("permission_overwrites")) {
            if (id.equals(element.getAsJsonObject().get("id").getAsString())) {
                return true;
            }
        }
        return false;
    }

    private static final class FakeDiscord implements AutoCloseable {
        private final HttpServer server;
        private final DiscordRestClient client;
        // Snowflake-like and distinct across schemas sharing the same guild fixture.
        private final String channel = "1" + Long.toUnsignedString(UUID.randomUUID().getMostSignificantBits());
        private final List<String> paths = new CopyOnWriteArrayList<>();
        private volatile JsonObject initial;
        private volatile JsonObject remote;
        private volatile JsonObject patched;
        private volatile JsonObject opening;
        private volatile Runnable afterCreate = () -> {};
        private volatile int createStatus = 201;
        private volatile int creates;
        private volatile int lists;
        private volatile int messages;

        FakeDiscord() throws IOException {
            server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
            server.createContext("/", exchange -> {
                try {
                    String method = exchange.getRequestMethod();
                    String path = exchange.getRequestURI().getPath();
                    paths.add(method + " " + path);
                    String input = new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
                    String response = "{}";
                    int status = 200;
                    if (path.equals("/guilds/" + GUILD + "/channels") && "POST".equals(method)) {
                        creates++;
                        initial = JsonParser.parseString(input).getAsJsonObject();
                        remote = initial.deepCopy();
                        remote.addProperty("id", channel);
                        remote.addProperty("guild_id", GUILD);
                        afterCreate.run();
                        status = createStatus;
                        response = remote.toString();
                        if (status >= 400 && status < 500) {
                            remote = null;
                        }
                    } else if (path.equals("/guilds/" + GUILD + "/channels") && "GET".equals(method)) {
                        lists++;
                        var channels = new JsonArray();
                        if (remote != null) {
                            channels.add(remote);
                        }
                        response = channels.toString();
                    } else if (path.equals("/channels/" + channel + "/messages") && "POST".equals(method)) {
                        messages++;
                        opening = JsonParser.parseString(input).getAsJsonObject();
                    } else if (path.equals("/channels/" + channel) && "PATCH".equals(method)) {
                        patched = JsonParser.parseString(input).getAsJsonObject();
                    } else if (path.startsWith("/channels/" + channel + "/permissions/") && "DELETE".equals(method)) {
                        exchange.sendResponseHeaders(204, -1);
                        return;
                    } else {
                        status = 404;
                    }
                    byte[] body = response.getBytes(StandardCharsets.UTF_8);
                    exchange.sendResponseHeaders(status, body.length);
                    exchange.getResponseBody().write(body);
                } finally {
                    exchange.close();
                }
            });
            server.start();
            client = new DiscordRestClient(() -> "fixture-token", new DiscordRateLimiter(), null, null,
                    "http://127.0.0.1:" + server.getAddress().getPort());
        }

        @Override public void close() {
            server.stop(0);
        }
    }
}

@EnabledIfEnvironmentVariable(named = "PARADIGM_REGRESSION_MARIADB", matches = "true")
class MariaDbDiscordTicketRoomsTest extends DiscordTicketRoomsTest {
    @Override SqlDialect dialect() {
        return new MysqlDialect();
    }

    @Override StorageConfig config() {
        var config = super.config();
        config.provider = "mysql";
        config.sql.host = "127.0.0.1";
        config.sql.port = 33317;
        config.sql.database = "paradigm_regression";
        config.sql.username = "paradigm_test";
        config.sql.password = "disposable-regression";
        return config;
    }
}
