package eu.avalanche7.paradigm.platform;

import java.util.List;
import net.minecraft.network.protocol.game.ClientboundPlayerInfoRemovePacket;
import net.minecraft.network.protocol.Packet;
import net.minecraft.network.protocol.game.ClientboundPlayerInfoUpdatePacket;
import net.minecraft.server.level.ServerPlayer;
import eu.avalanche7.paradigm.ParadigmAPI;
import eu.avalanche7.paradigm.platform.Interfaces.IVanishPlayerInfoPacket;

public final class VanishPackets {
    private VanishPackets() {}
    public static Packet<?> filter(Packet<?> packet, ServerPlayer viewer, java.util.Set<java.util.UUID> listed) {
        var services = ParadigmAPI.getServices();
        if (services == null) return packet;
        if (packet instanceof ClientboundPlayerInfoRemovePacket removal) {
            var wrapped = services.getPlatformAdapter().wrapPlayer(viewer);
            var entries = removal.profileIds().stream()
                    .filter(uuid -> listed.remove(uuid) || services.getVanishService().canSeeUuid(wrapped, uuid.toString())).toList();
            return entries.size() == removal.profileIds().size() ? packet : new ClientboundPlayerInfoRemovePacket(entries);
        }
        if (!(packet instanceof ClientboundPlayerInfoUpdatePacket info)) return packet;
        var wrapped = services.getPlatformAdapter().wrapPlayer(viewer);
        List<ClientboundPlayerInfoUpdatePacket.Entry> entries = info.entries().stream()
                .filter(entry -> services.getVanishService().canSeeUuid(wrapped, entry.profileId().toString())).toList();
        if (info.actions().contains(ClientboundPlayerInfoUpdatePacket.Action.ADD_PLAYER)) entries.forEach(entry -> listed.add(entry.profileId()));
        if (entries.size() == info.entries().size()) return packet;
        ClientboundPlayerInfoUpdatePacket filtered = new ClientboundPlayerInfoUpdatePacket(info.actions(), List.of());
        ((IVanishPlayerInfoPacket) filtered).paradigm$entries(entries);
        return filtered;
    }
}
