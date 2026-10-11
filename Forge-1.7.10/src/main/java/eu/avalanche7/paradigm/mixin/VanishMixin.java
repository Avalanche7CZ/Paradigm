package eu.avalanche7.paradigm.mixin;
import net.minecraft.command.ICommandSender;
import net.minecraft.command.server.CommandListPlayers;
import net.minecraft.entity.player.EntityPlayerMP;
import net.minecraft.server.MinecraftServer;
import net.minecraft.util.ChatComponentText;
import net.minecraft.util.ChatComponentTranslation;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import eu.avalanche7.paradigm.platform.visual.LegacyVisualController;
public final class VanishMixin {
 private VanishMixin() {}
 @Mixin(CommandListPlayers.class)
 public static abstract class OnlineList {
  @Inject(method = "processCommand", at = @At("HEAD"), cancellable = true)
  private void paradigm$visible(ICommandSender sender, String[] args, CallbackInfo callback) {
   var visuals = LegacyVisualController.current();
   if (visuals == null || !(sender instanceof EntityPlayerMP viewer)) return;
   var server = MinecraftServer.getServer();
   java.util.List<EntityPlayerMP> players = ((java.util.List<EntityPlayerMP>) server.getConfigurationManager().playerEntityList).stream().filter(target -> visuals.vanish().visible(target, viewer)).toList();
   boolean uuids = args.length > 0 && "uuids".equalsIgnoreCase(args[0]);
   sender.addChatMessage(new ChatComponentTranslation("commands.players.list", players.size(), server.getMaxPlayers()));
   sender.addChatMessage(new ChatComponentText(players.stream().map(player -> player.getCommandSenderName() + (uuids ? " (" + player.getUniqueID() + ")" : "")).collect(java.util.stream.Collectors.joining(", "))));
   callback.cancel();
  }
 }
}
