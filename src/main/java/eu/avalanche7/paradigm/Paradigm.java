package eu.avalanche7.paradigm;

import eu.avalanche7.paradigm.configs.*;
import eu.avalanche7.paradigm.core.ParadigmModule;
import eu.avalanche7.paradigm.core.Services;
import eu.avalanche7.paradigm.modules.*;
import eu.avalanche7.paradigm.utils.*;
import net.minecraftforge.fml.common.Mod;
import net.minecraftforge.fml.common.event.FMLPreInitializationEvent;
import net.minecraftforge.fml.common.event.FMLServerStartingEvent;
import net.minecraftforge.fml.common.event.FMLServerStoppingEvent;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import java.io.BufferedReader;
import java.io.File;
import java.io.InputStreamReader;
import java.net.URL;
import java.util.ArrayList;
import java.util.List;

@Mod(modid = Paradigm.MOD_ID, name = Paradigm.NAME, version = Paradigm.VERSION, acceptableRemoteVersions = "*")
public class Paradigm {

    public static final String MOD_ID = "paradigm";
    public static final String NAME = "Paradigm";
    public static final String VERSION = "12.0.6";

    private static final Logger LOGGER = LogManager.getLogger(MOD_ID);
    private final List<ParadigmModule> modules = new ArrayList<>();
    private Services services;

    @Mod.EventHandler
    public void preInit(FMLPreInitializationEvent event) {
        LOGGER.info("Initializing Paradigm Mod for Minecraft 1.12.2...");

        File configDir = new File(event.getSuggestedConfigurationFile().getParentFile(), "paradigm");
        if (!configDir.exists()) {
            configDir.mkdirs();
        }
        MainConfigHandler.init(configDir);
        AnnouncementsConfigHandler.init(configDir);
        MOTDConfigHandler.init(configDir);
        MentionConfigHandler.init(configDir);
        RestartConfigHandler.init(configDir);
        ChatConfigHandler.init(configDir);

        createServices(configDir);
        registerModules();

        modules.forEach(module -> {
            module.onLoad(event, services);
            module.registerEventListeners(services);
        });
    }

    private void createServices(File configDir) {
        DebugLogger debugLoggerInstance = new DebugLogger(LOGGER, MainConfigHandler.CONFIG);
        Placeholders placeholdersInstance = new Placeholders();
        MessageParser messageParserInstance = new MessageParser(placeholdersInstance);
        CMConfig cmConfigInstance = new CMConfig(debugLoggerInstance);
        cmConfigInstance.init(configDir);
        Lang langInstance = new Lang(LOGGER, MainConfigHandler.CONFIG, messageParserInstance);
        langInstance.init(configDir);
        langInstance.initializeLanguage();
        TaskScheduler taskSchedulerInstance = new TaskScheduler(debugLoggerInstance);
        PermissionsHandler permissionsHandlerInstance = new PermissionsHandler(LOGGER, cmConfigInstance, debugLoggerInstance);
        GroupChatManager groupChatManagerInstance = new GroupChatManager();

        this.services = new Services(
                LOGGER,
                MainConfigHandler.CONFIG,
                AnnouncementsConfigHandler.CONFIG,
                MOTDConfigHandler.getConfig(),
                MentionConfigHandler.CONFIG,
                RestartConfigHandler.CONFIG,
                ChatConfigHandler.CONFIG,
                cmConfigInstance,
                groupChatManagerInstance,
                debugLoggerInstance,
                langInstance,
                messageParserInstance,
                permissionsHandlerInstance,
                placeholdersInstance,
                taskSchedulerInstance
        );

        groupChatManagerInstance.setServices(this.services);
        permissionsHandlerInstance.initialize();
    }

    private void registerModules() {
        modules.add(new Announcements());
        modules.add(new MOTD());
        modules.add(new Mentions());
        modules.add(new Restart());
        modules.add(new StaffChat());
        modules.add(new GroupChat(new GroupChatManager()));
        modules.add(new CommandManager());
        LOGGER.info("Paradigm: Registered {} modules.", modules.size());
    }

    @Mod.EventHandler
    public void serverStarting(FMLServerStartingEvent event) {
        services.setServer(event.getServer());
        services.getTaskScheduler().initialize(event.getServer());

        LOGGER.info("==================================================");
        LOGGER.info("{} - Version {}", NAME, VERSION);
        LOGGER.info("Author: Avalanche7CZ");
        LOGGER.info("Discord: https://discord.com/invite/qZDcQdEFqQ");
        LOGGER.info("==================================================");
        UpdateChecker.checkForUpdates();

        modules.forEach(module -> {
            if (module.isEnabled(services)) {
                LOGGER.info("Paradigm: Enabling module: {}", module.getName());
                module.onServerStarting(event, services);
                module.onEnable(services);
                if (module.getCommand() != null) {
                    event.registerServerCommand(module.getCommand());
                }
            } else {
                LOGGER.info("Paradigm: Module disabled by config: {}", module.getName());
            }
        });
    }

    @Mod.EventHandler
    public void serverStopping(FMLServerStoppingEvent event) {
        modules.forEach(module -> {
            if (module.isEnabled(services)) {
                module.onServerStopping(event, services);
                module.onDisable(services);
            }
        });
        if (services.getTaskScheduler() != null) {
            services.getTaskScheduler().onServerStopping();
        }
        LOGGER.info("Paradigm modules have been processed for server stop.");
    }

    public static class UpdateChecker {
        private static final String LATEST_VERSION_URL = "https://raw.githubusercontent.com/Avalanche7CZ/Paradigm/1.12.2/version.txt?v=1";

        public static void checkForUpdates() {
            new Thread(() -> {
                try {
                    URL url = new URL(LATEST_VERSION_URL);
                    BufferedReader reader = new BufferedReader(new InputStreamReader(url.openStream()));
                    String latestVersion = reader.readLine();
                    reader.close();

                    if (latestVersion != null && !VERSION.equals(latestVersion.trim())) {
                        LOGGER.info("A new version of Paradigm is available: " + latestVersion.trim());
                        LOGGER.info("Please update at: https://www.curseforge.com/minecraft/mc-mods/paradigm or https://modrinth.com/mod/paradigm");
                    } else if (latestVersion != null) {
                        LOGGER.info("Paradigm is up to date: " + VERSION);
                    }
                } catch (Exception e) {
                    LOGGER.warn("Paradigm: Failed to check for updates: " + e.getMessage());
                }
            }).start();
        }
    }
}
