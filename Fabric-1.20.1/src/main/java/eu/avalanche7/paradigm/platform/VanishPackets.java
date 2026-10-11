package eu.avalanche7.paradigm.platform;

import java.util.List;
import net.minecraft.network.packet.s2c.play.PlayerRemoveS2CPacket;
import net.minecraft.network.packet.Packet;
import net.minecraft.network.packet.s2c.play.PlayerListS2CPacket;
import net.minecraft.server.network.ServerPlayerEntity;
import eu.avalanche7.paradigm.ParadigmAPI;
import eu.avalanche7.paradigm.platform.Interfaces.IVanishPlayerInfoPacket;

public final class VanishPackets {
    private VanishPackets() {}
    public static Packet<?> filter(Packet<?> packet, ServerPlayerEntity viewer, java.util.Set<java.util.UUID> listed) {
        var services = ParadigmAPI.getServices();
        if (services == null) return packet;
        if (packet instanceof PlayerRemoveS2CPacket removal) {
            var wrapped = services.getPlatformAdapter().wrapPlayer(viewer);
            var entries = removal.profileIds().stream()
                    .filter(uuid -> listed.remove(uuid) || services.getVanishService().canSeeUuid(wrapped, uuid.toString())).toList();
            return entries.size() == removal.profileIds().size() ? packet : new PlayerRemoveS2CPacket(entries);
        }
        if (!(packet instanceof PlayerListS2CPacket info)) return packet;
        var wrapped = services.getPlatformAdapter().wrapPlayer(viewer);
        List<PlayerListS2CPacket.Entry> entries = info.getEntries().stream()
                .filter(entry -> services.getVanishService().canSeeUuid(wrapped, entry.profileId().toString())).toList();
        if (info.getActions().contains(PlayerListS2CPacket.Action.ADD_PLAYER)) entries.forEach(entry -> listed.add(entry.profileId()));
        if (entries.size() == info.getEntries().size()) return packet;
        PlayerListS2CPacket filtered = new PlayerListS2CPacket(info.getActions(), List.of());
        ((IVanishPlayerInfoPacket) filtered).paradigm$entries(entries);
        return filtered;
    }
}
