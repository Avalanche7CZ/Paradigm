package eu.avalanche7.paradigm.modules.chat;

import com.mojang.brigadier.CommandDispatcher;
import eu.avalanche7.paradigm.core.ParadigmModule;
import eu.avalanche7.paradigm.core.Services;
import eu.avalanche7.paradigm.platform.IPlatformAdapter;
import net.fabricmc.fabric.api.networking.v1.ServerPlayConnectionEvents;
import net.minecraft.command.CommandRegistryAccess;
import net.minecraft.server.command.ServerCommandSource;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.text.MutableText;
import net.minecraft.text.Text;

import java.util.List;

public class MOTD implements ParadigmModule {

    private static final String NAME = "MOTD";
    private Services services;
    private IPlatformAdapter platform;

    @Override
    public String getName() {
        return NAME;
    }

    @Override
    public boolean isEnabled(Services services) {
        return services.getMainConfig().motdEnable.value;
    }

    @Override
    public void onLoad(Object event, Services services, Object modEventBus) {
        this.services = services;
        this.platform = services.getPlatformAdapter();
        if (services != null && services.getDebugLogger() != null) {
            services.getDebugLogger().debugLog(NAME + " module loaded.");
        }
    }

    @Override
    public void onServerStarting(Object event, Services services) {
        if (services != null && services.getDebugLogger() != null) {
            services.getDebugLogger().debugLog(NAME + " module: Server starting.");
        }
    }

    @Override
    public void onEnable(Services services) {
        if (services != null && services.getDebugLogger() != null) {
            services.getDebugLogger().debugLog(NAME + " module enabled.");
        }
    }

    @Override
    public void onDisable(Services services) {
        if (services != null && services.getDebugLogger() != null) {
            services.getDebugLogger().debugLog(NAME + " module disabled.");
        }
    }

    @Override
    public void onServerStopping(Object event, Services services) {
        if (services != null && services.getDebugLogger() != null) {
            services.getDebugLogger().debugLog(NAME + " module: Server stopping.");
        }
    }

    @Override
    public void registerCommands(CommandDispatcher<ServerCommandSource> dispatcher, CommandRegistryAccess registryAccess, Services services) {
    }

    @Override
    public void registerEventListeners(Object eventBus, Services services) {
        ServerPlayConnectionEvents.JOIN.register((handler, sender, server) -> {
            onPlayerJoin(handler.player, services);
        });
    }

    public void onPlayerJoin(ServerPlayerEntity player, Services services) {
        if (this.services == null || !isEnabled(this.services) || this.services.getMotdConfig() == null) {
            if (this.services != null && this.services.getDebugLogger() != null) {
                this.services.getDebugLogger().debugLog("MOTDModule: Services, MOTD module not enabled, or MOTDConfig is null. Skipping MOTD for " + player.getName().getString());
            }
            return;
        }
        Text motdMessage = createMOTDMessage(player, this.services);
        platform.sendSystemMessage(player, motdMessage);
        this.services.getDebugLogger().debugLog("Sent MOTD to " + player.getName().getString());
    }

    private Text createMOTDMessage(ServerPlayerEntity player, Services services) {
        if (services == null || services.getMotdConfig() == null) {
            if(services != null && services.getDebugLogger() != null) {
                services.getDebugLogger().debugLog("MOTDModule: Services or MOTDConfig is null in createMOTDMessage.");
            }
            return Text.empty();
        }
        List<String> lines = services.getMotdConfig().motdLines;
        if (lines == null || lines.isEmpty()) {
            return Text.empty();
        }
        MutableText motdMessage = platform.createLiteralComponent("");
        for (int i = 0; i < lines.size(); i++) {
            String line = lines.get(i);
            if (services.getMessageParser() != null) {
                motdMessage.append(services.getMessageParser().parseMessage(line, player));
            } else {
                if(services.getDebugLogger() != null) {
                    services.getDebugLogger().debugLog("MOTDModule: MessageParser is null in createMOTDMessage loop.");
                }
                motdMessage.append(platform.createLiteralComponent(line));
            }
            if (i < lines.size() - 1) {
                motdMessage.append(platform.createLiteralComponent("\n"));
            }
        }
        return motdMessage;
    }
}
