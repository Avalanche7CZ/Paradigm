package eu.avalanche7.paradigm.platform;

import java.util.List;
import net.minecraft.network.protocol.Packet;
import net.minecraft.network.protocol.game.ClientboundPlayerInfoPacket;
import net.minecraft.server.level.ServerPlayer;
import eu.avalanche7.paradigm.ParadigmAPI;
import eu.avalanche7.paradigm.platform.Interfaces.IVanishPlayerInfoPacket;

public final class VanishPackets {
    private VanishPackets() {}
    public static Packet<?> filter(Packet<?> packet, ServerPlayer viewer, java.util.Set<java.util.UUID> listed) {
        var services = ParadigmAPI.getServices();
        if (services == null || !(packet instanceof ClientboundPlayerInfoPacket info)) return packet;
        var wrapped = services.getPlatformAdapter().wrapPlayer(viewer);
        List<ClientboundPlayerInfoPacket.PlayerUpdate> entries = info.getEntries().stream()
                .filter(entry -> info.getAction() == ClientboundPlayerInfoPacket.Action.REMOVE_PLAYER ? listed.remove(entry.getProfile().getId()) : services.getVanishService().canSeeUuid(wrapped, entry.getProfile().getId().toString())).toList();
        if (info.getAction() == ClientboundPlayerInfoPacket.Action.ADD_PLAYER) entries.forEach(entry -> listed.add(entry.getProfile().getId()));
        if (entries.size() == info.getEntries().size()) return packet;
        ClientboundPlayerInfoPacket filtered = new ClientboundPlayerInfoPacket(info.getAction(), List.of());
        ((IVanishPlayerInfoPacket) filtered).paradigm$entries(entries);
        return filtered;
    }
}
