package eu.avalanche7.paradigm.modules.holograms;

import eu.avalanche7.paradigm.core.Services;
import eu.avalanche7.paradigm.platform.Interfaces.IComponent;
import eu.avalanche7.paradigm.platform.Interfaces.IHologramPlatform;
import eu.avalanche7.paradigm.platform.Interfaces.IPlayer;
import eu.avalanche7.paradigm.storage.identity.ServerIdentity;

import java.util.Map;
import java.util.List;
import java.util.concurrent.ConcurrentHashMap;

public final class HologramRenderer {
    private final Services services;
    private final IHologramPlatform platform;
    private final Map<String, IComponent> staticTemplates = new ConcurrentHashMap<>();

    public HologramRenderer(Services services, IHologramPlatform platform) {
        this.services = services;
        this.platform = platform;
    }

    public String upsert(String id, HologramDefinition definition, HologramLine line, String runtimeId, IPlayer viewer) {
        double y = definition.y - (line.index() * definition.lineSpacing);
        IHologramPlatform.Location location = new IHologramPlatform.Location(
                definition.dimension, definition.x, y, definition.z);
        if (!platform.isChunkLoaded(location)) return null;
        String key = HologramService.ownershipKey(id, definition, line.index());
        IComponent text = line.dynamic() || viewer != null
                ? parse(line.template(), definition.dimension, viewer)
                : staticTemplates.computeIfAbsent(key + "\n" + line.template(), ignored -> parse(line.template(), definition.dimension, null));
        return platform.upsertLine(new IHologramPlatform.LineRequest(key, location, definition.viewDistance, text.copy(), definition.display.copy()), runtimeId);
    }

    public String upsertInteraction(String id, HologramDefinition definition, String runtimeId) {
        if (!definition.interaction.enabled) return null;
        IHologramPlatform.Location location = new IHologramPlatform.Location(definition.dimension, definition.x, definition.y, definition.z);
        if (!platform.isChunkLoaded(location)) return null;
        return platform.upsertInteraction(new IHologramPlatform.InteractionRequest(
                HologramService.interactionOwnershipKey(id, definition), location,
                definition.interaction.width, definition.interaction.height), runtimeId);
    }

    public IHologramPlatform.LineRequest viewerRequest(String ownershipKey, HologramDefinition definition, HologramLine line, IPlayer viewer) {
        double y = definition.y - (line.index() * definition.lineSpacing);
        IHologramPlatform.Location location = new IHologramPlatform.Location(definition.dimension, definition.x, y, definition.z);
        return new IHologramPlatform.LineRequest(ownershipKey, location, definition.viewDistance,
                parse(line.template(), definition.dimension, viewer), definition.display.copy());
    }

    public void remove(String runtimeId) {
        if (runtimeId != null) platform.removeLine(runtimeId);
    }

    public boolean loaded(String runtimeId) {
        return runtimeId != null && platform.isEntityLoaded(runtimeId);
    }

    public void clearTemplateCache() {
        staticTemplates.clear();
    }

    public static List<String> globalPlaceholderTokens() {
        return List.of("{online_players}", "{max_players}", "{server_name}", "{server_id}", "{network_id}", "{world}");
    }

    private IComponent parse(String template, String dimension, IPlayer viewer) {
        String expanded = globalPlaceholders(template, dimension);
        return services.getMessageParser().parseMessage(expanded, viewer);
    }

    private String globalPlaceholders(String input, String dimension) {
        String value = input != null ? input : "";
        int online = services.getPlatformAdapter().getOnlinePlayers().size();
        int max = Math.max(online, services.getPlatformAdapter().getMaxPlayers());
        ServerIdentity identity = services.getStorageService() != null && services.getStorageService().context() != null
                ? services.getStorageService().context().serverIdentity() : null;
        value = value.replace("{online_players}", Integer.toString(online));
        value = value.replace("{max_players}", Integer.toString(max));
        value = value.replace("{server_name}", identity != null ? safe(identity.serverName()) : "Paradigm Server");
        value = value.replace("{server_id}", identity != null ? safe(identity.serverId()) : "default");
        value = value.replace("{network_id}", identity != null ? safe(identity.networkId()) : "default");
        value = value.replace("{world}", safe(dimension));
        return value;
    }

    private static String safe(String value) {
        return value != null ? value : "";
    }
}
