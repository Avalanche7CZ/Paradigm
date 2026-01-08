package eu.avalanche7.paradigm.modules.chat;

import com.mojang.brigadier.CommandDispatcher;
import eu.avalanche7.paradigm.core.ParadigmModule;
import eu.avalanche7.paradigm.core.Services;
import eu.avalanche7.paradigm.platform.Interfaces.IComponent;
import eu.avalanche7.paradigm.platform.Interfaces.IPlatformAdapter;
import eu.avalanche7.paradigm.platform.Interfaces.IPlayer;
import net.fabricmc.fabric.api.networking.v1.ServerPlayConnectionEvents;
import net.minecraft.command.CommandRegistryAccess;
import net.minecraft.server.command.ServerCommandSource;
import net.minecraft.server.network.ServerPlayerEntity;

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
        return services.getMainConfig().motdEnable.get();
    }

    @Override
    public void onLoad(Object event, Services services, Object modEventBus) {
        this.services = services;
        this.platform = services.getPlatformAdapter();
        services.getDebugLogger().debugLog(NAME + " module loaded.");
    }

    @Override
    public void onServerStarting(Object event, Services services) {}

    @Override
    public void onEnable(Services services) {}

    @Override
    public void onDisable(Services services) {}

    @Override
    public void onServerStopping(Object event, Services services) {}

    @Override
    public void registerCommands(CommandDispatcher<ServerCommandSource> dispatcher, CommandRegistryAccess registryAccess, Services services) {}

    @Override
    public void registerEventListeners(Object eventBus, Services services) {
        ServerPlayConnectionEvents.JOIN.register((handler, sender, server) -> {
            onPlayerJoin(handler.player);
        });
    }

    public void onPlayerJoin(ServerPlayerEntity mcPlayer) {
        if (this.services == null || !isEnabled(this.services)) {
            return;
        }
        IPlayer player = platform.wrapPlayer(mcPlayer);
        IComponent motdMessage = createMOTDMessage(player);
        platform.sendSystemMessage(mcPlayer, motdMessage.getOriginalText());
        this.services.getDebugLogger().debugLog("Sent MOTD to " + mcPlayer.getName().getString());
    }

    private IComponent createMOTDMessage(IPlayer player) {
        List<String> lines = services.getMotdConfig().motdLines;
        if (lines == null || lines.isEmpty()) {
            return platform.createComponentFromLiteral("");
        }

        IComponent motdMessage = platform.createComponentFromLiteral("");
        for (int i = 0; i < lines.size(); i++) {
            String line = lines.get(i);
            IComponent lineComponent = services.getMessageParser().parseMessage(line, player);
            motdMessage.append(lineComponent);
            if (i < lines.size() - 1) {
                motdMessage.append(platform.createComponentFromLiteral("\n"));
            }
        }

        return motdMessage;
    }
}

