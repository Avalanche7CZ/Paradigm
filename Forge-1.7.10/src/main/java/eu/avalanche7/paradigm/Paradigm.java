package eu.avalanche7.paradigm;

import java.util.ArrayList;
import java.util.List;

import cpw.mods.fml.common.FMLCommonHandler;
import cpw.mods.fml.common.Mod;
import cpw.mods.fml.common.event.FMLInitializationEvent;
import cpw.mods.fml.common.event.FMLPreInitializationEvent;
import cpw.mods.fml.common.event.FMLServerStartedEvent;
import cpw.mods.fml.common.event.FMLServerStartingEvent;
import cpw.mods.fml.common.event.FMLServerStoppedEvent;
import cpw.mods.fml.common.event.FMLServerStoppingEvent;
import cpw.mods.fml.common.eventhandler.SubscribeEvent;
import cpw.mods.fml.common.gameevent.PlayerEvent;
import cpw.mods.fml.common.gameevent.TickEvent;
import net.minecraft.entity.player.EntityPlayerMP;
import net.minecraftforge.common.MinecraftForge;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import eu.avalanche7.paradigm.configs.CooldownConfigHandler;
import eu.avalanche7.paradigm.core.CommonRuntime;
import eu.avalanche7.paradigm.core.ParadigmModule;
import eu.avalanche7.paradigm.core.Services;
import eu.avalanche7.paradigm.platform.ForgeConfig;
import eu.avalanche7.paradigm.platform.MinecraftLoginHandler;
import eu.avalanche7.paradigm.platform.PlatformAdapterImpl;
import eu.avalanche7.paradigm.utils.DebugLogger;
import eu.avalanche7.paradigm.utils.Placeholders;
import eu.avalanche7.paradigm.utils.TaskScheduler;
import eu.avalanche7.paradigm.utils.TelemetryReporter;

@Mod(modid = Paradigm.MOD_ID, name = "Paradigm", version = ModVersion.VALUE,
        acceptableRemoteVersions = "*")
public final class Paradigm {
    public static final String MOD_ID = "paradigm";
    private static final Logger LOGGER = LoggerFactory.getLogger(Paradigm.class);

    private final List<ParadigmModule> modules = new ArrayList<>();
    private PlatformAdapterImpl platform;
    private Services services;
    private static Services SERVICES_INSTANCE;
    private static Paradigm INSTANCE;
    private TelemetryReporter telemetryReporter;

    public Paradigm() {
        INSTANCE = this;
    }

    public static Services getServices() {
        return SERVICES_INSTANCE;
    }

    public static List<ParadigmModule> getModules() {
        return INSTANCE != null ? INSTANCE.modules : java.util.Collections.emptyList();
    }

    @Mod.EventHandler
    public void preInit(FMLPreInitializationEvent event) {
        if (event.getSide().isClient()) {
            LOGGER.info("Paradigm mod is only supported on the server side. Please remove it from the client.");
            return;
        }
        DebugLogger debugLogger = new DebugLogger(null);
        TaskScheduler scheduler = new TaskScheduler(debugLogger);
        ForgeConfig config = new ForgeConfig(event.getModConfigurationDirectory().toPath());
        platform = new PlatformAdapterImpl(null, new Placeholders(), scheduler, debugLogger, config);

        CommonRuntime.Runtime runtime =
                CommonRuntime.bootstrap(LOGGER, platform.getConfig(), platform);
        services = runtime.services();
        SERVICES_INSTANCE = services;
        platform.setPermissionsHandler(runtime.permissionsHandler());
        platform.setCommandToggleStore(services.getCommandToggleStore());
        platform.setPlaceholders(services.getPlaceholders());
        platform.provideMessageParser(services.getMessageParser());

        modules.clear();
        modules.addAll(runtime.modules());
        CommonRuntime.attachToApi(runtime, ModVersion.VALUE);
        modules.forEach(module -> module.onLoad(event, services, FMLCommonHandler.instance().bus()));
        modules.forEach(module -> module.registerEventListeners(MinecraftForge.EVENT_BUS, services));
        platform.events().register();
        FMLCommonHandler.instance().bus().register(this);
    }

    @Mod.EventHandler
    public void init(FMLInitializationEvent event) {
        if (services == null) return;
        for (ParadigmModule module : modules) {
            if (module.isEnabled(services)) {
                module.onEnable(services);
            }
        }

        LOGGER.info("==================================================");
        LOGGER.info("  ____                     _ _");
        LOGGER.info(" |  _ \\ __ _ _ __ __ _  __| (_) __ _ _ __ ___");
        LOGGER.info(" | |_) / _` | '__/ _` |/ _` | |/ _` | '_ ` _ \\");
        LOGGER.info(" |  __/ (_| | | | (_| | (_| | | (_| | | | | | |");
        LOGGER.info(" |_|   \\__,_|_|  \\__,_|\\__,_|_|\\__, |_| |_| |_|");
        LOGGER.info("                                |___/");
        LOGGER.info("");
        LOGGER.info("{} - Version {} - FORGE", "Paradigm", ModVersion.VALUE);
        LOGGER.info("Author: Avalanche7CZ");
        LOGGER.info("Discord: https://discord.com/invite/qZDcQdEFqQ");
        LOGGER.info("==================================================");

        String mcVersion = null;
        try {
            mcVersion = services != null && services.getPlatformAdapter() != null ? services.getPlatformAdapter().getMinecraftVersion() : null;
        } catch (Throwable failure) {
            LOGGER.debug("[Paradigm] Update check: could not resolve the Minecraft version.", failure);
        }

        eu.avalanche7.paradigm.utils.UpdateChecker.checkForUpdates(
                new eu.avalanche7.paradigm.utils.UpdateChecker.UpdateConfig(
                        "s4i32SJd",
                        "paradigm",
                        "https://raw.githubusercontent.com/Avalanche7CZ/Paradigm/main/version.txt"
                ),
                ModVersion.VALUE,
                mcVersion,
                "forge",
                LOGGER
        );
    }

    @Mod.EventHandler
    public void starting(FMLServerStartingEvent event) {
        if (services == null) return;
        services.setServer(event.getServer());
        MinecraftLoginHandler.bind(services);
        services.getTaskScheduler().setMainThreadExecutor(platform::executeOnServerThread);
        if (telemetryReporter == null) telemetryReporter = new TelemetryReporter(services);
        telemetryReporter.start();

        for (ParadigmModule module : modules) {
            if (module.isEnabled(services)) {
                module.onServerStarting(event, services);
            }
        }
        for (ParadigmModule module : modules) {
            platform.registerCommandContributor(module,
                    () -> platform.registerModuleCommands(module, services));
        }

    }

    @Mod.EventHandler
    public void started(FMLServerStartedEvent event) {
        if (services == null) return;
        platform.refreshRegisteredCommandContributors();
        services.refreshDiscoveredCommandPermissions();
    }

    @SubscribeEvent
    public void tick(TickEvent.ServerTickEvent event) {
        if (event.phase == TickEvent.Phase.END && platform != null) {
            platform.tick();
        }
    }

    @SubscribeEvent
    public void respawn(PlayerEvent.PlayerRespawnEvent event) {
        if (platform != null && event.player instanceof EntityPlayerMP player) {
            platform.playerRespawned(player);
        }
    }

    @SubscribeEvent
    public void dimensionChanged(PlayerEvent.PlayerChangedDimensionEvent event) {
        if (platform != null && event.player instanceof EntityPlayerMP player) platform.playerTransferred(player);
    }

    @SubscribeEvent
    public void disconnected(PlayerEvent.PlayerLoggedOutEvent event) {
        if (platform != null && event.player instanceof EntityPlayerMP player) {
            platform.playerDisconnected(player);
        }
    }

    @Mod.EventHandler
    public void stopping(FMLServerStoppingEvent event) {
        if (services == null) return;
        for (ParadigmModule module : modules) {
            if (module.isEnabled(services)) {
                module.onServerStopping(event, services);
                module.onDisable(services);
            }
        }
        if (telemetryReporter != null) telemetryReporter.stop();
        MinecraftLoginHandler.clear();
        services.shutdown();
        CooldownConfigHandler.saveCooldowns();
        LOGGER.info("Paradigm services shut down");
    }

    @Mod.EventHandler
    public void stopped(FMLServerStoppedEvent event) {
        if (services == null) return;
        for (ParadigmModule module : modules) {
            if (module.isEnabled(services)) {
                module.onServerStopped(event, services);
            }
        }
        FMLCommonHandler.instance().bus().unregister(this);
        platform.events().unregister();
        platform.clear();
        LOGGER.info("Paradigm Forge 1.7.10 cleanup complete");
    }
}
