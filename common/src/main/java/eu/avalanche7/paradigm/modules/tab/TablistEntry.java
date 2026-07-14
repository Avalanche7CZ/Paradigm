package eu.avalanche7.paradigm.modules.tab;

import eu.avalanche7.paradigm.platform.Interfaces.IPlayer;

public record TablistEntry(IPlayer player, String uuid, String playerName, String worldId,
                           int ping, TablistMetadata metadata) {
}
