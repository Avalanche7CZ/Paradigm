package eu.avalanche7.paradigm;

import com.mojang.logging.LogUtils;
import eu.avalanche7.paradigm.configs.CooldownConfigHandler;
import eu.avalanche7.paradigm.core.CommonRuntime;
import eu.avalanche7.paradigm.core.ParadigmModule;
import eu.avalanche7.paradigm.core.Services;
import eu.avalanche7.paradigm.platform.PlatformAdapterImpl;
import eu.avalanche7.paradigm.utils.TelemetryReporter;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.ModList;
import net.neoforged.fml.common.Mod;
import net.neoforged.fml.loading.FMLEnvironment;
import net.neoforged.neoforge.common.NeoForge;
import net.neoforged.neoforge.event.RegisterCommandsEvent;
import net.neoforged.neoforge.event.server.ServerStartingEvent;
import net.neoforged.neoforge.event.server.ServerStoppingEvent;
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

        var platformConfig = new eu.avalanche7.paradigm.platform.NeoForgeConfig();

        var placeholders = new eu.avalanche7.paradigm.utils.Placeholders();
        var debugLogger = new eu.avalanche7.paradigm.utils.DebugLogger(null);
        var taskScheduler = new eu.avalanche7.paradigm.utils.TaskScheduler(debugLogger);
        var platformAdapter = new PlatformAdapterImpl(null, placeholders, taskScheduler, debugLogger);

        CommonRuntime.Runtime runtime = CommonRuntime.bootstrap(LOGGER, platformConfig, platformAdapter);
        this.services = runtime.services();
        SERVICES_INSTANCE = this.services;

        try {
            platformAdapter.setPermissionsHandler(runtime.permissionsHandler());
        } catch (Throwable ignored) {
        }
        try {
            platformAdapter.provideMessageParser(services.getMessageParser());
        } catch (Throwable ignored) {
        }

        modules.clear();
        modules.addAll(runtime.modules());

        String modVersion = "unknown";
        try {
            modVersion = ModList.get().getModContainerById(MOD_ID)
                    .map(c -> c.getModInfo().getVersion().toString())
                    .orElse("unknown");
        } catch (Throwable ignored) {
        }
        CommonRuntime.attachToApi(runtime, modVersion);

        modules.forEach(m -> m.onLoad(null, services, NeoForge.EVENT_BUS));
        modules.forEach(m -> m.registerEventListeners(NeoForge.EVENT_BUS, services));

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
            LOGGER.info("{} - Version {} - NEOFORGE", displayName, version);
            LOGGER.info("Author: Avalanche7CZ");
            LOGGER.info("Discord: https://discord.com/invite/qZDcQdEFqQ");
            LOGGER.info("==================================================");

            String mcVersion = null;
            try {
                mcVersion = services != null && services.getPlatformAdapter() != null ? services.getPlatformAdapter().getMinecraftVersion() : null;
            } catch (Throwable ignored) {
            }

            eu.avalanche7.paradigm.utils.UpdateChecker.checkForUpdates(
                    new eu.avalanche7.paradigm.utils.UpdateChecker.UpdateConfig(
                            "s4i32SJd",
                            "paradigm",
                            "https://raw.githubusercontent.com/Avalanche7CZ/Paradigm/NeoForge/1.21.1/version.txt?v=1"
                    ),
                    version,
                    mcVersion,
                    "neoforge",
                    LOGGER
            );
        });

        NeoForge.EVENT_BUS.register(this);
    }

    @SubscribeEvent
    public void onServerStarting(ServerStartingEvent event) {
        services.setServer(event.getServer());

        try {
            services.getTaskScheduler().setMainThreadExecutor(event.getServer()::execute);
        } catch (Throwable ignored) {
        }

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
        try {
            if (services != null && services.getPlatformAdapter() instanceof eu.avalanche7.paradigm.platform.PlatformAdapterImpl pai) {
                pai.setCommandDispatcher(event.getDispatcher());
            }
        } catch (Throwable ignored) {
        }

        modules.forEach(module -> {
            if (module.isEnabled(services)) {
                Object registryAccess;
                try {
                    registryAccess = event.getBuildContext();
                } catch (Throwable t) {
                    registryAccess = null;
                }
                module.registerCommands(event.getDispatcher(), registryAccess, services);
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
        CooldownConfigHandler.saveCooldowns();
    }
}
