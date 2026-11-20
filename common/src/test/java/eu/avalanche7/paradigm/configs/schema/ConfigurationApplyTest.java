package eu.avalanche7.paradigm.configs.schema;

import static org.junit.jupiter.api.Assertions.*;

import java.io.ByteArrayInputStream;
import java.io.InputStreamReader;
import java.lang.reflect.Field;
import java.lang.reflect.Proxy;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.FileTime;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Delayed;
import java.util.concurrent.LinkedBlockingDeque;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import com.google.gson.JsonParser;
import com.google.gson.JsonPrimitive;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.slf4j.LoggerFactory;

import eu.avalanche7.paradigm.ParadigmAPI;
import eu.avalanche7.paradigm.configs.BaseConfigHandler;
import eu.avalanche7.paradigm.configs.ConfigSection;
import eu.avalanche7.paradigm.configs.CooldownConfigHandler;
import eu.avalanche7.paradigm.configs.DiscordConfigHandler;
import eu.avalanche7.paradigm.configs.MainConfigHandler;
import eu.avalanche7.paradigm.configs.TablistConfigHandler;
import eu.avalanche7.paradigm.core.CommonRuntime;
import eu.avalanche7.paradigm.core.ParadigmModule;
import eu.avalanche7.paradigm.core.Services;
import eu.avalanche7.paradigm.modules.commands.Reload;
import eu.avalanche7.paradigm.modules.dashboard.DashboardConfig;
import eu.avalanche7.paradigm.modules.dashboard.DashboardJson;
import eu.avalanche7.paradigm.modules.dashboard.DashboardRequestContext;
import eu.avalanche7.paradigm.modules.dashboard.DashboardService;
import eu.avalanche7.paradigm.modules.dashboard.api.ConfigApiHandler;
import eu.avalanche7.paradigm.modules.dashboard.auth.DashboardPrincipal;
import eu.avalanche7.paradigm.modules.menus.Menus;
import eu.avalanche7.paradigm.modules.permissions.PermissionsHandler;
import eu.avalanche7.paradigm.modules.tab.Tablist;
import eu.avalanche7.paradigm.platform.Interfaces.IConfig;
import eu.avalanche7.paradigm.platform.Interfaces.IEventSystem;
import eu.avalanche7.paradigm.platform.Interfaces.IPlatformAdapter;
import eu.avalanche7.paradigm.storage.StorageService;
import eu.avalanche7.paradigm.storage.identity.ServerScope;
import eu.avalanche7.paradigm.storage.managedconfig.ManagedConfigAppliedState;
import eu.avalanche7.paradigm.storage.managedconfig.ManagedConfigEntry;
import eu.avalanche7.paradigm.storage.managedconfig.ManagedConfigRevisionView;
import eu.avalanche7.paradigm.storage.repository.ManagedConfigRepository;
import eu.avalanche7.paradigm.storage.repository.ModerationRepository;
import eu.avalanche7.paradigm.utils.DebugLogger;
import eu.avalanche7.paradigm.utils.TaskScheduler;

class ConfigurationApplyTest {
    @TempDir Path root;
    private final Map<Field, Object> originals = new LinkedHashMap<>();
    private final LinkedBlockingDeque<Runnable> toggleTasks = new LinkedBlockingDeque<>();
    private final BlockingQueue<Runnable> timeouts = new LinkedBlockingQueue<>();
    private final BlockingQueue<Runnable> server = new LinkedBlockingQueue<>();
    private final BlockingQueue<ManagedConfigAppliedState> acknowledgements = new LinkedBlockingQueue<>();
    private final AtomicReference<ManagedConfigEntry> managed = new AtomicReference<>();
    private final AtomicReference<ManagedConfigAppliedState> applied = new AtomicReference<>();
    private final AtomicInteger cacheReads = new AtomicInteger();
    private final AtomicBoolean failCache = new AtomicBoolean();
    private final AtomicInteger tabListeners = new AtomicInteger();
    private final CountingScheduler scheduler = new CountingScheduler();
    private Services services;
    private StorageService bootstrapStorage;
    private StorageService storage;
    private DashboardService dashboard;
    private Tablist tab;

    @BeforeEach
    void setup() throws Exception {
        snapshotStatic(ParadigmAPI.class.getDeclaredField("instance"), false);
        snapshotStatic(Tablist.class.getDeclaredField("current"), false);
        snapshotStatic(Menus.class.getDeclaredField("current"), false);
        for (var section : ConfigSection.values()) {
            Class<?> type = switch (section) {
                case MAIN -> MainConfigHandler.class;
                case TABLIST -> TablistConfigHandler.class;
                case DISCORD -> DiscordConfigHandler.class;
                case COMMANDS, CUSTOMCOMMANDS, MENUS, HOLOGRAMS, DASHBOARD -> null;
                default -> Class.forName(BaseConfigHandler.class.getPackageName() + "." + switch (section) {
                    case MOTD -> "MOTD";
                    case MENTIONS -> "Mention";
                    case COOLDOWNS -> "Cooldown";
                    default -> section.name().charAt(0) + section.key().substring(1);
                } + "ConfigHandler");
            };
            if (type != null) {
                for (Field field : type.getDeclaredFields()) {
                    if (field.getType() == type) snapshotStatic(field, true);
                }
            }
        }

        Path configFolder = Files.createDirectories(root.resolve("paradigm"));
        Files.writeString(configFolder.resolve("storage.json"), "{\"provider\":\"json\"}");
        IConfig config = new IConfig() {
            @Override public Path getConfigDirectory() { return root; }
            @Override public String getModId() { return "paradigm"; }
        };
        var eventSystem = (IEventSystem) Proxy.newProxyInstance(getClass().getClassLoader(), new Class<?>[]{IEventSystem.class},
                (proxy, method, args) -> {
                    if (method.getName().equals("onPlayerJoin")) tabListeners.incrementAndGet();
                    return defaultValue(method.getReturnType());
                });
        var platform = (IPlatformAdapter) Proxy.newProxyInstance(getClass().getClassLoader(), new Class<?>[]{IPlatformAdapter.class},
                (proxy, method, args) -> switch (method.getName()) {
                    case "getConfig" -> config;
                    case "getEventSystem" -> eventSystem;
                    case "getTaskScheduler" -> scheduler;
                    case "getOnlinePlayers" -> List.of();
                    case "executeOnServerThread" -> {
                        server.add((Runnable) args[0]);
                        yield null;
                    }
                    default -> defaultValue(method.getReturnType());
                });
        var logger = LoggerFactory.getLogger(getClass());
        var boot = CommonRuntime.bootstrap(logger, config, platform).services();
        bootstrapStorage = boot.getStorageService();

        var repository = (ManagedConfigRepository) Proxy.newProxyInstance(getClass().getClassLoader(), new Class<?>[]{ManagedConfigRepository.class},
                (proxy, method, args) -> switch (method.getName()) {
                    case "listRevisionsOnly" -> {
                        var row = managed.get();
                        yield row == null ? List.of() : List.of(new ManagedConfigRevisionView(row.id(), row.scope(), row.serverId(),
                                row.section(), row.revision(), row.schemaFingerprint()));
                    }
                    case "listPendingAdoptionRequests" -> List.of();
                    case "get" -> Optional.ofNullable(managed.get());
                    case "getApplied" -> Optional.ofNullable(applied.get());
                    case "upsertApplied" -> {
                        var row = new ManagedConfigAppliedState((String) args[0], (String) args[1], (String) args[2],
                                (long) args[3], (long) args[4], System.currentTimeMillis(), (String) args[5], Map.of());
                        applied.set(row);
                        acknowledgements.add(row);
                        yield null;
                    }
                    default -> throw new AssertionError(method.getName());
                });
        var moderation = (ModerationRepository) Proxy.newProxyInstance(getClass().getClassLoader(), new Class<?>[]{ModerationRepository.class},
                (proxy, method, args) -> {
                    if (!method.getName().equals("listActivePunishmentRecords")) throw new AssertionError(method.getName());
                    cacheReads.incrementAndGet();
                    if (failCache.get()) throw new IllegalStateException("Test moderation cache failure");
                    return List.of();
                });
        storage = new StorageService(logger, boot.getDebugLogger(), config, boot.getPlayerDataStore(),
                null, boot.getModerationDataStore(), boot.getAdminUtilityDataStore()) {
            @Override public boolean isMysqlActive() { return managed.get() != null; }
            @Override public ManagedConfigRepository managedConfig() { return repository; }
            @Override public ModerationRepository moderation() { return moderation; }
        };
        var permissions = new PermissionsHandler(logger, boot.getCmConfig(), boot.getDebugLogger(), platform,
                boot.getPlayerDataStore(), storage) {
            @Override public void refreshInternalPermissions() {}
            @Override public void registerLuckPermsPermissions() {}
        };
        services = new Services(logger, null, null, null, null, null, null, boot.getCmConfig(),
                boot.getGroupChatManager(), boot.getDebugLogger(), boot.getLang(), boot.getMessageParser(),
                permissions, null, scheduler, boot.getPlayerDataStore(),
                boot.getModerationDataStore(), boot.getAdminUtilityDataStore(), storage, boot.getCommandToggleStore(), platform) {
            private final ConfigApplyService configApply = new ConfigApplyService(this, toggleTasks::add, timeouts::add);
            @Override public ConfigApplyService getConfigApplyService() { return configApply; }
        };

        dashboard = new DashboardService(services, new DashboardConfig());
        tab = new Tablist();
        var menus = new Menus();
        ParadigmAPI.setInstance(new ParadigmAPI.ParadigmAccessor() {
            @Override public List<ParadigmModule> getModules() { return List.of(tab, menus); }
            @Override public Services getServices() { return services; }
            @Override public String getModVersion() { return "test"; }
        });
        tab.onLoad(null, services, null);
        menus.onLoad(null, services, null);
        tabListeners.set(0);
        tab.registerEventListeners(null, services);
    }

    @AfterEach
    void cleanup() throws Exception {
        if (dashboard != null) dashboard.close();
        if (services != null) services.shutdown();
        if (storage != null) storage.close();
        if (bootstrapStorage != null) bootstrapStorage.close();
        for (var entry : originals.entrySet()) entry.getKey().set(null, entry.getValue());
    }

    @Test
    void nextSavePreservesTabSettingsAfterPublicationTimesOut() throws Exception {
        ConfigPatch enable = patch("tablist.enabled", true);
        ConfigValidationResult timedOut = timeOutPatch(enable);

        assertTrue(TablistConfigHandler.handler().read().enabled.value);
        assertTab(false);
        assertEquals(List.of("tablist.enabled"), timedOut.accepted());
        assertFalse(timedOut.locallyApplied());
        assertTrue(timedOut.applied().isEmpty());
        assertEquals(ConfigReloadBehavior.RELOAD_REQUIRED, timedOut.pending().get("tablist"));
        assertNotEquals(enable.revision(), timedOut.newRevision());
        var stale = dashboard.patchService().apply(enable);
        assertEquals("<revision>", stale.rejected().get(0).key());
        assertEquals(timedOut.newRevision(), stale.newRevision());
        assertTrue(server.isEmpty());

        var followUp = patch("tablist.header", List.of("Saved header"));
        var result = finish(CompletableFuture.supplyAsync(() -> dashboard.patchService().apply(followUp)));
        assertTrue(result.locallyApplied(), result.rejected().toString());
        assertTab(true);
        assertEquals(List.of("Saved header"), TablistConfigHandler.getConfig().header.value);
        var disk = TablistConfigHandler.handler().read();
        assertTrue(disk.enabled.value);
        assertEquals(List.of("Saved header"), disk.header.value);
        assertEquals(timedOut.newRevision(), followUp.revision());
    }

    @Test
    void patchWithOldRevisionPreservesHeaderEditedOnDisk() throws Exception {
        var patch = patch("tablist.header", List.of("Dashboard header"));
        var originalHeader = TablistConfigHandler.getConfig().header.value;
        Path file = root.resolve("paradigm/tablist.json");
        var json = JsonParser.parseString(Files.readString(file)).getAsJsonObject();
        json.getAsJsonObject("header").getAsJsonArray("value").set(0, new JsonPrimitive("Manual header"));
        Files.writeString(file, json.toString());

        var result = finish(CompletableFuture.supplyAsync(() -> dashboard.patchService().apply(patch)));

        assertTabDiskConflict(result);
        assertEquals(patch.revision(), result.newRevision());
        assertEquals(json.toString(), Files.readString(file));
        assertEquals(originalHeader, TablistConfigHandler.getConfig().header.value);
    }

    @Test
    void headerPatchCannotActivateAnExternalTabEditUntilExplicitReload() throws Exception {
        assertTab(false);
        var patch = patch("tablist.header", List.of("Dashboard header"));
        Path file = root.resolve("paradigm/tablist.json");
        var json = JsonParser.parseString(Files.readString(file)).getAsJsonObject();
        json.getAsJsonObject("enabled").addProperty("value", true);
        Files.writeString(file, json.toString());

        var result = finish(CompletableFuture.supplyAsync(() -> dashboard.patchService().apply(patch)));

        assertTabDiskConflict(result);
        assertTab(false);
        assertEquals(json.toString(), Files.readString(file));

        var reload = finish(services.getConfigApplyService().reload(Set.of(ConfigSection.TABLIST)));
        assertTrue(reload.locallyApplied(), reload.rejected().toString());
        assertTab(true);

        var freshPatch = patch("tablist.header", List.of("Dashboard header"));
        assertNotEquals(patch.revision(), freshPatch.revision());
        Files.writeString(file, "\n" + json + "\n");
        var saved = finish(CompletableFuture.supplyAsync(() -> dashboard.patchService().apply(freshPatch)));
        assertTrue(saved.ok(), saved.rejected().toString());
        assertTrue(saved.locallyApplied());
        assertTab(true);
        assertEquals(List.of("Dashboard header"), TablistConfigHandler.getConfig().header.value);
        var disk = TablistConfigHandler.handler().read();
        assertTrue(disk.enabled.value);
        assertEquals(List.of("Dashboard header"), disk.header.value);
    }

    @Test
    void sameSavedValueRetriesPublicationWithoutRewritingTheFile() throws Exception {
        timeOutPatch(patch("tablist.enabled", true));
        Path file = root.resolve("paradigm/tablist.json");
        Files.setLastModifiedTime(file, FileTime.fromMillis(1_000_000));
        FileTime savedAt = Files.getLastModifiedTime(file);

        var result = finish(CompletableFuture.supplyAsync(() -> dashboard.patchService().apply(patch("tablist.enabled", true))));
        assertTrue(result.locallyApplied(), result.rejected().toString());
        assertEquals(List.of("tablist"), result.applied());
        assertEquals(savedAt, Files.getLastModifiedTime(file));
        assertTrue(TablistConfigHandler.handler().read().enabled.value);
        assertTab(true);
    }

    @Test
    void commandPatchPreservesExternalTogglesUntilReload() throws Exception {
        var patch = patch("commands.fly", true);
        Path file = root.resolve("paradigm/commands.json");
        var json = JsonParser.parseString(Files.readString(file)).getAsJsonObject();
        json.getAsJsonObject("commands").addProperty("fly", false);
        Files.writeString(file, json.toString());

        var result = finish(CompletableFuture.supplyAsync(() -> dashboard.patchService().apply(patch)));

        assertTrue(result.accepted().isEmpty());
        assertEquals("commands.fly", result.rejected().get(0).key());
        assertTrue(result.rejected().get(0).reason().startsWith("commands.json changed on disk."));
        assertEquals(json.toString(), Files.readString(file));
        assertTrue(services.getCommandToggleStore().isEnabled("fly"));
    }

    @Test
    void cooldownPatchChecksTimingSettingsWithoutComparingPlayerUsage() throws Exception {
        var player = UUID.randomUUID();
        CooldownConfigHandler.setLastUsage(player, "home", 1L);
        CooldownConfigHandler.setLastUsage(player, "home", 2L);
        var patch = patch("cooldowns.cooldown.home", 10);

        var saved = finish(CompletableFuture.supplyAsync(() -> dashboard.patchService().apply(patch)));
        assertTrue(saved.ok(), saved.rejected().toString());
        assertEquals(10, CooldownConfigHandler.getCommandCooldownSeconds("home"));
        assertEquals(2L, CooldownConfigHandler.getLastUsage(player, "home"));

        var followUp = patch("cooldowns.cooldown.home", 20);
        Path file = root.resolve("paradigm/cooldowns.json");
        var json = JsonParser.parseString(Files.readString(file)).getAsJsonObject();
        json.getAsJsonObject("commandWarmupSeconds").addProperty("home", 5);
        Files.writeString(file, json.toString());

        var result = finish(CompletableFuture.supplyAsync(() -> dashboard.patchService().apply(followUp)));
        assertTrue(result.accepted().isEmpty());
        assertTrue(result.rejected().get(0).reason().startsWith("cooldowns.json changed on disk."));
        assertEquals(json.toString(), Files.readString(file));
        assertEquals(10, CooldownConfigHandler.getCommandCooldownSeconds("home"));
        assertEquals(0, CooldownConfigHandler.getCommandWarmupSeconds("home"));
    }

    @Test
    void commandTogglesKeepSubmissionOrderWithAnOutOfOrderExecutor() throws Exception {
        var runtime = services.getConfigApplyService();
        assertTrue(services.getCommandToggleStore().isEnabled("fly"));
        var disable = runtime.setCommandToggle("fly", false);
        var enable = runtime.setCommandToggle("fly", true);

        assertTrue(services.getCommandToggleStore().isEnabled("fly"));
        assertTrue(commandEnabledOnDisk());
        for (int i = 0; i < 2; i++) {
            Runnable task = toggleTasks.pollLast(5, TimeUnit.SECONDS);
            assertNotNull(task);
            finish(CompletableFuture.runAsync(task));
            assertEquals(i == 1, commandEnabledOnDisk());
            assertEquals(i == 1, services.getCommandToggleStore().isEnabled("fly"));
        }
        assertTrue(disable.join().locallyApplied());
        assertTrue(enable.join().locallyApplied());
    }

    private ConfigValidationResult timeOutPatch(ConfigPatch patch) throws Exception {
        var operation = CompletableFuture.supplyAsync(() -> dashboard.patchService().apply(patch));
        Runnable latePublication = server.poll(5, TimeUnit.SECONDS);
        assertNotNull(latePublication);
        Runnable timeout = timeouts.poll(5, TimeUnit.SECONDS);
        assertNotNull(timeout);
        timeout.run();
        var result = operation.get(5, TimeUnit.SECONDS);
        latePublication.run();
        return result;
    }

    private boolean commandEnabledOnDisk() throws Exception {
        var path = root.resolve("paradigm/commands.json");
        return com.google.gson.JsonParser.parseString(Files.readString(path)).getAsJsonObject()
                .getAsJsonObject("commands").get("fly").getAsBoolean();
    }

    @Test
    void tabActivationIsEquivalentAndRepeatedReloadKeepsOneTimerPerJob() throws Exception {
        var handler = TablistConfigHandler.handler();
        var edit = handler.edit();
        edit.value().enabled.value = true;
        edit.save();
        assertTrue(finish(services.getConfigApplyService().reload(Set.of(ConfigSection.TABLIST))).ok());
        assertTab(true);
        assertEquals(2, scheduler.activeTimers());
        assertEquals(2, scheduler.timers.size());

        var response = finish(CompletableFuture.supplyAsync(() -> new ConfigApiHandler(dashboard).patch(
                request(patch("tablist.enabled", false)))));
        assertEquals(200, response.status());
        assertTab(false);
        assertEquals(0, scheduler.activeTimers());

        manage("tablist", 7, "tablist.enabled", true);
        services.getManagedConfigSyncService().triggerImmediateSync();
        Runnable apply = server.poll(5, TimeUnit.SECONDS);
        assertNotNull(apply, "Managed TAB did not reach the server thread");
        assertNull(applied.get(), "The revision was acknowledged while local apply was queued");
        assertTab(false);
        apply.run();
        assertEquals(7, acknowledgement().appliedGlobalRevision());
        assertTab(true);
        assertEquals(2, scheduler.activeTimers());
        assertEquals(4, scheduler.timers.size());

        int listenerCount = tabListeners.get();
        assertEquals(1, listenerCount);
        assertTrue(finish(services.getConfigApplyService().reload(Set.of(ConfigSection.TABLIST))).ok());
        assertTrue(finish(services.getConfigApplyService().reload(Set.of(ConfigSection.TABLIST))).ok());
        assertEquals(2, scheduler.activeTimers());
        assertEquals(listenerCount, tabListeners.get());
    }

    @Test
    void targetedAndAllReloadWaitForTheSameModerationRefresh() throws Exception {
        services.getPunishmentService().start();
        int baseline = cacheReads.get();
        var targeted = finish(services.getConfigApplyService().reload(Set.of(ConfigSection.MODERATION)));
        assertTrue(targeted.ok(), targeted.rejected().toString());
        assertEquals(baseline + 1, cacheReads.get());
        var all = finish(services.getConfigApplyService().reload(Reload.reloadAllSections()));
        assertTrue(all.ok(), all.rejected().toString());
        assertTrue(all.applied().contains("moderation"));
        assertEquals(baseline + 2, cacheReads.get());
    }

    @Test
    void invalidFileRetainsLastGoodSettingsAndFailedSaveDoesNotPublish() throws Exception {
        Path tabFile = root.resolve("paradigm/tablist.json");
        Files.writeString(tabFile, "{\"enabled\":{\"value\":\"bogus\"}}");
        var result = finish(services.getConfigApplyService().reload(Set.of(ConfigSection.TABLIST)));
        assertFalse(result.ok());
        assertEquals("tablist", result.rejected().get(0).key());
        assertTab(false);
        var invalidContents = Files.readString(tabFile);
        var invalidPatch = dashboard.patchService().apply(patch("tablist.header", List.of("Do not replace the invalid file")));
        assertFalse(invalidPatch.ok());
        assertTrue(invalidPatch.accepted().isEmpty());
        assertEquals(invalidContents, Files.readString(tabFile));
        assertTab(false);

        Files.delete(tabFile);
        Files.createDirectory(tabFile);
        Files.writeString(tabFile.resolve("keep"), "keep");
        var save = finish(CompletableFuture.supplyAsync(() -> dashboard.patchService().apply(patch("tablist.enabled", true))));
        assertFalse(save.ok());
        assertTrue(save.accepted().isEmpty());
        assertTab(false);
        assertEquals(0, scheduler.activeTimers());
    }

    @Test
    void debugLoggerFollowsFileReloadAndRestartOnlySettingsRemainPending() throws Exception {
        var debug = services.getDebugLogger();
        assertFalse(debug.isEnabled());
        var candidate = MainConfigHandler.handler().edit();
        candidate.value().debugEnable.value = true;
        boolean previousForgeHandler = candidate.value().registerForgePermissionHandler.value;
        candidate.value().registerForgePermissionHandler.value = !previousForgeHandler;
        candidate.save();

        var result = finish(services.getConfigApplyService().reload(Set.of(ConfigSection.MAIN)));
        assertTrue(result.ok(), result.rejected().toString());
        assertTrue(debug.isEnabled());
        assertEquals(previousForgeHandler, MainConfigHandler.getConfig().registerForgePermissionHandler.value);
        assertEquals(ConfigReloadBehavior.RESTART_REQUIRED, result.pending().get("main"));
        assertEquals(!previousForgeHandler, MainConfigHandler.handler().reviewed().registerForgePermissionHandler.value);
    }

    @Test
    void managedTabRevisionWaitsForSuccessfulPublicationAfterTimeout() throws Exception {
        manage("tablist", 9, "tablist.enabled", true);
        services.getManagedConfigSyncService().triggerImmediateSync();
        Runnable latePublication = server.poll(5, TimeUnit.SECONDS);
        assertNotNull(latePublication);
        Runnable timeout = timeouts.poll(5, TimeUnit.SECONDS);
        assertNotNull(timeout);
        timeout.run();

        var outcome = acknowledgement();
        assertEquals(0, outcome.appliedGlobalRevision());
        assertTrue(outcome.lastError().contains("did not accept"));
        latePublication.run();
        assertTab(false);
        assertTrue(TablistConfigHandler.handler().read().enabled.value);

        storage.submitAcknowledged(() -> null).join();
        services.getManagedConfigSyncService().triggerImmediateSync();
        assertEquals(9, finish(CompletableFuture.supplyAsync(this::acknowledgement)).appliedGlobalRevision());
        assertTab(true);
    }

    @Test
    void managedRevisionRemainsUnacknowledgedOnFailedRuntimeApplyAndRetries() throws Exception {
        services.getPunishmentService().start();
        failCache.set(true);
        manage("moderation", 11, "moderation.cacheRefreshSeconds", 35);
        services.getManagedConfigSyncService().triggerImmediateSync();
        assertNull(applied.get());
        var rejected = finish(CompletableFuture.supplyAsync(this::acknowledgement));
        assertEquals(0, rejected.appliedGlobalRevision());
        assertTrue(rejected.lastError().contains("Test moderation cache failure"));

        storage.submitAcknowledged(() -> null).join();
        failCache.set(false);
        services.getManagedConfigSyncService().triggerImmediateSync();
        var accepted = finish(CompletableFuture.supplyAsync(this::acknowledgement));
        assertEquals(11, accepted.appliedGlobalRevision());
        assertEquals("", accepted.lastError());
    }

    @Test
    void managedRestartOnlySettingsRemainPendingUntilActive() throws Exception {
        String key = "main.registerForgePermissionHandler";
        String category = dashboard.schemaRegistry().snapshot().fields().stream()
                .filter(field -> key.equals(field.key())).findFirst().orElseThrow().category();
        boolean active = MainConfigHandler.getConfig().registerForgePermissionHandler.value;
        manage(category, 12, key, !active);
        services.getManagedConfigSyncService().triggerImmediateSync();
        var pending = finish(CompletableFuture.supplyAsync(this::acknowledgement));
        assertEquals(0, pending.appliedGlobalRevision());
        assertTrue(pending.lastError().contains("restart"));
        assertEquals(active, MainConfigHandler.getConfig().registerForgePermissionHandler.value);
        assertEquals(!active, MainConfigHandler.handler().reviewed().registerForgePermissionHandler.value);
    }

    @Test
    void normalizedPatchKeysStillRequireSectionEditPermission() {
        try (var restricted = new DashboardService(services, new DashboardConfig()) {
            @Override public boolean canEditConfigCategories(DashboardPrincipal principal, Set<String> categories) {
                assertEquals(Set.of("tablist"), categories);
                return false;
            }
        }) {
            var response = new ConfigApiHandler(restricted).patch(request(patch(" tablist.enabled ", true)));
            assertEquals(403, response.status());
            assertTrue(server.isEmpty());
            assertTab(false);
        }
    }

    @Test
    void formattingOnlyDiscordSaveRetainsTheAppliedConnectionSettings() throws Exception {
        var discord = services.getDiscordService();
        discord.start();
        var connection = discord.getClass().getDeclaredField("appliedConnectionSettings");
        connection.setAccessible(true);
        Object before = connection.get(discord);
        assertNotNull(before);
        String textField = dashboard.schemaRegistry().snapshot().fields().stream()
                .map(ConfigField::key).filter(key -> key.startsWith("discord.") && key.endsWith("Format")).findFirst().orElseThrow();
        var result = finish(CompletableFuture.supplyAsync(() -> dashboard.patchService().apply(patch(textField, "New format"))));
        assertTrue(result.ok(), result.rejected().toString());
        assertEquals(List.of("discord"), result.applied());
        assertSame(before, connection.get(discord));
    }

    @Test
    void discordLinkingSettingsUseTheStandardPatchAndReloadFlow() throws Exception {
        var discord = services.getDiscordService();
        discord.start();
        var connection = discord.getClass().getDeclaredField("appliedConnectionSettings");
        connection.setAccessible(true);
        var fields = dashboard.schemaRegistry().snapshot().fields();
        assertTrue(fields.stream().anyMatch(field -> field.key().equals("discord.linkingEnabled")));
        assertTrue(fields.stream().anyMatch(field -> field.key().equals("discord.linkingOwnerServerId")));
        var invalid = finish(CompletableFuture.supplyAsync(() -> dashboard.patchService().apply(patch("discord.linkingEnabled", true))));
        assertFalse(invalid.ok());
        assertFalse(DiscordConfigHandler.getConfig().linkingEnabled.get());

        var patch = new ConfigPatch(dashboard.schemaRegistry().snapshot().revision(), List.of(
                new ConfigPatchOperation("discord.guildId", "100000000000000001"),
                new ConfigPatchOperation("discord.linkingEnabled", true),
                new ConfigPatchOperation("discord.linkingOwnerServerId", " Hub-01 ")));
        var enabled = finish(CompletableFuture.supplyAsync(() -> dashboard.patchService().apply(patch)));
        assertTrue(enabled.ok(), enabled.rejected().toString());
        assertTrue(DiscordConfigHandler.getConfig().linkingEnabled.get());
        assertEquals("hub-01", DiscordConfigHandler.getConfig().linkingOwnerServerId.get());

        Object before = connection.get(discord);
        var changed = finish(CompletableFuture.supplyAsync(() -> dashboard.patchService().apply(patch("discord.linkingOwnerServerId", "hub-02"))));
        assertTrue(changed.ok(), changed.rejected().toString());
        assertSame(before, connection.get(discord));
        var reloaded = finish(services.getConfigApplyService().reload(Set.of(ConfigSection.DISCORD)));
        assertTrue(reloaded.ok(), reloaded.rejected().toString());
        assertSame(before, connection.get(discord));
        assertEquals("hub-02", DiscordConfigHandler.getConfig().linkingOwnerServerId.get());
    }

    @Test
    void disablingLinkingSchedulesCleanupWithoutAnyGatewayOrRelayChannel() throws Exception {
        storage.config().provider = "sqlite";
        var discord = services.getDiscordService();
        var config = DiscordConfigHandler.getConfig();
        config.enabled.value = true;
        config.linkingEnabled.value = true;
        config.guildId.value = "100000000000000001";
        scheduler.captureRaw = true;

        Path receipt = root.resolve("paradigm/data/discord-link-command.json");
        Files.createDirectories(receipt.getParent());
        Files.writeString(receipt, """
                {"owner":{"network":"%s","server":"%s"},"guild":"100000000000000001",
                 "application":"100000000000000002","id":"100000000000000003","version":"100000000000000004"}
                """.formatted(storage.context().networkId(), storage.context().serverId()));

        discord.start();
        var startTask = scheduler.raw.poll();
        assertNotNull(startTask);
        startTask.run();
        assertFalse(discord.isEnabled());

        // No bot token is installed in this fixture. Cleanup reaches the REST client and
        // reports the failure without contacting Discord or discarding the receipt.
        config.linkingEnabled.value = false;
        discord.applyConfiguration();
        var cleanup = scheduler.raw.poll();
        assertNotNull(cleanup);
        cleanup.run();
        assertTrue(discord.status().warnings().stream().anyMatch(message -> message.contains("Could not confirm /link removal")));
        assertTrue(Files.exists(receipt));

        config.linkingOwnerServerId.value = "different-server";
        discord.applyConfiguration();
        assertTrue(scheduler.raw.isEmpty());
    }

    private void assertTabDiskConflict(ConfigValidationResult result) {
        assertFalse(result.ok());
        assertTrue(result.accepted().isEmpty());
        assertTrue(result.applied().isEmpty());
        assertEquals("tablist.header", result.rejected().get(0).key());
        assertEquals("tablist.json changed on disk. Reload TAB, then refresh the editor and retry.",
                result.rejected().get(0).reason());
    }

    private void assertTab(boolean enabled) {
        assertEquals(enabled, TablistConfigHandler.getConfig().enabled.value);
        assertEquals(enabled, services.isModuleActive(tab));
        assertEquals(enabled, tab.isActive());
    }

    private ConfigPatch patch(String key, Object value) {
        return new ConfigPatch(dashboard.schemaRegistry().snapshot().revision(), List.of(new ConfigPatchOperation(key, value)));
    }

    private DashboardRequestContext request(ConfigPatch patch) {
        return new DashboardRequestContext(null, new DashboardPrincipal("console", "Console", true)) {
            @Override public InputStreamReader bodyReader() {
                return new InputStreamReader(new ByteArrayInputStream(DashboardJson.toJson(patch).getBytes(StandardCharsets.UTF_8)),
                        StandardCharsets.UTF_8);
            }
            @Override public String header(String name) { return null; }
        };
    }

    private void manage(String section, long revision, String key, Object value) {
        String networkId = storage.context().networkId();
        managed.set(new ManagedConfigEntry(section, networkId, ServerScope.GLOBAL, "", section,
                Map.of(key, value), dashboard.schemaRegistry().structuralFingerprint(), revision, 0, null, null, null));
    }

    private ManagedConfigAppliedState acknowledgement() {
        try {
            var ack = acknowledgements.poll(5, TimeUnit.SECONDS);
            assertNotNull(ack, "Managed config did not report its local outcome");
            return ack;
        } catch (InterruptedException failure) {
            Thread.currentThread().interrupt();
            throw new AssertionError(failure);
        }
    }

    private <T> T finish(CompletableFuture<T> future) {
        return assertTimeoutPreemptively(Duration.ofSeconds(8), () -> {
            while (!future.isDone()) {
                var task = server.poll(20, TimeUnit.MILLISECONDS);
                if (task != null) task.run();
            }
            return future.get();
        });
    }

    private void snapshotStatic(Field field, boolean clear) throws IllegalAccessException {
        field.setAccessible(true);
        originals.put(field, field.get(null));
        if (clear) field.set(null, null);
    }

    private static Object defaultValue(Class<?> type) {
        if (type == boolean.class) return false;
        if (type == int.class) return 0;
        if (type == long.class) return 0L;
        return null;
    }

    private static final class CountingScheduler extends TaskScheduler {
        private final List<Timer> timers = new ArrayList<>();
        private final BlockingQueue<Runnable> raw = new LinkedBlockingQueue<>();
        private boolean captureRaw;

        CountingScheduler() { super(new DebugLogger(null)); }

        long activeTimers() { return timers.stream().filter(timer -> !timer.isCancelled()).count(); }

        @Override public ScheduledFuture<?> scheduleAtFixedRate(Runnable task, long delay, long period, TimeUnit unit) {
            var timer = new Timer();
            timers.add(timer);
            return timer;
        }

        @Override public ScheduledFuture<?> schedule(Runnable task, long delay, TimeUnit unit) { return new Timer(); }

        @Override public ScheduledFuture<?> scheduleRaw(Runnable task, long delay, TimeUnit unit) {
            if (!captureRaw) return super.scheduleRaw(task, delay, unit);
            raw.add(task);
            return new Timer();
        }
    }

    private static final class Timer implements ScheduledFuture<Object> {
        private boolean cancelled;
        @Override public boolean cancel(boolean interrupt) { cancelled = true; return true; }
        @Override public boolean isCancelled() { return cancelled; }
        @Override public boolean isDone() { return cancelled; }
        @Override public long getDelay(TimeUnit unit) { return 0; }
        @Override public int compareTo(Delayed other) { return 0; }
        @Override public Object get() { return null; }
        @Override public Object get(long timeout, TimeUnit unit) { return null; }
    }
}
