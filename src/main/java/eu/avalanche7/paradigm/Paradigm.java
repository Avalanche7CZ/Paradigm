package eu.avalanche7.paradigm;

import com.mojang.brigadier.CommandDispatcher;
import eu.avalanche7.paradigm.core.ParadigmModule;
import eu.avalanche7.paradigm.core.Services;
import eu.avalanche7.paradigm.modules.*;
import eu.avalanche7.paradigm.configs.*;
import eu.avalanche7.paradigm.utils.*;
import eu.avalanche7.paradigm.platform.IPlatformAdapter;
import eu.avalanche7.paradigm.platform.PlatformAdapterImpl;
import net.fabricmc.api.DedicatedServerModInitializer;
import net.fabricmc.fabric.api.command.v2.CommandRegistrationCallback;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerLifecycleEvents;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.server.command.ServerCommandSource;
import net.minecraft.server.MinecraftServer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.net.URL;
import java.util.ArrayList;
import java.util.List;

public class Paradigm implements DedicatedServerModInitializer {

    public static final String MOD_ID = "paradigm";
    private static final Logger LOGGER = LoggerFactory.getLogger(MOD_ID);
    private final List<ParadigmModule> modules = new ArrayList<>();
    private Services services;

    private DebugLogger debugLoggerInstance;
    private Lang langInstance;
    private MessageParser messageParserInstance;
    private PermissionsHandler permissionsHandlerInstance;
    private Placeholders placeholdersInstance;
    private TaskScheduler taskSchedulerInstance;
    private GroupChatManager groupChatManagerInstance;
    private CMConfig cmConfigInstance;
    private CustomToastManager customToastManagerInstance;
    private IPlatformAdapter platformAdapterInstance;

    @Override
    public void onInitializeServer() {
        LOGGER.info("Initializing Paradigm Mod for Fabric 1.21.1...");
        loadConfigurations();

        createUtilityInstances();
        initializeServices();
        registerModules();

        modules.forEach(module -> module.registerEventListeners(null, services));
        modules.forEach(module -> module.onLoad(null, services, null));

        registerFabricEvents();

        FabricLoader.getInstance().getModContainer(MOD_ID).ifPresent(modContainer -> {
            String version = modContainer.getMetadata().getVersion().getFriendlyString();
            String displayName = modContainer.getMetadata().getName();

            LOGGER.info("Paradigm Fabric mod (1.21.1) has been set up.");
            LOGGER.info("==================================================");
            LOGGER.info("{} - Version {}", displayName, version);
            LOGGER.info("Author: Avalanche7CZ");
            LOGGER.info("Discord: https://discord.com/invite/qZDcQdEFqQ");
            LOGGER.info("==================================================");
            UpdateChecker.checkForUpdates(version, LOGGER);
        });
    }

    private void loadConfigurations() {
        try {
            MainConfigHandler.load();
            AnnouncementsConfigHandler.load();
            MentionConfigHandler.load();
            RestartConfigHandler.load();
            ChatConfigHandler.load();
            ToastConfigHandler.load();
            MOTDConfigHandler.loadConfig();
            if (this.cmConfigInstance == null) {
                this.cmConfigInstance = new CMConfig(new DebugLogger(MainConfigHandler.CONFIG));
            }
            this.cmConfigInstance.loadCommands();
            if(this.langInstance == null) {
                if (this.placeholdersInstance == null) this.placeholdersInstance = new Placeholders();
                if (this.messageParserInstance == null) this.messageParserInstance = new MessageParser(this.placeholdersInstance);
                if (this.debugLoggerInstance == null) this.debugLoggerInstance = new DebugLogger(MainConfigHandler.CONFIG);
                this.langInstance = new Lang(LOGGER, MainConfigHandler.CONFIG, this.messageParserInstance);
            }
            this.langInstance.initializeLanguage();
        } catch (Exception e) {
            LOGGER.error("Failed to load configuration for {}", MOD_ID, e);
            throw new RuntimeException("Configuration loading failed for " + MOD_ID, e);
        }
    }


    private void createUtilityInstances() {
        this.placeholdersInstance = new Placeholders();
        this.messageParserInstance = new MessageParser(this.placeholdersInstance);
        this.customToastManagerInstance = new CustomToastManager(this.messageParserInstance);
        this.debugLoggerInstance = new DebugLogger(MainConfigHandler.CONFIG);
        this.taskSchedulerInstance = new TaskScheduler(this.debugLoggerInstance);
        this.permissionsHandlerInstance = new PermissionsHandler(LOGGER, this.cmConfigInstance, this.debugLoggerInstance);
        this.groupChatManagerInstance = new GroupChatManager();
        this.platformAdapterInstance = new PlatformAdapterImpl(
                this.permissionsHandlerInstance,
                this.placeholdersInstance,
                this.taskSchedulerInstance,
                this.debugLoggerInstance
        );
    }

    private void initializeServices() {
        this.services = new Services(
                LOGGER,
                MainConfigHandler.CONFIG,
                AnnouncementsConfigHandler.CONFIG,
                MOTDConfigHandler.getConfig(),
                new MentionConfigHandler(),
                RestartConfigHandler.CONFIG,
                ChatConfigHandler.CONFIG,
                new ToastConfigHandler(),
                this.cmConfigInstance,
                this.groupChatManagerInstance,
                this.debugLoggerInstance,
                this.langInstance,
                this.messageParserInstance,
                this.permissionsHandlerInstance,
                this.placeholdersInstance,
                this.taskSchedulerInstance,
                this.customToastManagerInstance,
                this.platformAdapterInstance
        );
        this.groupChatManagerInstance.setServices(this.services);
    }

    private void registerModules() {
        modules.add(new Announcements());
        modules.add(new MOTD());
        modules.add(new Mentions());
        modules.add(new Restart());
        modules.add(new StaffChat());
        modules.add(new GroupChat(this.groupChatManagerInstance));
        modules.add(new CommandManager());
        modules.add(new CustomToasts());
        LOGGER.info("Paradigm: Registered {} modules.", modules.size());
    }

    private void registerFabricEvents() {
        ServerLifecycleEvents.SERVER_STARTING.register(this::onServerStarting);
        ServerLifecycleEvents.SERVER_STOPPING.register(this::onServerStopping);
        CommandRegistrationCallback.EVENT.register(this::onRegisterCommands);
    }

    private void onServerStarting(MinecraftServer server) {
        services.setServer(server);
        modules.forEach(module -> {
            if (module.isEnabled(services)) {
                module.onEnable(services);
                module.onServerStarting(null, services);
            }
        });
    }

    private void onRegisterCommands(CommandDispatcher<ServerCommandSource> dispatcher, net.minecraft.command.CommandRegistryAccess registryAccess, net.minecraft.server.command.CommandManager.RegistrationEnvironment environment) {
        modules.forEach(module -> {
            if (module.isEnabled(services)) {
                module.registerCommands(dispatcher, registryAccess, services);
            }
        });
    }

    private void onServerStopping(MinecraftServer server) {
        modules.forEach(module -> {
            if (module.isEnabled(services)) {
                module.onServerStopping(null, services);
                module.onDisable(services);
            }
        });
        if (this.taskSchedulerInstance != null) {
            this.taskSchedulerInstance.onServerStopping();
        }
        LOGGER.info("Paradigm modules (1.21.1) have been processed for server stop.");
    }

    public static class UpdateChecker {
        private static final String LATEST_VERSION_URL = "https://raw.githubusercontent.com/Avalanche7CZ/Paradigm/Fabric/1.21.1/version.txt?v=1";

        public static void checkForUpdates(String currentVersion, Logger logger) {
            try {
                URL url = new URL(LATEST_VERSION_URL);
                try (BufferedReader reader = new BufferedReader(new InputStreamReader(url.openStream()))) {
                    String latestVersion = reader.readLine();
                    if (latestVersion != null && !currentVersion.equals(latestVersion.trim())) {
                        logger.info("Paradigm: A new version is available: {} (Current: {})", latestVersion.trim(), currentVersion);
                        logger.info("Paradigm: Please update at: https://www.curseforge.com/minecraft/mc-mods/paradigm or https://modrinth.com/mod/paradigm");
                    } else if (latestVersion != null) {
                        logger.info("Paradigm: You are running the latest version: {}", currentVersion);
                    } else {
                        logger.info("Paradigm: Could not determine the latest version.");
                    }
                }
            } catch (Exception e) {
                logger.warn("Paradigm: Failed to check for updates: {}", e.getMessage());
            }
        }
    }
}