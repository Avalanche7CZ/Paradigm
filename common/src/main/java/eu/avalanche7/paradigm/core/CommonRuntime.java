package eu.avalanche7.paradigm.core;

import java.util.List;
import java.util.Objects;

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
import eu.avalanche7.paradigm.modules.commands.Reload;
import eu.avalanche7.paradigm.modules.commands.shared.CommandCatalog;
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

        // --- configs ---
        DebugLogger bootstrapDebugLogger = new DebugLogger(null);
        MainConfigHandler.init(platformConfig, bootstrapDebugLogger);
        bootstrapDebugLogger = new DebugLogger(MainConfigHandler.getConfig());

        AnnouncementsConfigHandler.init(platformConfig, bootstrapDebugLogger);
        MOTDConfigHandler.init(platformConfig, bootstrapDebugLogger);
        MentionConfigHandler.init(platformConfig, bootstrapDebugLogger);
        RestartConfigHandler.init(platformConfig, bootstrapDebugLogger);
        ChatConfigHandler.init(platformConfig, bootstrapDebugLogger);
        ModerationConfigHandler.init(platformConfig, bootstrapDebugLogger);
        CooldownConfigHandler.init(platformConfig, bootstrapDebugLogger);
        EmojiConfigHandler.init(platformConfig, bootstrapDebugLogger);
        TablistConfigHandler.init(platformConfig, bootstrapDebugLogger);
        DiscordConfigHandler.init(platformConfig, bootstrapDebugLogger);

        // --- utilities ---
        DebugLogger debugLogger = new DebugLogger(MainConfigHandler.getConfig());
        CMConfig cmConfig = new CMConfig(debugLogger, platformConfig);
        cmConfig.loadCommands();

        Placeholders placeholders = new Placeholders();
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
        Reload.recordModuleStates(runtime.services());
        ApiProviderRegistry.install(new ParadigmApiProvider(runtime.services(), modVersion));
    }

    public record Runtime(List<ParadigmModule> modules, Services services, PermissionsHandler permissionsHandler) {
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

    private static void registerExternalCommandGuard(Services services) {
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
