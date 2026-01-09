package eu.avalanche7.paradigm.modules.chat;

import eu.avalanche7.paradigm.core.ParadigmModule;
import eu.avalanche7.paradigm.core.Services;
import eu.avalanche7.paradigm.platform.Interfaces.IComponent;
import eu.avalanche7.paradigm.platform.Interfaces.IEventSystem;
import eu.avalanche7.paradigm.platform.Interfaces.IPlatformAdapter;
import eu.avalanche7.paradigm.platform.Interfaces.IPlayer;

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
    public void registerCommands(Object dispatcher, Object registryAccess, Services services) {}

    @Override
    public void registerEventListeners(Object eventBus, Services services) {
        IEventSystem events = services.getPlatformAdapter().getEventSystem();
        events.onPlayerJoin(evt -> onPlayerJoin(evt.getPlayer()));
    }

    public void onPlayerJoin(IPlayer player) {
        if (this.services == null || !isEnabled(this.services)) {
            return;
        }
        if (player == null) return;

        IComponent motdMessage = createMOTDMessage(player);
        platform.sendSystemMessage(player, motdMessage);
        this.services.getDebugLogger().debugLog("Sent MOTD to " + player.getName());
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
