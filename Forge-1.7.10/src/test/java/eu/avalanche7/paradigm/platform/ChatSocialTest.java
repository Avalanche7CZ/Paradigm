package eu.avalanche7.paradigm.platform;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.function.Consumer;
import java.util.function.Supplier;

import cpw.mods.fml.common.gameevent.PlayerEvent;
import net.minecraft.entity.player.EntityPlayerMP;
import net.minecraft.util.ChatComponentTranslation;
import net.minecraftforge.event.ServerChatEvent;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.slf4j.LoggerFactory;

import eu.avalanche7.paradigm.configs.ChatConfigHandler;
import eu.avalanche7.paradigm.configs.MainConfigHandler;
import eu.avalanche7.paradigm.configs.MentionConfigHandler;
import eu.avalanche7.paradigm.core.PlayerMessenger;
import eu.avalanche7.paradigm.core.Services;
import eu.avalanche7.paradigm.modules.Mentions;
import eu.avalanche7.paradigm.modules.chat.GroupChat;
import eu.avalanche7.paradigm.modules.chat.PrivateMessages;
import eu.avalanche7.paradigm.modules.chat.StaffChat;
import eu.avalanche7.paradigm.modules.commands.moderation.MuteCommand;
import eu.avalanche7.paradigm.modules.moderation.PunishmentRecord;
import eu.avalanche7.paradigm.modules.moderation.PunishmentService;
import eu.avalanche7.paradigm.modules.moderation.PunishmentType;
import eu.avalanche7.paradigm.modules.permissions.ParadigmPermissions;
import eu.avalanche7.paradigm.modules.permissions.PermissionsHandler;
import eu.avalanche7.paradigm.platform.Interfaces.ICommandSource;
import eu.avalanche7.paradigm.platform.Interfaces.IPlatformAdapter;
import eu.avalanche7.paradigm.platform.Interfaces.IPlayer;
import eu.avalanche7.paradigm.storage.StorageService;
import eu.avalanche7.paradigm.storage.repository.PlayerRepository;
import eu.avalanche7.paradigm.utils.CommandToggleStore;
import eu.avalanche7.paradigm.utils.DebugLogger;
import eu.avalanche7.paradigm.utils.GroupChatManager;
import eu.avalanche7.paradigm.utils.Lang;
import eu.avalanche7.paradigm.utils.MessageParser;

class ChatSocialTest {
    @TempDir Path directory;
    private Services services;
    private IPlatformAdapter platform;
    private MinecraftEventSystem events;
    private PlayerRepository players;
    private PermissionsHandler permissions;
    private final Map<String, Boolean> grants = new HashMap<>();
    private final Map<String, ForgeCommandBuilder> roots = new HashMap<>();
    private final Map<String, IPlayer> online = new HashMap<>();
    private final List<String> delivered = new ArrayList<>();
    private IPlayer a, b, spy;

    @BeforeEach
    void setup() {
        var config = new ForgeConfig(directory);
        MainConfigHandler.init(config, new DebugLogger(null));
        ChatConfigHandler.init(config, new DebugLogger(null));
        MentionConfigHandler.init(config, new DebugLogger(null));
        services = mock(Services.class);
        when(services.moduleActivationGuard(any())).thenReturn(() -> true);
        when(services.canDispatchModuleEvents(org.mockito.ArgumentMatchers.any())).thenReturn(true);
        platform = mock(IPlatformAdapter.class);
        events = new MinecraftEventSystem();
        grants.clear();
        permissions = new PermissionsHandler(LoggerFactory.getLogger("chat-permissions"), null,
                new DebugLogger(null), platform, null, null) {
            @Override
            public boolean hasPermission(IPlayer player, eu.avalanche7.paradigm.modules.permissions.PermissionDefinition permission) {
                return grants.getOrDefault(permission.node(), false);
            }

            @Override
            public boolean hasPermission(IPlayer player, String node) {
                return grants.getOrDefault(node, false);
            }
        };
        when(services.getPlatformAdapter()).thenReturn(platform);
        when(services.getMainConfig()).thenReturn(MainConfigHandler.getConfig());
        when(services.getChatConfig()).thenReturn(ChatConfigHandler.getConfig());
        when(services.getPermissionsHandler()).thenReturn(permissions);
        when(services.getDebugLogger()).thenReturn(new DebugLogger(null));
        when(services.getLogger()).thenReturn(LoggerFactory.getLogger("chat-test"));
        when(services.getPlayerMessenger()).thenReturn(mock(PlayerMessenger.class));
        when(platform.getEventSystem()).thenReturn(events);
        when(platform.createCommandBuilder()).thenAnswer(i -> new ForgeCommandBuilder());
        doAnswer(i -> { var root = i.getArgument(0, ForgeCommandBuilder.class); roots.put(root.literalName(), root); return null; }).when(platform).registerCommand(any());
        when(platform.createLiteralComponent(anyString())).thenAnswer(i -> new MinecraftComponent(i.getArgument(0, String.class)));
        when(platform.createComponentFromLiteral(anyString())).thenAnswer(i -> new MinecraftComponent(i.getArgument(0, String.class)));
        when(platform.createEmptyComponent()).thenAnswer(i -> new MinecraftComponent(""));
        when(platform.getPlayerByUuid(anyString())).thenAnswer(i -> online.get(i.getArgument(0)));
        when(platform.getOnlinePlayers()).thenAnswer(i -> List.copyOf(online.values()));
        when(platform.getVisiblePlayers(any())).thenAnswer(i -> List.copyOf(online.values()));
        when(platform.getPlayerByName(anyString())).thenAnswer(i -> online.values().stream().filter(player -> player.getName().equals(i.getArgument(0))).findFirst().orElse(null));
        when(platform.getPlayerName(any())).thenAnswer(i -> i.getArgument(0, IPlayer.class).getName());
        MessageParser parser = mock(MessageParser.class);
        when(parser.parseMessage(anyString(), any())).thenAnswer(i -> new MinecraftComponent(i.getArgument(0, String.class)));
        when(services.getMessageParser()).thenReturn(parser);
        Lang lang = mock(Lang.class);
        when(lang.translate(anyString())).thenAnswer(i -> new MinecraftComponent(i.getArgument(0, String.class)));
        when(lang.getTranslation(anyString())).thenAnswer(i -> i.getArgument(0));
        when(services.getLang()).thenReturn(lang);
        doAnswer(i -> { delivered.add(i.getArgument(0, IPlayer.class).getName() + ":" + i.getArgument(1, eu.avalanche7.paradigm.platform.Interfaces.IComponent.class).getRawText()); return null; }).when(platform).sendSystemMessage(any(), any());
        CommandToggleStore toggles = mock(CommandToggleStore.class);
        when(toggles.isEnabled(anyString())).thenReturn(true);
        when(services.getCommandToggleStore()).thenReturn(toggles);
        StorageService storage = mock(StorageService.class);
        players = mock(PlayerRepository.class);
        when(storage.players()).thenReturn(players);
        when(services.getStorageService()).thenReturn(storage);
        doAnswer(i -> { i.getArgument(3, Consumer.class).accept(i.getArgument(1, Supplier.class).get()); return null; }).when(storage).runAsync(anyString(), any(), any(), any(), any());
        a = player("A"); b = player("B"); spy = player("Spy");
    }

    private IPlayer player(String name) {
        IPlayer player = mock(IPlayer.class);
        when(player.getUUID()).thenReturn(UUID.randomUUID().toString());
        when(player.getName()).thenReturn(name);
        online.put(player.getUUID(), player);
        return player;
    }

    private ForgeCommandContext context(IPlayer player, Map<String, Object> args) {
        ICommandSource source = mock(ICommandSource.class);
        when(source.getPlayer()).thenReturn(player);
        when(source.requirePlayer()).thenReturn(player);
        return new ForgeCommandContext(source, args, "test");
    }

    private void msg(PrivateMessages module, IPlayer sender, IPlayer target, String text) throws Exception {
        roots.get("msg").children().get(0).children().get(0).executor().execute(context(sender, Map.of("player", target, "message", text)));
    }

    private PrivateMessages pm() {
        PrivateMessages module = new PrivateMessages();
        module.onLoad(null, services, null);
        module.registerCommands(null, null, services);
        module.registerEventListeners(null, services);
        return module;
    }

    @Test
    void groupInviteUsesTheCatalogKeyAndDoesNotClaimAnOfflineInviteSucceeded() {
        GroupChatManager manager = new GroupChatManager();
        manager.setServices(services);
        assertTrue(manager.createGroup(a, "Test"));
        delivered.clear();
        assertTrue(manager.invitePlayer(a, b));
        assertTrue(delivered.contains("A:group.invite_sent"));
        assertTrue(manager.getPlayerData(b).getInvitations().contains("Test"));
        delivered.clear();
        assertFalse(manager.invitePlayer(a, "Missing"));
        assertEquals(List.of("A:group.request_player_offline"), delivered);
    }

    @Test
    void ignoreBlocksDeliveryAndDoesNotCreateAReplyLink() throws Exception {
        PrivateMessages module = pm();
        var ignored = java.util.Set.of(a.getUUID());
        when(players.listIgnoredPlayers(b.getUUID())).thenReturn(ignored);
        msg(module, a, b, "blocked");
        assertEquals(List.of("A:pm.blocked_by_ignore"), delivered);
        delivered.clear();
        roots.get("reply").children().get(0).executor().execute(context(a, Map.of("message", "reply")));
        assertEquals(List.of("A:pm.no_reply_target"), delivered);
    }

    @Test
    void pmUpdatesBothReplyDirectionsAndExcludesParticipantsFromSpyCopies() throws Exception {
        PrivateMessages module = pm();
        grants.put(ParadigmPermissions.SOCIAL_SPY.node(), true);
        roots.get("socialspy").executor().execute(context(spy, Map.of()));
        roots.get("socialspy").executor().execute(context(a, Map.of()));
        delivered.clear();
        msg(module, a, b, "original");
        assertEquals(3, delivered.size());
        assertEquals(1, delivered.stream().filter(line -> line.startsWith("A:")).count());
        assertEquals(1, delivered.stream().filter(line -> line.startsWith("Spy:") && line.contains("SocialSpy")).count());
        delivered.clear();
        roots.get("reply").children().get(0).executor().execute(context(b, Map.of("message", "return")));
        assertEquals(3, delivered.size());
        assertTrue(delivered.stream().anyMatch(line -> line.startsWith("A:") && line.contains("return")));
        delivered.clear();
        roots.get("reply").children().get(0).executor().execute(context(a, Map.of("message", "again")));
        assertTrue(delivered.stream().anyMatch(line -> line.startsWith("B:") && line.contains("again")));
    }

    @Test
    void socialSpyRechecksPermissionAndModuleDisableClearsItsState() throws Exception {
        PrivateMessages module = pm();
        grants.put(ParadigmPermissions.SOCIAL_SPY.node(), true);
        roots.get("socialspy").executor().execute(context(spy, Map.of()));
        delivered.clear(); grants.put(ParadigmPermissions.SOCIAL_SPY.node(), false);
        msg(module, a, b, "denied");
        assertEquals(2, delivered.size());
        grants.put(ParadigmPermissions.SOCIAL_SPY.node(), true); module.onDisable(services); delivered.clear();
        msg(module, a, b, "disabled spy");
        assertEquals(2, delivered.size());
    }

    @Test
    void disconnectClearsBothReplyLinksAndSpyState() throws Exception {
        PrivateMessages module = pm();
        msg(module, a, b, "linked");
        EntityPlayerMP handle = mock(EntityPlayerMP.class);
        UUID nativeUuid = UUID.fromString(b.getUUID());
        when(handle.getUniqueID()).thenReturn(nativeUuid);
        events.left(new PlayerEvent.PlayerLoggedOutEvent(handle));
        online.remove(b.getUUID()); delivered.clear();
        roots.get("reply").children().get(0).executor().execute(context(a, Map.of("message", "offline")));
        assertEquals(List.of("A:pm.no_reply_target"), delivered);
    }

    @Test
    void mentionsKeepChatAndSoundWhenTitlesAreUnsupported() {
        grants.put(ParadigmPermissions.MENTION_PLAYER.node(), true);
        Mentions mentions = new Mentions();
        mentions.onLoad(null, services, null);
        mentions.handleChatMessage(a, "@B hello", services);
        verify(platform).playSound(eq(b), eq("minecraft:entity.experience_orb.pickup"), eq("players"), eq(1.0f), eq(1.0f));
        verify(platform, never()).sendTitle(any(), any(), any());
        assertTrue(delivered.stream().anyMatch(line -> line.startsWith("B:")));
    }

    private ServerChatEvent chat(EntityPlayerMP player, String message) {
        return new ServerChatEvent(player, message, new ChatComponentTranslation("chat.type.text", "A", message)) {
            @Override
            public boolean isCancelable() {
                return true;
            }
        };
    }

    @Test
    void mutePrecedesRoutingAndStaffPrecedesGroupWithoutPublicMentions() throws Exception {
        PunishmentService punishments = mock(PunishmentService.class);
        when(services.getPunishmentService()).thenReturn(punishments);
        new MuteCommand().registerEventListeners(null, services);
        StaffChat staff = new StaffChat(); staff.onLoad(null, services, null); staff.registerCommands(null, null, services); staff.registerEventListeners(null, services);
        GroupChatManager manager = new GroupChatManager(); manager.setServices(services);
        GroupChat group = new GroupChat(manager); group.onLoad(null, services, null); group.registerEventListeners(null, services);
        Mentions mentions = new Mentions(); mentions.onLoad(null, services, null); mentions.registerEventListeners(null, services);
        grants.put(PermissionsHandler.STAFF_CHAT_PERMISSION, true);
        grants.put(PermissionsHandler.GROUPCHAT_PERMISSION, true);
        manager.createGroup(a, "test"); manager.setGroupChatToggled(a, true);
        roots.get("sc").executor().execute(context(a, Map.of()));
        verify(platform, never()).showPersistentBossBar(any(), any(), any(), any());
        EntityPlayerMP handle = mock(EntityPlayerMP.class); UUID nativeUuid = UUID.fromString(a.getUUID());
        when(handle.getUniqueID()).thenReturn(nativeUuid); when(handle.getCommandSenderName()).thenReturn("A");
        when(handle.getGameProfile()).thenReturn(new com.mojang.authlib.GameProfile(nativeUuid, "A"));
        var event = chat(handle, "@B staff");
        events.chat(event);
        assertTrue(event.isCanceled());
        verify(services.getPlayerMessenger(), times(1)).broadcastToPermission(any(), eq(PermissionsHandler.STAFF_CHAT_PERMISSION), any());
        verify(platform, never()).playSound(any(), anyString(), anyString(), anyFloat(), anyFloat());
        clearInvocations(services.getPlayerMessenger());
        roots.get("sc").executor().execute(context(a, Map.of()));
        delivered.clear();
        var grouped = chat(handle, "@B group");
        events.chat(grouped);
        assertTrue(grouped.isCanceled());
        assertTrue(delivered.stream().anyMatch(line -> line.contains("[Group: test]")));
        verify(services.getPlayerMessenger(), never()).broadcastToPermission(any(), anyString(), any());
        verify(platform, never()).playSound(any(), anyString(), anyString(), anyFloat(), anyFloat());
        grants.put(PermissionsHandler.GROUPCHAT_PERMISSION, false);
        var revokedGroup = chat(handle, "@B revoked group");
        events.chat(revokedGroup);
        assertTrue(revokedGroup.isCanceled());
        assertFalse(manager.isGroupChatToggled(a));
        manager.setGroupChatToggled(a, true);
        grants.put(PermissionsHandler.GROUPCHAT_PERMISSION, true);
        roots.get("sc").executor().execute(context(a, Map.of()));
        grants.put(PermissionsHandler.STAFF_CHAT_PERMISSION, false);
        var revokedStaff = chat(handle, "@B revoked staff");
        events.chat(revokedStaff);
        assertTrue(revokedStaff.isCanceled());
        grants.put(PermissionsHandler.STAFF_CHAT_PERMISSION, true);
        roots.get("sc").executor().execute(context(a, Map.of()));
        PunishmentRecord mute = mock(PunishmentRecord.class); when(mute.type()).thenReturn(PunishmentType.MUTE);
        when(punishments.activeFor(a.getUUID(), null)).thenReturn(List.of(mute));
        var blocked = chat(handle, "@B muted");
        events.chat(blocked);
        assertTrue(blocked.isCanceled());
        verify(services.getPlayerMessenger(), never()).broadcastToPermission(any(), anyString(), any());
        verify(platform, never()).playSound(any(), anyString(), anyString(), anyFloat(), anyFloat());
    }
}
