package eu.avalanche7.paradigm.modules.tab;

import eu.avalanche7.paradigm.storage.identity.ServerIdentity;

public final class TablistPlaceholderResolver {
    private TablistPlaceholderResolver() {
    }

    public static String expand(String input, TablistEntry entry, int onlineCount, int maxPlayers,
                                ServerIdentity identity, boolean includePing) {
        String value = input != null ? input : "";
        value = value.replace("{online_players}", Integer.toString(onlineCount));
        value = value.replace("{max_players}", Integer.toString(Math.max(maxPlayers, onlineCount)));
        value = value.replace("{server_name}", identity != null ? safe(identity.serverName()) : "Paradigm Server");
        value = value.replace("{server_id}", identity != null ? safe(identity.serverId()) : "default");
        value = value.replace("{network_id}", identity != null ? safe(identity.networkId()) : "default");
        value = value.replace("{world}", safe(entry.worldId()));
        value = value.replace("{ping}", includePing ? Integer.toString(entry.ping()) : "");
        return value;
    }

    private static String safe(String value) {
        return value != null ? value : "";
    }
}
