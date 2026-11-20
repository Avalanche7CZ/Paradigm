package eu.avalanche7.paradigm.mixin;

import cpw.mods.fml.common.FMLCommonHandler;
import net.minecraft.entity.player.EntityPlayer;
import net.minecraft.entity.player.EntityPlayerMP;
import net.minecraft.server.management.ServerConfigurationManager;
import net.minecraft.util.ChunkCoordinates;
import net.minecraft.world.World;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Coerce;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.Redirect;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

import eu.avalanche7.paradigm.platform.LegacyRespawnPlacement;

@Mixin(ServerConfigurationManager.class)
public abstract class LegacyRespawnMixin {
    private static final String FORGE = "respawnPlayer(Lnet/minecraft/entity/player/EntityPlayerMP;IZ)Lnet/minecraft/entity/player/EntityPlayerMP;";
    private static final String CRUCIBLE = "respawnPlayer(Lnet/minecraft/entity/player/EntityPlayerMP;IZLorg/bukkit/Location;Z)Lnet/minecraft/entity/player/EntityPlayerMP;";
    private static final String NOTIFY = "Lcpw/mods/fml/common/FMLCommonHandler;firePlayerRespawnEvent(Lnet/minecraft/entity/player/EntityPlayer;)V";

    @Inject(method = FORGE, at = @At("HEAD"), remap = false, require = 0)
    private void paradigm$beginForge(EntityPlayerMP player, int dimension, boolean endReturn, CallbackInfoReturnable<EntityPlayerMP> callback) {
        LegacyRespawnPlacement.begin(player, endReturn, false, true, false);
    }

    @Inject(method = CRUCIBLE, at = @At("HEAD"), remap = false, require = 0)
    private void paradigm$beginCrucible(EntityPlayerMP player, int dimension, boolean endReturn, @Coerce Object location,
            boolean death, CallbackInfoReturnable<EntityPlayerMP> callback) {
        LegacyRespawnPlacement.begin(player, endReturn, true, death, location != null);
    }

    @Redirect(method = FORGE, at = @At(value = "INVOKE", target =
            "Lnet/minecraft/entity/player/EntityPlayerMP;getBedLocation(I)Lnet/minecraft/util/ChunkCoordinates;"),
            remap = false, require = 0)
    private ChunkCoordinates paradigm$readBed(EntityPlayerMP player, int dimension) {
        ChunkCoordinates bed = player.getBedLocation(dimension);
        LegacyRespawnPlacement.bedRead(bed);
        return bed;
    }

    @Redirect(method = FORGE, at = @At(value = "INVOKE", target =
            "Lnet/minecraft/entity/player/EntityPlayer;verifyRespawnCoordinates(Lnet/minecraft/world/World;Lnet/minecraft/util/ChunkCoordinates;Z)Lnet/minecraft/util/ChunkCoordinates;"),
            remap = false, require = 0)
    private ChunkCoordinates paradigm$verifyDevelopmentBed(World world, ChunkCoordinates bed, boolean forced) {
        return paradigm$verifyBed(world, bed, forced);
    }

    @Redirect(method = FORGE, at = @At(value = "INVOKE", target =
            "Lnet/minecraft/entity/player/EntityPlayer;func_71056_a(Lnet/minecraft/world/World;Lnet/minecraft/util/ChunkCoordinates;Z)Lnet/minecraft/util/ChunkCoordinates;"),
            remap = false, require = 0)
    private ChunkCoordinates paradigm$verifyRuntimeBed(World world, ChunkCoordinates bed, boolean forced) {
        return paradigm$verifyBed(world, bed, forced);
    }

    private static ChunkCoordinates paradigm$verifyBed(World world, ChunkCoordinates bed, boolean forced) {
        ChunkCoordinates result = EntityPlayer.verifyRespawnCoordinates(world, bed, forced);
        LegacyRespawnPlacement.bedValidated(result);
        return result;
    }

    @Redirect(method = {FORGE, CRUCIBLE}, at = @At(value = "INVOKE", target = NOTIFY), remap = false, require = 0)
    private void paradigm$captureNativeDestination(FMLCommonHandler handler, EntityPlayer player) {
        if (player instanceof EntityPlayerMP replacement) {
            LegacyRespawnPlacement.beforeForgeNotification(replacement);
        }
        handler.firePlayerRespawnEvent(player);
    }

    @Inject(method = {FORGE, CRUCIBLE}, at = @At("RETURN"), remap = false, require = 0)
    private void paradigm$finish(CallbackInfoReturnable<EntityPlayerMP> callback) {
        LegacyRespawnPlacement.finish(callback.getReturnValue());
    }
}
