package eu.avalanche7.paradigm.modules.chat;

import eu.avalanche7.paradigm.core.ParadigmModule;
import eu.avalanche7.paradigm.core.Services;
import eu.avalanche7.paradigm.platform.Interfaces.IComponent;
import eu.avalanche7.paradigm.platform.Interfaces.IPlatformAdapter;
import eu.avalanche7.paradigm.platform.Interfaces.IPlayer;
import eu.avalanche7.paradigm.platform.MinecraftPlayer;
import net.minecraft.server.level.ServerPlayer;
import net.neoforged.neoforge.event.entity.player.PlayerEvent;
import net.neoforged.neoforge.event.server.ServerStartingEvent;
import net.neoforged.neoforge.event.server.ServerStoppingEvent;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.event.lifecycle.FMLCommonSetupEvent;
import com.mojang.brigadier.CommandDispatcher;
import net.minecraft.commands.CommandSourceStack;

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
    public void onLoad(FMLCommonSetupEvent event, Services services, IEventBus modEventBus) {
        this.services = services;
        this.platform = services.getPlatformAdapter();
        services.getDebugLogger().debugLog(NAME + " module loaded.");
    }

    @Override
    public void onServerStarting(ServerStartingEvent event, Services services) {}

    @Override
    public void onEnable(Services services) {}

    @Override
    public void onDisable(Services services) {}

    @Override
    public void onServerStopping(ServerStoppingEvent event, Services services) {}

    @Override
    public void registerCommands(CommandDispatcher<?> dispatcher, Services services) {}

    @Override
    public void registerEventListeners(IEventBus forgeEventBus, Services services) {
        forgeEventBus.register(this);
    }

    @SubscribeEvent
    public void onPlayerJoin(PlayerEvent.PlayerLoggedInEvent event) {
        if (this.services == null || !isEnabled(this.services) || !(event.getEntity() instanceof ServerPlayer)) {
            return;
        }
        ServerPlayer mcPlayer = (ServerPlayer) event.getEntity();
        IPlayer player = new MinecraftPlayer(mcPlayer);
        IComponent motdMessage = createMOTDMessage(player);
        platform.sendSystemMessage(player, motdMessage);
        this.services.getDebugLogger().debugLog("Sent MOTD to " + platform.getPlayerName(player));
    }

    private IComponent createMOTDMessage(IPlayer player) {
        List<String> lines = services.getMotdConfig().motdLines;
        if (lines == null || lines.isEmpty()) {
            return platform.createLiteralComponent("");
        }

        IComponent motdMessage = platform.createLiteralComponent("");
        for (int i = 0; i < lines.size(); i++) {
            String line = lines.get(i);
            IComponent lineComponent = services.getMessageParser().parseMessage(line, player);
            motdMessage.append(lineComponent);
            if (i < lines.size() - 1) {
                motdMessage.append(platform.createLiteralComponent("\n"));
            }
        }

        return motdMessage;
    }
}