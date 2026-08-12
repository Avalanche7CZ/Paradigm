package eu.avalanche7.paradigm.modules.commands.shared;

import eu.avalanche7.paradigm.core.PlayerMessenger;
import eu.avalanche7.paradigm.core.Services;
import eu.avalanche7.paradigm.platform.Interfaces.ICommandSource;
import eu.avalanche7.paradigm.platform.Interfaces.IPlayer;

public final class CommandMessages {
    private CommandMessages() {
    }

    public static void send(Services services, IPlayer player, String header, String key, String fallback, String... placeholders) {
        if (services == null || player == null) {
            return;
        }
        services.getPlayerMessenger().sendDecorated(player, header, key, fallback, placeholders);
    }

    public static void source(Services services, ICommandSource source, String header, String key, String fallback, String... placeholders) {
        if (services == null || source == null) {
            return;
        }
        IPlayer player = source.getPlayer();
        PlayerMessenger messenger = services.getPlayerMessenger();
        if (player != null) {
            messenger.sendDecorated(player, header, key, fallback, placeholders);
            return;
        }
        messenger.logToConsole(header, fallback, placeholders);
    }
}
