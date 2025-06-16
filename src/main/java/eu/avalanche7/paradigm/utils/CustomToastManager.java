package eu.avalanche7.paradigm.utils;

import eu.avalanche7.paradigm.configs.ToastConfigHandler;
import eu.avalanche7.paradigm.platform.IPlatformAdapter;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.item.ItemStack;
import java.util.UUID;
import java.util.concurrent.TimeUnit;

public class CustomToastManager {

    private final IPlatformAdapter platform;
    private final MessageParser messageParser;
    private final TaskScheduler taskScheduler;
    private final DebugLogger debugLogger;

    public CustomToastManager(IPlatformAdapter platform, MessageParser messageParser, TaskScheduler taskScheduler, DebugLogger debugLogger) {
        this.platform = platform;
        this.messageParser = messageParser;
        this.taskScheduler = taskScheduler;
        this.debugLogger = debugLogger;
    }

    public boolean showToast(ServerPlayer player, String toastId) {
        try {
            ToastConfigHandler.ToastDefinition definition = ToastConfigHandler.TOASTS.get(toastId);
            if (definition == null) {
                debugLogger.debugLog("Paradigm: Toast definition not found for ID: " + toastId);
                return false;
            }

            ResourceLocation id = ResourceLocation.fromNamespaceAndPath("paradigm", "toast/" + UUID.randomUUID());
            ItemStack icon = platform.createItemStack(definition.icon);

            IPlatformAdapter.AdvancementFrame frame;
            try {
                frame = IPlatformAdapter.AdvancementFrame.valueOf(definition.frame.toUpperCase());
            } catch (IllegalArgumentException e) {
                frame = IPlatformAdapter.AdvancementFrame.TASK;
            }

            Component titleComponent;
            if (definition.title_override != null && !definition.title_override.isEmpty()) {
                titleComponent = messageParser.parseMessage(definition.title_override, player);
            } else {
                titleComponent = null;
            }

            Component descriptionComponent = messageParser.parseMessage(definition.title, player);

            platform.displayToast(player, id, icon, titleComponent, descriptionComponent, frame);
            taskScheduler.schedule(() -> platform.revokeToast(player, id), 5, TimeUnit.SECONDS);

            return true;
        } catch (Exception e) {
            debugLogger.debugLog("Paradigm: An error occurred in showToast for ID: " + toastId, e);
            return false;
        }
    }
}