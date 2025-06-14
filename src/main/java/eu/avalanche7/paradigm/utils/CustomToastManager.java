package eu.avalanche7.paradigm.utils;

import eu.avalanche7.paradigm.configs.ToastConfigHandler;
import eu.avalanche7.paradigm.core.Services;
import net.minecraft.advancement.Advancement;
import net.minecraft.advancement.AdvancementCriterion;
import net.minecraft.advancement.AdvancementDisplay;
import net.minecraft.advancement.AdvancementEntry;
import net.minecraft.advancement.AdvancementFrame;
import net.minecraft.advancement.AdvancementProgress;
import net.minecraft.advancement.criterion.Criteria;
import net.minecraft.advancement.criterion.ImpossibleCriterion;
import net.minecraft.item.ItemStack;
import net.minecraft.network.packet.s2c.play.AdvancementUpdateS2CPacket;
import net.minecraft.registry.Registries;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.text.Text;
import net.minecraft.util.Identifier;

import java.util.Collections;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.TimeUnit;

public class CustomToastManager {

    private final MessageParser messageParser;

    public CustomToastManager(MessageParser messageParser) {
        this.messageParser = messageParser;
    }

    public boolean showToast(ServerPlayerEntity player, String toastId, Services services) {
        ToastConfigHandler.ToastDefinition definition = ToastConfigHandler.TOASTS.get(toastId);
        if (definition == null) {
            return false;
        }

        Text title = messageParser.parseMessage(definition.title, player);
        AdvancementFrame frame;
        try {
            frame = AdvancementFrame.valueOf(definition.frame.toUpperCase());
        } catch (IllegalArgumentException e) {
            frame = AdvancementFrame.TASK;
        }

        return showToast(player, definition.icon, title, frame, services);
    }

    public boolean showToast(ServerPlayerEntity player, String iconIdString, Text title, AdvancementFrame frame, Services services) {
        if (player == null || services == null) return false;

        Identifier iconId = Identifier.of(iconIdString);
        ItemStack icon = new ItemStack(Registries.ITEM.get(iconId));
        Text description = Text.literal("");

        Identifier advancementId = Identifier.of("paradigm", "toast/" + System.currentTimeMillis());

        AdvancementDisplay display = new AdvancementDisplay(
                icon, title, description,
                Optional.of(Identifier.of("textures/gui/advancements/backgrounds/stone.png")),
                frame, true, true, false
        );

        AdvancementCriterion<?> criterion = new AdvancementCriterion<>(
                Criteria.IMPOSSIBLE,
                new ImpossibleCriterion.Conditions()
        );

        Advancement advancement = Advancement.Builder.createUntelemetered()
                .display(display)
                .criterion("trigger", criterion)
                .build(advancementId).value();

        AdvancementEntry advancementEntry = new AdvancementEntry(advancementId, advancement);

        AdvancementProgress progress = new AdvancementProgress();
        progress.init(advancement.requirements());
        progress.obtain("trigger");

        AdvancementUpdateS2CPacket addAndGrantPacket = new AdvancementUpdateS2CPacket(
                false,
                Collections.singletonList(advancementEntry),
                Collections.emptySet(),
                Map.of(advancementId, progress)
        );

        player.networkHandler.send(addAndGrantPacket, null);

        Runnable removeTask = () -> {
            AdvancementUpdateS2CPacket removePacket = new AdvancementUpdateS2CPacket(
                    false,
                    Collections.emptyList(),
                    Collections.singleton(advancementId),
                    Collections.emptyMap()
            );
            if (!player.isDisconnected()) {
                player.networkHandler.send(removePacket, null);
            }
        };

        services.getTaskScheduler().schedule(removeTask, 5, TimeUnit.SECONDS);

        return true;
    }
}