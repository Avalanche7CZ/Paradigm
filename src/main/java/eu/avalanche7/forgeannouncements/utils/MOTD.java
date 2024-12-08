package eu.avalanche7.forgeannouncements.utils;

import eu.avalanche7.forgeannouncements.configs.MainConfigHandler;
import eu.avalanche7.forgeannouncements.configs.MOTDConfigHandler;
import net.minecraft.Util;
import net.minecraft.network.chat.*;
import net.minecraft.server.level.ServerPlayer;
import net.minecraftforge.event.entity.player.PlayerEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;

import java.util.List;

@Mod.EventBusSubscriber(modid = "forgeannouncements")
public class MOTD {

    @SubscribeEvent
    public static void onPlayerJoin(PlayerEvent.PlayerLoggedInEvent event) {
        if (!MainConfigHandler.CONFIG.motdEnable.get()) {
            DebugLogger.debugLog("MOTD feature is disabled.");
            return;
        }

        ServerPlayer player = (ServerPlayer) event.getEntity();
        Component motdMessage = createMOTDMessage(player);
        player.sendMessage(motdMessage, Util.NIL_UUID);
    }

    private static Component createMOTDMessage(ServerPlayer player) {
        List<String> lines = MOTDConfigHandler.getConfig().motdLines;
        MutableComponent motdMessage = new TextComponent("");

        for (String line : lines) {
            motdMessage.append(MessageParser.parseMessage(line, player)).append("\n");
        }

        return motdMessage;
    }
}