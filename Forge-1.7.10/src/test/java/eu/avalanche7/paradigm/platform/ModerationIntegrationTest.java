package eu.avalanche7.paradigm.platform;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import java.net.InetSocketAddress;
import java.nio.file.Path;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;
import java.util.function.Supplier;

import com.mojang.authlib.GameProfile;
import net.minecraft.entity.player.EntityPlayerMP;
import net.minecraft.server.MinecraftServer;
import net.minecraft.util.ChatComponentTranslation;
import net.minecraft.util.EnumChatFormatting;
import net.minecraftforge.event.ServerChatEvent;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.slf4j.LoggerFactory;

import eu.avalanche7.paradigm.configs.MainConfigHandler;
import eu.avalanche7.paradigm.configs.ModerationConfigHandler;
import eu.avalanche7.paradigm.core.ParadigmEvents;
import eu.avalanche7.paradigm.core.PlayerMessenger;
import eu.avalanche7.paradigm.core.Services;
import eu.avalanche7.paradigm.modules.audit.AuditService;
import eu.avalanche7.paradigm.modules.commands.moderation.JailCommand;
import eu.avalanche7.paradigm.modules.commands.moderation.MuteCommand;
import eu.avalanche7.paradigm.modules.moderation.IpAddressUtil;
import eu.avalanche7.paradigm.modules.moderation.PunishmentRecord;
import eu.avalanche7.paradigm.modules.moderation.PunishmentService;
import eu.avalanche7.paradigm.modules.moderation.PunishmentType;
import eu.avalanche7.paradigm.modules.moderation.WarnEscalationService;
import eu.avalanche7.paradigm.platform.Interfaces.IPlatformAdapter;
import eu.avalanche7.paradigm.storage.StorageConfig;
import eu.avalanche7.paradigm.storage.StorageService;
import eu.avalanche7.paradigm.storage.identity.ServerIdentityService;
import eu.avalanche7.paradigm.storage.identity.ServerScope;
import eu.avalanche7.paradigm.storage.identity.StorageContext;
import eu.avalanche7.paradigm.storage.model.StoredJailState;
import eu.avalanche7.paradigm.storage.model.StoredLocation;
import eu.avalanche7.paradigm.storage.sqlite.SqliteStorageProvider;
import eu.avalanche7.paradigm.utils.DebugLogger;
import eu.avalanche7.paradigm.utils.Lang;
import eu.avalanche7.paradigm.utils.MessageParser;
import eu.avalanche7.paradigm.utils.TaskScheduler;

class ModerationIntegrationTest {
    @TempDir
    Path directory;
    private static final UUID UUID_VALUE = UUID.fromString("00000000-0000-0000-0000-000000000017");
    private SqliteStorageProvider provider;
    private Services services;
    private PunishmentService punishments;
    private IPlatformAdapter platform;
    private TaskScheduler scheduler;
    private MinecraftEventSystem events;
    private PlayerMessenger messenger;
    private AuditService audit;

    @BeforeEach
    void setUp() {
        ForgeConfig config = new ForgeConfig(directory);
        MainConfigHandler.init(config, new DebugLogger(null));
        MainConfigHandler.getConfig().moderationCommandsEnable.value = true;
        ModerationConfigHandler.init(config, new DebugLogger(null));
        provider = provider("a");
        provider.initialize();
        StorageService storage = mock(StorageService.class);
        when(storage.context()).thenReturn(new StorageContext(new eu.avalanche7.paradigm.storage.identity.ServerIdentity("network", "a", "a")));
        when(storage.moderation()).thenReturn(provider.moderation());
        services = mock(Services.class);
        when(services.canDispatchModuleEvents(org.mockito.ArgumentMatchers.any())).thenReturn(true);
        when(services.getChatFormatter()).thenReturn(mock(eu.avalanche7.paradigm.modules.chat.ChatFormatter.class));
        platform = mock(IPlatformAdapter.class);
        doAnswer(i -> { i.getArgument(0, Runnable.class).run(); return null; }).when(platform).executeOnServerThread(any());
        scheduler = mock(TaskScheduler.class);
        when(scheduler.scheduleAtFixedRate(any(), anyLong(), anyLong(), any()))
                .thenReturn(mock(java.util.concurrent.ScheduledFuture.class));
        events = new MinecraftEventSystem();
        messenger = mock(PlayerMessenger.class);
        audit = mock(AuditService.class);
        when(services.getLogger()).thenReturn(LoggerFactory.getLogger("moderation-test"));
        when(services.getStorageService()).thenReturn(storage);
        when(services.getPlatformAdapter()).thenReturn(platform);
        when(services.getTaskScheduler()).thenReturn(scheduler);
        when(services.getPlayerMessenger()).thenReturn(messenger);
        when(services.getMainConfig()).thenReturn(MainConfigHandler.getConfig());
        when(platform.getEventSystem()).thenReturn(events);
        when(services.getParadigmEvents()).thenReturn(new ParadigmEvents());
        Lang lang = mock(Lang.class);
        when(lang.getTranslation(anyString())).thenReturn("Permanent");
        when(services.getLang()).thenReturn(lang);
        MessageParser parser = mock(MessageParser.class);
        when(parser.parseMessage(anyString(), isNull())).thenAnswer(i -> new MinecraftComponent(i.getArgument(0, String.class)));
        when(services.getMessageParser()).thenReturn(parser);
        when(platform.createEmptyComponent()).thenAnswer(i -> new MinecraftComponent(""));
        when(platform.createComponentFromLiteral(anyString())).thenAnswer(i -> new MinecraftComponent(i.getArgument(0, String.class)));
        doAnswer(i -> {
            try {
                Object result = i.getArgument(1, Supplier.class).get();
                i.getArgument(3, Consumer.class).accept(result);
            } catch (RuntimeException failure) {
                i.getArgument(4, Consumer.class).accept(failure);
            }
            return null;
        }).when(storage).runAsync(anyString(), any(), any(), any(), any());
        punishments = new PunishmentService(services, audit);
        when(services.getPunishmentService()).thenReturn(punishments);
        punishments.start();
        MinecraftLoginHandler.bind(services);
    }

    @AfterEach
    void tearDown() {
        MinecraftLoginHandler.clear();
        punishments.stop();
        provider.close();
    }

    private SqliteStorageProvider provider(String serverId) {
        StorageConfig config = new StorageConfig();
        config.provider = "sqlite";
        config.networkId = "network";
        config.serverId = serverId;
        config.sqlite.path = directory.resolve("shared.db").toString();
        config.runtimeLibraries.enabled = false;
        var identity = new ServerIdentityService(null, config);
        return new SqliteStorageProvider(config, new StorageContext(identity.current()), identity, null, null);
    }

    private PunishmentRecord create(PunishmentType type, ServerScope scope, String ip, Long expires) {
        return punishments.create(type, scope, UUID_VALUE.toString(), "Player", ip,
                "Test reason", UUID.randomUUID().toString(), "Staff", expires);
    }

    @Test
    void jailReplacementOnBoundServerThreadCompletesWithoutWaitingForTick() {
        PlatformAdapterImpl actual = spy(new PlatformAdapterImpl(null, null, null, null, new ForgeConfig(directory)));
        actual.setMinecraftServer(mock(MinecraftServer.class));
        when(services.getPlatformAdapter()).thenReturn(actual);
        doReturn(null).when(actual).getPlayerByUuid(UUID_VALUE.toString());
        StoredLocation location = new StoredLocation("0", 10, 70, 20, 0, 0);

        PunishmentRecord first = assertTimeout(java.time.Duration.ofSeconds(2), () ->
                punishments.replaceJail(UUID_VALUE.toString(), "Player", location, "First", null, "Staff", null,
                        eu.avalanche7.paradigm.modules.audit.AuditSource.COMMAND));
        PunishmentRecord replacement = assertTimeout(java.time.Duration.ofSeconds(2), () ->
                punishments.replaceJail(UUID_VALUE.toString(), "Player", location, "Replacement", null, "Staff", null,
                        eu.avalanche7.paradigm.modules.audit.AuditSource.COMMAND));

        assertEquals(replacement.punishmentId(), provider.moderation().getJailState(UUID_VALUE.toString()).orElseThrow().punishmentId());
        assertFalse(punishments.find(first.punishmentId()).orElseThrow().activeAt(System.currentTimeMillis()));
        assertEquals(1, punishments.activeRecords(UUID_VALUE.toString(), PunishmentType.JAIL).size());
    }

    @Test
    void asynchronousJailReplacementWaitsForServerTick() throws Exception {
        PlatformAdapterImpl actual = spy(new PlatformAdapterImpl(null, null, null, null, new ForgeConfig(directory)));
        actual.setMinecraftServer(mock(MinecraftServer.class));
        when(services.getPlatformAdapter()).thenReturn(actual);
        Thread serverThread = Thread.currentThread();
        doAnswer(call -> {
            assertEquals(serverThread, Thread.currentThread());
            return null;
        }).when(actual).getPlayerByUuid(UUID_VALUE.toString());
        CountDownLatch queued = new CountDownLatch(1);
        doAnswer(call -> {
            Object result = call.callRealMethod();
            queued.countDown();
            return result;
        }).when(actual).executeOnServerThread(any());
        StoredLocation location = new StoredLocation("0", 10, 70, 20, 0, 0);
        CompletableFuture<PunishmentRecord> result = CompletableFuture.supplyAsync(() ->
                punishments.replaceJail(UUID_VALUE.toString(), "Player", location, "Async", null, "Staff", null,
                        eu.avalanche7.paradigm.modules.audit.AuditSource.DASHBOARD));
        try {
            assertTrue(queued.await(2, TimeUnit.SECONDS));
            assertFalse(result.isDone());
            long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
            while (!result.isDone() && System.nanoTime() < deadline) {
                actual.tick();
                Thread.sleep(5);
            }
            assertEquals(result.get(1, TimeUnit.SECONDS).punishmentId(),
                    provider.moderation().getJailState(UUID_VALUE.toString()).orElseThrow().punishmentId());
            verify(actual, atLeast(2)).executeOnServerThread(any());
        } finally {
            result.cancel(false);
        }
    }

    @Test
    void legacyDisconnectTextPreservesLinesAndDetachedInheritedFormatting() {
        MinecraftComponent screen = (MinecraftComponent) new MinecraftComponent("Banned").withColor("red").withFormatting("bold");
        screen.append(new MinecraftComponent("\nReason").withColor("white").withItalic(true).withFormatting("bold"));
        String text = screen.toLegacyText();
        assertTrue(text.contains("§c§lBanned§r"));
        assertTrue(text.contains("§f§l§o\nReason§r"));
        assertEquals("Banned\nReason", EnumChatFormatting.getTextWithoutFormattingCodes(text));
        assertEquals(text, ((MinecraftComponent) screen.copy()).toLegacyText());
    }

    @Test
    void commonRefreshIsScheduledOnceAndCancelledOnStop() {
        punishments.stop();
        var handle = mock(java.util.concurrent.ScheduledFuture.class);
        when(scheduler.scheduleAtFixedRate(any(), anyLong(), anyLong(), any())).thenReturn(handle);
        punishments.start();
        punishments.start();
        verify(scheduler, times(2)).scheduleAtFixedRate(any(), eq(10L), eq(10L), eq(TimeUnit.SECONDS));
        punishments.stop();
        punishments.stop();
        verify(handle).cancel(false);
        assertFalse(punishments.isStarted());
    }

    @Test
    void commonJailRollsBackItsPunishmentAndStateWhenNativeTeleportFails() throws Exception {
        provider.moderation().setJailLocation(new StoredLocation("123456", 10, 70, -5, 90, 12));
        var builder = mock(eu.avalanche7.paradigm.platform.Interfaces.ICommandBuilder.class, RETURNS_SELF);
        var executors = new java.util.ArrayList<eu.avalanche7.paradigm.platform.Interfaces.ICommandBuilder.CommandExecutor>();
        doAnswer(i -> { executors.add(i.getArgument(0)); return builder; }).when(builder).executes(any());
        when(platform.createCommandBuilder()).thenReturn(builder);
        var player = mock(eu.avalanche7.paradigm.platform.Interfaces.IPlayer.class);
        when(player.getUUID()).thenReturn(UUID_VALUE.toString());
        when(player.getName()).thenReturn("Player");
        when(platform.getPlayerByUuid(UUID_VALUE.toString())).thenReturn(player);
        var source = mock(eu.avalanche7.paradigm.platform.Interfaces.ICommandSource.class);
        when(source.getSourceName()).thenReturn("Console");
        var context = mock(eu.avalanche7.paradigm.platform.Interfaces.ICommandContext.class);
        when(context.getSource()).thenReturn(source);
        when(context.getPlayerArgument("player")).thenReturn(player);
        new JailCommand().registerCommands(null, null, services);
        executors.get(1).execute(context);
        assertTrue(provider.moderation().getJailState(UUID_VALUE.toString()).isEmpty());
        assertTrue(punishments.activeFor(UUID_VALUE.toString(), null).isEmpty());
        var ledger = punishments.history(UUID_VALUE.toString(), 1, 10);
        assertTrue(ledger.isEmpty(), "Failed teleport must not publish a successful jail record.");
    }

    @Test
    void permanentBanSurvivesColdRepositoryReloadAndRevocationPublishesAuditAndEvents() {
        var listener = mock(ParadigmEvents.Listener.class);
        services.getParadigmEvents().register(listener);
        var record = create(PunishmentType.BAN, ServerScope.GLOBAL, null, null);
        provider.close();
        provider = provider("a");
        provider.initialize();
        when(services.getStorageService().moderation()).thenReturn(provider.moderation());
        punishments.refreshNow();
        var loaded = punishments.loginBlock(UUID_VALUE.toString(), null).orElseThrow();
        assertEquals(record, loaded);
        assertTrue(punishments.revoke(record.punishmentId(), null, "Console", "Appeal"));
        assertTrue(punishments.loginBlock(UUID_VALUE.toString(), null).isEmpty());
        assertEquals("Appeal", provider.moderation().findPunishmentRecord(record.punishmentId()).orElseThrow().revokeReason());
        verify(listener).onPunishmentCreated(record);
        verify(listener).onPunishmentRevoked(any());
        verify(audit, times(2)).record(any(), any(), any(), any(), any(), anyString(), anyMap());
    }

    @Test
    void sharedSqlContextsSeparateServerBansAndApplyGlobalBansToBothServers() {
        var serverBan = create(PunishmentType.BAN, ServerScope.SERVER, null, null);
        try (var other = provider("b")) {
            other.initialize();
            var cache = new eu.avalanche7.paradigm.modules.moderation.ActivePunishmentCache();
            cache.replace(other.moderation().listActivePunishmentRecords(0));
            assertTrue(punishments.loginBlock(UUID_VALUE.toString(), null).isPresent());
            assertTrue(cache.loginBlock(UUID_VALUE.toString(), null, "network", "b").isEmpty());
            assertTrue(punishments.revoke(serverBan.punishmentId(), null, "Console", "Replace"));
            var global = create(PunishmentType.BAN, ServerScope.GLOBAL, null, null);
            cache.replace(other.moderation().listActivePunishmentRecords(0));
            assertEquals(global.punishmentId(), cache.loginBlock(UUID_VALUE.toString(), null, "network", "b").orElseThrow().punishmentId());
            assertTrue(cache.loginBlock(UUID_VALUE.toString(), null, "other-network", "b").isEmpty());
        }
    }

    @Test
    void admissionRejectsActiveUuidAndIpBansWithCommonScreenButIgnoresExpiredBan() {
        var expired = create(PunishmentType.BAN, ServerScope.GLOBAL, null, System.currentTimeMillis() - 1);
        GameProfile profile = new GameProfile(UUID_VALUE, "Player");
        var address = new InetSocketAddress("127.0.0.1", 45123);
        assertNull(MinecraftLoginHandler.rejection(address, profile));
        var ban = create(PunishmentType.BAN, ServerScope.GLOBAL, null, System.currentTimeMillis() + 60000);
        String screen = MinecraftLoginHandler.rejection(address, profile);
        assertTrue(screen.contains(ban.punishmentId()));
        assertTrue(screen.contains("Test reason"));
        assertTrue(screen.contains("Staff"));
        assertTrue(punishments.revoke(ban.punishmentId(), null, "Console", "Test"));
        var ipBan = create(PunishmentType.IP_BAN, ServerScope.GLOBAL, "/127.0.0.1:45123", null);
        assertTrue(MinecraftLoginHandler.rejection(address, new GameProfile(UUID.randomUUID(), "Other")).contains(ipBan.punishmentId()));
        assertEquals("127.0.0.1", ipBan.subjectIpAddress());
        assertEquals(IpAddressUtil.hash("127.0.0.1"), ipBan.subjectIpHash());
        assertFalse(punishments.activeFor(UUID_VALUE.toString(), null).contains(expired));
    }

    @Test
    void onlineDisconnectUsesSameLegacyFormattingAsAdmission() {
        var adapter = new PlatformAdapterImpl(null, new eu.avalanche7.paradigm.utils.Placeholders(),
                scheduler, new DebugLogger(null), new ForgeConfig(directory));
        EntityPlayerMP player = mock(EntityPlayerMP.class);
        player.playerNetServerHandler = mock(net.minecraft.network.NetHandlerPlayServer.class);
        var reason = (MinecraftComponent) new MinecraftComponent("Banned\nReason").withColor("red");
        assertTrue(adapter.disconnectPlayer(new MinecraftPlayer(player), reason));
        verify(player.playerNetServerHandler).kickPlayerFromServer(reason.toLegacyText());
    }

    @Test
    void nativeAddressExtractionDoesNotIncludePortsOrResolveUnknownHosts() {
        assertEquals("127.0.0.1", MinecraftLoginHandler.remoteAddress(new InetSocketAddress("127.0.0.1", 1234)));
        String ipv6 = MinecraftLoginHandler.remoteAddress(new InetSocketAddress("::1", 1234));
        assertEquals(IpAddressUtil.canonicalize("[::1]:1234"), IpAddressUtil.canonicalize(ipv6));
        assertNull(MinecraftLoginHandler.remoteAddress(InetSocketAddress.createUnresolved("proxy.example", 1234)));
        assertNull(MinecraftLoginHandler.remoteAddress(null));
    }

    @Test
    void commonMuteCancelsNativeChatBeforeLaterListenersAndExpiredMuteDoesNot() {
        try (var runtime = mockStatic(eu.avalanche7.paradigm.Paradigm.class)) {
        runtime.when(eu.avalanche7.paradigm.Paradigm::getServices).thenReturn(services);
        new MuteCommand().registerEventListeners(null, services);
        var later = mock(eu.avalanche7.paradigm.platform.Interfaces.IEventSystem.ChatEventListener.class);
        events.onPlayerChat(later);
        EntityPlayerMP player = mock(EntityPlayerMP.class);
        when(player.getUniqueID()).thenReturn(UUID_VALUE);
        when(player.getCommandSenderName()).thenReturn("Player");
        when(player.getGameProfile()).thenReturn(new GameProfile(UUID_VALUE, "Player"));
        var mute = create(PunishmentType.MUTE, ServerScope.SERVER, null, null);
        ServerChatEvent blocked = new ServerChatEvent(player, "secret", new ChatComponentTranslation("chat.type.text", "Player", "secret")) {
            @Override
            public boolean isCancelable() {
                return true;
            }
        };
        events.chat(blocked);
        assertTrue(blocked.isCanceled());
        verifyNoInteractions(later);
        verify(messenger).sendDecorated(any(), eq("Moderation"), eq("moderation.muted_chat_blocked"), anyString(), any(String[].class));
        punishments.revoke(mute.punishmentId(), null, "Console", "Test");
        create(PunishmentType.MUTE, ServerScope.SERVER, null, System.currentTimeMillis() - 1);
        ServerChatEvent normal = new ServerChatEvent(player, "normal", new ChatComponentTranslation("chat.type.text", "Player", "normal"));
        events.chat(normal);
        assertFalse(normal.isCanceled());
        verify(later).onPlayerChat(any());
        }
    }

    @Test
    void commonWarningEscalationCreatesStoredTemporaryGlobalBan() {
        ModerationConfigHandler.getConfig().warnEscalationEnabled.value = true;
        ModerationConfigHandler.getConfig().warnEscalationRules.value = List.of(new ModerationConfigHandler.EscalationRule(1, "1h", "5s", "Escalation"));
        var listener = mock(ParadigmEvents.Listener.class);
        services.getParadigmEvents().register(listener);
        var warning = create(PunishmentType.WARN, ServerScope.GLOBAL, null, null);
        var result = new WarnEscalationService(services).evaluate(UUID_VALUE.toString(), "Player", warning, null, "Staff");
        assertNotNull(result);
        assertEquals(PunishmentType.BAN, result.punishment().type());
        assertTrue(result.punishment().expiresAtMs() > System.currentTimeMillis());
        assertTrue(punishments.loginBlock(UUID_VALUE.toString(), null).isPresent());
        assertEquals(2, punishments.history(UUID_VALUE.toString(), 1, 10).size());
        verify(listener).onPunishmentCreated(warning);
        verify(listener).onPunishmentCreated(result.punishment());
        assertEquals(warning.punishmentId(), result.punishment().metadata().get(WarnEscalationService.METADATA_TRIGGER));
        assertEquals("Staff", result.punishment().actorName());
        assertEquals(ServerScope.GLOBAL, result.punishment().scope());
        verify(audit, times(2)).record(any(), any(), any(), eq(eu.avalanche7.paradigm.modules.audit.AuditActionType.MODERATION_ACTION),
                eq(eu.avalanche7.paradigm.modules.audit.AuditResult.SUCCESS), anyString(), anyMap());
    }

    @Test
    void jailPersistsAndExpiredJoinStateNeverTeleportsBeforeCleanup() {
        var location = new StoredLocation("7", 10, 70, -5, 90, 12);
        var jail = new StoredJailState("a", UUID_VALUE.toString(), "Player", "Reason", "Staff", location, 1, System.currentTimeMillis() - 1);
        provider.moderation().setJailState(jail);
        provider.close();
        provider = provider("a");
        provider.initialize();
        when(services.getStorageService().moderation()).thenReturn(provider.moderation());
        assertEquals(jail, provider.moderation().getJailState(UUID_VALUE.toString()).orElseThrow());
        doAnswer(i -> { i.getArgument(0, Runnable.class).run(); return null; }).when(scheduler).schedule(any(Runnable.class), anyLong(), any(TimeUnit.class));
        new JailCommand().registerEventListeners(null, services);
        EntityPlayerMP player = mock(EntityPlayerMP.class);
        when(player.getUniqueID()).thenReturn(UUID_VALUE);
        when(player.getEntityData()).thenReturn(new net.minecraft.nbt.NBTTagCompound());
        events.joined(new cpw.mods.fml.common.gameevent.PlayerEvent.PlayerLoggedInEvent(player));
        verify(platform, never()).teleportPlayer(any(), any(eu.avalanche7.paradigm.data.PlayerDataStore.StoredLocation.class));
        assertTrue(provider.moderation().consumeExpiredJails(System.currentTimeMillis()).isEmpty());
        assertTrue(provider.moderation().getJailState(UUID_VALUE.toString()).isEmpty());
    }
}
