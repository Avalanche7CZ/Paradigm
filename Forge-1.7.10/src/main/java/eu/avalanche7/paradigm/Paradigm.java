package eu.avalanche7.paradigm;

import java.util.ArrayList;
import java.util.List;

import cpw.mods.fml.common.FMLCommonHandler;
import cpw.mods.fml.common.Mod;
import cpw.mods.fml.common.event.FMLInitializationEvent;
import cpw.mods.fml.common.event.FMLPreInitializationEvent;
import cpw.mods.fml.common.event.FMLServerStartingEvent;
import cpw.mods.fml.common.event.FMLServerStoppedEvent;
import cpw.mods.fml.common.event.FMLServerStoppingEvent;
import cpw.mods.fml.common.eventhandler.SubscribeEvent;
import cpw.mods.fml.common.gameevent.TickEvent;
import net.minecraftforge.common.MinecraftForge;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import eu.avalanche7.paradigm.configs.CooldownConfigHandler;
import eu.avalanche7.paradigm.core.CommonRuntime;
import eu.avalanche7.paradigm.core.ParadigmModule;
import eu.avalanche7.paradigm.core.Services;
import eu.avalanche7.paradigm.modules.StorageLifecycle;
import eu.avalanche7.paradigm.modules.commands.Help;
import eu.avalanche7.paradigm.modules.commands.Reload;
import eu.avalanche7.paradigm.platform.ForgeConfig;
import eu.avalanche7.paradigm.platform.PlatformAdapterImpl;
import eu.avalanche7.paradigm.utils.DebugLogger;
import eu.avalanche7.paradigm.utils.Placeholders;
import eu.avalanche7.paradigm.utils.TaskScheduler;

@Mod(modid = Paradigm.MOD_ID, name = "Paradigm", version = ModVersion.VALUE,
        acceptableRemoteVersions = "*")
public final class Paradigm {
    public static final String MOD_ID = "paradigm";
    private static final Logger LOGGER = LoggerFactory.getLogger(Paradigm.class);

    private final List<ParadigmModule> activeModules = new ArrayList<>();
    private final Reload commandManagement = new Reload();
    private PlatformAdapterImpl platform;
    private Services services;

    @Mod.EventHandler
    public void preInit(FMLPreInitializationEvent event) {
        DebugLogger debugLogger = new DebugLogger(null);
        TaskScheduler scheduler = new TaskScheduler(debugLogger);
        ForgeConfig config = new ForgeConfig(event.getModConfigurationDirectory().toPath());
        platform = new PlatformAdapterImpl(null, new Placeholders(), scheduler, debugLogger, config);

        CommonRuntime.Runtime runtime =
                CommonRuntime.bootstrap(LOGGER, platform.getConfig(), platform);
        services = runtime.services();
        platform.setPermissionsHandler(runtime.permissionsHandler());
        platform.setPlaceholders(services.getPlaceholders());
        platform.provideMessageParser(services.getMessageParser());

        for (ParadigmModule module : runtime.modules()) {
            if (module instanceof StorageLifecycle || module instanceof Help) {
                activeModules.add(module);
            }
        }
        CommonRuntime.attachToApi(
                new CommonRuntime.Runtime(List.copyOf(activeModules), services, runtime.permissionsHandler()),
                ModVersion.VALUE);
        for (ParadigmModule module : activeModules) {
            module.onLoad(event, services, FMLCommonHandler.instance().bus());
            module.registerEventListeners(MinecraftForge.EVENT_BUS, services);
        }
        platform.events().register();
        FMLCommonHandler.instance().bus().register(this);
        LOGGER.info("Paradigm Forge 1.7.10 core initialized; feature modules require native "
                    + "adapter capabilities.");
    }

    @Mod.EventHandler
    public void init(FMLInitializationEvent event) {
        for (ParadigmModule module : activeModules) {
            if (module.isEnabled(services)) {
                module.onEnable(services);
            }
        }
    }

    @Mod.EventHandler
    public void starting(FMLServerStartingEvent event) {
        platform.setMinecraftServer(event.getServer());
        services.setServer(event.getServer());
        services.getTaskScheduler().setMainThreadExecutor(platform::executeOnServerThread);
        LOGGER.info("Paradigm MinecraftServer and scheduler bound");
        for (ParadigmModule module : activeModules) {
            if (module.isEnabled(services)) {
                module.onServerStarting(event, services);
                LOGGER.info("Paradigm module started: {}", module.getName());
            }
        }
        for (ParadigmModule module : activeModules) {
            if (module.isEnabled(services)) {
                if (module instanceof Help) {
                    platform.registerCommandContributor(module, () -> {
                        if (services.getCommandToggleStore().isEnabled("paradigm.help")) {
                            module.registerCommands(event.getServer().getCommandManager(), null, services);
                        }
                    });
                }
            }
        }
        platform.registerCommandContributor(commandManagement,
                () -> commandManagement.registerCommandToggleCommands(services,
                        id -> "paradigm.help".equals(id) || "paradigm.command".equals(id)));
    }

    @SubscribeEvent
    public void tick(TickEvent.ServerTickEvent event) {
        if (event.phase == TickEvent.Phase.END && platform != null) {
            platform.tick();
        }
    }

    @Mod.EventHandler
    public void stopping(FMLServerStoppingEvent event) {
        for (ParadigmModule module : activeModules) {
            if (module.isEnabled(services)) {
                module.onServerStopping(event, services);
                module.onDisable(services);
                LOGGER.info("Paradigm module disabled: {}", module.getName());
            }
        }
        services.shutdown();
        CooldownConfigHandler.saveCooldowns();
        LOGGER.info("Paradigm services shut down");
    }

    @Mod.EventHandler
    public void stopped(FMLServerStoppedEvent event) {
        for (ParadigmModule module : activeModules) {
            if (module.isEnabled(services)) {
                module.onServerStopped(event, services);
                LOGGER.info("Paradigm module stopped: {}", module.getName());
            }
        }
        FMLCommonHandler.instance().bus().unregister(this);
        platform.events().unregister();
        platform.clear();
        LOGGER.info("Paradigm Forge 1.7.10 cleanup complete");
    }
}
