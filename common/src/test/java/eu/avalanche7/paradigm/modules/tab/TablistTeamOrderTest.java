package eu.avalanche7.paradigm.modules.tab;

import static org.junit.jupiter.api.Assertions.*;

import java.lang.reflect.Proxy;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicBoolean;

import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.ChannelOutboundHandlerAdapter;
import io.netty.channel.ChannelPromise;
import io.netty.channel.embedded.EmbeddedChannel;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import eu.avalanche7.paradigm.modules.tab.ScoreboardConnection.Change;
import eu.avalanche7.paradigm.platform.Interfaces.IPlatformAdapter;
import eu.avalanche7.paradigm.platform.Interfaces.IPlayer;

class TablistTeamOrderTest {
    private static final Object JOIN = new Object(), DIMENSION_CHANGE = new Object(), RESPAWN = new Object();
    private static final int CREATE = ScoreboardConnection.CREATE, REMOVE = ScoreboardConnection.REMOVE,
            UPDATE = ScoreboardConnection.UPDATE, ADD = ScoreboardConnection.ADD, LEAVE = ScoreboardConnection.LEAVE;

    // Packet instances have native identity even when their payloads are identical.
    private static final class Packet {
        final Change change;
        Packet(String name, int mode, String... members) { change = new Change(name, mode, List.of(members)); }
    }

    private static final ScoreboardConnection.Codec CODEC = new ScoreboardConnection.Codec() {
        @Override public Change team(Object packet) { return packet instanceof Packet value ? value.change : null; }
        @Override public boolean opensScoreboard(Object packet) { return packet == JOIN; }
        @Override public Object remove(String name) { return new Packet(name, REMOVE); }
    };

    private record Player(String getName, String getUUID) implements IPlayer {
        @Override public Object getOriginalPlayer() { return this; }
    }

    private static final class Client {
        final ScoreboardConnection connection = new ScoreboardConnection(CODEC);
        final EmbeddedChannel channel;
        final Map<String, Set<String>> teams = new HashMap<>();
        final List<Change> received = new ArrayList<>();

        Client(ChannelOutboundHandlerAdapter encoder) {
            channel = new EmbeddedChannel(encoder, connection);
        }

        Client() { this(new ChannelOutboundHandlerAdapter()); }

        void read() {
            Object packet;
            while ((packet = channel.readOutbound()) != null) {
                if (packet == JOIN) {
                    teams.clear();
                } else if (packet instanceof Packet value) {
                    Change op = value.change;
                    String name = op.name();
                    received.add(op);
                    if (op.mode() == CREATE) assertNull(teams.putIfAbsent(name, new HashSet<>()), "Duplicate CREATE for " + name);
                    else assertTrue(teams.containsKey(name), "Unknown " + name + " for mode " + op.mode());
                    switch (op.mode()) {
                        case CREATE, ADD -> {
                            teams.values().forEach(members -> members.removeAll(op.members()));
                            teams.get(name).addAll(op.members());
                        }
                        case REMOVE -> teams.remove(name);
                        case LEAVE -> assertTrue(teams.get(name).removeAll(op.members()));
                        default -> { }
                    }
                }
            }
        }

        void nativePacket(Object packet) {
            channel.writeAndFlush(packet);
            read();
        }

        void own(Packet packet, String subject, Object appearance) {
            connection.sendOwned(packet, subject, appearance, this::nativePacket);
        }

        List<String> ordered() {
            return teams.entrySet().stream().sorted(Map.Entry.comparingByKey()).flatMap(entry -> entry.getValue().stream()).toList();
        }
    }

    @Test void initialAndRepeatedRefreshesNeverCreateOrRemoveAnUnknownTeam() {
        var client = new Client();
        client.own(new Packet("pg3", CREATE, "Member"), "Member", "a");
        assertFalse(client.connection.view().ready());
        client.nativePacket(JOIN);
        for (int i = 0; i < 200; i++) client.own(new Packet("pg3", CREATE, "Member"), "Member", "a");
        client.own(new Packet("unknown", REMOVE), "Member", "a");
        client.own(new Packet("unknown", UPDATE), "Member", "a");
        client.own(new Packet("unknown", ADD, "Member"), "Member", "a");
        assertEquals(List.of(new Change("pg3", CREATE, List.of("Member"))), client.received);
    }

    @Test void foreignCollisionOrdersRemovalBeforeCreateAndOnlyTakesThatName() {
        var client = new Client();
        client.nativePacket(JOIN);
        client.nativePacket(new Packet("vanilla", CREATE, "Vanilla"));
        client.own(new Packet("pg3", CREATE, "Member"), "Member", "a");
        client.nativePacket(new Packet("pg3", CREATE, "External"));
        client.own(new Packet("pg3", UPDATE), "Member", "b");
        client.own(new Packet("pg3", REMOVE), "Member", "a");
        client.connection.clearOwned();
        client.read();
        assertEquals(Map.of("pg3", Set.of("External"), "vanilla", Set.of("Vanilla")), client.teams);
        assertEquals(List.of(CREATE, REMOVE, CREATE),
                client.received.stream().filter(op -> op.name().equals("pg3")).map(Change::mode).toList());
    }

    @Test void externalMembershipWinsAndCanBeRestoredAfterTheExternalTeamReleasesIt() {
        var client = new Client();
        client.nativePacket(JOIN);
        client.own(new Packet("pg3", CREATE, "Member"), "Member", "a");
        client.nativePacket(new Packet("vanilla", CREATE, "Other"));
        client.nativePacket(new Packet("vanilla", ADD, "Member"));
        assertFalse(client.connection.view().owned().get("pg3").containsSubject());
        client.own(new Packet("pg3", ADD, "Member"), "Member", "a");
        assertEquals(Set.of("Member", "Other"), client.teams.get("vanilla"));
        client.nativePacket(new Packet("vanilla", LEAVE, "Member"));
        client.own(new Packet("pg3", ADD, "Member"), "Member", "a");
        assertEquals(Set.of("Member"), client.teams.get("pg3"));
        assertEquals(Set.of("Other"), client.teams.get("vanilla"));
    }

    @Test void writesCancelledBeforeEnqueueCanRetryAndFailureAtTheEncoderSuspendsOwnPackets() {
        var fail = new AtomicBoolean();
        var client = new Client(new ChannelOutboundHandlerAdapter() {
            @Override public void write(ChannelHandlerContext ctx, Object object, ChannelPromise promise) throws Exception {
                if (object instanceof Packet && fail.get()) promise.setFailure(new IllegalStateException("rejected by encoder"));
                else super.write(ctx, object, promise);
            }
        });
        client.nativePacket(JOIN);
        client.connection.sendOwned(new Packet("pg3", CREATE, "Member"), "Member", "a", ignored -> {});
        assertTrue(client.connection.view().owned().isEmpty());
        fail.set(true);
        client.own(new Packet("pg3", CREATE, "Member"), "Member", "a");
        assertFalse(client.connection.view().ready());
        client.own(new Packet("pg3", UPDATE), "Member", "b");
        assertTrue(client.received.isEmpty());
        fail.set(false);
        client.nativePacket(JOIN);
        client.own(new Packet("pg3", CREATE, "Member"), "Member", "a");
        assertEquals(List.of("Member"), client.ordered());
    }

    @Test void reentrantForeignCreateWaitsForOwnedCreateAndSafeHandover() {
        var reenter = new AtomicBoolean(true);
        var client = new Client(new ChannelOutboundHandlerAdapter() {
            @Override public void write(ChannelHandlerContext ctx, Object object, ChannelPromise promise) throws Exception {
                if (object instanceof Packet && reenter.getAndSet(false)) {
                    ctx.channel().writeAndFlush(new Packet("pg3", CREATE, "External"));
                }
                super.write(ctx, object, promise);
            }
        });
        client.nativePacket(JOIN);
        client.own(new Packet("pg3", CREATE, "Member"), "Member", "a");
        assertEquals(List.of(CREATE, REMOVE, CREATE), client.received.stream().map(Change::mode).toList());
        assertEquals(List.of("External"), client.ordered());
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void reloadOrVanishQueuesCleanupAfterAnInFlightWriteAndPreservesForeignMembership(boolean vanish) {
        var gate = new ChannelOutboundHandlerAdapter() {
            private Object waiting;
            private ChannelPromise acknowledgement;
            private ChannelHandlerContext context;
            @Override public void write(ChannelHandlerContext ctx, Object object, ChannelPromise promise) throws Exception {
                if (object instanceof Packet packet && packet.change.name().equals("pg3") && packet.change.mode() == CREATE) {
                    waiting = object;
                    acknowledgement = promise;
                    context = ctx;
                } else super.write(ctx, object, promise);
            }
            void release() { context.writeAndFlush(waiting, acknowledgement); }
        };
        var client = new Client(gate);
        client.nativePacket(JOIN);
        client.nativePacket(new Packet("vanilla", CREATE, "Other"));
        client.own(new Packet("pg3", CREATE, "Member"), "Member", "a");
        assertTrue(client.connection.view().owned().isEmpty());
        if (vanish) client.connection.visibility("Member", false);
        else client.connection.clearOwned();
        gate.release();
        client.read();
        assertEquals(Map.of("vanilla", Set.of("Other")), client.teams);
        client.connection.clearOwned();
        client.read();
        assertEquals(List.of(CREATE, CREATE, REMOVE), client.received.stream().map(Change::mode).toList());
        if (vanish) {
            client.own(new Packet("other", CREATE, "Member"), "Member", "a");
            assertFalse(client.teams.containsKey("other"));
            client.connection.visibility("Member", true);
            client.own(new Packet("other", CREATE, "Member"), "Member", "a");
            assertEquals(Set.of("Member"), client.teams.get("other"));
        }
    }

    @Test void repeatedWorldChangesRespawnsAndBackendJoinResetsHaveDistinctLifecycles() {
        var client = new Client();
        client.nativePacket(JOIN);
        for (int i = 0; i < 40; i++) {
            client.own(new Packet("pg3", CREATE, "Member"), "Member", "a");
            client.nativePacket(DIMENSION_CHANGE);
            client.nativePacket(RESPAWN);
            assertEquals(List.of("Member"), client.ordered());
        }
        assertEquals(1, client.received.size());
        client.nativePacket(JOIN);
        client.own(new Packet("pg3", CREATE, "Member"), "Member", "a");
        assertEquals(2, client.received.size());
    }

    @Test void sortingHandlesVanishDisconnectAndSameUuidReconnectWithoutTouchingTheNewSession() {
        var owner = new Player("Owner", "owner");
        var builder = new Player("Builder", "builder");
        Map<IPlayer, Client> clients = new IdentityHashMap<>();
        clients.put(owner, new Client());
        clients.put(builder, new Client());
        clients.values().forEach(client -> client.nativePacket(JOIN));
        var platform = platform(clients);
        var order = new TablistTeamOrder();
        var sorted = List.<IPlayer>of(owner, builder);
        assertTrue(order.apply(sorted, platform, (viewer, subject) -> true));
        assertEquals(List.of("Owner", "Builder"), clients.get(builder).ordered());
        assertTrue(order.apply(sorted, platform, (viewer, subject) -> subject != owner || viewer == owner));
        assertEquals(List.of("Builder"), clients.get(builder).ordered());
        assertTrue(order.apply(sorted, platform, (viewer, subject) -> true));
        for (int i = 0; i < 40; i++) {
            var stale = clients.get(builder);
            builder = new Player("Builder", "builder");
            var next = new Client();
            clients.put(builder, next);
            next.nativePacket(JOIN);
            sorted = List.of(owner, builder);
            assertTrue(order.apply(sorted, platform, (viewer, subject) -> true));
            stale.connection.clearOwned();
            stale.channel.close();
            assertEquals(List.of("Owner", "Builder"), next.ordered());
            int packets = next.received.size();
            assertTrue(order.apply(sorted, platform, (viewer, subject) -> true));
            assertEquals(packets, next.received.size());
        }
        order.clear(platform, sorted);
        clients.get(owner).read();
        clients.get(builder).read();
        assertEquals(List.of(), clients.get(builder).ordered());
    }

    @Test void loaderAdaptersSelectOnlyTheirSupportedOrderingAndResetProtocol() throws Exception {
        Path root = Path.of(System.getProperty("paradigm.repository.root"));
        var virtual = List.of("Fabric-1.19.2", "Fabric-1.20.1", "Fabric-1.21.1",
                "Forge-1.18.2", "Forge-1.19.2", "Forge-1.20.1", "Forge-1.21.1", "NeoForge-1.21.1");
        var nativeOrder = List.of("Fabric-1.21.11", "Forge-1.21.11", "NeoForge-26.1.2");
        String source = "src/main/java/eu/avalanche7/paradigm/";
        for (String module : virtual) {
            Path java = root.resolve(module).resolve(source);
            var adapter = Files.readString(java.resolve("platform/PlatformAdapterImpl.java"));
            assertTrue(adapter.contains("connection.sendOwned("), module);
            assertTrue(adapter.contains("Tablist.applyTeamOrder"), module);
            var codec = Files.readString(java.resolve("platform/ScoreboardPacketCodec.java"));
            assertTrue(codec.contains(module.startsWith("Fabric-") ? "GameJoinS2CPacket" : "ClientboundLoginPacket"), module);
            assertFalse(codec.contains("RespawnPacket") || codec.contains("PlayerRespawnS2CPacket"), module);
            var mixins = Files.readString(root.resolve(module).resolve("src/main/resources/paradigm.mixins.json"));
            assertTrue(mixins.contains("\"ScoreboardConnectionMixin\""), module);
        }
        for (String module : nativeOrder) {
            var adapter = Files.readString(root.resolve(module).resolve(source).resolve("platform/PlatformAdapterImpl.java"));
            assertTrue(adapter.contains("UPDATE_LIST_ORDER"), module);
            assertFalse(adapter.contains("sendPlayerListOrderTeam") || adapter.contains("Tablist.applyTeamOrder"), module);
        }
        Path legacy = root.resolve("Forge-1.7.10").resolve(source);
        var codec = Files.readString(legacy.resolve("platform/ScoreboardPacketCodec.java"));
        assertTrue(codec.contains("S01PacketJoinGame"));
        assertFalse(codec.contains("S07PacketRespawn"));
        assertTrue(codec.contains("S38PacketPlayerListItem"));
    }

    private IPlatformAdapter platform(Map<IPlayer, Client> clients) {
        return (IPlatformAdapter) Proxy.newProxyInstance(getClass().getClassLoader(), new Class<?>[]{IPlatformAdapter.class}, (proxy, method, args) -> {
            return switch (method.getName()) {
                case "hasPlayerListTeam" -> false;
                case "getScoreboardConnection" -> clients.get((IPlayer) args[0]).connection;
                case "sendPlayerListOrderTeam" -> {
                    var client = clients.get((IPlayer) args[0]);
                    String name = (String) args[1], subject = (String) args[2];
                    boolean create = (boolean) args[3];
                    client.own(create ? new Packet(name, CREATE, subject) : new Packet(name, REMOVE), subject, "");
                    yield client.connection.view().owned().containsKey(name) == create;
                }
                default -> throw new UnsupportedOperationException(method.getName());
            };
        });
    }
}
