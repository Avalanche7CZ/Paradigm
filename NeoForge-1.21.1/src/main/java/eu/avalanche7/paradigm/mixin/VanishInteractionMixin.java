package eu.avalanche7.paradigm.mixin;

import static eu.avalanche7.paradigm.platform.VanishInteractions.hidden;

import java.util.function.Predicate;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Holder;
import net.minecraft.core.particles.ParticleOptions;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.sounds.SoundEvent;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.ExperienceOrb;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.ai.Brain;
import net.minecraft.world.entity.ai.memory.MemoryModuleType;
import net.minecraft.world.entity.ai.targeting.TargetingConditions;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.entity.projectile.AbstractArrow;
import net.minecraft.world.entity.vehicle.AbstractMinecart;
import net.minecraft.world.entity.vehicle.Boat;
import net.minecraft.world.item.enchantment.EnchantedItemInUse;
import net.minecraft.world.item.enchantment.effects.PlaySoundEffect;
import net.minecraft.world.item.enchantment.effects.SpawnParticlesEffect;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.entity.ContainerOpenersCounter;
import net.minecraft.world.level.block.entity.ShulkerBoxBlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.gameevent.GameEvent;
import net.minecraft.world.phys.Vec3;

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

    @Mixin({ItemEntity.class, ExperienceOrb.class, AbstractArrow.class})
    public static abstract class Pickup {
        @Inject(method = "playerTouch", at = @At("HEAD"), cancellable = true, require = 1)
        private void paradigm$preventPickup(Player player, CallbackInfo ci) {
            if (hidden(player)) ci.cancel();
        }
    }

    @Mixin({Entity.class, LivingEntity.class, AbstractMinecart.class, Boat.class})
    public static abstract class Push {
        @Inject(method = "push(Lnet/minecraft/world/entity/Entity;)V", at = @At("HEAD"), cancellable = true, require = 1)
        private void paradigm$preventPush(Entity other, CallbackInfo ci) {
            if (hidden(this) || hidden(other)) ci.cancel();
        }
    }

    @Mixin(Entity.class)
    public static abstract class Movement {
        @Inject(method = {"playStepSound", "playSwimSound(F)V", "doWaterSplashEffect", "spawnSprintParticle", "playCombinationStepSounds", "playMuffledStepSound"}, at = @At("HEAD"), cancellable = true, require = 1)
        private void paradigm$hideMovementEffect(CallbackInfo ci) {
            if (hidden(this)) ci.cancel();
        }

        @Inject(method = "isIgnoringBlockTriggers", at = @At("HEAD"), cancellable = true, require = 1)
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

        @Redirect(method = "causeFallDamage", at = @At(value = "INVOKE",
                target = "Lnet/minecraft/world/entity/LivingEntity;playSound(Lnet/minecraft/sounds/SoundEvent;FF)V"), require = 1)
        private void paradigm$hideFallSound(LivingEntity entity, SoundEvent sound, float volume, float pitch) {
            if (!hidden(entity)) entity.playSound(sound, volume, pitch);
        }

        @Redirect(method = "checkFallDamage", at = @At(value = "INVOKE",
                target = "Lnet/minecraft/server/level/ServerLevel;sendParticles(Lnet/minecraft/core/particles/ParticleOptions;DDDIDDDD)I"), require = 1)
        private int paradigm$hideLandingParticles(ServerLevel world, ParticleOptions particles,
                double x, double y, double z, int count, double offsetX, double offsetY, double offsetZ, double speed) {
            return hidden(this) ? 0 : world.sendParticles(particles, x, y, z, count, offsetX, offsetY, offsetZ, speed);
        }
    }

    @Mixin(Player.class)
    public static abstract class PlayerMovement {
        @Inject(method = "playStepSound", at = @At("HEAD"), cancellable = true, require = 1)
        private void paradigm$hideMovementEffect(CallbackInfo ci) {
            if (hidden(this)) ci.cancel();
        }
    }

    @Mixin(TargetingConditions.class)
    public static abstract class Targeting {
        @Inject(method = "test", at = @At("HEAD"), cancellable = true, require = 1)
        private void paradigm$ignoreTarget(LivingEntity attacker, LivingEntity target,
                CallbackInfoReturnable<Boolean> cir) {
            if (hidden(target)) cir.setReturnValue(false);
        }
    }

    @Mixin(Mob.class)
    public static abstract class MobTarget {
        @Shadow private LivingEntity target;

        @Inject(method = "setTarget", at = @At("HEAD"), cancellable = true, require = 1)
        private void paradigm$rejectHiddenTarget(LivingEntity target, CallbackInfo ci) {
            if (hidden(target)) ci.cancel();
        }

        @Inject(method = "serverAiStep", at = @At("HEAD"), require = 1)
        private void paradigm$clearHiddenTargets(CallbackInfo ci) {
            Mob mob = (Mob) (Object) this;
            if (hidden(target)) {
                mob.setTarget(null);
                mob.getNavigation().stop();
            }
            Brain<?> brain = mob.getBrain();
            if (brain.hasMemoryValue(MemoryModuleType.ATTACK_TARGET) && hidden(brain.getMemoryInternal(MemoryModuleType.ATTACK_TARGET).orElse(null))) {
                brain.eraseMemory(MemoryModuleType.ATTACK_TARGET);
                mob.getNavigation().stop();
            }
            if (brain.hasMemoryValue(MemoryModuleType.TEMPTING_PLAYER) && hidden(brain.getMemoryInternal(MemoryModuleType.TEMPTING_PLAYER).orElse(null))) {
                brain.eraseMemory(MemoryModuleType.TEMPTING_PLAYER);
                mob.getNavigation().stop();
            }
        }
    }

    @Mixin(ContainerOpenersCounter.class)
    public static abstract class Openers {
        @Inject(method = {"incrementOpeners", "decrementOpeners"}, at = @At("HEAD"), cancellable = true, require = 1)
        private void paradigm$skipHiddenViewer(Player viewer, Level world, BlockPos pos, BlockState state, CallbackInfo ci) {
            if (hidden(viewer)) ci.cancel();
        }

        @ModifyArg(method = "getPlayersWithContainerOpen", at = @At(value = "INVOKE",
                target = "Lnet/minecraft/world/level/Level;getEntities(Lnet/minecraft/world/level/entity/EntityTypeTest;Lnet/minecraft/world/phys/AABB;Ljava/util/function/Predicate;)Ljava/util/List;"), index = 2, require = 1)
        private Predicate<Player> paradigm$filterHiddenViewers(Predicate<Player> viewers) {
            return viewer -> !hidden(viewer) && viewers.test(viewer);
        }
    }

    @Mixin(ShulkerBoxBlockEntity.class)
    public static abstract class Shulker {
        @Inject(method = {"startOpen", "stopOpen"}, at = @At("HEAD"), cancellable = true, require = 1)
        private void paradigm$skipHiddenViewer(Player viewer, CallbackInfo ci) {
            if (hidden(viewer)) ci.cancel();
        }
    }

    @Mixin(ServerLevel.class)
    public static abstract class Vibration {
        @Inject(method = "gameEvent", at = @At("HEAD"), cancellable = true, require = 1)
        private void paradigm$hideMovementVibrations(Holder<GameEvent> event, Vec3 pos, GameEvent.Context context, CallbackInfo ci) {
            if (hidden(context.sourceEntity()) && (event == GameEvent.STEP || event == GameEvent.SWIM
                    || event == GameEvent.SPLASH || event == GameEvent.HIT_GROUND || event == GameEvent.FLAP
                    || event == GameEvent.ELYTRA_GLIDE || event == GameEvent.CONTAINER_OPEN || event == GameEvent.CONTAINER_CLOSE)) {
                ci.cancel();
            }
        }
    }

    @Mixin({PlaySoundEffect.class, SpawnParticlesEffect.class})
    public static abstract class EquipmentEffects {
        @Inject(method = "apply", at = @At("HEAD"), cancellable = true, require = 1)
        private void paradigm$hideSoulSpeedEffects(ServerLevel world, int level, EnchantedItemInUse equipment,
                Entity entity, Vec3 pos, CallbackInfo ci) {
            if (!hidden(entity)) return;
            if ((Object) this instanceof PlaySoundEffect sound && sound.soundEvent() == SoundEvents.SOUL_ESCAPE) ci.cancel();
            if ((Object) this instanceof SpawnParticlesEffect particles && particles.particle().getType() == ParticleTypes.SOUL) ci.cancel();
        }
    }
}
