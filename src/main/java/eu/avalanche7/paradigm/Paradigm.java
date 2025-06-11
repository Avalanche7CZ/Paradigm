package eu.avalanche7.paradigm;

import com.mojang.brigadier.CommandDispatcher;
import eu.avalanche7.paradigm.core.ParadigmModule;
import eu.avalanche7.paradigm.core.Services;
import eu.avalanche7.paradigm.modules.*;
import eu.avalanche7.paradigm.configs.*;
import eu.avalanche7.paradigm.utils.*;
import com.mojang.logging.LogUtils;
import net.minecraft.commands.CommandSourceStack;
import net.minecraftforge.common.MinecraftForge;
import net.minecraftforge.event.RegisterCommandsEvent;
import net.minecraftforge.event.server.ServerStartingEvent;
import net.minecraftforge.event.server.ServerStoppingEvent;
import net.minecraftforge.eventbus.api.IEventBus;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.ModLoadingContext;
import net.minecraftforge.fml.common.Mod;
import net.minecraftforge.fml.config.ModConfig;
import net.minecraftforge.fml.event.lifecycle.FMLCommonSetupEvent;
import net.minecraftforge.fml.javafmlmod.FMLJavaModLoadingContext;
import net.minecraftforge.fml.loading.FMLPaths;
import org.slf4j.Logger;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.net.URL;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

@Mod(Paradigm.MOD_ID)
public class Paradigm {

    public static final String MOD_ID = "paradigm";
    private static final Logger LOGGER = LogUtils.getLogger();
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


    public Paradigm() {
        LOGGER.info("Initializing Paradigm Mod (Instance-Based Services)...");
        IEventBus modEventBus = FMLJavaModLoadingContext.get().getModEventBus();
        modEventBus.addListener(this::commonSetup);

        MinecraftForge.EVENT_BUS.register(this);

        createUtilityInstances();
        initializeServices();
        registerModules();

        modules.forEach(module -> module.registerEventListeners(MinecraftForge.EVENT_BUS, services));
        modules.forEach(module -> module.onLoad(null, services, modEventBus));


        try {
            Path serverConfigDir = FMLPaths.GAMEDIR.get().resolve("config/" + MOD_ID);
            Files.createDirectories(serverConfigDir);

            ModLoadingContext.get().registerConfig(ModConfig.Type.SERVER, MainConfigHandler.SERVER_CONFIG, serverConfigDir.resolve("main.toml").toString());
            ModLoadingContext.get().registerConfig(ModConfig.Type.SERVER, AnnouncementsConfigHandler.SERVER_CONFIG, serverConfigDir.resolve("announcements.toml").toString());
            ModLoadingContext.get().registerConfig(ModConfig.Type.SERVER, MentionConfigHandler.SERVER_CONFIG, serverConfigDir.resolve("mentions.toml").toString());
            ModLoadingContext.get().registerConfig(ModConfig.Type.SERVER, RestartConfigHandler.SERVER_CONFIG, serverConfigDir.resolve("restarts.toml").toString());
            ModLoadingContext.get().registerConfig(ModConfig.Type.SERVER, ChatConfigHandler.SERVER_CONFIG, serverConfigDir.resolve("chat.toml").toString());

            MOTDConfigHandler.loadConfig();
            this.cmConfigInstance.loadCommands();
            this.langInstance.initializeLanguage();

        } catch (Exception e) {
            LOGGER.error("Failed to register or load configuration", e);
            throw new RuntimeException("Configuration loading failed for " + MOD_ID, e);
        }
    }

    private void createUtilityInstances() {
        this.placeholdersInstance = new Placeholders();
        this.messageParserInstance = new MessageParser(this.placeholdersInstance);
        this.debugLoggerInstance = new DebugLogger(MainConfigHandler.CONFIG);
        this.langInstance = new Lang(LOGGER, MainConfigHandler.CONFIG, this.messageParserInstance);
        this.taskSchedulerInstance = new TaskScheduler(this.debugLoggerInstance);
        this.cmConfigInstance = new CMConfig(this.debugLoggerInstance);
        this.permissionsHandlerInstance = new PermissionsHandler(LOGGER, this.cmConfigInstance, this.debugLoggerInstance);
        this.groupChatManagerInstance = new GroupChatManager();
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
                this.cmConfigInstance,
                this.groupChatManagerInstance,
                this.debugLoggerInstance,
                this.langInstance,
                this.messageParserInstance,
                this.permissionsHandlerInstance,
                this.placeholdersInstance,
                this.taskSchedulerInstance
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

        LOGGER.info("Paradigm: Registered {} modules.", modules.size());
    }

    private void commonSetup(final FMLCommonSetupEvent event) {
        event.enqueueWork(() -> {
            modules.forEach(module -> {
                if (module.isEnabled(services)) {
                    LOGGER.info("Paradigm: Enabling module: {}", module.getName());
                    module.onEnable(services);
                } else {
                    LOGGER.info("Paradigm: Module disabled by config: {}", module.getName());
                }
            });
        });

        String version = ModLoadingContext.get().getActiveContainer().getModInfo().getVersion().toString();
        String displayName = ModLoadingContext.get().getActiveContainer().getModInfo().getDisplayName();
        LOGGER.info("Paradigm mod has been set up.");
        LOGGER.info("==================================================");
        LOGGER.info("{} - Version {}", displayName, version);
        LOGGER.info("Author: Avalanche7CZ");
        LOGGER.info("Discord: https://discord.com/invite/qZDcQdEFqQ");
        LOGGER.info("==================================================");
        Paradigm.UpdateChecker.checkForUpdates(version, LOGGER);
    }

    @SubscribeEvent
    public void onServerStarting(ServerStartingEvent event) {
        services.setServer(event.getServer());
        modules.forEach(module -> {
            if (module.isEnabled(services)) {
                module.onServerStarting(event, services);
            }
        });
    }

    @SubscribeEvent
    public void onRegisterCommands(RegisterCommandsEvent event) {
        CommandDispatcher<CommandSourceStack> dispatcher = event.getDispatcher();
        modules.forEach(module -> {
            if (module.isEnabled(services)) {
                module.registerCommands(dispatcher, services);
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
        if (this.taskSchedulerInstance != null) {
            this.taskSchedulerInstance.onServerStopping();
        }
        LOGGER.info("Paradigms modules have been processed for server stop.");
    }

    public static class UpdateChecker {
        private static final String LATEST_VERSION_URL = "https://raw.githubusercontent.com/Avalanche7CZ/Paradigm/main/version.txt";

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
