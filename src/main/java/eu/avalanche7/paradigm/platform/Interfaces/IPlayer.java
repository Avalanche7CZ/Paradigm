package eu.avalanche7.paradigm.platform.Interfaces;

import net.minecraft.server.network.ServerPlayerEntity;
import java.util.UUID;

public interface IPlayer {
    String getName();
    UUID getUUID();
    ServerPlayerEntity getOriginalPlayer();
}
