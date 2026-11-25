package eu.avalanche7.paradigm.platform;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import java.nio.file.Path;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import io.netty.channel.embedded.EmbeddedChannel;
import net.minecraft.entity.EntityTracker;
import net.minecraft.entity.EntityTrackerEntry;
import net.minecraft.entity.player.EntityPlayerMP;
import net.minecraft.network.NetHandlerPlayServer;
import net.minecraft.network.play.server.S01PacketJoinGame;
import net.minecraft.network.play.server.S38PacketPlayerListItem;
import net.minecraft.network.play.server.S3EPacketTeams;
import net.minecraft.scoreboard.Scoreboard;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.management.ServerConfigurationManager;
import net.minecraft.util.ChatComponentText;
import net.minecraft.util.IChatComponent;
import net.minecraft.util.IntHashMap;
import net.minecraft.world.WorldServer;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.mockito.ArgumentCaptor;

import eu.avalanche7.paradigm.mixin.EntityTrackerAccess;
import eu.avalanche7.paradigm.modules.tab.ScoreboardConnection;
import eu.avalanche7.paradigm.platform.Interfaces.IPlatformAdapter;
import eu.avalanche7.paradigm.platform.visual.LegacyTablistController;
import eu.avalanche7.paradigm.platform.visual.LegacyVisualController;
import eu.avalanche7.paradigm.utils.DebugLogger;
import eu.avalanche7.paradigm.utils.Placeholders;
import eu.avalanche7.paradigm.utils.TaskScheduler;

class LegacyVisualTest {
    @TempDir Path directory;
    MinecraftServer server;
    WorldServer world;
    EntityPlayerMP subject, viewer;
    EntityTrackerEntry entry;
    LegacyVisualController visuals;
    PlatformAdapterImpl platform;
    List<EntityPlayerMP> online;
    final Map<EntityPlayerMP, Scoreboard> clientBoards = new IdentityHashMap<>();

    @BeforeEach
    @SuppressWarnings("unchecked")
    void setup() {
        server = mock(MinecraftServer.class);
        ServerConfigurationManager manager = mock(ServerConfigurationManager.class, withSettings().useConstructor(server));
        when(server.getConfigurationManager()).thenReturn(manager);
        online = manager.playerEntityList;
        world = mock(WorldServer.class);
        when(world.getScoreboard()).thenReturn(new Scoreboard());
        EntityTracker tracker = mock(EntityTracker.class, withSettings().extraInterfaces(EntityTrackerAccess.class));
        when(world.getEntityTracker()).thenReturn(tracker);
        IntHashMap ids = new IntHashMap();
        when(((EntityTrackerAccess) tracker).paradigm$trackedEntities()).thenReturn(ids);
        subject = player("Subject", UUID.randomUUID(), 12);
        viewer = player("Viewer", UUID.randomUUID(), 13);
        online.addAll(List.of(subject, viewer));
        entry = mock(EntityTrackerEntry.class);
        entry.trackingPlayers = new java.util.HashSet<>(List.of(viewer));
        ids.addKey(12, entry);
        visuals = new LegacyVisualController(server);
        DebugLogger debug = new DebugLogger(null);
        platform = new PlatformAdapterImpl(null, new Placeholders(), new TaskScheduler(debug), debug, new ForgeConfig(directory));
        platform.setMinecraftServer(server);
    }

    EntityPlayerMP player(String name, UUID uuid, int id) {
        EntityPlayerMP player = mock(EntityPlayerMP.class);
        when(player.getCommandSenderName()).thenReturn(name);
        when(player.getUniqueID()).thenReturn(uuid);
        when(player.getEntityId()).thenReturn(id);
        player.worldObj = world;
        var network = mock(net.minecraft.network.NetworkManager.class, withSettings().extraInterfaces(ScoreboardConnection.Access.class));
        var connection = new ScoreboardConnection(new ScoreboardConnection.Codec() {
            @Override public ScoreboardConnection.Change team(Object packet) {
                if (!(packet instanceof S3EPacketTeams)) return null;
                return connectionCodec(packet);
            }
            @Override public boolean opensScoreboard(Object packet) { return packet instanceof S01PacketJoinGame; }
            @Override public Object remove(String name) {
                return new S3EPacketTeams(new net.minecraft.scoreboard.ScorePlayerTeam(new Scoreboard(), name), ScoreboardConnection.REMOVE);
            }
        });
        var channel = new EmbeddedChannel(connection);
        when(((ScoreboardConnection.Access) network).paradigm$scoreboard()).thenReturn(connection);
        var board = new Scoreboard();
        clientBoards.put(player, board);
        channel.writeOutbound(new S01PacketJoinGame());
        channel.readOutbound();
        when(network.isChannelOpen()).thenReturn(true);
        player.playerNetServerHandler = mock(NetHandlerPlayServer.class, withSettings().useConstructor(server, network, player));
        doAnswer(call -> {
            Object outgoing = call.getArgument(0);
            channel.writeOutbound(outgoing);
            Object written;
            while ((written = channel.readOutbound()) != null) {
                if (!(written instanceof S3EPacketTeams)) continue;
                var change = connectionCodec(written);
                var team = board.getTeam(change.name());
                if (change.mode() == ScoreboardConnection.CREATE) team = board.createTeam(change.name());
                else assertNotNull(team, "Packet references an unknown team " + change.name());
                switch (change.mode()) {
                    case ScoreboardConnection.CREATE, ScoreboardConnection.ADD -> {
                        for (String member : change.members()) board.func_151392_a(member, change.name());
                    }
                    case ScoreboardConnection.REMOVE -> board.removeTeam(team);
                    default -> { }
                }
            }
            return null;
        }).when(player.playerNetServerHandler).sendPacket(any());
        return player;
    }

    private static ScoreboardConnection.Change connectionCodec(Object packet) {
        return new ScoreboardConnection.Change((String) field(packet, "field_149320_a"),
                (int) field(packet, "field_149314_f"), packetMembers(packet));
    }

    @SuppressWarnings("unchecked")
    private static java.util.Collection<String> packetMembers(Object packet) {
        return (java.util.Collection<String>) field(packet, "field_149317_e");
    }

    private static Object field(Object packet, String name) {
        try {
            var field = packet.getClass().getDeclaredField(name);
            field.setAccessible(true);
            return field.get(packet);
        } catch (ReflectiveOperationException failure) {
            throw new AssertionError(failure);
        }
    }

    @Test void hideRemovesNativeWatchersAndListOnceWithoutChangingPlayerState() {
        assertTrue(visuals.vanish().set(subject, true));
        assertFalse(visuals.vanish().visible(subject, viewer));
        assertTrue(visuals.vanish().visible(subject, subject));
        assertFalse(visuals.vanish().listed("Subject", viewer));
        assertTrue(visuals.vanish().set(subject, true));
        verify(entry).removeFromWatchingList(viewer);
        verify(viewer.playerNetServerHandler).sendPacket(isA(S38PacketPlayerListItem.class));
        verify(subject, never()).setInvisible(anyBoolean());
        assertTrue(online.contains(subject));
    }

    @Test void showUsesNativeRangeAndChunkChecksAndIsIdempotent() {
        visuals.vanish().set(subject, true);
        visuals.vanish().set(subject, false);
        visuals.vanish().set(subject, false);
        verify(entry).tryStartWachingThis(viewer);
        verify(viewer.playerNetServerHandler, times(2)).sendPacket(isA(S38PacketPlayerListItem.class));
        assertTrue(visuals.vanish().visible(subject, viewer));
        assertTrue(visuals.vanish().listed("Subject", viewer));
    }

    @Test void restoredStateIsHiddenBeforeNewViewersCanTrackAndSurvivesDimensionAndRespawn() {
        visuals.vanish().prepare(subject.getUniqueID(), true);
        visuals.connected(subject);
        EntityPlayerMP joining = player("Joining", UUID.randomUUID(), 14);
        assertFalse(visuals.vanish().visible(subject, joining));
        assertFalse(visuals.vanish().listed("Subject", joining));
        subject.worldObj = mock(WorldServer.class);
        assertFalse(visuals.vanish().visible(subject, joining));
        EntityPlayerMP replacement = player("Subject", subject.getUniqueID(), 16);
        assertFalse(visuals.vanish().visible(replacement, joining));
        visuals.vanish().set(replacement, true);
        visuals.disconnected(subject);
        assertFalse(visuals.vanish().visible(replacement, joining));
        visuals.disconnected(replacement);
        assertTrue(visuals.vanish().visible(replacement, joining));
    }

    @Test void twoVanishedPlayersAreHiddenFromEachOtherAndClearRestoresBoth() {
        visuals.vanish().set(subject, true);
        visuals.vanish().set(viewer, true);
        assertFalse(visuals.vanish().visible(subject, viewer));
        assertFalse(visuals.vanish().visible(viewer, subject));
        visuals.clear();
        assertTrue(visuals.vanish().visible(subject, viewer));
        assertTrue(visuals.vanish().visible(viewer, subject));
    }

    @Test void combinedTitleFallbackIsOneFormattedChatComponentAndRepeatedFeedbackIsBounded() {
        ChatComponentText title = new ChatComponentText("§cTitle");
        ChatComponentText subtitle = new ChatComponentText("§bSubtitle");
        visuals.title(subject, title, subtitle);
        visuals.title(subject, title, subtitle);
        ArgumentCaptor<IChatComponent> messages = ArgumentCaptor.forClass(IChatComponent.class);
        verify(subject).addChatMessage(messages.capture());
        assertEquals("§cTitle\n§bSubtitle", messages.getValue().getUnformattedText());
        visuals.clearTitles(subject);
        visuals.title(subject, title, subtitle);
        verify(subject, times(2)).addChatMessage(any());
        assertFalse(platform.supportsTitles());
        assertTrue(platform.supportsPersistentBossBar());
    }

    @Test void actionbarRequiresTheInspectedClientAndServerVersionOtherwiseUsesChat() {
        assertTrue(eu.avalanche7.paradigm.platform.visual.LegacyClientCapabilities.compatibleActionbar("0.7.10", "0.7.10"));
        assertFalse(eu.avalanche7.paradigm.platform.visual.LegacyClientCapabilities.compatibleActionbar("0.7.10", null));
        assertFalse(eu.avalanche7.paradigm.platform.visual.LegacyClientCapabilities.compatibleActionbar("0.7.10", "0.6.0"));
        assertTrue(eu.avalanche7.paradigm.platform.visual.LegacyClientCapabilities.compatibleActionbar("0.8.0", "0.8.0"));
        visuals.actionbar(subject, new ChatComponentText("Portable actionbar"));
        verify(subject).addChatMessage(argThat(message -> message.getUnformattedText().equals("Portable actionbar")));
        verifyNoInteractions(subject.playerNetServerHandler);
    }

    @Test void titleFeedbackDoesNotSuppressTheFirstActionbarFallbackAndRapidChangesAreBounded() {
        visuals.title(subject, new ChatComponentText("Title"), null);
        visuals.actionbar(subject, new ChatComponentText("Action"));
        visuals.actionbar(subject, new ChatComponentText("Updated action"));
        verify(subject, times(2)).addChatMessage(any());
        visuals.actionbar(subject, new ChatComponentText(""));
        visuals.actionbar(subject, new ChatComponentText("After clear"));
        verify(subject, times(3)).addChatMessage(any());
    }

    @Test void defaultMotdFormattingRemainsDeliverableWithClipboardTagFallback() {
        eu.avalanche7.paradigm.configs.EmojiConfigHandler.init(platform.getConfig(), new DebugLogger(null));
        var parser = new eu.avalanche7.paradigm.utils.MessageParser(new Placeholders(), platform);
        platform.provideMessageParser(parser);
        var defaults = new eu.avalanche7.paradigm.configs.MOTDConfigHandler.Config();
        for (String line : defaults.motdLines) assertDoesNotThrow(() -> parser.parseMessage(line, null));
        var message = (MinecraftComponent) parser.parseMessage("<click:copy:admin@paradigm.com>Copy Email</click>", null);
        var text = (IChatComponent) message.getHandle().getSiblings().get(0);
        assertEquals(net.minecraft.event.ClickEvent.Action.SUGGEST_COMMAND, text.getChatStyle().getChatClickEvent().getAction());
        assertEquals("admin@paradigm.com", text.getChatStyle().getChatClickEvent().getValue());
        assertEquals("Copy Email", message.getRawText());
    }

    @Test void persistentFallbackIsIdempotentAndLogoutAllowsCleanReconnect() {
        IChatComponent text = new ChatComponentText("Status");
        visuals.persistent(subject, text);
        visuals.persistent(subject, text);
        verify(subject).addChatMessage(any());
        visuals.disconnected(subject);
        visuals.persistent(subject, text);
        verify(subject, times(2)).addChatMessage(any());
        visuals.transferred(subject);
        visuals.persistent(subject, text);
        verify(subject, times(2)).addChatMessage(any());
        verifyNoInteractions(subject.playerNetServerHandler);
    }

    @Test void restartFallbackDoesNotSpamEveryCountdownUpdateAndCancellationResetsIt() {
        visuals.restart(new ChatComponentText("60 seconds"));
        visuals.restart(new ChatComponentText("59 seconds"));
        verify(subject).addChatMessage(any());
        visuals.removeRestart();
        visuals.restart(new ChatComponentText("60 seconds"));
        verify(subject, atMost(2)).addChatMessage(any());
        verifyNoInteractions(subject.playerNetServerHandler);
    }

    @Test void tabFormattingPreservesIdentityAndCapsFieldsWithoutBrokenFormatting() {
        assertEquals(new LegacyTablistController.NameParts("§a[Staff] ", "§f 12ms"),
                LegacyTablistController.parts("Subject", "§a[Staff] Subject§f 12ms"));
        assertNull(LegacyTablistController.parts("Subject", "Alias"));
        assertNull(LegacyTablistController.parts("Subject", "Subject\nfooter"));
        assertEquals("1234567890123 §a", LegacyTablistController.parts("Subject", "123456789012345§aSubject").prefix());
        assertFalse(platform.setPlayerListHeaderFooter(new MinecraftPlayer(subject), platform.createEmptyComponent(), platform.createEmptyComponent()));
        assertFalse(platform.setPlayerListOrder(new MinecraftPlayer(subject), 2));
    }

    @Test void teamsSentDuringLoginDoNotCreatePg3Twice() {
        var client = clientBoards.get(viewer);
        var tab = visuals.tablist();
        tab.display(subject, "§aSubject");
        tab.display(viewer, "§aViewer");
        var third = player("Third", UUID.randomUUID(), 15);
        online.add(third);
        tab.display(third, "§aThird");
        assertNotNull(client.getTeam("pg3"));
        tab.joined(viewer);
        tab.joined(viewer);
        assertSame(client.getTeam("pg3"), client.getPlayersTeam("Third"));
        verify(viewer.playerNetServerHandler, times(3)).sendPacket(any());
    }

    @Test void tabUpdatesDirtyFieldsReplaysJoiningViewerAndResetRemovesOnlyItsTeam() {
        var tab = visuals.tablist();
        assertTrue(tab.display(subject, "§aSubject"));
        assertTrue(tab.display(subject, "§aSubject"));
        verify(viewer.playerNetServerHandler).sendPacket(any());
        assertTrue(tab.display(subject, "§bSubject"));
        verify(viewer.playerNetServerHandler, times(2)).sendPacket(any());
        EntityPlayerMP joining = player("Joining", UUID.randomUUID(), 14);
        online.add(joining);
        tab.joined(joining);
        verify(joining.playerNetServerHandler).sendPacket(any());
        tab.reset(subject);
        tab.reset(subject);
        verify(joining.playerNetServerHandler, times(2)).sendPacket(any());
        assertNull(world.getScoreboard().getPlayersTeam("Subject"));
    }

    @Test void existingScoreboardTeamsTakePrecedenceAndInvalidAliasRestoresPriorState() {
        var tab = visuals.tablist();
        assertTrue(tab.display(subject, "§aSubject"));
        assertFalse(tab.display(subject, "Alias"));
        verify(viewer.playerNetServerHandler, times(2)).sendPacket(any());
        when(subject.getTeam()).thenReturn(new Scoreboard().createTeam("existing"));
        assertFalse(tab.display(subject, "§aSubject"));
        assertNull(clientBoards.get(viewer).getPlayersTeam("Subject"));
    }

    @Test void revealRetriesNativeEligibilityAfterTeleportAndStopsOnLogout() {
        visuals.setVanished(subject, true);
        visuals.setVanished(subject, false);
        entry.trackingPlayers.clear();
        visuals.vanish().tick();
        verify(entry, times(2)).tryStartWachingThis(viewer);
        visuals.disconnected(subject);
        visuals.vanish().tick();
        verify(entry, times(2)).tryStartWachingThis(viewer);
    }

    @Test void vanishRemovesFormattingTeamsAndNeverReplaysHiddenMembershipToNewViewers() {
        visuals.tablist().display(subject, "§aSubject");
        visuals.setVanished(subject, true);
        EntityPlayerMP joining = player("Joining", UUID.randomUUID(), 14);
        online.add(joining);
        visuals.joined(joining);
        assertNull(clientBoards.get(joining).getPlayersTeam("Subject"));
        visuals.setVanished(subject, false);
        visuals.setVanished(subject, false);
        assertNotNull(clientBoards.get(joining).getPlayersTeam("Subject"));
        assertNotNull(clientBoards.get(viewer).getPlayersTeam("Subject"));
    }

    @Test void servicesRebindingTheSameServerPreservesVisualSessionAndShutdownReason() {
        var current = LegacyVisualController.current();
        current.shutdown("Reason");
        platform.setMinecraftServer(server);
        assertSame(current, LegacyVisualController.current());
        assertEquals("Reason", LegacyVisualController.current().shutdownReason("Server closed"));
    }

    @Test void shutdownUsesNativeSaveAndDisconnectLifecycleAndAsyncCallWaitsForTick() throws Exception {
        Thread worker = new Thread(() -> platform.shutdownServer(platform.createLiteralComponent("Release shutdown")));
        worker.start(); worker.join();
        verify(server, never()).initiateShutdown();
        platform.tick();
        verify(server).initiateShutdown();
        assertTrue(LegacyVisualController.current().shutdownReason("Server closed").contains("Release shutdown"));
        verifyNoInteractions(subject.playerNetServerHandler);
    }

    @Test void unsupportedBossModesUseFeedbackWithoutAllocatingAnyEntityIdsOrPackets() {
        platform.sendBossBar(List.of(new MinecraftPlayer(subject)), platform.createLiteralComponent("Announcement"), 2,
                IPlatformAdapter.BossBarColor.RED, 0.25f);
        verify(subject).addChatMessage(any());
        verifyNoInteractions(subject.playerNetServerHandler);
        verify(world, never()).spawnEntityInWorld(any());
    }
}
