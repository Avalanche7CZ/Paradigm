package eu.avalanche7.paradigm;

import com.mojang.brigadier.CommandDispatcher;
import eu.avalanche7.paradigm.core.CommonRuntime;
import eu.avalanche7.paradigm.core.ParadigmModule;
import eu.avalanche7.paradigm.core.Services;
import eu.avalanche7.paradigm.platform.PlatformAdapterImpl;
import eu.avalanche7.paradigm.utils.TelemetryReporter;
import net.fabricmc.api.DedicatedServerModInitializer;
import net.fabricmc.fabric.api.command.v2.CommandRegistrationCallback;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerLifecycleEvents;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.command.ServerCommandSource;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.List;

public class Paradigm implements DedicatedServerModInitializer, ParadigmAPI.ParadigmAccessor {

    public static final String MOD_ID = "paradigm";
    private static final Logger LOGGER = LoggerFactory.getLogger(MOD_ID);

    private final List<ParadigmModule> modules = new ArrayList<>();
    private Services services;
    private static String modVersion = "unknown";

    private PlatformAdapterImpl platformAdapterInstance;
    private TelemetryReporter telemetryReporter;

    @Override
    public void onInitializeServer() {
        LOGGER.info("Initializing Paradigm Mod for Fabric 1.21.8...");

        var platformConfig = new eu.avalanche7.paradigm.platform.FabricConfig();

        var placeholders = new eu.avalanche7.paradigm.utils.Placeholders();
        var debugLogger = new eu.avalanche7.paradigm.utils.DebugLogger(null);
        var taskScheduler = new eu.avalanche7.paradigm.utils.TaskScheduler(debugLogger);
        this.platformAdapterInstance = new PlatformAdapterImpl(null, placeholders, taskScheduler, debugLogger);

        CommonRuntime.Runtime runtime = CommonRuntime.bootstrap(LOGGER, platformConfig, this.platformAdapterInstance);
        this.services = runtime.services();

        try {
            this.platformAdapterInstance.setPermissionsHandler(runtime.permissionsHandler());
        } catch (Throwable ignored) {
        }
        try {
            this.platformAdapterInstance.provideMessageParser(services.getMessageParser());
        } catch (Throwable ignored) {
        }

        this.modules.clear();
        this.modules.addAll(runtime.modules());

        FabricLoader.getInstance().getModContainer(MOD_ID).ifPresent(modContainer -> modVersion = modContainer.getMetadata().getVersion().getFriendlyString());
        CommonRuntime.attachToApi(runtime, modVersion);

        modules.forEach(module -> module.registerEventListeners(null, services));
        modules.forEach(module -> module.onLoad(null, services, null));

        registerFabricEvents();

        FabricLoader.getInstance().getModContainer(MOD_ID).ifPresent(modContainer -> {
            String displayName = modContainer.getMetadata().getName();
            LOGGER.info("Paradigm Fabric mod (1.21.8) has been set up.");
            LOGGER.info("==================================================");
            LOGGER.info("  ____                     _ _");
            LOGGER.info(" |  _ \\ __ _ _ __ __ _  __| (_) __ _ _ __ ___");
            LOGGER.info(" | |_) / _` | '__/ _` |/ _` | |/ _` | '_ ` _ \\");
            LOGGER.info(" |  __/ (_| | | | (_| | (_| | | (_| | | | | | |");
            LOGGER.info(" |_|   \\__,_|_|  \\__,_|\\__,_|_|\\__, |_| |_| |_|");
            LOGGER.info("                                |___/");
            LOGGER.info("");
            LOGGER.info("{} - Version {}", displayName, getModVersion());
            LOGGER.info("Author: Avalanche7CZ");
            LOGGER.info("Discord: https://discord.com/invite/qZDcQdEFqQ");
            LOGGER.info("==================================================");

            eu.avalanche7.paradigm.utils.UpdateChecker.checkForUpdates(
                    new eu.avalanche7.paradigm.utils.UpdateChecker.UpdateConfig(
                            "s4i32SJd",
                            "paradigm",
                            "https://raw.githubusercontent.com/Avalanche7CZ/Paradigm/Fabric/1.21.8/version.txt?v=1"
                    ),
                    getModVersion(),
                    services != null && services.getPlatformAdapter() != null ? services.getPlatformAdapter().getMinecraftVersion() : null,
                    "fabric",
                    LOGGER
            );
        });
    }

    private void registerFabricEvents() {
        ServerLifecycleEvents.SERVER_STARTING.register(this::onServerStarting);
        ServerLifecycleEvents.SERVER_STOPPING.register(this::onServerStopping);
        CommandRegistrationCallback.EVENT.register(this::onRegisterCommands);
    }

    private void onServerStarting(MinecraftServer server) {
        services.setServer(server);

        try {
            services.getTaskScheduler().setMainThreadExecutor(server::execute);
        } catch (Throwable ignored) {
        }

        if (telemetryReporter == null) {
            telemetryReporter = new TelemetryReporter(services);
        }
        telemetryReporter.start();

        try {
            services.getPermissionsHandler().registerLuckPermsPermissions();
        } catch (Throwable ignored) {
        }

        modules.forEach(module -> {
            if (module.isEnabled(services)) {
                module.onEnable(services);
                module.onServerStarting(null, services);
            }
        });
    }

    private void onRegisterCommands(CommandDispatcher<ServerCommandSource> dispatcher, net.minecraft.command.CommandRegistryAccess registryAccess, net.minecraft.server.command.CommandManager.RegistrationEnvironment environment) {
        try {
            this.platformAdapterInstance.setCommandDispatcher(dispatcher);
        } catch (Throwable ignored) {
        }

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

        if (telemetryReporter != null) {
            telemetryReporter.stop();
        }

        try {
            services.getTaskScheduler().onServerStopping();
        } catch (Throwable ignored) {
        }
    }

    @Override
    public List<ParadigmModule> getModules() {
        return modules;
    }

    @Override
    public Services getServices() {
        return services;
    }

    @Override
    public String getModVersion() {
        return modVersion;
    }
}
