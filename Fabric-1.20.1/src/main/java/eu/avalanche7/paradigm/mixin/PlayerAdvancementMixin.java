package eu.avalanche7.paradigm.mixin;

import java.util.ArrayDeque;
import java.util.Deque;

import eu.avalanche7.paradigm.platform.Interfaces.IEventSystem.PlayerAdvancementEventListener;
import eu.avalanche7.paradigm.platform.MinecraftEventSystem;
import net.minecraft.advancement.Advancement;
import net.minecraft.advancement.AdvancementDisplay;
import net.minecraft.advancement.AdvancementProgress;
import net.minecraft.advancement.PlayerAdvancementTracker;
import net.minecraft.server.network.ServerPlayerEntity;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

@Mixin(PlayerAdvancementTracker.class)
public abstract class PlayerAdvancementMixin {
    @Shadow
    private ServerPlayerEntity owner;

    @Shadow
    public abstract AdvancementProgress getProgress(Advancement advancement);

    @Unique
    private final Deque<Boolean> paradigm$wasDoneBeforeGrant = new ArrayDeque<>();

    @Inject(method = "grantCriterion", at = @At("HEAD"))
    private void paradigm$captureBeforeGrant(Advancement advancement, String criterionName,
                                              CallbackInfoReturnable<Boolean> cir) {
        paradigm$wasDoneBeforeGrant.push(getProgress(advancement).isDone());
    }

    @Inject(method = "grantCriterion", at = @At("RETURN"))
    private void paradigm$afterGrantCriterion(Advancement advancement, String criterionName,
                                               CallbackInfoReturnable<Boolean> cir) {
        boolean wasDoneBeforeGrant = paradigm$wasDoneBeforeGrant.isEmpty() || paradigm$wasDoneBeforeGrant.pop();
        if (wasDoneBeforeGrant || MinecraftEventSystem.getAdvancementListeners().isEmpty()) {
            return;
        }
        if (!getProgress(advancement).isDone()) {
            return;
        }
        AdvancementDisplay display = advancement.getDisplay();
        if (display == null || !display.shouldAnnounceToChat()) {
            return;
        }
        MinecraftEventSystem.AdvancementEventImpl event = new MinecraftEventSystem.AdvancementEventImpl(
                owner, display.getTitle().getString(), display.getDescription().getString());
        for (PlayerAdvancementEventListener listener : MinecraftEventSystem.getAdvancementListeners()) {
            try {
                listener.onPlayerAdvancement(event);
            } catch (Exception e) {
                System.err.println("Error in advancement event listener: " + e.getMessage());
            }
        }
    }
}
