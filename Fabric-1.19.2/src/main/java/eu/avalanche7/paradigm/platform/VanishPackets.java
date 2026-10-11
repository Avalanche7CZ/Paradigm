package eu.avalanche7.paradigm.platform;

import java.util.List;
import net.minecraft.network.Packet;
import net.minecraft.network.packet.s2c.play.PlayerListS2CPacket;
import net.minecraft.server.network.ServerPlayerEntity;
import eu.avalanche7.paradigm.ParadigmAPI;
import eu.avalanche7.paradigm.platform.Interfaces.IVanishPlayerInfoPacket;

public final class VanishPackets {
    private VanishPackets() {}
    public static Packet<?> filter(Packet<?> packet, ServerPlayerEntity viewer, java.util.Set<java.util.UUID> listed) {
        var services = ParadigmAPI.getServices();
        if (services == null || !(packet instanceof PlayerListS2CPacket info)) return packet;
        var wrapped = services.getPlatformAdapter().wrapPlayer(viewer);
        List<PlayerListS2CPacket.Entry> entries = info.getEntries().stream()
                .filter(entry -> (info.getAction() == PlayerListS2CPacket.Action.REMOVE_PLAYER && listed.remove(entry.getProfile().getId()))
                        || services.getVanishService().canSeeUuid(wrapped, entry.getProfile().getId().toString())).toList();
        if (info.getAction() == PlayerListS2CPacket.Action.ADD_PLAYER) entries.forEach(entry -> listed.add(entry.getProfile().getId()));
        if (entries.size() == info.getEntries().size()) return packet;
        PlayerListS2CPacket filtered = new PlayerListS2CPacket(info.getAction(), List.of());
        ((IVanishPlayerInfoPacket) filtered).paradigm$entries(entries);
        return filtered;
    }
}
