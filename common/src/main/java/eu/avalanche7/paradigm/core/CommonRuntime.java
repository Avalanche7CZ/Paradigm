package eu.avalanche7.paradigm.core;

import java.util.List;
import java.util.Objects;
import java.util.function.Consumer;

import org.slf4j.Logger;

import eu.avalanche7.paradigm.ParadigmAPI;
import eu.avalanche7.paradigm.api.internal.ApiProviderRegistry;
import eu.avalanche7.paradigm.api.internal.ParadigmApiProvider;
import eu.avalanche7.paradigm.configs.*;
import eu.avalanche7.paradigm.data.AdminUtilityDataStore;
import eu.avalanche7.paradigm.data.ModerationDataStore;
import eu.avalanche7.paradigm.data.PlayerDataStore;
import eu.avalanche7.paradigm.data.WarpStore;
import eu.avalanche7.paradigm.modules.*;
import eu.avalanche7.paradigm.modules.chat.*;
import eu.avalanche7.paradigm.modules.commands.shared.CommandCatalog;
import eu.avalanche7.paradigm.modules.permissions.CommandNodeAccessGate;
import eu.avalanche7.paradigm.modules.permissions.PermissionsHandler;
import eu.avalanche7.paradigm.platform.Interfaces.IConfig;
import eu.avalanche7.paradigm.platform.Interfaces.IEventSystem;
import eu.avalanche7.paradigm.platform.Interfaces.IPlatformAdapter;
import eu.avalanche7.paradigm.storage.StorageService;
import eu.avalanche7.paradigm.utils.*;

public final class CommonRuntime {
    private CommonRuntime() {}

    public static Runtime bootstrap(Logger logger, IConfig platformConfig, IPlatformAdapter platformAdapter) {
        Objects.requireNonNull(logger, "logger");
        Objects.requireNonNull(platformConfig, "platformConfig");
        Objects.requireNonNull(platformAdapter, "platformAdapter");

        MainConfigHandler.init(platformConfig, new DebugLogger(null));
        DebugLogger debugLogger = new DebugLogger(MainConfigHandler.getConfig());
        AnnouncementsConfigHandler.init(platformConfig, debugLogger);
        MOTDConfigHandler.init(platformConfig, debugLogger);
        MentionConfigHandler.init(platformConfig, debugLogger);
        RestartConfigHandler.init(platformConfig, debugLogger);
        ChatConfigHandler.init(platformConfig, debugLogger);
        ModerationConfigHandler.init(platformConfig, debugLogger);
        CooldownConfigHandler.init(platformConfig, debugLogger);
        EmojiConfigHandler.init(platformConfig, debugLogger);
        TablistConfigHandler.init(platformConfig, debugLogger);
        DiscordConfigHandler.init(platformConfig, debugLogger);
        AfkConfigHandler.init(platformConfig, debugLogger);
        TicketsConfigHandler.init(platformConfig, debugLogger);

        CMConfig cmConfig = new CMConfig(debugLogger, platformConfig);
        cmConfig.loadCommands();

        Placeholders placeholders = new Placeholders(cmConfig);
        Placeholders.setPingResolver(platformAdapter::getPlayerPing);
        TaskScheduler taskScheduler = adoptPlatformScheduler(logger, platformAdapter, debugLogger);
        PlayerDataStore playerDataStore = new PlayerDataStore(logger, debugLogger, platformAdapter.getConfig());
        ModerationDataStore moderationDataStore = new ModerationDataStore(logger, debugLogger, platformAdapter.getConfig());
        AdminUtilityDataStore adminUtilityDataStore = new AdminUtilityDataStore(logger, platformAdapter.getConfig());
        WarpStore warpStore = new WarpStore(logger, debugLogger, platformAdapter.getConfig());
        StorageService storageService = new StorageService(logger, debugLogger, platformAdapter.getConfig(), playerDataStore, warpStore, moderationDataStore, adminUtilityDataStore);
        CommandToggleStore commandToggleStore = new CommandToggleStore(logger, debugLogger, platformAdapter.getConfig());

        PermissionsHandler permissionsHandler = new PermissionsHandler(logger, cmConfig, debugLogger, platformAdapter, playerDataStore, storageService);

        MessageParser messageParser = new MessageParser(placeholders, platformAdapter);
        platformAdapter.provideMessageParser(messageParser);

        Lang lang = new Lang(logger, MainConfigHandler.getConfig(), messageParser, platformAdapter);
        lang.initializeLanguage();

        GroupChatManager groupChatManager = new GroupChatManager();

        Services services = new Services(
                logger,
                MainConfigHandler.getConfig(),
                AnnouncementsConfigHandler.getConfig(),
                MOTDConfigHandler.getConfig(),
                MentionConfigHandler.getConfig(),
                RestartConfigHandler.getConfig(),
                ChatConfigHandler.getConfig(),
                cmConfig,
                groupChatManager,
                debugLogger,
                lang,
                messageParser,
                permissionsHandler,
                placeholders,
                taskScheduler,
                playerDataStore,
                moderationDataStore,
                adminUtilityDataStore,
                storageService,
                commandToggleStore,
                platformAdapter
        );

        services.getPunishmentService();
        registerActivityPlaceholders(services);

        registerDefaultCommandToggles(commandToggleStore);

        UpdateChecker.registerInGameNotifier(services);

        groupChatManager.setServices(services);
        registerExternalCommandGuard(services);
        registerPunishmentGuard(services);

        List<ParadigmModule> modules = ParadigmModules.compose(groupChatManager);

        return new Runtime(modules, services, permissionsHandler);
    }

    public static void attachToApi(Runtime runtime, String modVersion) {
        if (runtime == null) throw new IllegalArgumentException("runtime cannot be null");
        ParadigmAPI.setInstance(new ParadigmAPI.ParadigmAccessor() {
            @Override
            public List<ParadigmModule> getModules() {
                return runtime.modules();
            }

            @Override
            public Services getServices() {
                return runtime.services();
            }

            @Override
            public String getModVersion() {
                return modVersion != null ? modVersion : "unknown";
            }
        });
        ApiProviderRegistry.install(new ParadigmApiProvider(runtime.services(), modVersion));
    }

    public enum Startup {
        PREPARED_IN_LOADER_SETUP, ACTIVATE_AT_SERVER_START, LEGACY_FORGE
    }

    public static final class Runtime {
        private enum Phase {
            CREATED, PREPARED, STARTED, STOPPING, STOPPED
        }

        private final List<ParadigmModule> modules;
        private final Services services;
        private final PermissionsHandler permissionsHandler;
        private TelemetryReporter telemetry;
        private Phase phase = Phase.CREATED;

        public Runtime(List<ParadigmModule> modules, Services services, PermissionsHandler permissionsHandler) {
            this.modules = List.copyOf(modules);
            this.services = services;
            this.permissionsHandler = permissionsHandler;
        }

        public List<ParadigmModule> modules() {
            return modules;
        }

        public Services services() {
            return services;
        }

        public PermissionsHandler permissionsHandler() {
            return permissionsHandler;
        }

        /** Only loaders with an early activation event call this. */
        public synchronized void prepareModules() {
            if (phase != Phase.CREATED) return;
            phase = Phase.PREPARED;
            for (ParadigmModule module : modules) {
                services.enableModule(module);
            }
        }

        public void serverStarting(Object event, Object server, Consumer<Runnable> mainThread, Startup startup) {
            serverStarting(event, server, mainThread, startup, null);
        }

        public synchronized void serverStarting(Object event, Object server, Consumer<Runnable> mainThread,
                                                Startup startup, Runnable beforeModules) {
            if (phase != Phase.CREATED && phase != Phase.PREPARED) return;
            phase = Phase.STARTED;
            services.setServer(server);
            try {
                services.getTaskScheduler().setMainThreadExecutor(mainThread);
            } catch (Throwable failure) {
                services.getLogger().error("[Paradigm] Startup: failed to bind the task scheduler to the Minecraft server thread.", failure);
            }
            telemetry = new TelemetryReporter(services);
            telemetry.start();
            if (startup != Startup.LEGACY_FORGE) {
                try {
                    permissionsHandler.registerLuckPermsPermissions();
                } catch (Throwable failure) {
                    services.getLogger().warn("[Paradigm] Startup: LuckPerms permission registration failed; platform authorization fallback remains active.", failure);
                }
            }
            if (beforeModules != null) beforeModules.run();
            for (ParadigmModule module : modules) {
                if (!module.isEnabled(services)) continue;
                if (startup != Startup.PREPARED_IN_LOADER_SETUP) services.enableModule(module);
                module.onServerStarting(event, services);
            }
        }

        public void serverStopping(Object event) {
            serverStopping(event, null);
        }

        public synchronized void serverStopping(Object event, Runnable afterModuleStops) {
            if (phase == Phase.STOPPING || phase == Phase.STOPPED) return;
            phase = Phase.STOPPING;
            Logger logger = services.getLogger();
            Services.shutdownStep(logger, "rejecting new work", services::beginStopping);
            Services.shutdownStep(logger, "detaching the public API", ApiProviderRegistry::uninstall);
            CommandNodeAccessGate.uninstall();
            ParadigmAPI.setInstance(null);
            // Pass the loader event to active modules in registration order. Services retries incomplete cleanup.
            for (ParadigmModule module : modules) {
                if (services.isModuleActive(module)) services.stopModule(module, event);
            }
            if (telemetry != null) Services.shutdownStep(logger, "stopping telemetry", telemetry::stop);
            if (afterModuleStops != null) Services.shutdownStep(logger, "running the loader stop hook", afterModuleStops);
            Services.shutdownStep(logger, "flushing shared services", services::shutdown);
            Services.shutdownStep(logger, "saving command cooldowns", CooldownConfigHandler::saveCooldowns);
        }

        public synchronized void serverStopped(Object event) {
            if (phase == Phase.STOPPED) return;
            try {
                // Some failed starts reach the final loader event without a stopping event.
                serverStopping(null);
                phase = Phase.STOPPED;
                Logger logger = services.getLogger();
                for (ParadigmModule module : modules) {
                    Services.shutdownStep(logger, "running the stopped hook for " + module.getName(), () -> {
                        if (module.isEnabled(services)) module.onServerStopped(event, services);
                    });
                }
            } finally {
                phase = Phase.STOPPED;
                StorageService storage = services.getStorageService();
                if (storage != null) storage.close();
            }
        }
    }

    private static TaskScheduler adoptPlatformScheduler(Logger logger, IPlatformAdapter platformAdapter, DebugLogger debugLogger) {
        TaskScheduler platformScheduler = null;
        try {
            platformScheduler = platformAdapter.getTaskScheduler();
        } catch (AbstractMethodError compatibilityFailure) {
            logger.debug("[Paradigm] Startup: platform adapter predates the shared scheduler contract; creating the common scheduler.",
                    compatibilityFailure);
        } catch (RuntimeException failure) {
            logger.warn("[Paradigm] Startup: failed to obtain the platform task scheduler; creating the common scheduler.", failure);
        }
        if (platformScheduler != null) {
            return platformScheduler;
        }
        logger.warn("[Paradigm] Platform adapter {} exposed no TaskScheduler; creating a runtime-owned one. "
                + "Tasks scheduled by the adapter itself may then outlive server shutdown.", platformAdapter.getClass().getName());
        return new TaskScheduler(debugLogger);
    }

    private static void registerActivityPlaceholders(Services services) {
        Placeholders.setActivityResolver(player -> {
            if (player == null) {
                return null;
            }
            boolean afk = services.getAfkService().isAfk(player);
            String tag = services.getAfkService().afkTag();
            if (!services.getPlaytimeService().isRunning() || !services.getPlaytimeService().isPlaytimeLoaded(player.getUUID())) {
                return Placeholders.PlayerActivity.withoutPlaytime(afk, tag);
            }
            return new Placeholders.PlayerActivity(afk, tag, services.getPlaytimeService().onlinePlaytimeMs(player));
        });
    }

    private static void registerExternalCommandGuard(Services services) {
        CommandNodeAccessGate.install(services.getPermissionsHandler());
        IEventSystem events = eventSystemOrNull(services, "external command guard");
        if (events == null) {
            return;
        }
        events.onPlayerCommand(event -> {
            if (event == null || event.isCancelled()) {
                return;
            }
            IPlatformAdapter platform = services.getPlatformAdapter();
            PermissionsHandler.CommandGuardResult result = services.getPermissionsHandler()
                    .evaluateCommandPermission(event.getPlayer(), event.getCommand());
            if (result.allowed()) {
                services.getDebugLogger().debugLog("[Permissions] External command allowed: /" + event.getCommand() + " node=" + result.node() + " reason=" + result.reason());
                return;
            }

            event.setCancelled(true);
            services.getDebugLogger().debugLog("[Permissions] External command denied: /" + event.getCommand() + " node=" + result.node() + " reason=" + result.reason());
            if (event.getPlayer() != null && platform != null) {
                String node = result.node() != null ? result.node() : "unknown";
                platform.sendSystemMessage(
                        event.getPlayer(),
                        platform.createLiteralComponent("§cYou do not have permission to use this command. §7(" + node + ")")
                );
            }
        });
    }

    private static void registerPunishmentGuard(Services services) {
        IEventSystem events = eventSystemOrNull(services, "punishment guard");
        if (events == null) {
            return;
        }
        events.onPlayerJoin(event -> {
            if (event != null && event.getPlayer() != null) services.getPunishmentService().enforcePlayer(event.getPlayer());
        });
    }

    private static IEventSystem eventSystemOrNull(Services services, String guardName) {
        IEventSystem events = services.getPlatformAdapter().getEventSystem();
        if (events == null) {
            services.getLogger().warn("Paradigm: platform adapter exposes no event system; {} is inactive.", guardName);
        }
        return events;
    }

    private static void registerDefaultCommandToggles(CommandToggleStore store) {
        for (CommandCatalog.Entry entry : CommandCatalog.entries()) {
            store.registerCommand(entry.id(), entry.defaultEnabled(), entry.protectedCommand(),
                    entry.roots().toArray(String[]::new));
        }
    }
}
