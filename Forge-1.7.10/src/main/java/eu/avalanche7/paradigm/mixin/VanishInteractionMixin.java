package eu.avalanche7.paradigm.mixin;

import static eu.avalanche7.paradigm.platform.VanishInteractions.hidden;
import static eu.avalanche7.paradigm.platform.VanishInteractions.nearestVisiblePlayer;
import static eu.avalanche7.paradigm.platform.VanishInteractions.notifyInventory;
import static eu.avalanche7.paradigm.platform.VanishInteractions.silentViewer;

import java.util.List;

import net.minecraft.block.Block;
import net.minecraft.block.BlockPressurePlateWeighted;
import net.minecraft.entity.Entity;
import net.minecraft.entity.EntityCreature;
import net.minecraft.entity.EntityLiving;
import net.minecraft.entity.EntityLivingBase;
import net.minecraft.entity.ai.EntityAITarget;
import net.minecraft.entity.ai.EntityAITempt;
import net.minecraft.entity.item.EntityItem;
import net.minecraft.entity.item.EntityMinecart;
import net.minecraft.entity.item.EntityXPOrb;
import net.minecraft.entity.player.EntityPlayer;
import net.minecraft.entity.player.EntityPlayerMP;
import net.minecraft.entity.player.InventoryPlayer;
import net.minecraft.entity.projectile.EntityArrow;
import net.minecraft.inventory.ContainerChest;
import net.minecraft.inventory.IInventory;
import net.minecraft.server.management.ServerConfigurationManager;
import net.minecraft.tileentity.TileEntityChest;
import net.minecraft.tileentity.TileEntityEnderChest;
import net.minecraft.util.AxisAlignedBB;
import net.minecraft.world.Teleporter;
import net.minecraft.world.World;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.Redirect;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

import eu.avalanche7.paradigm.platform.VanishInteractions;

public final class VanishInteractionMixin {
    private VanishInteractionMixin() {}

    @Mixin({EntityItem.class, EntityXPOrb.class, EntityArrow.class})
    public static abstract class Pickup {
        @Inject(method = "onCollideWithPlayer", at = @At("HEAD"), cancellable = true)
        private void paradigm$preventPickup(EntityPlayer player, CallbackInfo ci) {
            if (hidden(player)) ci.cancel();
        }
    }

    @Mixin(EntityXPOrb.class)
    public static abstract class Orb {
        @Shadow private EntityPlayer closestPlayer;

        @Inject(method = "onUpdate", at = @At("HEAD"))
        private void paradigm$releaseOrbTarget(CallbackInfo ci) {
            if (hidden(closestPlayer)) closestPlayer = null;
        }

        @Redirect(method = "onUpdate", at = @At(value = "INVOKE",
                target = "Lnet/minecraft/world/World;getClosestPlayerToEntity(Lnet/minecraft/entity/Entity;D)Lnet/minecraft/entity/player/EntityPlayer;"))
        private EntityPlayer paradigm$findOrbRecipient(World world, Entity orb, double range) {
            return nearestVisiblePlayer(world, orb, range);
        }
    }

    @Mixin({Entity.class, EntityMinecart.class})
    public static abstract class Push {
        @Inject(method = "applyEntityCollision", at = @At("HEAD"), cancellable = true)
        private void paradigm$preventPush(Entity other, CallbackInfo ci) {
            if (hidden(this) || hidden(other)) ci.cancel();
        }
    }

    @Mixin(Entity.class)
    public static abstract class Movement {
        @Inject(method = "doesEntityNotTriggerPressurePlate", at = @At("HEAD"), cancellable = true)
        private void paradigm$ignoreTriggers(CallbackInfoReturnable<Boolean> cir) {
            if (hidden(this)) cir.setReturnValue(true);
        }

        @Inject(method = "func_145780_a", at = @At("HEAD"), cancellable = true)
        private void paradigm$hideStepSound(int x, int y, int z, Block block, CallbackInfo ci) {
            if (hidden(this)) ci.cancel();
        }

        @Redirect(method = {"moveEntity", "handleWaterMovement"}, at = @At(value = "INVOKE",
                target = "Lnet/minecraft/entity/Entity;playSound(Ljava/lang/String;FF)V"))
        private void paradigm$hideWaterSound(Entity entity, String sound, float volume, float pitch) {
            if (!hidden(entity) || "random.fizz".equals(sound)) entity.playSound(sound, volume, pitch);
        }

        @Redirect(method = {"onEntityUpdate", "handleWaterMovement"}, at = @At(value = "INVOKE",
                target = "Lnet/minecraft/world/World;spawnParticle(Ljava/lang/String;DDDDDD)V"))
        private void paradigm$hideMovementParticles(World world, String particle, double x, double y, double z,
                double velocityX, double velocityY, double velocityZ) {
            if (!hidden(this)) world.spawnParticle(particle, x, y, z, velocityX, velocityY, velocityZ);
        }
    }

    @Mixin(EntityLivingBase.class)
    public static abstract class LivingMovement {
        @Inject(method = "canBePushed", at = @At("HEAD"), cancellable = true)
        private void paradigm$ignorePushQueries(CallbackInfoReturnable<Boolean> cir) {
            if (hidden(this)) cir.setReturnValue(false);
        }

        @Redirect(method = "fall", at = @At(value = "INVOKE",
                target = "Lnet/minecraft/entity/EntityLivingBase;playSound(Ljava/lang/String;FF)V"))
        private void paradigm$hideFallSound(EntityLivingBase entity, String sound, float volume, float pitch) {
            if (!hidden(entity)) entity.playSound(sound, volume, pitch);
        }
    }

    @Mixin(BlockPressurePlateWeighted.class)
    public static abstract class WeightedPlate {
        @Redirect(method = "func_150065_e", at = @At(value = "INVOKE",
                target = "Lnet/minecraft/world/World;getEntitiesWithinAABB(Ljava/lang/Class;Lnet/minecraft/util/AxisAlignedBB;)Ljava/util/List;"))
        private List<?> paradigm$ignoreHiddenWeight(World world, Class<?> type, AxisAlignedBB box) {
            List<?> entities = world.getEntitiesWithinAABB(type, box);
            entities.removeIf(VanishInteractions::hidden);
            return entities;
        }
    }

    @Mixin(World.class)
    public static abstract class VulnerablePlayers {
        @Redirect(method = "getClosestVulnerablePlayer", at = @At(value = "INVOKE",
                target = "Lnet/minecraft/entity/player/EntityPlayer;isEntityAlive()Z"))
        private boolean paradigm$skipHiddenTarget(EntityPlayer player) {
            return player.isEntityAlive() && !hidden(player);
        }
    }

    @Mixin(EntityAITarget.class)
    public static abstract class Targeting {
        @Inject(method = "isSuitableTarget", at = @At("HEAD"), cancellable = true)
        private void paradigm$ignoreTarget(EntityLivingBase target, boolean includeInvulnerable, CallbackInfoReturnable<Boolean> cir) {
            if (hidden(target)) cir.setReturnValue(false);
        }
    }

    @Mixin(EntityLiving.class)
    public static abstract class MobTarget {
        @Shadow private EntityLivingBase attackTarget;

        @Inject(method = "setAttackTarget", at = @At("HEAD"), cancellable = true)
        private void paradigm$rejectHiddenTarget(EntityLivingBase target, CallbackInfo ci) {
            if (hidden(target)) ci.cancel();
        }

        @Inject(method = "onLivingUpdate", at = @At("HEAD"))
        private void paradigm$clearHiddenTarget(CallbackInfo ci) {
            if (hidden(attackTarget)) {
                EntityLiving mob = (EntityLiving) (Object) this;
                mob.setAttackTarget(null);
                mob.getNavigator().clearPathEntity();
            }
        }
    }

    @Mixin(EntityCreature.class)
    public static abstract class CreatureTarget {
        @Shadow protected Entity entityToAttack;

        @Inject(method = "setTarget", at = @At("HEAD"), cancellable = true)
        private void paradigm$rejectHiddenTarget(Entity target, CallbackInfo ci) {
            if (hidden(target)) ci.cancel();
        }

        @Inject(method = "updateEntityActionState", at = @At("HEAD"))
        private void paradigm$clearHiddenTarget(CallbackInfo ci) {
            if (hidden(entityToAttack)) {
                entityToAttack = null;
                ((EntityCreature) (Object) this).setPathToEntity(null);
            }
        }
    }

    @Mixin(EntityAITempt.class)
    public static abstract class Temptation {
        @Shadow private EntityPlayer temptingPlayer;

        @Redirect(method = "shouldExecute", at = @At(value = "INVOKE",
                target = "Lnet/minecraft/world/World;getClosestPlayerToEntity(Lnet/minecraft/entity/Entity;D)Lnet/minecraft/entity/player/EntityPlayer;"))
        private EntityPlayer paradigm$findVisiblePlayer(World world, Entity creature, double range) {
            return nearestVisiblePlayer(world, creature, range);
        }

        @Inject(method = "continueExecuting", at = @At("HEAD"), cancellable = true)
        private void paradigm$stopFollowingHiddenPlayer(CallbackInfoReturnable<Boolean> cir) {
            if (hidden(temptingPlayer)) cir.setReturnValue(false);
        }
    }

    @Mixin(ContainerChest.class)
    public static abstract class ChestMenu {
        @Redirect(method = "<init>", at = @At(value = "INVOKE",
                target = "Lnet/minecraft/inventory/IInventory;openInventory()V"))
        private void paradigm$openForViewer(IInventory chest, IInventory viewerInventory, IInventory lowerChest) {
            notifyInventory(chest, viewerInventory instanceof InventoryPlayer inventory ? inventory.player : null, true);
        }

        @Redirect(method = "onContainerClosed", at = @At(value = "INVOKE",
                target = "Lnet/minecraft/inventory/IInventory;closeInventory()V"))
        private void paradigm$closeForViewer(IInventory chest, EntityPlayer viewer) {
            notifyInventory(chest, viewer, false);
        }
    }

    @Mixin(TileEntityChest.class)
    public static abstract class Chest {
        @Inject(method = {"openInventory", "closeInventory"}, at = @At("HEAD"), cancellable = true)
        private void paradigm$skipHiddenViewer(CallbackInfo ci) {
            if (silentViewer()) ci.cancel();
        }

        @Redirect(method = "updateEntity", at = @At(value = "INVOKE",
                target = "Lnet/minecraft/world/World;getEntitiesWithinAABB(Ljava/lang/Class;Lnet/minecraft/util/AxisAlignedBB;)Ljava/util/List;"))
        private List<?> paradigm$recountVisibleViewers(World world, Class<?> type, AxisAlignedBB box) {
            List<?> entities = world.getEntitiesWithinAABB(type, box);
            entities.removeIf(VanishInteractions::hidden);
            return entities;
        }
    }

    @Mixin(TileEntityEnderChest.class)
    public static abstract class EnderChest {
        @Inject(method = {"func_145969_a", "func_145970_b"}, at = @At("HEAD"), cancellable = true)
        private void paradigm$skipHiddenViewer(CallbackInfo ci) {
            if (silentViewer()) ci.cancel();
        }
    }

    @Mixin(ServerConfigurationManager.class)
    public static abstract class ContainerLifecycle {
        @Inject(method = "playerLoggedOut", at = @At("HEAD"))
        private void paradigm$closeBeforeLogout(EntityPlayerMP player, CallbackInfo ci) {
            if (player.openContainer instanceof ContainerChest) player.closeContainer();
        }

        @Inject(method = "transferPlayerToDimension(Lnet/minecraft/entity/player/EntityPlayerMP;ILnet/minecraft/world/Teleporter;)V", remap = false, at = @At("HEAD"))
        private void paradigm$closeBeforeTransfer(EntityPlayerMP player, int dimension, Teleporter teleporter, CallbackInfo ci) {
            if (player.openContainer instanceof ContainerChest) player.closeScreen();
        }
    }
}
