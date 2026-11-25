package eu.avalanche7.paradigm.platform.visual;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import cpw.mods.fml.relauncher.ReflectionHelper;
import io.netty.channel.embedded.EmbeddedChannel;
import net.minecraft.entity.player.EntityPlayerMP;
import net.minecraft.network.NetHandlerPlayServer;
import net.minecraft.network.NetworkManager;
import net.minecraft.network.play.server.S01PacketJoinGame;
import net.minecraft.network.play.server.S38PacketPlayerListItem;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.management.ServerConfigurationManager;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import eu.avalanche7.paradigm.modules.tab.ScoreboardConnection;

class LegacyTablistOrderTest {
    final List<EntityPlayerMP> online = new ArrayList<>();
    final Map<EntityPlayerMP, List<String>> clients = new HashMap<>();
    final List<S38PacketPlayerListItem> packets = new ArrayList<>();
    LegacyTablistController tab;
    LegacyVanishController vanish;
    EntityPlayerMP builder, owner;
    boolean fail;

    @BeforeEach void setup() {
        MinecraftServer server = mock(MinecraftServer.class);
        var manager = mock(ServerConfigurationManager.class);
        ReflectionHelper.setPrivateValue(ServerConfigurationManager.class, manager, online, "playerEntityList");
        when(server.getConfigurationManager()).thenReturn(manager);
        vanish = mock(LegacyVanishController.class);
        when(vanish.listed(anyString(), any())).thenReturn(true);
        tab = new LegacyTablistController(server, vanish);
        builder = player("Builder"); owner = player("Owner");
        online.addAll(List.of(builder, owner));
        for (var viewer : online) for (var subject : online) viewer.playerNetServerHandler.sendPacket(new S38PacketPlayerListItem(subject.getCommandSenderName(), true, 10));
        packets.clear();
    }

    EntityPlayerMP player(String name) {
        var player = mock(EntityPlayerMP.class);
        when(player.getCommandSenderName()).thenReturn(name);
        when(player.getUniqueID()).thenReturn(UUID.nameUUIDFromBytes(name.getBytes(java.nio.charset.StandardCharsets.UTF_8)));
        clients.put(player, new ArrayList<>());
        var transport = mock(NetworkManager.class, withSettings().extraInterfaces(ScoreboardConnection.Access.class));
        var journal = new ScoreboardConnection(new ScoreboardConnection.Codec() {
            @Override public ScoreboardConnection.Change team(Object packet) { return null; }
            @Override public Object remove(String name) { throw new UnsupportedOperationException(); }
            @Override public boolean opensScoreboard(Object packet) { return packet instanceof S01PacketJoinGame; }
            @Override public ScoreboardConnection.Listing listing(Object object) {
                return object instanceof S38PacketPlayerListItem packet
                        ? new ScoreboardConnection.Listing(packet.func_149122_c(), packet.func_149121_d()) : null;
            }
        });
        when(((ScoreboardConnection.Access) transport).paradigm$scoreboard()).thenReturn(journal);
        var channel = new EmbeddedChannel(journal);
        channel.writeOutbound(new S01PacketJoinGame());
        channel.readOutbound();
        player.playerNetServerHandler = mock(NetHandlerPlayServer.class);
        ReflectionHelper.setPrivateValue(NetHandlerPlayServer.class, player.playerNetServerHandler, transport, "netManager");
        doAnswer(call -> {
            var packet = (S38PacketPlayerListItem) call.getArgument(0);
            if (fail && packet.func_149121_d()) throw new IllegalStateException("send failed");
            packets.add(packet);
            channel.writeOutbound(packet);
            assertSame(packet, channel.readOutbound());
            if (!packet.func_149121_d()) clients.get(player).remove(packet.func_149122_c());
            else if (!clients.get(player).contains(packet.func_149122_c())) clients.get(player).add(packet.func_149122_c());
            return null;
        }).when(player.playerNetServerHandler).sendPacket(any());
        return player;
    }

    @Test void insertionOrderChangesWithoutChangingIdentityOrSteadyStateSpam() {
        assertTrue(tab.order(List.of(owner, builder)));
        assertTrue(clients.values().stream().allMatch(names -> names.equals(List.of("Owner", "Builder"))));
        assertEquals("Builder", builder.getCommandSenderName());
        packets.clear();
        assertTrue(tab.order(List.of(owner, builder)));
        assertTrue(packets.isEmpty());
        tab.resetOrder();
        assertTrue(clients.values().stream().allMatch(names -> names.equals(List.of("Builder", "Owner"))));
    }

    @Test void hiddenPlayersAreNeverReaddedAndForeignListEntriesAreNotDeleted() {
        when(vanish.listed("Owner", builder)).thenReturn(false);
        builder.playerNetServerHandler.sendPacket(new S38PacketPlayerListItem("Owner", false, 10));
        builder.playerNetServerHandler.sendPacket(new S38PacketPlayerListItem("PluginEntry", true, 10));
        packets.clear();
        assertTrue(tab.order(List.of(owner, builder)));
        assertEquals(List.of("Builder", "PluginEntry"), clients.get(builder));
        assertTrue(packets.stream().noneMatch(packet -> packet.func_149122_c().equals("PluginEntry")));
    }

    @Test void interruptedRebuildRetriesMissingEntriesRatherThanCachingSuccess() {
        fail = true;
        assertThrows(IllegalStateException.class, () -> tab.order(List.of(owner, builder)));
        fail = false;
        assertTrue(tab.order(List.of(owner, builder)));
        assertTrue(clients.values().stream().allMatch(names -> names.equals(List.of("Owner", "Builder"))));
    }
}
