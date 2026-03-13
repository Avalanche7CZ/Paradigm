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
import net.minecraftforge.event.RegisterCommandsEvent;
import net.minecraftforge.event.server.ServerStartingEvent;
import net.minecraftforge.event.server.ServerStoppingEvent;
import net.minecraftforge.eventbus.api.bus.EventBus;
import net.minecraftforge.fml.ModList;
import net.minecraftforge.fml.common.Mod;
import net.minecraftforge.fml.event.lifecycle.FMLCommonSetupEvent;
import net.minecraftforge.fml.javafmlmod.FMLJavaModLoadingContext;
import net.minecraftforge.fml.loading.FMLEnvironment;
import org.slf4j.Logger;

import java.util.ArrayList;
import java.util.List;
import java.util.Random;

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
        this(resolveLoadingContext());
    }

    private static FMLJavaModLoadingContext resolveLoadingContext() {
        try {
            return (FMLJavaModLoadingContext) FMLJavaModLoadingContext.class.getMethod("get").invoke(null);
        } catch (ReflectiveOperationException e) {
            throw new IllegalStateException("Unable to resolve FMLJavaModLoadingContext", e);
        }
    }

    public Paradigm(FMLJavaModLoadingContext ctx) {
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

        var modBusGroup = ctx.getModBusGroup();
        modules.forEach(m -> m.onLoad(null, services, modBusGroup));
        modules.forEach(m -> m.registerEventListeners(null, services));

        // Forge 61.x: use direct bus addListener() instead of EventBusMigrationHelper
        EventBus.create(modBusGroup, FMLCommonSetupEvent.class).addListener(this::commonSetup);
        ServerStartingEvent.BUS.addListener(this::onServerStarting);
        RegisterCommandsEvent.BUS.addListener(this::onRegisterCommands);
        ServerStoppingEvent.BUS.addListener(this::onServerStopping);
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
            } catch (Throwable ignored) {
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

    public void onServerStarting(ServerStartingEvent event) {
        services.setServer(event.getServer());

        try {
            services.getTaskScheduler().setMainThreadExecutor(event.getServer()::execute);
        } catch (Throwable ignored) {
        }

        if (telemetryReporter == null) telemetryReporter = new TelemetryReporter(services);
        telemetryReporter.start();

        paradigm$applyServerListMotd(event.getServer());

        modules.forEach(module -> {
            if (module.isEnabled(services)) {
                module.onServerStarting(event, services);
            }
        });
    }

    public void onRegisterCommands(RegisterCommandsEvent event) {
        try {
            if (services != null && services.getPlatformAdapter() instanceof eu.avalanche7.paradigm.platform.PlatformAdapterImpl pai) {
                pai.setCommandDispatcher(event.getDispatcher());
            }
        } catch (Throwable ignored) {
        }

        modules.forEach(module -> {
            if (module.isEnabled(services)) {
                module.registerCommands(event.getDispatcher(), event.getBuildContext(), services);
            }
        });
    }

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

    private void paradigm$applyServerListMotd(net.minecraft.server.MinecraftServer server) {
        try {
            if (server == null || services == null) return;

            var cfg = services.getMotdConfig();
            if (cfg == null || !cfg.serverlistMotdEnabled.value || cfg.motds == null || cfg.motds.value == null || cfg.motds.value.isEmpty()) {
                return;
            }

            var selectedMotd = cfg.motds.value.get(new Random().nextInt(cfg.motds.value.size()));
            String line1 = selectedMotd.line1 != null ? selectedMotd.line1 : "";
            String line2 = selectedMotd.line2 != null ? selectedMotd.line2 : "";

            String legacyLine1 = paradigm$toLegacyStatusText(line1);
            String legacyLine2 = paradigm$toLegacyStatusText(line2);
            String motd = legacyLine1 + "\n" + legacyLine2;

            server.setMotd(motd);
            server.invalidateStatus();
        } catch (Throwable t) {
            LOGGER.warn("Failed to apply custom server-list MOTD", t);
        }
    }

    private String paradigm$toLegacyStatusText(String rawText) {
        try {
            var parsed = services.getMessageParser().parseMessage(rawText, null);
            if (parsed instanceof eu.avalanche7.paradigm.platform.MinecraftComponent mc) {
                return paradigm$componentToLegacyText(mc.getHandle());
            }
        } catch (Throwable ignored) {
        }
        return rawText != null ? rawText.replace('&', '§') : "";
    }

    private String paradigm$componentToLegacyText(net.minecraft.network.chat.Component component) {
        StringBuilder result = new StringBuilder();
        component.visit((style, text) -> {
            net.minecraft.network.chat.TextColor color = style.getColor();
            if (color != null) {
                int rgb = color.getValue();
                net.minecraft.ChatFormatting formatting = paradigm$getFormattingForColor(rgb);
                if (formatting != null) {
                    result.append('§').append(formatting.getChar());
                } else {
                    result.append('§').append(paradigm$getNearestFormattingCode(rgb));
                }
            }

            if (style.isBold()) result.append("§l");
            if (style.isItalic()) result.append("§o");
            if (style.isUnderlined()) result.append("§n");
            if (style.isStrikethrough()) result.append("§m");
            if (style.isObfuscated()) result.append("§k");

            result.append(text);
            return java.util.Optional.empty();
        }, net.minecraft.network.chat.Style.EMPTY);
        return result.toString();
    }

    private char paradigm$getNearestFormattingCode(int rgb) {
        int r = (rgb >> 16) & 0xFF;
        int g = (rgb >> 8) & 0xFF;
        int b = rgb & 0xFF;
        int brightness = (r + g + b) / 3;

        if (r > g && r > b) return 'c';
        if (g > r && g > b) return 'a';
        if (b > r && b > g) return 'b';
        if (brightness > 128) return 'f';
        return '7';
    }

    private net.minecraft.ChatFormatting paradigm$getFormattingForColor(int rgb) {
        for (net.minecraft.ChatFormatting formatting : net.minecraft.ChatFormatting.values()) {
            if (formatting.isColor() && formatting.getColor() != null && formatting.getColor() == rgb) {
                return formatting;
            }
        }
        return null;
    }
}
