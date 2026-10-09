package eu.avalanche7.paradigm.platform.visual;

import com.gtnewhorizon.gtnhlib.GTNHLib;
import net.minecraft.entity.player.EntityPlayerMP;
import net.minecraft.util.EnumChatFormatting;
import net.minecraft.util.IChatComponent;

final class LegacyGTNHActionbar {
    private LegacyGTNHActionbar() {}
    static void send(EntityPlayerMP player, IChatComponent message, int ticks) {
        IChatComponent text = message.createCopy();
        if (text.getChatStyle().getColor() == null) text.getChatStyle().setColor(EnumChatFormatting.WHITE);
        GTNHLib.proxy.sendMessageAboveHotbar(player, text, ticks, true, true);
    }
}
