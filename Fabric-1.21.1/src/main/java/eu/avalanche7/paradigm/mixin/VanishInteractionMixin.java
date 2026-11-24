package eu.avalanche7.paradigm.mixin;

import static eu.avalanche7.paradigm.platform.VanishInteractions.hidden;

import java.util.function.Predicate;

import net.minecraft.block.BlockState;
import net.minecraft.block.entity.ShulkerBoxBlockEntity;
import net.minecraft.block.entity.ViewerCountManager;
import net.minecraft.enchantment.EnchantmentEffectContext;
import net.minecraft.enchantment.effect.entity.PlaySoundEnchantmentEffect;
import net.minecraft.enchantment.effect.entity.SpawnParticlesEnchantmentEffect;
import net.minecraft.entity.Entity;
import net.minecraft.entity.ExperienceOrbEntity;
import net.minecraft.entity.ItemEntity;
import net.minecraft.entity.LivingEntity;
import net.minecraft.entity.ai.TargetPredicate;
import net.minecraft.entity.ai.brain.Brain;
import net.minecraft.entity.ai.brain.MemoryModuleType;
import net.minecraft.entity.mob.MobEntity;
import net.minecraft.entity.player.PlayerEntity;
import net.minecraft.entity.projectile.PersistentProjectileEntity;
import net.minecraft.entity.vehicle.AbstractMinecartEntity;
import net.minecraft.entity.vehicle.BoatEntity;
import net.minecraft.particle.ParticleEffect;
import net.minecraft.particle.ParticleTypes;
import net.minecraft.predicate.entity.EntityPredicates;
import net.minecraft.registry.entry.RegistryEntry;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.sound.SoundEvent;
import net.minecraft.sound.SoundEvents;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Vec3d;
import net.minecraft.world.World;
import net.minecraft.world.event.GameEvent;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.ModifyArg;
import org.spongepowered.asm.mixin.injection.Redirect;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;


public final class VanishInteractionMixin {
    private VanishInteractionMixin() {}

    @Mixin({ItemEntity.class, ExperienceOrbEntity.class, PersistentProjectileEntity.class})
    public static abstract class Pickup {
        @Inject(method = "onPlayerCollision", at = @At("HEAD"), cancellable = true, require = 1)
        private void paradigm$preventPickup(PlayerEntity player, CallbackInfo ci) {
            if (hidden(player)) ci.cancel();
        }
    }

    @Mixin(ExperienceOrbEntity.class)
    public static abstract class Orb {
        @Shadow private PlayerEntity target;

        @Inject(method = "expensiveUpdate", at = @At("HEAD"), require = 1)
        private void paradigm$releaseOrbTarget(CallbackInfo ci) {
            if (hidden(target)) target = null;
        }

        @Redirect(method = "expensiveUpdate", at = @At(value = "INVOKE",
                target = "Lnet/minecraft/world/World;getClosestPlayer(Lnet/minecraft/entity/Entity;D)Lnet/minecraft/entity/player/PlayerEntity;"), require = 1)
        private PlayerEntity paradigm$findOrbRecipient(World world, Entity orb, double range) {
            return world.getClosestPlayer(orb.getX(), orb.getY(), orb.getZ(), range,
                    player -> EntityPredicates.EXCEPT_SPECTATOR.test(player) && !hidden(player));
        }
    }

    @Mixin({Entity.class, LivingEntity.class, AbstractMinecartEntity.class, BoatEntity.class})
    public static abstract class Push {
        @Inject(method = "pushAwayFrom(Lnet/minecraft/entity/Entity;)V", at = @At("HEAD"), cancellable = true, require = 1)
        private void paradigm$preventPush(Entity other, CallbackInfo ci) {
            if (hidden(this) || hidden(other)) ci.cancel();
        }
    }

    @Mixin(Entity.class)
    public static abstract class Movement {
        @Inject(method = {"playStepSound", "playSwimSound(F)V", "onSwimmingStart", "spawnSprintingParticles", "playCombinationStepSounds", "playSecondaryStepSound"}, at = @At("HEAD"), cancellable = true, require = 1)
        private void paradigm$hideMovementEffect(CallbackInfo ci) {
            if (hidden(this)) ci.cancel();
        }

        @Inject(method = "canAvoidTraps", at = @At("HEAD"), cancellable = true, require = 1)
        private void paradigm$ignoreTriggers(CallbackInfoReturnable<Boolean> cir) {
            if (hidden(this)) cir.setReturnValue(true);
        }
    }

    @Mixin(LivingEntity.class)
    public static abstract class LivingMovement {
        @Inject(method = "playBlockFallSound", at = @At("HEAD"), cancellable = true, require = 1)
        private void paradigm$hideMovementEffect(CallbackInfo ci) {
            if (hidden(this)) ci.cancel();
        }

        @Inject(method = "isPushable", at = @At("HEAD"), cancellable = true, require = 1)
        private void paradigm$ignorePushQueries(CallbackInfoReturnable<Boolean> cir) {
            if (hidden(this)) cir.setReturnValue(false);
        }

        @Redirect(method = "handleFallDamage", at = @At(value = "INVOKE",
                target = "Lnet/minecraft/entity/LivingEntity;playSound(Lnet/minecraft/sound/SoundEvent;FF)V"), require = 1)
        private void paradigm$hideFallSound(LivingEntity entity, SoundEvent sound, float volume, float pitch) {
            if (!hidden(entity)) entity.playSound(sound, volume, pitch);
        }

        @Redirect(method = "fall", at = @At(value = "INVOKE",
                target = "Lnet/minecraft/server/world/ServerWorld;spawnParticles(Lnet/minecraft/particle/ParticleEffect;DDDIDDDD)I"), require = 1)
        private int paradigm$hideLandingParticles(ServerWorld world, ParticleEffect particles,
                double x, double y, double z, int count, double offsetX, double offsetY, double offsetZ, double speed) {
            return hidden(this) ? 0 : world.spawnParticles(particles, x, y, z, count, offsetX, offsetY, offsetZ, speed);
        }
    }

    @Mixin(PlayerEntity.class)
    public static abstract class PlayerMovement {
        @Inject(method = "playStepSound", at = @At("HEAD"), cancellable = true, require = 1)
        private void paradigm$hideMovementEffect(CallbackInfo ci) {
            if (hidden(this)) ci.cancel();
        }
    }

    @Mixin(TargetPredicate.class)
    public static abstract class Targeting {
        @Inject(method = "test", at = @At("HEAD"), cancellable = true, require = 1)
        private void paradigm$ignoreTarget(LivingEntity attacker, LivingEntity target,
                CallbackInfoReturnable<Boolean> cir) {
            if (hidden(target)) cir.setReturnValue(false);
        }
    }

    @Mixin(MobEntity.class)
    public static abstract class MobTarget {
        @Shadow private LivingEntity target;

        @Inject(method = "setTarget", at = @At("HEAD"), cancellable = true, require = 1)
        private void paradigm$rejectHiddenTarget(LivingEntity target, CallbackInfo ci) {
            if (hidden(target)) ci.cancel();
        }

        @Inject(method = "tickNewAi", at = @At("HEAD"), require = 1)
        private void paradigm$clearHiddenTargets(CallbackInfo ci) {
            MobEntity mob = (MobEntity) (Object) this;
            if (hidden(target)) {
                mob.setTarget(null);
                mob.getNavigation().stop();
            }
            Brain<?> brain = mob.getBrain();
            if (brain.hasMemoryModule(MemoryModuleType.ATTACK_TARGET) && hidden(brain.getOptionalRegisteredMemory(MemoryModuleType.ATTACK_TARGET).orElse(null))) {
                brain.forget(MemoryModuleType.ATTACK_TARGET);
                mob.getNavigation().stop();
            }
            if (brain.hasMemoryModule(MemoryModuleType.TEMPTING_PLAYER) && hidden(brain.getOptionalRegisteredMemory(MemoryModuleType.TEMPTING_PLAYER).orElse(null))) {
                brain.forget(MemoryModuleType.TEMPTING_PLAYER);
                mob.getNavigation().stop();
            }
        }
    }

    @Mixin(ViewerCountManager.class)
    public static abstract class Openers {
        @Inject(method = {"openContainer", "closeContainer"}, at = @At("HEAD"), cancellable = true, require = 1)
        private void paradigm$skipHiddenViewer(PlayerEntity viewer, World world, BlockPos pos, BlockState state, CallbackInfo ci) {
            if (hidden(viewer)) ci.cancel();
        }

        @ModifyArg(method = "getViewingPlayers", at = @At(value = "INVOKE",
                target = "Lnet/minecraft/world/World;getEntitiesByType(Lnet/minecraft/util/TypeFilter;Lnet/minecraft/util/math/Box;Ljava/util/function/Predicate;)Ljava/util/List;"), index = 2, require = 1)
        private Predicate<PlayerEntity> paradigm$filterHiddenViewers(Predicate<PlayerEntity> viewers) {
            return viewer -> !hidden(viewer) && viewers.test(viewer);
        }
    }

    @Mixin(ShulkerBoxBlockEntity.class)
    public static abstract class Shulker {
        @Inject(method = {"onOpen", "onClose"}, at = @At("HEAD"), cancellable = true, require = 1)
        private void paradigm$skipHiddenViewer(PlayerEntity viewer, CallbackInfo ci) {
            if (hidden(viewer)) ci.cancel();
        }
    }

    @Mixin(ServerWorld.class)
    public static abstract class Vibration {
        @Inject(method = "emitGameEvent", at = @At("HEAD"), cancellable = true, require = 1)
        private void paradigm$hideMovementVibrations(RegistryEntry<GameEvent> event, Vec3d pos, GameEvent.Emitter context, CallbackInfo ci) {
            if (hidden(context.sourceEntity()) && (event == GameEvent.STEP || event == GameEvent.SWIM
                    || event == GameEvent.SPLASH || event == GameEvent.HIT_GROUND || event == GameEvent.FLAP
                    || event == GameEvent.ELYTRA_GLIDE || event == GameEvent.CONTAINER_OPEN || event == GameEvent.CONTAINER_CLOSE)) {
                ci.cancel();
            }
        }
    }

    @Mixin({PlaySoundEnchantmentEffect.class, SpawnParticlesEnchantmentEffect.class})
    public static abstract class EquipmentEffects {
        @Inject(method = "apply", at = @At("HEAD"), cancellable = true, require = 1)
        private void paradigm$hideSoulSpeedEffects(ServerWorld world, int level, EnchantmentEffectContext equipment,
                Entity entity, Vec3d pos, CallbackInfo ci) {
            if (!hidden(entity)) return;
            if ((Object) this instanceof PlaySoundEnchantmentEffect sound && sound.soundEvent() == SoundEvents.PARTICLE_SOUL_ESCAPE) ci.cancel();
            if ((Object) this instanceof SpawnParticlesEnchantmentEffect particles && particles.particle().getType() == ParticleTypes.SOUL) ci.cancel();
        }
    }
}
