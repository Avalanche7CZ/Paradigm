package eu.avalanche7.paradigm.mixin;

import net.minecraft.network.chat.Component;
import net.minecraft.server.players.PlayerList;
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

        if (paradigm$isVanillaJoinLeave(message)) {
            System.out.println("[Paradigm-Mixin] Suppressed vanilla: " + paradigm$safeToString(message));
            ci.cancel();
        }
    }

    @org.spongepowered.asm.mixin.Unique
    private boolean paradigm$isVanillaJoinLeave(Component message) {
        try {
            if (message.getContents() instanceof net.minecraft.network.chat.contents.TranslatableContents tc) {
                String key = tc.getKey();
                return "multiplayer.player.joined".equals(key) ||
                       "multiplayer.player.left".equals(key) ||
                       "multiplayer.player.joined.renamed".equals(key);
            }

            String text = message.getString();
            if (text != null) {
                String lower = text.toLowerCase();
                if ((lower.endsWith(" joined the game") || lower.endsWith(" left the game")) &&
                    !lower.contains("&") && !lower.contains("§")) {
                    return true;
                }
            }
        } catch (Throwable t) {
        }
        return false;
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
