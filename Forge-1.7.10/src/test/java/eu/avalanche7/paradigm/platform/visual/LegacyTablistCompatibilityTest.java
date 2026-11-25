package eu.avalanche7.paradigm.platform.visual;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

import java.util.ArrayList;
import java.util.Collection;
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
import net.minecraft.network.play.server.S3EPacketTeams;
import net.minecraft.scoreboard.ScorePlayerTeam;
import net.minecraft.scoreboard.Scoreboard;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.management.ServerConfigurationManager;
import net.minecraft.world.WorldServer;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import eu.avalanche7.paradigm.modules.tab.ScoreboardConnection;
import eu.avalanche7.paradigm.platform.Interfaces.IPlatformAdapter;
import eu.avalanche7.paradigm.platform.MinecraftComponent;
import eu.avalanche7.paradigm.utils.MessageParser;

class LegacyTablistCompatibilityTest {
    private final List<EntityPlayerMP> online = new ArrayList<>();
    private final Map<UUID, Scoreboard> clients = new HashMap<>();
    private final Map<UUID, List<Integer>> modes = new HashMap<>();
    private LegacyTablistController tab;
    private EntityPlayerMP subject, viewer;

    @BeforeEach void setup() {
        MinecraftServer server = mock(MinecraftServer.class);
        ServerConfigurationManager manager = mock(ServerConfigurationManager.class);
        ReflectionHelper.setPrivateValue(ServerConfigurationManager.class, manager, online, "playerEntityList");
        when(server.getConfigurationManager()).thenReturn(manager);
        tab = new LegacyTablistController(server, new LegacyVanishController(server));
        subject = player("Alice");
        viewer = player("Bob");
        online.addAll(List.of(subject, viewer));
    }

    @Test void onePlayerGetsLegacyPrefixAndSuffixWithoutSteadyStatePacketSpam() {
        online.remove(viewer);
        assertTrue(tab.display(subject, "§6[Admin] Alice§b [Staff]"));
        ScorePlayerTeam team = client(subject).getPlayersTeam("Alice");
        assertEquals("§6[Admin] ", team.getColorPrefix());
        assertEquals("§b [Staff]", team.getColorSuffix());
        tab.display(subject, "§6[Admin] Alice§b [Staff]");
        tab.tick(); tab.tick();
        assertEquals(List.of(0), modes.get(subject.getUniqueID()));
        assertNull(subject.worldObj.getScoreboard().getPlayersTeam("Alice"));
    }

    @Test void nativeTeamsArePreservedAndFormattingResumesAfterTheConflictClears() {
        Scoreboard board = new Scoreboard();
        ScorePlayerTeam existing = board.createTeam("mod-team");
        board.func_151392_a("Alice", "mod-team");
        when(subject.getTeam()).thenReturn(existing);
        assertFalse(tab.display(subject, "§6Alice"));
        assertTrue(modes.get(viewer.getUniqueID()).isEmpty());
        assertSame(existing, board.getPlayersTeam("Alice"));
        when(subject.getTeam()).thenReturn(null);
        tab.tick();
        assertNotNull(client(viewer).getPlayersTeam("Alice"));
        assertSame(existing, board.getPlayersTeam("Alice"));
    }

    @Test void perViewerBukkitTeamsWinAndRemovalResynchronizesOnlyThatViewer() {
        assertTrue(tab.display(subject, "§6Alice"));
        ScorePlayerTeam plugin = team("plugin", "Alice");
        external(viewer, new S3EPacketTeams(plugin, 0));
        tab.tick();
        assertEquals("plugin", client(viewer).getPlayersTeam("Alice").getRegisteredName());
        assertTrue(client(subject).getPlayersTeam("Alice").getRegisteredName().startsWith("pg"));
        external(viewer, new S3EPacketTeams(plugin, 1));
        tab.tick();
        assertTrue(client(viewer).getPlayersTeam("Alice").getRegisteredName().startsWith("pg"));
        int delivered = modes.get(viewer.getUniqueID()).size();
        tab.tick(); tab.display(subject, "§6Alice");
        assertEquals(delivered, modes.get(viewer.getUniqueID()).size());
        assertEquals(List.of(0), modes.get(subject.getUniqueID()));
    }

    @Test void rapidTeamChangeBeforeATickRepairsMembershipWithoutDuplicateTeamCreation() {
        tab.display(subject, "§6Alice");
        String owned = client(viewer).getPlayersTeam("Alice").getRegisteredName();
        ScorePlayerTeam plugin = team("temporary", "Alice");
        external(viewer, new S3EPacketTeams(plugin, 0));
        external(viewer, new S3EPacketTeams(plugin, 1));
        tab.tick();
        assertEquals(owned, client(viewer).getPlayersTeam("Alice").getRegisteredName());
        assertEquals(List.of(0, 0, 1, 3), modes.get(viewer.getUniqueID()));
    }

    @Test void removingAnOwnedClientTeamCausesOneCreateReplay() {
        tab.display(subject, "§6Alice");
        ScorePlayerTeam owned = client(viewer).getPlayersTeam("Alice");
        external(viewer, new S3EPacketTeams(owned, 1));
        tab.tick(); tab.tick();
        assertNotNull(client(viewer).getPlayersTeam("Alice"));
        assertEquals(List.of(0, 1, 0), modes.get(viewer.getUniqueID()));
    }

    @Test void pluginReusingAnOwnedIdDoesNotCreateADuplicateOrLoseItsTeamOnReset() {
        tab.display(subject, "§6Alice");
        String owned = client(viewer).getPlayersTeam("Alice").getRegisteredName();
        external(viewer, new S3EPacketTeams(team(owned, "PluginPlayer"), 0));
        tab.tick();
        assertEquals(owned, client(viewer).getPlayersTeam("PluginPlayer").getRegisteredName());
        assertNull(client(viewer).getPlayersTeam("Alice"));
        tab.reset(subject);
        assertNotNull(client(viewer).getTeam(owned));
        assertEquals(List.of(0, 1, 0), modes.get(viewer.getUniqueID()));
    }

    @Test void multiplePlayersHaveIndependentFormattingForEveryViewer() {
        tab.display(subject, "§6[A] Alice!");
        tab.display(viewer, "§b[B] Bob?");
        for (EntityPlayerMP recipient : online) {
            assertEquals("§6[A] ", client(recipient).getPlayersTeam("Alice").getColorPrefix());
            assertEquals("!", client(recipient).getPlayersTeam("Alice").getColorSuffix());
            assertEquals("§b[B] ", client(recipient).getPlayersTeam("Bob").getColorPrefix());
            assertEquals("?", client(recipient).getPlayersTeam("Bob").getColorSuffix());
        }
        tab.reset(subject);
        assertNotNull(client(viewer).getPlayersTeam("Bob"));
        assertNull(client(viewer).getPlayersTeam("Alice"));
    }

    @Test void joinDisconnectAndResetRemoveOnlyParadigmState() {
        ScorePlayerTeam unrelated = team("unrelated", "Elsewhere");
        external(viewer, new S3EPacketTeams(unrelated, 0));
        tab.display(subject, "§6Alice");
        EntityPlayerMP joining = player("Joining");
        online.add(joining);
        tab.joined(joining);
        assertNotNull(client(joining).getPlayersTeam("Alice"));
        tab.disconnected(subject);
        online.remove(subject);
        assertNull(client(viewer).getPlayersTeam("Alice"));
        assertNotNull(client(viewer).getTeam("unrelated"));
        tab.tick();
        assertNull(client(joining).getPlayersTeam("Alice"));
        tab.clear();
        assertNotNull(client(viewer).getTeam("unrelated"));
    }

    @Test void componentSegmentResetsDoNotConsumeTheClosingBracketBudget() {
        var parts = LegacyTablistController.parts("Alice", "§r§4[§r§4Admin§r§4]§r§fAlice§r");
        assertEquals("§4[Admin] §f", parts.prefix());
    }

    @Test void configuredAdminPrefixKeepsItsColorsThroughTheRealLegacySerializer() {
        assertEquals("§6[§4Admin§6] §f", configuredPrefix("&6[&4Admin&6] &7"));
    }

    @Test void configuredLeadAdminPreservesRankColorsAndTextWhenTheLastTransitionCannotFit() {
        assertEquals("§8[§4L.Admin] §f", configuredPrefix("&8[&4L.Admin&8] &7"));
    }

    private String configuredPrefix(String prefix) {
        IPlatformAdapter platform = mock(IPlatformAdapter.class);
        when(platform.replacePlaceholders(anyString(), any())).thenAnswer(call ->
                ((String) call.getArgument(0)).replace("{prefix}", prefix).replace("{player_name}", "Alice"));
        when(platform.createComponentFromLiteral(anyString())).thenAnswer(call -> new MinecraftComponent((String) call.getArgument(0)));
        when(platform.createEmptyComponent()).thenAnswer(call -> new MinecraftComponent(""));
        MinecraftComponent component = (MinecraftComponent) new MessageParser(null, platform)
                .parseMessage("{prefix} <color:white>{player_name}</color>", null);
        var parts = LegacyTablistController.parts("Alice", component.toLegacyText());
        assertTrue(parts.prefix().length() <= 16);
        return parts.prefix();
    }

    @Test void decoratedRankPreservesAllVisibleTextBeforeSacrificingItToTheLimit() {
        var parts = LegacyTablistController.parts("Alice", "§8[§r§4§lAdmin§r§8]§r§fAlice§r");
        assertEquals("§8[§4Admin§8] §f", parts.prefix());
        assertTrue(parts.prefix().length() <= 16);
        assertEquals("§r", parts.suffix());
    }

    @Test void compactionKeepsRealStyleChangesAndResetsAndDropsOnlyRedundantState() {
        var parts = LegacyTablistController.parts("Alice", "§4A§lB§oC§r§fAlice§r§4!§4?");
        assertEquals("§4A§lB§oC §f", parts.prefix());
        assertEquals("§4!?", parts.suffix());
        parts = LegacyTablistController.parts("Alice", "§4§lA§4B§rAlice");
        assertEquals("§4§lA§4B §r", parts.prefix());
    }

    @Test void prefixKeepsASeparatorAndTheUsernameColorAtTheLegacyLimit() {
        var parts = LegacyTablistController.parts("Alice", "§6[Administrator] §r§fAlice§r");
        assertTrue(parts.prefix().length() <= 16);
        assertTrue(parts.prefix().endsWith(" §f"), parts.prefix());
        assertEquals("§r", parts.suffix());
    }

    @Test void visiblePrefixGetsOneSeparatorWithoutChangingColorOnlyPrefixes() {
        assertEquals("§6[Admin] ", LegacyTablistController.parts("Alice", "§6[Admin]Alice").prefix());
        assertEquals("§6[Admin] §f", LegacyTablistController.parts("Alice", "§6[Admin] §fAlice").prefix());
        assertEquals("§6", LegacyTablistController.parts("Alice", "§6Alice").prefix());
        assertEquals("§6[Admin] §7", LegacyTablistController.parts("Alice", "§6[Admin] §7 Alice").prefix());
    }

    @Test void truncationKeepsResetAndDecorationStateAndNeverSplitsASurrogateOrCode() {
        var parts = LegacyTablistController.parts("Alice", "§6[LongAdministrator]§r§lAlice123456789012345§b");
        assertTrue(parts.prefix().length() <= 16);
        assertTrue(parts.prefix().endsWith(" §r§l"), parts.prefix());
        assertEquals("123456789012345", parts.suffix());
        parts = LegacyTablistController.parts("Alice", "123456789012😀longAlice123456789012345😀");
        assertTrue(parts.prefix().length() <= 16);
        assertEquals("123456789012345", parts.suffix());
        assertFalse(Character.isHighSurrogate(parts.prefix().charAt(parts.prefix().length() - 1)));
        assertNull(LegacyTablistController.parts("Alice", "Alice\rfooter"));
    }

    @Test void reconnectReplaysTheLatestRankAndSteadyStateSendsNothing() {
        tab.display(subject, "§6[Admin] Alice");
        tab.display(subject, "§b[VIP] Alice");
        assertEquals("§b[VIP] ", client(viewer).getPlayersTeam("Alice").getColorPrefix());
        int count = modes.get(viewer.getUniqueID()).size();
        tab.tick();
        assertEquals(count, modes.get(viewer.getUniqueID()).size());
        tab.disconnected(viewer);
        online.remove(viewer);
        viewer = player("Bob");
        online.add(viewer);
        tab.joined(viewer);
        assertEquals("§b[VIP] ", client(viewer).getPlayersTeam("Alice").getColorPrefix());
        assertEquals(List.of(0), modes.get(viewer.getUniqueID()));
    }

    private EntityPlayerMP player(String name) {
        EntityPlayerMP player = mock(EntityPlayerMP.class);
        UUID uuid = UUID.randomUUID();
        when(player.getCommandSenderName()).thenReturn(name);
        when(player.getUniqueID()).thenReturn(uuid);
        player.worldObj = mock(WorldServer.class);
        when(player.worldObj.getScoreboard()).thenReturn(new Scoreboard());
        clients.put(uuid, new Scoreboard());
        modes.put(uuid, new ArrayList<>());
        var transport = mock(NetworkManager.class, withSettings().extraInterfaces(ScoreboardConnection.Access.class));
        var journal = new ScoreboardConnection(new ScoreboardConnection.Codec() {
            @Override public ScoreboardConnection.Change team(Object packet) {
                if (!(packet instanceof S3EPacketTeams teams)) return null;
                return new ScoreboardConnection.Change(teamId(teams), teamMode(teams), teamMembers(teams));
            }
            @Override public boolean opensScoreboard(Object packet) { return packet instanceof S01PacketJoinGame; }
            @Override public Object remove(String name) {
                return new S3EPacketTeams(new ScorePlayerTeam(new Scoreboard(), name), ScoreboardConnection.REMOVE);
            }
        });
        when(((ScoreboardConnection.Access) transport).paradigm$scoreboard()).thenReturn(journal);
        var channel = new EmbeddedChannel(journal);
        channel.writeOutbound(new S01PacketJoinGame());
        channel.readOutbound();
        player.playerNetServerHandler = mock(NetHandlerPlayServer.class);
        ReflectionHelper.setPrivateValue(NetHandlerPlayServer.class, player.playerNetServerHandler, transport, "netManager");
        doAnswer(call -> {
            Object outgoing = call.getArgument(0);
            channel.writeOutbound(outgoing);
            for (Object packet; (packet = channel.readOutbound()) != null;) {
                if (!(packet instanceof S3EPacketTeams team)) continue;
                int mode = teamMode(team);
                applyClientPacket(client(player), teamId(team), mode, teamMembers(team), team);
                modes.get(uuid).add(mode);
            }
            return null;
        }).when(player.playerNetServerHandler).sendPacket(isA(S3EPacketTeams.class));
        return player;
    }

    private static String teamId(S3EPacketTeams packet) {
        return ReflectionHelper.getPrivateValue(S3EPacketTeams.class, packet, "field_149320_a");
    }

    private static int teamMode(S3EPacketTeams packet) {
        return ReflectionHelper.getPrivateValue(S3EPacketTeams.class, packet, "field_149314_f");
    }

    private static Collection<String> teamMembers(S3EPacketTeams packet) {
        return ReflectionHelper.getPrivateValue(S3EPacketTeams.class, packet, "field_149317_e");
    }

    private Scoreboard client(EntityPlayerMP player) { return clients.get(player.getUniqueID()); }
    private void external(EntityPlayerMP viewer, S3EPacketTeams packet) { viewer.playerNetServerHandler.sendPacket(packet); }
    private ScorePlayerTeam team(String id, String member) {
        Scoreboard board = new Scoreboard();
        ScorePlayerTeam team = board.createTeam(id);
        board.func_151392_a(member, id);
        return team;
    }

    /** Mirrors vanilla 1.7.10 handleTeams against a real native Scoreboard. */
    private void applyClientPacket(Scoreboard board, String id, int mode, Collection<String> members, S3EPacketTeams packet) {
        ScorePlayerTeam team = mode == 0 ? board.createTeam(id) : board.getTeam(id);
        if (mode == 0 || mode == 2) {
            team.setNamePrefix(ReflectionHelper.getPrivateValue(S3EPacketTeams.class, packet, "field_149319_c"));
            team.setNameSuffix(ReflectionHelper.getPrivateValue(S3EPacketTeams.class, packet, "field_149316_d"));
        }
        if (mode == 0 || mode == 3) for (String member : members) board.func_151392_a(member, id);
        if (mode == 4) for (String member : members) board.removePlayerFromTeam(member, team);
        if (mode == 1) board.removeTeam(team);
    }
}
