package eu.avalanche7.paradigm;

import com.mojang.brigadier.CommandDispatcher;
import eu.avalanche7.paradigm.core.ParadigmModule;
import eu.avalanche7.paradigm.core.Services;
import eu.avalanche7.paradigm.modules.*;
import eu.avalanche7.paradigm.modules.chat.GroupChat;
import eu.avalanche7.paradigm.modules.chat.MOTD;
import eu.avalanche7.paradigm.modules.chat.StaffChat;
import eu.avalanche7.paradigm.configs.*;
import eu.avalanche7.paradigm.platform.IPlatformAdapter;
import eu.avalanche7.paradigm.platform.PlatformAdapterImpl;
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
import net.minecraftforge.fml.event.lifecycle.FMLCommonSetupEvent;
import net.minecraftforge.fml.javafmlmod.FMLJavaModLoadingContext;
import net.minecraftforge.fml.ModList;
import org.slf4j.Logger;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.net.URL;
import java.util.ArrayList;
import java.util.List;

@Mod(Paradigm.MOD_ID)
public class Paradigm {

    public static final String MOD_ID = "paradigm";
    private static final Logger LOGGER = LogUtils.getLogger();
    private final List<ParadigmModule> modules = new ArrayList<>();
    private final Services services;

    public Paradigm() {
        IEventBus modEventBus = FMLJavaModLoadingContext.get().getModEventBus();

        this.services = this.initialize();

        registerModules();

        modules.forEach(module -> module.registerEventListeners(MinecraftForge.EVENT_BUS, services));
        modules.forEach(module -> module.onLoad(null, services, modEventBus));

        modEventBus.addListener(this::commonSetup);
        MinecraftForge.EVENT_BUS.register(this);
    }

    private Services initialize() {
        MainConfigHandler.load();
        AnnouncementsConfigHandler.load();
        MentionConfigHandler.load();
        RestartConfigHandler.load();
        ChatConfigHandler.load();
        MOTDConfigHandler.loadConfig();

        DebugLogger debugLogger = new DebugLogger(MainConfigHandler.CONFIG);
        CMConfig cmConfig = new CMConfig(debugLogger);
        cmConfig.loadCommands();
        Placeholders placeholders = new Placeholders();
        TaskScheduler taskScheduler = new TaskScheduler(debugLogger);
        PermissionsHandler permissionsHandler = new PermissionsHandler(LOGGER, cmConfig, debugLogger);

        IPlatformAdapter platformAdapter = new PlatformAdapterImpl(permissionsHandler, placeholders, taskScheduler, debugLogger);

        MessageParser messageParser = new MessageParser(placeholders, platformAdapter);

        platformAdapter.provideMessageParser(messageParser);

        Lang lang = new Lang(LOGGER, MainConfigHandler.CONFIG, messageParser);
        lang.initializeLanguage();

        GroupChatManager groupChatManager = new GroupChatManager(platformAdapter, lang, debugLogger, messageParser);

        CooldownConfigHandler cooldownConfigHandler = new CooldownConfigHandler(debugLogger);
        cooldownConfigHandler.loadCooldowns();

        return new Services(
                LOGGER,
                MainConfigHandler.CONFIG,
                AnnouncementsConfigHandler.CONFIG,
                MOTDConfigHandler.getConfig(),
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
                cooldownConfigHandler
        );
    }

    private void registerModules() {
        modules.add(new Announcements());
        modules.add(new MOTD());
        modules.add(new Mentions());
        modules.add(new Restart());
        modules.add(new StaffChat());
        modules.add(new GroupChat(services.getGroupChatManager()));
        modules.add(new CommandManager());
        modules.add(new eu.avalanche7.paradigm.modules.chat.JoinLeaveMessages());
    }

    private void commonSetup(final FMLCommonSetupEvent event) {
        event.enqueueWork(() -> modules.forEach(module -> {
            if (module.isEnabled(services)) {
                module.onEnable(services);
            }
        }));

        ModList.get().getModContainerById(MOD_ID).ifPresent(modContainer -> {
            String version = ModLoadingContext.get().getActiveContainer().getModInfo().getVersion().toString();
            String displayName = ModLoadingContext.get().getActiveContainer().getModInfo().getDisplayName();
            LOGGER.info("==================================================");
            LOGGER.info("{} - Version {}", displayName, version);
            LOGGER.info("Author: Avalanche7CZ");
            LOGGER.info("Discord: https://discord.com/invite/qZDcQdEFqQ");
            LOGGER.info("==================================================");
            UpdateChecker.checkForUpdates(version, LOGGER);
        });
    }

    @SubscribeEvent
    public void onServerStarting(ServerStartingEvent event) {
        services.getPlatformAdapter().setMinecraftServer(event.getServer());
        services.getTaskScheduler().initialize(event.getServer());
        services.getPermissionsHandler().initialize();
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
        services.getTaskScheduler().onServerStopping();
    }

    public static class UpdateChecker {
        private static final String LATEST_VERSION_URL = "https://raw.githubusercontent.com/Avalanche7CZ/Paradigm/1.19.2/version.txt";

        public static void checkForUpdates(String currentVersion, Logger logger) {
            try (BufferedReader reader = new BufferedReader(new InputStreamReader(new URL(LATEST_VERSION_URL).openStream()))) {
                String latestVersion = reader.readLine();
                if (latestVersion != null && !currentVersion.equals(latestVersion.trim())) {
                    logger.info("Paradigm: A new version is available: {} (Current: {})", latestVersion.trim(), currentVersion);
                }
            } catch (Exception e) {
                logger.warn("Paradigm: Failed to check for updates.");
            }
        }
    }
}
