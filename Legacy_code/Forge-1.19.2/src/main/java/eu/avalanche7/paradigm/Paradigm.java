/*
 * IMPORTANT FOR PORTING (1.20.1 → 1.19.2):
 * Forge 1.19.2 only calls the no-argument constructor of the main mod class.
 * All initialization (event bus registration, services, modules, etc.)
 * MUST be in the no-arg constructor. Do NOT use a constructor with FMLJavaModLoadingContext!
 * If you move init logic to a different constructor, the mod will NOT load.
 */

package eu.avalanche7.paradigm;

import eu.avalanche7.paradigm.core.ParadigmModule;
import eu.avalanche7.paradigm.core.Services;
import eu.avalanche7.paradigm.modules.*;
import eu.avalanche7.paradigm.configs.*;
import eu.avalanche7.paradigm.modules.CustomCommands;
import eu.avalanche7.paradigm.modules.chat.GroupChat;
import eu.avalanche7.paradigm.modules.chat.JoinLeaveMessages;
import eu.avalanche7.paradigm.modules.chat.MOTD;
import eu.avalanche7.paradigm.modules.chat.StaffChat;
import eu.avalanche7.paradigm.platform.Interfaces.IPlatformAdapter;
import eu.avalanche7.paradigm.platform.PlatformAdapterImpl;
import eu.avalanche7.paradigm.utils.*;
import eu.avalanche7.paradigm.utils.TelemetryReporter;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.mojang.logging.LogUtils;
import net.minecraftforge.common.MinecraftForge;
import net.minecraftforge.event.RegisterCommandsEvent;
import net.minecraftforge.event.server.ServerStartingEvent;
import net.minecraftforge.event.server.ServerStoppingEvent;
import net.minecraftforge.eventbus.api.IEventBus;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;
import net.minecraftforge.fml.event.lifecycle.FMLCommonSetupEvent;
import net.minecraftforge.fml.javafmlmod.FMLJavaModLoadingContext;
import net.minecraftforge.fml.ModList;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.fml.loading.FMLEnvironment;
import org.slf4j.Logger;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.net.HttpURLConnection;
import java.net.URI;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import eu.avalanche7.paradigm.webeditor.store.WebEditorStore;

@Mod(Paradigm.MOD_ID)
public class Paradigm {

    public static final String MOD_ID = "paradigm";
    private static final Logger LOGGER = LogUtils.getLogger();
    private final List<ParadigmModule> modules = new ArrayList<>();
    private Services services;
    private static Services SERVICES_INSTANCE;
    private TelemetryReporter telemetryReporter;
    private static Paradigm INSTANCE;

    public static Services getServices() {
        return SERVICES_INSTANCE;
    }

    public static List<ParadigmModule> getModules() {
        return INSTANCE.modules;
    }

    private static Paradigm instance;
    public Paradigm() {
        INSTANCE = this;
        if (FMLEnvironment.dist == Dist.CLIENT) {
            LOGGER.info("Paradigm mod is only supported on the server side. Please remove it from the client.");
            return;
        }
        IEventBus modEventBus = FMLJavaModLoadingContext.get().getModEventBus();
        this.services = this.initialize();
        SERVICES_INSTANCE = this.services;
        registerModules();
        modules.forEach(module -> module.onLoad(null, services, modEventBus));
        modules.forEach(module -> module.registerEventListeners(MinecraftForge.EVENT_BUS, services));
        modEventBus.addListener(this::commonSetup);
        MinecraftForge.EVENT_BUS.register(this);
    }
    public static Paradigm getInstance() {
        return INSTANCE;
    }
    public static List<ParadigmModule> getModulesStatic() {
        return INSTANCE != null ? INSTANCE.modules : java.util.Collections.emptyList();
    }

    private Services initialize() {
        MainConfigHandler.load();
        AnnouncementsConfigHandler.load();
        MOTDConfigHandler.load();
        MentionConfigHandler.load();
        RestartConfigHandler.load();
        ChatConfigHandler.load();
        EmojiConfigHandler.CONFIG.loadEmojis();

        DebugLogger debugLogger = new DebugLogger(MainConfigHandler.CONFIG);
        debugLogger.debugLog("Paradigm: DebugLogger constructed and all configs loaded.");

        ChatConfigHandler.setJsonValidator(debugLogger);
        AnnouncementsConfigHandler.setJsonValidator(debugLogger);
        MentionConfigHandler.setJsonValidator(debugLogger);
        RestartConfigHandler.setJsonValidator(debugLogger);
        MOTDConfigHandler.setJsonValidator(debugLogger);

        CMConfig cmConfig = new CMConfig(debugLogger);
        cmConfig.loadCommands();
        debugLogger.debugLog("Paradigm: CMConfig loaded.");
        Placeholders placeholders = new Placeholders();
        debugLogger.debugLog("Paradigm: Placeholders created.");
        TaskScheduler taskScheduler = new TaskScheduler(debugLogger);
        debugLogger.debugLog("Paradigm: TaskScheduler created.");
        PermissionsHandler permissionsHandler = new PermissionsHandler(LOGGER, cmConfig, debugLogger);
        debugLogger.debugLog("Paradigm: PermissionsHandler created.");

        IPlatformAdapter platformAdapter = new PlatformAdapterImpl(permissionsHandler, placeholders, taskScheduler, debugLogger);
        debugLogger.debugLog("Paradigm: PlatformAdapterImpl created.");

        MessageParser messageParser = new MessageParser(placeholders, platformAdapter);
        platformAdapter.provideMessageParser(messageParser);
        debugLogger.debugLog("Paradigm: MessageParser created and provided to PlatformAdapter.");

        Lang lang = new Lang(LOGGER, MainConfigHandler.CONFIG, messageParser, platformAdapter);
        lang.initializeLanguage();
        debugLogger.debugLog("Paradigm: Lang created and initialized.");
        GroupChatManager groupChatManager = new GroupChatManager(platformAdapter, lang, debugLogger, messageParser);
        debugLogger.debugLog("Paradigm: GroupChatManager created.");
        CooldownConfigHandler cooldownConfigHandler = new CooldownConfigHandler(debugLogger);
        cooldownConfigHandler.loadCooldowns();
        debugLogger.debugLog("Paradigm: CooldownConfigHandler created and cooldowns loaded.");

        WebEditorStore webEditorStore = new WebEditorStore();

        debugLogger.debugLog("Paradigm: Creating Services object.");
        return new Services(
                LOGGER,
                MainConfigHandler.CONFIG,
                AnnouncementsConfigHandler.CONFIG,
                MOTDConfigHandler.CONFIG,
                MentionConfigHandler.CONFIG,
                RestartConfigHandler.CONFIG,
                ChatConfigHandler.CONFIG,
                cmConfig,
                groupChatManager,
                debugLogger,
                lang,
                messageParser,
                permissionsHandler,
                placeholders,
                taskScheduler,
                platformAdapter,
                cooldownConfigHandler,
                webEditorStore
        );
    }

    private void registerModules() {
        modules.add(new Announcements());
        modules.add(new MOTD());
        modules.add(new Mentions());
        modules.add(new Restart());
        modules.add(new StaffChat());
        modules.add(new GroupChat(services.getGroupChatManager()));
        modules.add(new CustomCommands());
        modules.add(new JoinLeaveMessages());
        modules.add(new eu.avalanche7.paradigm.modules.commands.reload());
        modules.add(new eu.avalanche7.paradigm.modules.commands.help());
        modules.add(new eu.avalanche7.paradigm.modules.commands.editor());
    }

    private void commonSetup(final FMLCommonSetupEvent event) {
        event.enqueueWork(() -> modules.forEach(module -> {
            if (module.isEnabled(services)) {
                module.onEnable(services);
            }
        }));

        ModList.get().getModContainerById(MOD_ID).ifPresent(modContainer -> {
            String version = modContainer.getModInfo().getVersion().toString();
            String displayName = modContainer.getModInfo().getDisplayName();
            LOGGER.info("==================================================");
            LOGGER.info("  ____                     _ _");
            LOGGER.info(" |  _ \\ __ _ _ __ __ _  __| (_) __ _ _ __ ___");
            LOGGER.info(" | |_) / _` | '__/ _` |/ _` | |/ _` | '_ ` _ \\");
            LOGGER.info(" |  __/ (_| | | | (_| | (_| | | (_| | | | | | |");
            LOGGER.info(" |_|   \\__,_|_|  \\__,_|\\__,_|_|\\__, |_| |_| |_|");
            LOGGER.info("                                |___/");
            LOGGER.info("");
            LOGGER.info(" Version: {} - FORGE", version);
            LOGGER.info(" Author: Avalanche7CZ");
            LOGGER.info(" Discord: https://discord.com/invite/qZDcQdEFqQ");
            LOGGER.info("==================================================");
            String mcVersion = UpdateChecker.getMinecraftVersionSafe();
            String loader = "forge";
            UpdateChecker.checkForUpdates(version, mcVersion, loader, LOGGER);
        });
    }

    @SubscribeEvent
    public void onServerStarting(ServerStartingEvent event) {
        services.getPlatformAdapter().setMinecraftServer(event.getServer());
        services.getTaskScheduler().initialize(event.getServer());
        services.getPermissionsHandler().initialize();
        if (telemetryReporter == null) telemetryReporter = new TelemetryReporter(services);
        telemetryReporter.start();
        modules.forEach(module -> {
            if (module.isEnabled(services)) {
                module.onServerStarting(event, services);
            }
        });
    }

    @SubscribeEvent
    public void onRegisterCommands(RegisterCommandsEvent event) {
        modules.forEach(module -> {
            if (module.isEnabled(services)) {
                module.registerCommands(event.getDispatcher(), services);
            }
        });
    }

    @SubscribeEvent
    public void onServerStopping(ServerStoppingEvent event) {
        modules.forEach(module -> {
            if (module.isEnabled(services)) {
                module.onServerStopping(event, services);
                module.onDisable(services);
            }
        });
        if (telemetryReporter != null) telemetryReporter.stop();
        services.getTaskScheduler().onServerStopping();
        services.getCooldownConfigHandler().saveCooldowns();
    }

    public static class UpdateChecker {
        private static final String LATEST_VERSION_URL = "https://raw.githubusercontent.com/Avalanche7CZ/Paradigm/Forge/main/version.txt?v=1";
        private static final String MODRINTH_PROJECT_ID = "s4i32SJd";
        private static final String CURSEFORGE_SLUG = "paradigm";
        private static final String MODRINTH_PROJECT_PAGE = "https://modrinth.com/mod/" + MODRINTH_PROJECT_ID;
        private static final String CURSEFORGE_PROJECT_PAGE = "https://www.curseforge.com/minecraft/mc-mods/" + CURSEFORGE_SLUG;

        public static void checkForUpdates(String currentVersion, String mcVersion, String loader, Logger logger) {
            boolean foundOnModrinth = checkModrinth(currentVersion, mcVersion, loader, logger);
            if (!foundOnModrinth) {
                try (BufferedReader reader = new BufferedReader(new InputStreamReader(URI.create(LATEST_VERSION_URL).toURL().openStream(), StandardCharsets.UTF_8))) {
                    String latestVersion = reader.readLine();
                    if (latestVersion != null && !currentVersion.equals(latestVersion.trim())) {
                        logger.info("Paradigm: A new version is available: {} (Current: {})", latestVersion.trim(), currentVersion);
                        logger.info("Modrinth: {}", MODRINTH_PROJECT_PAGE);
                        logger.info("CurseForge: {}", CURSEFORGE_PROJECT_PAGE);
                    }
                } catch (Exception e) {
                    logger.warn("Paradigm: Failed to check for updates (GitHub).");
                }
            }
        }

        private static boolean checkModrinth(String currentVersion, String mcVersion, String loader, Logger logger) {
            HttpURLConnection conn = null;
            try {
                StringBuilder apiUrl = new StringBuilder("https://api.modrinth.com/v2/project/" + MODRINTH_PROJECT_ID + "/version");
                List<String> params = new ArrayList<>();
                if (mcVersion != null && !mcVersion.isBlank()) params.add("game_versions=" + URLEncoder.encode(mcVersion, StandardCharsets.UTF_8));
                if (loader != null && !loader.isBlank()) params.add("loaders=" + URLEncoder.encode(loader, StandardCharsets.UTF_8));
                if (!params.isEmpty()) apiUrl.append("?").append(String.join("&", params));

                conn = (HttpURLConnection) URI.create(apiUrl.toString()).toURL().openConnection();
                conn.setRequestProperty("User-Agent", "Paradigm-UpdateChecker/1.0 (+https://modrinth.com/mod/" + MODRINTH_PROJECT_ID + ")");
                conn.setConnectTimeout(4000);
                conn.setReadTimeout(6000);

                try (BufferedReader br = new BufferedReader(new InputStreamReader(conn.getInputStream(), StandardCharsets.UTF_8))) {
                    JsonArray arr = JsonParser.parseReader(br).getAsJsonArray();
                    if (arr.isEmpty()) return false;

                    String newestCompatible = null;
                    String publishedAtNewest = null;

                    for (JsonElement el : arr) {
                        JsonObject obj = el.getAsJsonObject();
                        boolean loaderOk = false;
                        JsonArray loaders = obj.getAsJsonArray("loaders");
                        if (loaders != null) {
                            for (JsonElement l : loaders) {
                                if (loader != null && loader.equalsIgnoreCase(l.getAsString())) { loaderOk = true; break; }
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

        public static String getMinecraftVersionSafe() {
            try {
                Class<?> sc = Class.forName("net.minecraft.SharedConstants");
                try {
                    Object verObj = sc.getMethod("getCurrentVersion").invoke(null);
                    if (verObj != null) {
                        try {
                            Object name = verObj.getClass().getMethod("getName").invoke(verObj);
                            if (name instanceof String && !((String) name).isEmpty()) return (String) name;
                        } catch (NoSuchMethodException e1) {
                            try {
                                Object id = verObj.getClass().getMethod("getId").invoke(verObj);
                                if (id instanceof String && !((String) id).isEmpty()) return (String) id;
                            } catch (NoSuchMethodException ignored) { }
                        }
                    }
                } catch (NoSuchMethodException e) {
                    try {
                        Object str = sc.getMethod("getGameVersion").invoke(null);
                        if (str instanceof String && !((String) str).isEmpty()) return (String) str;
                    } catch (NoSuchMethodException ignored) { }
                }
                try {
                    Object vs = sc.getField("VERSION_STRING").get(null);
                    if (vs instanceof String && !((String) vs).isEmpty()) return (String) vs;
                } catch (NoSuchFieldException ignored) { }
            } catch (Throwable t) { }
            try {
                Class<?> fv = Class.forName("net.minecraftforge.versions.forge.ForgeVersion");
                Object mcVer = fv.getField("mcVersion").get(null);
                if (mcVer instanceof String && !((String) mcVer).isEmpty()) return (String) mcVer;
            } catch (Throwable t) { }
            try {
                Class<?> mcp = Class.forName("net.minecraftforge.versions.mcp.MCPVersion");
                Object ver = mcp.getMethod("getMCVersion").invoke(null);
                if (ver instanceof String && !((String) ver).isEmpty()) return (String) ver;
            } catch (Throwable t) { }
            return null;
        }
    }
}
