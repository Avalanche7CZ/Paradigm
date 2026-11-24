package eu.avalanche7.paradigm.mixin;

import static eu.avalanche7.paradigm.platform.VanishInteractions.hidden;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Holder;
import net.minecraft.core.particles.ParticleOptions;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.sounds.SoundEvent;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.world.entity.ContainerUser;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntitySelector;
import net.minecraft.world.entity.ExperienceOrb;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.ai.Brain;
import net.minecraft.world.entity.ai.memory.MemoryModuleType;
import net.minecraft.world.entity.ai.targeting.TargetingConditions;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.entity.projectile.arrow.AbstractArrow;
import net.minecraft.world.entity.vehicle.boat.AbstractBoat;
import net.minecraft.world.entity.vehicle.minecart.AbstractMinecart;
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
import org.spongepowered.asm.mixin.injection.Group;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.Redirect;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;


public final class VanishInteractionMixin {
    private VanishInteractionMixin() {}

    @Mixin({ItemEntity.class, ExperienceOrb.class, AbstractArrow.class})
    public static abstract class Pickup {
        @Inject(method = {"playerTouch", "m_6123_"}, remap = false, at = @At("HEAD"), cancellable = true, require = 1)
        private void paradigm$preventPickup(Player player, CallbackInfo ci) {
            if (hidden(player)) ci.cancel();
        }
    }

    @Mixin(ExperienceOrb.class)
    public static abstract class Orb {
        @Shadow(aliases = "f_20771_", remap = false) private Player followingPlayer;

        @Inject(method = {"followNearbyPlayer", "m_386140_"}, remap = false, at = @At("HEAD"), require = 1)
        private void paradigm$releaseOrbTarget(CallbackInfo ci) {
            if (hidden(followingPlayer)) followingPlayer = null;
        }

        @Group(name = "findOrbRecipient", min = 1)
        @Redirect(method = "followNearbyPlayer", remap = false, at = @At(value = "INVOKE",
                target = "Lnet/minecraft/world/level/Level;getNearestPlayer(Lnet/minecraft/world/entity/Entity;D)Lnet/minecraft/world/entity/player/Player;"), require = 0)
        private Player paradigm$findOrbRecipient(Level world, Entity orb, double range) {
            return world.getNearestPlayer(orb.getX(), orb.getY(), orb.getZ(), range,
                    player -> EntitySelector.NO_SPECTATORS.test(player) && !hidden(player));
        }

        @Group(name = "findOrbRecipient", min = 1)
        @Redirect(method = "m_386140_", remap = false, at = @At(value = "INVOKE",
                target = "Lnet/minecraft/world/level/Level;m_45930_(Lnet/minecraft/world/entity/Entity;D)Lnet/minecraft/world/entity/player/Player;"), require = 0)
        private Player paradigm$findOrbRecipientSrg(Level world, Entity orb, double range) {
            return paradigm$findOrbRecipient(world, orb, range);
        }
    }

    @Mixin({Entity.class, LivingEntity.class, AbstractMinecart.class, AbstractBoat.class})
    public static abstract class Push {
        @Inject(method = {"push(Lnet/minecraft/world/entity/Entity;)V", "m_7334_(Lnet/minecraft/world/entity/Entity;)V"}, remap = false, at = @At("HEAD"), cancellable = true, require = 1)
        private void paradigm$preventPush(Entity other, CallbackInfo ci) {
            if (hidden(this) || hidden(other)) ci.cancel();
        }
    }

    @Mixin(Entity.class)
    public static abstract class Movement {
        @Inject(method = {"playStepSound", "playSwimSound(F)V", "doWaterSplashEffect", "spawnSprintParticle", "playCombinationStepSounds", "playMuffledStepSound", "m_7355_", "m_5625_(F)V", "m_5841_", "m_20076_", "m_276961_", "m_280568_"}, remap = false, at = @At("HEAD"), cancellable = true, require = 1)
        private void paradigm$hideMovementEffect(CallbackInfo ci) {
            if (hidden(this)) ci.cancel();
        }

        @Inject(method = {"isIgnoringBlockTriggers", "m_6090_"}, remap = false, at = @At("HEAD"), cancellable = true, require = 1)
        private void paradigm$ignoreTriggers(CallbackInfoReturnable<Boolean> cir) {
            if (hidden(this)) cir.setReturnValue(true);
        }
    }

    @Mixin(LivingEntity.class)
    public static abstract class LivingMovement {
        @Inject(method = {"playBlockFallSound", "m_21229_"}, remap = false, at = @At("HEAD"), cancellable = true, require = 1)
        private void paradigm$hideMovementEffect(CallbackInfo ci) {
            if (hidden(this)) ci.cancel();
        }

        @Inject(method = {"isPushable", "m_6094_"}, remap = false, at = @At("HEAD"), cancellable = true, require = 1)
        private void paradigm$ignorePushQueries(CallbackInfoReturnable<Boolean> cir) {
            if (hidden(this)) cir.setReturnValue(false);
        }

        @Group(name = "hideFallSound", min = 1)
        @Redirect(method = "causeFallDamage", remap = false, at = @At(value = "INVOKE",
                target = "Lnet/minecraft/world/entity/LivingEntity;playSound(Lnet/minecraft/sounds/SoundEvent;FF)V"), require = 0)
        private void paradigm$hideFallSound(LivingEntity entity, SoundEvent sound, float volume, float pitch) {
            if (!hidden(entity)) entity.playSound(sound, volume, pitch);
        }

        @Group(name = "hideFallSound", min = 1)
        @Redirect(method = "m_142535_", remap = false, at = @At(value = "INVOKE",
                target = "Lnet/minecraft/world/entity/LivingEntity;m_5496_(Lnet/minecraft/sounds/SoundEvent;FF)V"), require = 0)
        private void paradigm$hideFallSoundSrg(LivingEntity entity, SoundEvent sound, float volume, float pitch) {
            paradigm$hideFallSound(entity, sound, volume, pitch);
        }

        @Group(name = "hideLandingParticles", min = 1)
        @Redirect(method = "checkFallDamage", remap = false, at = @At(value = "INVOKE",
                target = "Lnet/minecraft/server/level/ServerLevel;sendParticles(Lnet/minecraft/core/particles/ParticleOptions;DDDIDDDD)I"), require = 0)
        private int paradigm$hideLandingParticles(ServerLevel world, ParticleOptions particles,
                double x, double y, double z, int count, double offsetX, double offsetY, double offsetZ, double speed) {
            return hidden(this) ? 0 : world.sendParticles(particles, x, y, z, count, offsetX, offsetY, offsetZ, speed);
        }

        @Group(name = "hideLandingParticles", min = 1)
        @Redirect(method = "m_7840_", remap = false, at = @At(value = "INVOKE",
                target = "Lnet/minecraft/server/level/ServerLevel;m_8767_(Lnet/minecraft/core/particles/ParticleOptions;DDDIDDDD)I"), require = 0)
        private int paradigm$hideLandingParticlesSrg(ServerLevel world, ParticleOptions particles,
                double x, double y, double z, int count, double offsetX, double offsetY, double offsetZ, double speed) {
            return paradigm$hideLandingParticles(world, particles, x, y, z, count, offsetX, offsetY, offsetZ, speed);
        }
    }

    @Mixin(Player.class)
    public static abstract class PlayerMovement {
        @Inject(method = {"playStepSound", "m_7355_"}, remap = false, at = @At("HEAD"), cancellable = true, require = 1)
        private void paradigm$hideMovementEffect(CallbackInfo ci) {
            if (hidden(this)) ci.cancel();
        }
    }

    @Mixin(TargetingConditions.class)
    public static abstract class Targeting {
        @Inject(method = {"test", "m_26885_"}, remap = false, at = @At("HEAD"), cancellable = true, require = 1)
        private void paradigm$ignoreTarget(ServerLevel world, LivingEntity attacker, LivingEntity target,
                CallbackInfoReturnable<Boolean> cir) {
            if (hidden(target)) cir.setReturnValue(false);
        }
    }

    @Mixin(Mob.class)
    public static abstract class MobTarget {
        @Shadow(aliases = "f_21362_", remap = false) private LivingEntity target;

        @Inject(method = {"setTarget", "m_6710_"}, remap = false, at = @At("HEAD"), cancellable = true, require = 1)
        private void paradigm$rejectHiddenTarget(LivingEntity target, CallbackInfo ci) {
            if (hidden(target)) ci.cancel();
        }

        @Inject(method = {"serverAiStep", "m_6140_"}, remap = false, at = @At("HEAD"), require = 1)
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
        @Inject(method = {"incrementOpeners", "m_155452_"}, remap = false, at = @At("HEAD"), cancellable = true, require = 1)
        private void paradigm$skipHiddenOpen(LivingEntity viewer, Level world, BlockPos pos, BlockState state, double range, CallbackInfo ci) {
            if (hidden(viewer)) ci.cancel();
        }

        @Inject(method = {"decrementOpeners", "m_155468_"}, remap = false, at = @At("HEAD"), cancellable = true, require = 1)
        private void paradigm$skipHiddenClose(LivingEntity viewer, Level world, BlockPos pos, BlockState state, CallbackInfo ci) {
            if (hidden(viewer)) ci.cancel();
        }

        @Inject(method = {"hasContainerOpen", "m_415886_"}, remap = false, at = @At("HEAD"), cancellable = true, require = 1)
        private void paradigm$skipHiddenViewer(Entity entity, BlockPos pos, CallbackInfoReturnable<Boolean> cir) {
            if (hidden(entity)) cir.setReturnValue(false);
        }
    }

    @Mixin(ShulkerBoxBlockEntity.class)
    public static abstract class Shulker {
        @Inject(method = {"startOpen", "stopOpen", "m_5856_", "m_5785_"}, remap = false, at = @At("HEAD"), cancellable = true, require = 1)
        private void paradigm$skipHiddenViewer(ContainerUser viewer, CallbackInfo ci) {
            if (hidden(viewer.getLivingEntity())) ci.cancel();
        }
    }

    @Mixin(ServerLevel.class)
    public static abstract class Vibration {
        @Inject(method = {"gameEvent", "m_214171_"}, remap = false, at = @At("HEAD"), cancellable = true, require = 1)
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
        @Inject(method = {"apply", "m_338607_"}, remap = false, at = @At("HEAD"), cancellable = true, require = 1)
        private void paradigm$hideSoulSpeedEffects(ServerLevel world, int level, EnchantedItemInUse equipment,
                Entity entity, Vec3 pos, CallbackInfo ci) {
            if (!hidden(entity)) return;
            if ((Object) this instanceof PlaySoundEffect sound && sound.soundEvents().size() == 1
                    && sound.soundEvents().get(0) == SoundEvents.SOUL_ESCAPE) ci.cancel();
            if ((Object) this instanceof SpawnParticlesEffect particles && particles.particle().getType() == ParticleTypes.SOUL) ci.cancel();
        }
    }
}
