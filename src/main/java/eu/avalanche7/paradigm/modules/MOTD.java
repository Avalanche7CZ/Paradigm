package eu.avalanche7.paradigm.modules;

import eu.avalanche7.paradigm.core.ParadigmModule;
import eu.avalanche7.paradigm.core.Services;
import eu.avalanche7.paradigm.platform.IPlatformAdapter;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.MutableComponent;
import net.minecraft.server.level.ServerPlayer;
import net.minecraftforge.event.entity.player.PlayerEvent;
import net.minecraftforge.event.server.ServerStartingEvent;
import net.minecraftforge.event.server.ServerStoppingEvent;
import net.minecraftforge.eventbus.api.IEventBus;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.event.lifecycle.FMLCommonSetupEvent;
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
    public void registerCommands(CommandDispatcher<CommandSourceStack> dispatcher, Services services) {}

    @Override
    public void registerEventListeners(IEventBus forgeEventBus, Services services) {
        forgeEventBus.register(this);
    }

    @SubscribeEvent
    public void onPlayerJoin(PlayerEvent.PlayerLoggedInEvent event) {
        if (this.services == null || !isEnabled(this.services) || !(event.getEntity() instanceof ServerPlayer)) {
            return;
        }
        ServerPlayer player = (ServerPlayer) event.getEntity();
        Component motdMessage = createMOTDMessage(player);
        platform.sendSystemMessage(player, motdMessage);
        this.services.getDebugLogger().debugLog("Sent MOTD to " + platform.getPlayerName(player));
    }

    private Component createMOTDMessage(ServerPlayer player) {
        List<String> lines = services.getMotdConfig().motdLines;
        if (lines == null || lines.isEmpty()) {
            return Component.empty();
        }
        MutableComponent motdMessage = Component.literal("");
        for (String line : lines) {
            motdMessage.append(services.getMessageParser().parseMessage(line, player)).append(Component.literal("\n"));
        }
        return motdMessage;
    }
}