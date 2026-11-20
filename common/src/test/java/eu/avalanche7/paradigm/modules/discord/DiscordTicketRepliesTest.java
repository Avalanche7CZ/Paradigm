package eu.avalanche7.paradigm.modules.discord;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.function.BiFunction;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.junit.jupiter.api.io.TempDir;

import eu.avalanche7.paradigm.configs.TicketsConfigHandler;
import eu.avalanche7.paradigm.modules.discord.client.DiscordInboundMessage;
import eu.avalanche7.paradigm.modules.discord.client.DiscordRateLimiter;
import eu.avalanche7.paradigm.modules.discord.client.DiscordRestClient;
import eu.avalanche7.paradigm.modules.permissions.ParadigmPermissions;
import eu.avalanche7.paradigm.modules.tickets.SqlTicketRepository;
import eu.avalanche7.paradigm.modules.tickets.Ticket;
import eu.avalanche7.paradigm.modules.tickets.TicketActor;
import eu.avalanche7.paradigm.modules.tickets.TicketAuthorType;
import eu.avalanche7.paradigm.modules.tickets.TicketCreate;
import eu.avalanche7.paradigm.modules.tickets.TicketIds;
import eu.avalanche7.paradigm.modules.tickets.TicketMessage;
import eu.avalanche7.paradigm.modules.tickets.TicketMutation;
import eu.avalanche7.paradigm.modules.tickets.TicketPriority;
import eu.avalanche7.paradigm.modules.tickets.TicketService;
import eu.avalanche7.paradigm.modules.tickets.TicketStatus;
import eu.avalanche7.paradigm.modules.tickets.TicketWriteResult;
import eu.avalanche7.paradigm.storage.StorageConfig;
import eu.avalanche7.paradigm.storage.identity.ServerIdentity;
import eu.avalanche7.paradigm.storage.identity.StorageContext;
import eu.avalanche7.paradigm.storage.mysql.MysqlDialect;
import eu.avalanche7.paradigm.storage.sql.SqlConnectionProvider;
import eu.avalanche7.paradigm.storage.sql.SqlDialect;
import eu.avalanche7.paradigm.storage.sql.SqlExecutor;
import eu.avalanche7.paradigm.storage.sql.SqlMigrationRunner;
import eu.avalanche7.paradigm.storage.sqlite.SqliteDialect;

class DiscordTicketRepliesTest {
    private static final String GUILD = "110000000000000001";
    private static final String CREATOR = "110000000000000002";
    private static final String STAFF = "110000000000000003";
    private static final String OTHER = "110000000000000004";
    private static final String ROLES = "room permissions";
    private static final String STAFF_PERMISSION = "paradigm.ticket.category.support";
    private static final DiscordService.LinkingSettings OWNER = new DiscordService.LinkingSettings(GUILD, true);
    private static final DiscordService.LinkingSettings SATELLITE = new DiscordService.LinkingSettings(GUILD, false);

    @TempDir Path directory;
    private final String network = UUID.randomUUID().toString();
    private final UUID creator = UUID.randomUUID();
    private final UUID staff = UUID.randomUUID();
    private final String channel = "1" + Long.toUnsignedString(UUID.randomUUID().getLeastSignificantBits());

    SqlDialect dialect() {
        return new SqliteDialect();
    }

    StorageConfig config() {
        var config = new StorageConfig();
        config.runtimeLibraries.enabled = false;
        config.sqlite.path = directory.resolve("replies.db").toString();
        return config;
    }

    @Test
    void onlyOwnerDeliversFreshVisibleRepliesFromSharedWritersAndKeepsReceiptsOnRestart() throws Exception {
        try (var owner = connect("owner"); var satellite = connect("satellite"); var discord = new FakeDiscord()) {
            Ticket ticket = create(satellite);
            assertTrue(satellite.tickets.reply(actor(creator, false), ticket.ticketKey(), "reply before binding", null).ok());
            bind(owner, ticket);
            assertTrue(satellite.tickets.reply(actor(staff, true), ticket.ticketKey(), "reply from dashboard", null).ok());
            append(satellite, ticket, TicketAuthorType.SYSTEM, "internal note");

            discord.worker(satellite).deliver(SATELLITE, ROLES, () -> true);
            assertTrue(discord.messages.isEmpty());
            discord.worker(owner).deliver(OWNER, ROLES, () -> true);
            try (var restarted = connect("owner")) {
                discord.worker(restarted).deliver(OWNER, ROLES, () -> true);
            }
            assertEquals(List.of("Staff: reply from dashboard"), discord.texts());
            assertEquals(List.of("/channels/" + channel + "/messages"), discord.paths);
            assertTrue(discord.messages.get(0).getAsJsonObject("allowed_mentions").getAsJsonArray("parse").isEmpty());
        }
    }

    @Test
    void acceptsLinkedCreatorsAndAuthorizedStaffOnceWithoutEchoOrSatelliteWrites() throws Exception {
        try (var owner = connect("owner"); var satellite = connect("satellite"); var discord = new FakeDiscord()) {
            Ticket ticket = create(satellite);
            bind(owner, ticket);
            link(satellite, creator, CREATOR);
            link(satellite, staff, STAFF);
            var playerReply = message("110000000000001001", CREATOR, "player from Discord");
            var staffReply = message("110000000000001002", STAFF, "staff from Discord");

            assertTrue(handle(satellite, discord, SATELLITE, playerReply));
            assertEquals(1, messages(satellite, ticket).size());
            assertTrue(handle(owner, discord, OWNER, playerReply));
            assertTrue(handle(owner, discord, OWNER, staffReply));
            try (var restarted = connect("owner")) {
                assertTrue(handle(restarted, discord, OWNER, playerReply));
                discord.worker(restarted).deliver(OWNER, ROLES, () -> true);
            }
            var saved = messages(satellite, ticket);
            assertEquals(3, saved.size());
            assertTrue(saved.stream().anyMatch(m -> m.messageId().equals(TicketIds.discordReplyId(playerReply.messageId()))
                    && m.authorUuid().equals(creator.toString()) && m.authorType() == TicketAuthorType.PLAYER
                    && m.text().equals(playerReply.content())));
            assertTrue(saved.stream().anyMatch(m -> m.messageId().equals(TicketIds.discordReplyId(staffReply.messageId()))
                    && m.authorUuid().equals(staff.toString()) && m.authorType() == TicketAuthorType.STAFF));
            assertTrue(discord.messages.isEmpty());
        }
    }

    @Test
    void rejectsUnlinkedUnauthorizedAndClosedRepliesAndIgnoresBotsWebhooksAndOtherChannels() throws Exception {
        try (var owner = connect("owner"); var discord = new FakeDiscord()) {
            Ticket ticket = create(owner);
            bind(owner, ticket);
            link(owner, creator, CREATOR);
            link(owner, staff, STAFF);
            UUID other = UUID.randomUUID();

            assertTrue(handle(owner, discord, OWNER, message("110000000000001003", OTHER, "unlinked")));
            link(owner, other, OTHER);
            assertTrue(handle(owner, discord, OWNER, message("110000000000001004", OTHER, "not authorized")));
            assertTrue(discord.worker(owner).handle(OWNER, message("110000000000001005", STAFF, "wrong category"),
                    owner.links, owner.tickets,
                    (uuid, bound) -> TicketActor.administrative(uuid.toString(), "Staff", ParadigmPermissions.TICKET_STAFF_REPLY::equals),
                    true, () -> true));
            var latest = owner.repository.findById(network, ticket.ticketId()).orElseThrow();
            assertTrue(owner.repository.applyMutation(TicketMutation.of(latest.toBuilder().status(TicketStatus.CLOSED).build(),
                    latest.revision(), null, List.of())).ok());
            assertTrue(handle(owner, discord, OWNER, message("110000000000001006", CREATOR, "closed")));
            latest = owner.repository.findById(network, ticket.ticketId()).orElseThrow();
            assertTrue(owner.repository.applyMutation(TicketMutation.of(latest.toBuilder().status(TicketStatus.OPEN).build(),
                    latest.revision(), null, List.of())).ok());
            try (var restarted = connect("owner")) {
                assertTrue(handle(restarted, discord, OWNER, message("110000000000001006", CREATOR, "closed")));
            }

            assertTrue(handle(owner, discord, OWNER, new DiscordInboundMessage("110000000000001007", channel, GUILD,
                    CREATOR, "Bot", true, false, false, "bot", List.of(), Map.of())));
            assertTrue(handle(owner, discord, OWNER, new DiscordInboundMessage("110000000000001008", channel, GUILD,
                    CREATOR, "Webhook", false, true, false, "webhook", List.of(), Map.of())));
            assertFalse(handle(owner, discord, OWNER, new DiscordInboundMessage("110000000000001009", OTHER, GUILD,
                    CREATOR, "Creator", false, false, false, "another channel", List.of(), Map.of())));

            assertEquals(1, messages(owner, ticket).size());
            assertEquals(4, discord.messages.size());
            assertTrue(discord.texts().get(0).contains("Link your Minecraft account"));
            assertTrue(discord.texts().get(1).contains("permission"));
            assertTrue(discord.texts().get(2).contains("permission"));
            assertTrue(discord.texts().get(3).contains("closed"));
            assertEquals("110000000000001003", discord.messages.get(0).getAsJsonObject("message_reference").get("message_id").getAsString());
        }
    }

    @Test
    void outboundUncertainDeliveryIsRecordedAndDoesNotUndoOrResendReply() throws Exception {
        try (var owner = connect("owner"); var satellite = connect("satellite"); var discord = new FakeDiscord()) {
            Ticket ticket = create(satellite);
            bind(owner, ticket);
            assertTrue(satellite.tickets.reply(actor(creator, false), ticket.ticketKey(), "keep this reply", null).ok());
            discord.status = 503;
            discord.worker(owner).deliver(OWNER, ROLES, () -> true);
            try (var restarted = connect("owner")) {
                discord.status = 200;
                discord.worker(restarted).deliver(OWNER, ROLES, () -> true);
                assertTrue(restarted.store.unconfirmedReply(GUILD).isPresent());
            }
            assertEquals(2, messages(satellite, ticket).size());
            assertEquals(List.of("Creator: keep this reply"), discord.texts());
            assertTrue(discord.warnings.stream().anyMatch(warning -> warning.contains("unconfirmed")));
        }
    }

    @Test
    void concurrentDuplicateMutationsAndRevokedLinkAreCheckedInsideSqlTransaction() throws Exception {
        try (var owner = connect("owner"); var satellite = connect("satellite")) {
            Ticket ticket = create(satellite);
            link(satellite, creator, CREATOR);
            var message = new TicketMessage(TicketIds.discordReplyId("110000000000001010"), ticket.ticketId(), network,
                    ticket.ticketKey(), TicketAuthorType.PLAYER, creator.toString(), "Creator",
                    "owner", "one reply", System.currentTimeMillis());
            var mutation = TicketMutation.of(ticket, ticket.revision(), message, List.of());
            var go = new CountDownLatch(1);
            var executor = Executors.newFixedThreadPool(2);
            try {
                var first = executor.submit(() -> {
                    go.await();
                    return owner.repository.applyDiscordReply(mutation, CREATOR).status();
                });
                var second = executor.submit(() -> {
                    go.await();
                    return satellite.repository.applyDiscordReply(mutation, CREATOR).status();
                });
                go.countDown();
                var outcomes = List.of(first.get(10, TimeUnit.SECONDS), second.get(10, TimeUnit.SECONDS));
                assertTrue(outcomes.contains(TicketWriteResult.Status.OK));
                assertTrue(outcomes.contains(TicketWriteResult.Status.DUPLICATE_REPLY));
            } finally {
                executor.shutdownNow();
            }
            assertTrue(satellite.links.unlink(creator));
            var fresh = owner.repository.findById(network, ticket.ticketId()).orElseThrow();
            var revoked = new TicketMessage(TicketIds.discordReplyId("110000000000001011"), ticket.ticketId(), network,
                    ticket.ticketKey(), TicketAuthorType.PLAYER, creator.toString(), "Creator",
                    "owner", "must not save", System.currentTimeMillis());
            assertEquals(TicketWriteResult.Status.PERMISSION_DENIED, owner.repository.applyDiscordReply(
                    TicketMutation.of(fresh, fresh.revision(), revoked, List.of()), CREATOR).status());
            assertEquals(2, messages(satellite, ticket).size());
        }
    }

    private Connection connect(String server) {
        return new Connection(server);
    }

    private Ticket create(Connection connection) {
        long now = System.currentTimeMillis() - 60_000;
        var ticket = new Ticket(TicketIds.ticketId(), "T-1", network, "satellite", creator.toString(), "Creator",
                "support", "subject", TicketStatus.OPEN, TicketPriority.NORMAL, null, null,
                now, now, null, null, now, 0, Map.of());
        var initial = new TicketMessage(TicketIds.messageId(), ticket.ticketId(), network, ticket.ticketKey(),
                TicketAuthorType.PLAYER, creator.toString(), "Creator", "satellite", "private initial body", now);
        assertTrue(connection.repository.createTicket(new TicketCreate(ticket, initial, null)).ok());
        return ticket;
    }

    private void bind(Connection connection, Ticket ticket) {
        connection.sql.update("""
                INSERT INTO discord_ticket_rooms (network_id, ticket_id, guild_id, marker, phase, opening_pending)
                VALUES (?, ?, ?, ?, 'CREATING', 0)
                """, stmt -> {
            stmt.setString(1, network);
            stmt.setString(2, ticket.ticketId());
            stmt.setString(3, GUILD);
            stmt.setString(4, UUID.randomUUID().toString());
        });
        assertTrue(connection.store.bind(ticket.ticketId(), channel));
        connection.store.applied(ticket.ticketId(), connection.links.discordId(creator).orElse(null), ROLES, true);
    }

    private static void link(Connection connection, UUID uuid, String discordId) {
        assertEquals(DiscordAccountLinks.Claim.LINKED,
                connection.links.claim(discordId, GUILD, connection.links.issue(uuid, GUILD).orElseThrow()));
    }

    private void append(Connection connection, Ticket ticket, TicketAuthorType type, String text) {
        Ticket latest = connection.repository.findById(network, ticket.ticketId()).orElseThrow();
        var message = new TicketMessage(TicketIds.messageId(), ticket.ticketId(), network, ticket.ticketKey(),
                type, null, "System", "satellite", text, System.currentTimeMillis());
        assertTrue(connection.repository.applyMutation(TicketMutation.of(latest, latest.revision(), message, List.of())).ok());
    }

    private List<TicketMessage> messages(Connection connection, Ticket ticket) {
        return connection.repository.listMessages(network, ticket.ticketKey(), 0, 20);
    }

    private boolean handle(Connection connection, FakeDiscord discord, DiscordService.LinkingSettings linking,
                           DiscordInboundMessage message) {
        BiFunction<UUID, Ticket, TicketActor> actors = (uuid, ticket) -> actor(uuid, uuid.equals(staff));
        return discord.worker(connection).handle(linking, message, connection.links, connection.tickets, actors, true, () -> true);
    }

    private DiscordInboundMessage message(String id, String author, String text) {
        return new DiscordInboundMessage(id, channel, GUILD, author, "Discord Display", false, false, false,
                text, List.of(), Map.of());
    }

    private static TicketActor actor(UUID uuid, boolean staff) {
        return TicketActor.administrative(uuid.toString(), staff ? "Staff" : "Creator",
                permission -> permission.equals(ParadigmPermissions.TICKET_REPLY)
                        || (staff && (permission.equals(ParadigmPermissions.TICKET_STAFF_REPLY)
                            || permission.node().equals(STAFF_PERMISSION))));
    }

    private final class Connection implements AutoCloseable {
        private final SqlConnectionProvider database = new SqlConnectionProvider(config(), dialect(), null);
        private final SqlExecutor sql = new SqlExecutor(database);
        private final SqlTicketRepository repository;
        private final DiscordTicketRoomStore store;
        private final DiscordAccountLinks links = new DiscordAccountLinks(sql, network);
        private final TicketService tickets;

        Connection(String server) {
            new SqlMigrationRunner(database, null).runAvailableMigrations();
            repository = new SqlTicketRepository(sql, new StorageContext(new ServerIdentity(network, server, server)));
            store = new DiscordTicketRoomStore(sql, network, repository);
            var ticketConfig = new TicketsConfigHandler.Config();
            ticketConfig.enabled.value = true;
            var category = new TicketsConfigHandler.CategoryEntry("support", "Support", "", "NORMAL");
            category.staffPermission = STAFF_PERMISSION;
            ticketConfig.categories = List.of(category);
            tickets = new TicketService(null) {
                @Override public String networkId() { return network; }
                @Override public String serverId() { return server; }
                @Override public SqlTicketRepository repository() { return repository; }
                @Override public TicketsConfigHandler.Config config() { return ticketConfig; }
            };
        }

        @Override public void close() {
            database.close();
        }
    }

    private static final class FakeDiscord implements AutoCloseable {
        private final List<String> warnings = new CopyOnWriteArrayList<>();
        private final List<String> paths = new CopyOnWriteArrayList<>();
        private final List<JsonObject> messages = new CopyOnWriteArrayList<>();
        private final HttpServer server;
        private final DiscordRestClient rest;
        private volatile int status = 200;

        FakeDiscord() throws IOException {
            server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
            server.createContext("/", exchange -> {
                try {
                    paths.add(exchange.getRequestURI().getPath());
                    var json = new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
                    messages.add(JsonParser.parseString(json).getAsJsonObject());
                    exchange.sendResponseHeaders(status, -1);
                } finally {
                    exchange.close();
                }
            });
            server.start();
            rest = new DiscordRestClient(() -> "fixture-token", new DiscordRateLimiter(), null, null,
                    "http://127.0.0.1:" + server.getAddress().getPort());
        }

        List<String> texts() {
            return messages.stream().map(json -> json.get("content").getAsString()).toList();
        }

        DiscordTicketReplies worker(Connection connection) {
            return new DiscordTicketReplies(connection.store, rest, warnings::add);
        }

        @Override public void close() {
            server.stop(0);
        }
    }
}

@EnabledIfEnvironmentVariable(named = "PARADIGM_TICKET_REPLY_TEST_MARIADB_PORT", matches = "[0-9]+")
class MariaDbDiscordTicketRepliesTest extends DiscordTicketRepliesTest {
    @Override SqlDialect dialect() {
        return new MysqlDialect();
    }

    @Override StorageConfig config() {
        var config = super.config();
        config.provider = "mysql";
        config.sql.host = "127.0.0.1";
        config.sql.port = Integer.parseInt(System.getenv("PARADIGM_TICKET_REPLY_TEST_MARIADB_PORT"));
        config.sql.database = "paradigm_reply_test";
        config.sql.username = "paradigm_reply_test";
        config.sql.password = "disposable-ticket-test";
        return config;
    }
}
