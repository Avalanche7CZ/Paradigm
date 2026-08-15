/*
 * IMPORTANT FOR PORTING (1.20.1 → 1.19.2):
 * Forge 1.19.2 only calls the no-argument constructor of the main mod class.
 * All initialization (event bus registration, services, modules, etc.)
 * MUST be in the no-arg constructor. Do NOT use a constructor with FMLJavaModLoadingContext!
 * If you move init logic to a different constructor, the mod will NOT load.
 */

package eu.avalanche7.paradigm;

import com.mojang.logging.LogUtils;
import eu.avalanche7.paradigm.configs.CooldownConfigHandler;
import eu.avalanche7.paradigm.core.CommonRuntime;
import eu.avalanche7.paradigm.core.ParadigmModule;
import eu.avalanche7.paradigm.core.Services;
import eu.avalanche7.paradigm.platform.ForgeConfig;
import eu.avalanche7.paradigm.platform.PlatformAdapterImpl;
import eu.avalanche7.paradigm.utils.TelemetryReporter;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.common.MinecraftForge;
import net.minecraftforge.event.RegisterCommandsEvent;
import net.minecraftforge.event.server.ServerStartingEvent;
import net.minecraftforge.event.server.ServerStoppedEvent;
import net.minecraftforge.event.server.ServerStoppingEvent;
import net.minecraftforge.eventbus.api.EventPriority;
import net.minecraftforge.eventbus.api.IEventBus;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.ModList;
import net.minecraftforge.fml.common.Mod;
import net.minecraftforge.fml.event.lifecycle.FMLCommonSetupEvent;
import net.minecraftforge.fml.javafmlmod.FMLJavaModLoadingContext;
import net.minecraftforge.fml.loading.FMLEnvironment;
import org.slf4j.Logger;

import java.util.ArrayList;
import java.util.List;

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
        return INSTANCE != null ? INSTANCE.modules : java.util.Collections.emptyList();
    }

    public Paradigm() {
        INSTANCE = this;

        if (FMLEnvironment.dist == Dist.CLIENT) {
            LOGGER.info("Paradigm mod is only supported on the server side. Please remove it from the client.");
            return;
        }

        var platformConfig = new ForgeConfig();

        var placeholders = new eu.avalanche7.paradigm.utils.Placeholders();
        var debugLogger = new eu.avalanche7.paradigm.utils.DebugLogger(null);
        var taskScheduler = new eu.avalanche7.paradigm.utils.TaskScheduler(debugLogger);
        var platformAdapter = new PlatformAdapterImpl(null, placeholders, taskScheduler, debugLogger);

        CommonRuntime.Runtime runtime = CommonRuntime.bootstrap(LOGGER, platformConfig, platformAdapter);
        this.services = runtime.services();
        SERVICES_INSTANCE = this.services;

        try {
            platformAdapter.setPermissionsHandler(runtime.permissionsHandler());
        } catch (Throwable failure) {
            LOGGER.error("[Paradigm] Startup: failed to attach the permission handler to the Forge platform adapter.", failure);
        }
        try {
            platformAdapter.provideMessageParser(services.getMessageParser());
        } catch (Throwable failure) {
            LOGGER.error("[Paradigm] Startup: failed to attach the message parser to the Forge platform adapter.", failure);
        }

        modules.clear();
        modules.addAll(runtime.modules());

        String modVersion = "unknown";
        try {
            modVersion = ModList.get().getModContainerById(MOD_ID)
                    .map(c -> c.getModInfo().getVersion().toString())
                    .orElse("unknown");
        } catch (Throwable failure) {
            LOGGER.debug("[Paradigm] Startup: could not read the mod version; using 'unknown'.", failure);
        }
        CommonRuntime.attachToApi(runtime, modVersion);

        IEventBus modEventBus = FMLJavaModLoadingContext.get().getModEventBus();
        modules.forEach(m -> m.onLoad(null, services, modEventBus));
        modules.forEach(m -> m.registerEventListeners(MinecraftForge.EVENT_BUS, services));

        modEventBus.addListener(this::commonSetup);
        MinecraftForge.EVENT_BUS.register(this);
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
            LOGGER.info("{} - Version {} - FORGE", displayName, version);
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
                            "https://raw.githubusercontent.com/Avalanche7CZ/Paradigm/Forge/main/version.txt?v=1"
                    ),
                    version,
                    mcVersion,
                    "forge",
                    LOGGER
            );
        });
    }

    @SubscribeEvent
    public void onServerStarting(ServerStartingEvent event) {
        services.setServer(event.getServer());
        try {
            services.getTaskScheduler().setMainThreadExecutor(event.getServer()::execute);
        } catch (Throwable failure) {
            LOGGER.error("[Paradigm] Startup: failed to bind the task scheduler to the Minecraft server thread.", failure);
        }

        if (telemetryReporter == null) telemetryReporter = new TelemetryReporter(services);
        telemetryReporter.start();

        try {
            services.getPermissionsHandler().registerLuckPermsPermissions();
        } catch (Throwable failure) {
            LOGGER.warn("[Paradigm] Startup: LuckPerms permission registration failed; platform authorization fallback remains active.", failure);
        }

        modules.forEach(module -> {
            if (module.isEnabled(services)) {
                module.onServerStarting(event, services);
            }
        });
    }

    @SubscribeEvent(priority = EventPriority.LOWEST)
    public void onRegisterCommands(RegisterCommandsEvent event) {
        try {
            if (services != null && services.getPlatformAdapter() instanceof eu.avalanche7.paradigm.platform.PlatformAdapterImpl pai) {
                pai.setCommandDispatcher(event.getDispatcher());
            }
        } catch (Throwable failure) {
            LOGGER.error("[Paradigm] Commands: failed to retain the active command dispatcher.", failure);
        }

        modules.forEach(module -> {
            if (module.isEnabled(services)) {
                Object registryAccess;
                try {
                    registryAccess = event.getBuildContext();
                } catch (Throwable failure) {
                    LOGGER.debug("[Paradigm] Commands: build context is unavailable on this Forge version.", failure);
                    registryAccess = null;
                }
                module.registerCommands(event.getDispatcher(), registryAccess, services);
            }
        });
        services.refreshDiscoveredCommandPermissions();
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
        services.shutdown();
        CooldownConfigHandler.saveCooldowns();
    }

    @SubscribeEvent
    public void onServerStopped(ServerStoppedEvent event) {
        modules.forEach(module -> {
            if (module.isEnabled(services)) {
                module.onServerStopped(event, services);
            }
        });
    }
}
