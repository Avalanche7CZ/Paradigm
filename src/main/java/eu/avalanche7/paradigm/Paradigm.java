package eu.avalanche7.paradigm;

import com.mojang.brigadier.CommandDispatcher;
import eu.avalanche7.paradigm.configs.*;
import eu.avalanche7.paradigm.core.ParadigmModule;
import eu.avalanche7.paradigm.core.Services;
import eu.avalanche7.paradigm.modules.*;
import eu.avalanche7.paradigm.modules.chat.GroupChat;
import eu.avalanche7.paradigm.modules.chat.MOTD;
import eu.avalanche7.paradigm.modules.chat.StaffChat;
import eu.avalanche7.paradigm.platform.Interfaces.IPlatformAdapter;
import eu.avalanche7.paradigm.platform.PlatformAdapterImpl;
import eu.avalanche7.paradigm.utils.*;
import net.fabricmc.api.DedicatedServerModInitializer;
import net.fabricmc.fabric.api.command.v2.CommandRegistrationCallback;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerLifecycleEvents;
import net.fabricmc.loader.api.FabricLoader;
import net.fabricmc.loader.api.ModContainer;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.command.ServerCommandSource;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.net.HttpURLConnection;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

public class Paradigm implements DedicatedServerModInitializer {

    public static final String MOD_ID = "paradigm";
    private static final Logger LOGGER = LoggerFactory.getLogger(MOD_ID);
    private final List<ParadigmModule> modules = new ArrayList<>();
    private static List<ParadigmModule> modulesStatic;
    private Services services;
    private static Services servicesInstance;
    private DebugLogger debugLoggerInstance;
    private Lang langInstance;
    private static String modVersion = "unknown";
    private MessageParser messageParserInstance;
    private PermissionsHandler permissionsHandlerInstance;
    private Placeholders placeholdersInstance;
    private TaskScheduler taskSchedulerInstance;
    private GroupChatManager groupChatManagerInstance;
    private CMConfig cmConfigInstance;
    private IPlatformAdapter platformAdapterInstance;
    private CooldownConfigHandler cooldownConfigHandlerInstance;
    private TelemetryReporter telemetryReporter;

    @Override
    public void onInitializeServer() {
        LOGGER.info("Initializing Paradigm Mod for Fabric 1.20.1...");
        loadConfigurations();

        initializeServices();
        registerModules();
        servicesInstance = services;
        modulesStatic = modules;

        modules.forEach(module -> module.registerEventListeners(null, services));
        modules.forEach(module -> module.onLoad(null, services, null));

        registerFabricEvents();

        FabricLoader.getInstance().getModContainer(MOD_ID).ifPresent(modContainer -> {
            modVersion = modContainer.getMetadata().getVersion().getFriendlyString();
            String displayName = modContainer.getMetadata().getName();

            LOGGER.info("Paradigm Fabric mod (1.20.1) has been set up.");
            LOGGER.info("==================================================");
            LOGGER.info("{} - Version {}", displayName, getModVersion());
            LOGGER.info("Author: Avalanche7CZ");
            LOGGER.info("Discord: https://discord.com/invite/qZDcQdEFqQ");
            LOGGER.info("==================================================");
            UpdateChecker.checkForUpdates(getModVersion(), LOGGER);
        });
    }

    private void loadConfigurations() {
        try {
            LOGGER.info("[Paradigm] Loading configurations...");

            DebugLogger bootstrapDebugLogger = new DebugLogger(null);
            MainConfigHandler.setJsonValidator(bootstrapDebugLogger);
            AnnouncementsConfigHandler.setJsonValidator(bootstrapDebugLogger);
            ChatConfigHandler.setJsonValidator(bootstrapDebugLogger);
            MentionConfigHandler.setJsonValidator(bootstrapDebugLogger);
            RestartConfigHandler.setJsonValidator(bootstrapDebugLogger);
            MOTDConfigHandler.setJsonValidator(bootstrapDebugLogger);

            MainConfigHandler.getConfig();
            AnnouncementsConfigHandler.getConfig();
            MentionConfigHandler.getConfig();
            RestartConfigHandler.getConfig();
            ChatConfigHandler.getConfig();
            MOTDConfigHandler.getConfig();
            CooldownConfigHandler.load();

            debugLoggerInstance = new DebugLogger(MainConfigHandler.getConfig());
            MainConfigHandler.setJsonValidator(debugLoggerInstance);
            AnnouncementsConfigHandler.setJsonValidator(debugLoggerInstance);
            ChatConfigHandler.setJsonValidator(debugLoggerInstance);
            MentionConfigHandler.setJsonValidator(debugLoggerInstance);
            RestartConfigHandler.setJsonValidator(debugLoggerInstance);
            MOTDConfigHandler.setJsonValidator(debugLoggerInstance);

            if (this.cmConfigInstance == null) {
                this.cmConfigInstance = new CMConfig(debugLoggerInstance);
            }
            this.cmConfigInstance.loadCommands();

            createUtilityInstances();

            if(this.langInstance == null) {
                this.langInstance = new Lang(LOGGER, MainConfigHandler.getConfig(), this.messageParserInstance);
            }
            this.langInstance.initializeLanguage();

            LOGGER.info("[Paradigm] All configurations loaded successfully.");
        } catch (Exception e) {
            LOGGER.error("Failed to load configuration for {}", MOD_ID, e);
            throw new RuntimeException("Configuration loading failed for " + MOD_ID, e);
        }
    }

    private void createUtilityInstances() {
        this.placeholdersInstance = new Placeholders();
        this.debugLoggerInstance = new DebugLogger(MainConfigHandler.getConfig());
        this.taskSchedulerInstance = new TaskScheduler(this.debugLoggerInstance);
        this.permissionsHandlerInstance = new PermissionsHandler(LOGGER, this.cmConfigInstance, this.debugLoggerInstance);
        this.groupChatManagerInstance = new GroupChatManager();
        this.cooldownConfigHandlerInstance = new CooldownConfigHandler();
        this.platformAdapterInstance = new PlatformAdapterImpl(
                this.permissionsHandlerInstance,
                this.placeholdersInstance,
                this.taskSchedulerInstance,
                this.debugLoggerInstance
        );

        this.messageParserInstance = new MessageParser(this.placeholdersInstance, this.platformAdapterInstance);
        this.platformAdapterInstance.provideMessageParser(this.messageParserInstance);
    }

    private void initializeServices() {
        LOGGER.info("[Paradigm] Initializing Services with configs:");
        LOGGER.info("[Paradigm] MainConfig: " + (MainConfigHandler.getConfig() != null ? "NOT NULL" : "NULL"));
        LOGGER.info("[Paradigm] AnnouncementsConfig: " + (AnnouncementsConfigHandler.getConfig() != null ? "NOT NULL" : "NULL"));
        LOGGER.info("[Paradigm] MentionConfig: " + (MentionConfigHandler.getConfig() != null ? "NOT NULL" : "NULL"));

        this.services = new Services(
                LOGGER,
                MainConfigHandler.getConfig(),
                AnnouncementsConfigHandler.getConfig(),
                MOTDConfigHandler.getConfig(),
                MentionConfigHandler.getConfig(),
                RestartConfigHandler.getConfig(),
                ChatConfigHandler.getConfig(),
                this.cmConfigInstance,
                this.groupChatManagerInstance,
                this.debugLoggerInstance,
                this.langInstance,
                this.messageParserInstance,
                this.permissionsHandlerInstance,
                this.placeholdersInstance,
                this.taskSchedulerInstance,
                this.platformAdapterInstance,
                this.cooldownConfigHandlerInstance
        );
        this.groupChatManagerInstance.setServices(this.services);
        LOGGER.info("[Paradigm] Services initialized successfully");
    }

    private void registerModules() {
        modules.add(new eu.avalanche7.paradigm.modules.commands.Help());
        modules.add(new Announcements());
        modules.add(new MOTD());
        modules.add(new Mentions());
        modules.add(new Restart());
        modules.add(new StaffChat());
        modules.add(new GroupChat(this.groupChatManagerInstance));
        modules.add(new CommandManager());
        modules.add(new eu.avalanche7.paradigm.modules.commands.Reload());
        LOGGER.info("Paradigm: Registered {} modules.", modules.size());
    }

    private void registerFabricEvents() {
        ServerLifecycleEvents.SERVER_STARTING.register(this::onServerStarting);
        ServerLifecycleEvents.SERVER_STOPPING.register(this::onServerStopping);
        CommandRegistrationCallback.EVENT.register(this::onRegisterCommands);
    }

    private void onServerStarting(MinecraftServer server) {
        services.setServer(server);

        if (telemetryReporter == null) {
            telemetryReporter = new TelemetryReporter(services);
        }
        telemetryReporter.start();

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
        var root = dispatcher.getRoot().getChild("paradigm");
        if (root != null) {
            LOGGER.info("[Paradigm] Root /paradigm command node present. Has executes? {}", root.getCommand() != null);
        } else {
            LOGGER.warn("[Paradigm] Root /paradigm command node NOT found after registration!");
        }
    }

    public static Services getServices() {
        return servicesInstance;
    }

    public static List<ParadigmModule> getModules() {
        return modulesStatic != null ? modulesStatic : List.of();
    }

    public static String getModVersion() {
        if (!"unknown".equals(modVersion)) return modVersion;
        try {
            return net.fabricmc.loader.api.FabricLoader.getInstance().getModContainer(MOD_ID)
                    .map(c -> c.getMetadata().getVersion().getFriendlyString())
                    .orElse("unknown");
        } catch (Throwable t) {
            return "unknown";
        }
    }

    private void onServerStopping(MinecraftServer server) {
        modules.forEach(module -> {
            if (module.isEnabled(services)) {
                module.onServerStopping(null, services);
                module.onDisable(services);
            }
        });

        if (telemetryReporter != null) {
            telemetryReporter.stop();
        }

        if (this.taskSchedulerInstance != null) {
            this.taskSchedulerInstance.onServerStopping();
        }
        LOGGER.info("Paradigm modules (1.20.1) have been processed for server stop.");
    }

    public static class UpdateChecker {
        private static final String LATEST_VERSION_URL = "https://raw.githubusercontent.com/Avalanche7CZ/Paradigm/Fabric/1.20.1/version.txt?v=1";
        private static final String MODRINTH_PROJECT_ID = "s4i32SJd";
        private static final String CURSEFORGE_SLUG = "paradigm";
        private static final String MODRINTH_PROJECT_PAGE = "https://modrinth.com/mod/" + MODRINTH_PROJECT_ID;
        private static final String CURSEFORGE_PROJECT_PAGE = "https://www.curseforge.com/minecraft/mc-mods/" + CURSEFORGE_SLUG;

        public static void checkForUpdates(String currentVersion, Logger logger) {
            boolean foundOnModrinth = checkModrinth(currentVersion, logger);
            if (!foundOnModrinth) {
                try (BufferedReader reader = new BufferedReader(new InputStreamReader(URI.create(LATEST_VERSION_URL).toURL().openStream(), StandardCharsets.UTF_8))) {
                    String latestVersion = reader.readLine();
                    if (latestVersion != null && !currentVersion.equals(latestVersion.trim())) {
                        logger.info("Paradigm: A new version is available: {} (Current: {})", latestVersion.trim(), currentVersion);
                        logger.info("Modrinth: {}", MODRINTH_PROJECT_PAGE);
                        logger.info("CurseForge: {}", CURSEFORGE_PROJECT_PAGE);
                    }
                } catch (Exception e) {
                    logger.warn("Paradigm: Failed to check for updates (GitHub)." );
                }
            }
        }

        private static boolean checkModrinth(String currentVersion, Logger logger) {
            HttpURLConnection conn = null;
            try {
                String apiUrl = "https://api.modrinth.com/v2/project/" + MODRINTH_PROJECT_ID + "/version";
                conn = (HttpURLConnection) URI.create(apiUrl).toURL().openConnection();
                conn.setRequestProperty("User-Agent", "Paradigm-UpdateChecker/1.0 (+https://modrinth.com/mod/" + MODRINTH_PROJECT_ID + ")");
                conn.setConnectTimeout(4000);
                conn.setReadTimeout(6000);

                try (BufferedReader br = new BufferedReader(new InputStreamReader(conn.getInputStream(), StandardCharsets.UTF_8))) {
                    JsonArray arr = JsonParser.parseReader(br).getAsJsonArray();
                    if (arr.isEmpty()) return false;

                    String mcVersion = getMinecraftVersionSafe();
                    String loader = "fabric";

                    String newestCompatible = null;
                    String publishedAtNewest = null;

                    for (JsonElement el : arr) {
                        JsonObject obj = el.getAsJsonObject();
                        boolean loaderOk = false;
                        JsonArray loaders = obj.getAsJsonArray("loaders");
                        if (loaders != null) {
                            for (JsonElement l : loaders) {
                                if (loader.equalsIgnoreCase(l.getAsString())) { loaderOk = true; break; }
                            }
                        }
                        if (!loaderOk) continue;

                        boolean versionOk = true;
                        if (mcVersion != null) {
                            versionOk = false;
                            JsonArray gameVersions = obj.getAsJsonArray("game_versions");
                            if (gameVersions != null) {
                                for (JsonElement v : gameVersions) {
                                    if (mcVersion.equals(v.getAsString())) { versionOk = true; break; }
                                }
                            }
                        }
                        if (!versionOk) continue;

                        String ver = obj.get("version_number").getAsString();
                        String published = obj.has("date_published") ? obj.get("date_published").getAsString() : null;

                        if (newestCompatible == null) {
                            newestCompatible = ver;
                            publishedAtNewest = published;
                        } else if (isAfter(published, publishedAtNewest)) {
                            newestCompatible = ver;
                            publishedAtNewest = published;
                        }
                    }

                    if (newestCompatible != null && !newestCompatible.equals(currentVersion)) {
                        logger.info("Paradigm: A new version is available on Modrinth: {} (Current: {})", newestCompatible, currentVersion);
                        logger.info("Modrinth: {}", MODRINTH_PROJECT_PAGE);
                        logger.info("CurseForge: {}", CURSEFORGE_PROJECT_PAGE);
                        return true;
                    }
                }
            } catch (Exception ex) {
                logger.debug("Paradigm: Modrinth check failed: {}", ex.toString());
            } finally {
                if (conn != null) conn.disconnect();
            }
            return false;
        }

        private static boolean isAfter(String a, String b) {
            if (a == null) return false;
            if (b == null) return true;
            return a.compareTo(b) > 0;
        }

        private static String getMinecraftVersionSafe() {
            try {
                return net.minecraft.SharedConstants.getGameVersion().getName();
            } catch (Throwable t) {
                return null;
            }
        }
    }
}
