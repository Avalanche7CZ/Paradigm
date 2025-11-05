/*
 * NOTE FOR PORTING (1.19.2 → 1.18.2):
 * - TranslatableComponent is in net.minecraft.network.chat, not .contents.
 * - Use instanceof TranslatableComponent for vanilla join/leave detection.
 * - Do NOT use net.minecraft.network.chat.contents.TranslatableContents (does not exist in 1.18.2).
 */

package eu.avalanche7.paradigm.mixin;

import eu.avalanche7.paradigm.Paradigm;
import eu.avalanche7.paradigm.configs.ChatConfigHandler;
import eu.avalanche7.paradigm.core.Services;
import net.minecraft.network.chat.Component;
import net.minecraft.server.players.PlayerList;
import net.minecraft.network.chat.TranslatableComponent;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(PlayerList.class)
public abstract class PlayerListMixin {

    static {
        System.out.println("[Paradigm-Mixin] PlayerListMixin loaded!");
    }

    @Inject(method = "*(Lnet/minecraft/network/chat/Component;Z)V", at = @At("HEAD"), cancellable = true)
    private void paradigm$filterJoinLeaveMessages(Component message, boolean overlay, CallbackInfo ci) {
        if (message == null) return;

        int type = paradigm$getVanillaJoinLeaveType(message);
        if (type == 0) return;

        Services services = Paradigm.getServices();
        if (services == null) return;
        ChatConfigHandler.Config cfg = services.getChatConfig();
        boolean joinLeaveEnabled = cfg != null && Boolean.TRUE.equals(cfg.enableJoinLeaveMessages.get());

        if (joinLeaveEnabled) {
            System.out.println("[Paradigm-Mixin] Suppressed vanilla: " + paradigm$safeToString(message));
            ci.cancel();
        }
    }

    @org.spongepowered.asm.mixin.Unique
    private int paradigm$getVanillaJoinLeaveType(Component message) {
        try {
            if (message instanceof TranslatableComponent tc) {
                String key = tc.getKey();
                if ("multiplayer.player.joined".equals(key) || "multiplayer.player.joined.renamed".equals(key)) return 1;
                if ("multiplayer.player.left".equals(key)) return 2;
            }
            String text = message.getString();
            if (text != null) {
                String lower = text.toLowerCase();
                if (!lower.contains("&") && !lower.contains("§")) {
                    if (lower.endsWith(" joined the game")) return 1;
                    if (lower.endsWith(" left the game")) return 2;
                }
            }
        } catch (Throwable ignored) {}
        return 0;
    }

    @org.spongepowered.asm.mixin.Unique
    private String paradigm$safeToString(Component c) {
        try {
            String text = c == null ? "null" : c.getString();
            return text.length() > 50 ? text.substring(0, 50) + "..." : text;
        } catch (Throwable t) {
            return "<component>";
        }
    }
}