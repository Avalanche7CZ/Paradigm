package eu.avalanche7.paradigm.modules.chat;

import eu.avalanche7.paradigm.configs.ChatConfigHandler;
import eu.avalanche7.paradigm.core.ParadigmModule;
import eu.avalanche7.paradigm.core.Services;
import eu.avalanche7.paradigm.platform.Interfaces.ICommandBuilder;
import eu.avalanche7.paradigm.platform.Interfaces.ICommandContext;
import eu.avalanche7.paradigm.platform.Interfaces.IEventSystem;
import eu.avalanche7.paradigm.platform.Interfaces.IPlatformAdapter;
import eu.avalanche7.paradigm.platform.Interfaces.IPlayer;
import eu.avalanche7.paradigm.utils.PermissionsHandler;

import java.util.concurrent.ConcurrentHashMap;

public class PrivateMessages implements ParadigmModule {

    private static final String NAME = "PrivateMessages";

    private final ConcurrentHashMap<String, String> lastContactByUuid = new ConcurrentHashMap<>();
    private Services services;
    private IPlatformAdapter platform;

    @Override
    public String getName() {
        return NAME;
    }

    @Override
    public boolean isEnabled(Services services) {
        if (services == null || services.getChatConfig() == null) {
            return true;
        }
        return Boolean.TRUE.equals(services.getChatConfig().enablePrivateMessages.value);
    }

    @Override
    public void onLoad(Object event, Services services, Object modEventBus) {
        this.services = services;
        this.platform = services.getPlatformAdapter();
    }

    @Override
    public void onServerStarting(Object event, Services services) {
    }

    @Override
    public void onEnable(Services services) {
    }

    @Override
    public void onDisable(Services services) {
        lastContactByUuid.clear();
    }

    @Override
    public void onServerStopping(Object event, Services services) {
        onDisable(services);
    }

    @Override
    public void registerCommands(Object dispatcher, Object registryAccess, Services services) {
        registerMsgRoot("msg");
        registerMsgRoot("tell");
        registerMsgRoot("w");
        registerMsgRoot("whisper");

        registerReplyRoot("reply");
        registerReplyRoot("r");
    }

    private void registerMsgRoot(String literal) {
        ICommandBuilder command = platform.createCommandBuilder()
                .literal(literal)
                .requires(source -> source.getPlayer() != null &&
                        platform.hasPermission(
                                source.getPlayer(),
                                PermissionsHandler.PRIVATE_MESSAGE_PERMISSION,
                                PermissionsHandler.PRIVATE_MESSAGE_PERMISSION_LEVEL
                        ))
                .then(platform.createCommandBuilder()
                        .argument("player", ICommandBuilder.ArgumentType.PLAYER)
                        .then(platform.createCommandBuilder()
                                .argument("message", ICommandBuilder.ArgumentType.GREEDY_STRING)
                                .executes(this::executeMsgCommand)));
        platform.registerCommand(command);
    }

    private void registerReplyRoot(String literal) {
        ICommandBuilder command = platform.createCommandBuilder()
                .literal(literal)
                .requires(source -> source.getPlayer() != null &&
                        platform.hasPermission(
                                source.getPlayer(),
                                PermissionsHandler.PRIVATE_REPLY_PERMISSION,
                                PermissionsHandler.PRIVATE_REPLY_PERMISSION_LEVEL
                        ))
                .then(platform.createCommandBuilder()
                        .argument("message", ICommandBuilder.ArgumentType.GREEDY_STRING)
                        .executes(this::executeReplyCommand));
        platform.registerCommand(command);
    }

    @Override
    public void registerEventListeners(Object eventBus, Services services) {
        IEventSystem events = services.getPlatformAdapter().getEventSystem();
        if (events != null) {
            events.onPlayerLeave(event -> clearConversationLinks(event.getPlayer()));
        }
    }

    private int executeMsgCommand(ICommandContext ctx) {
        IPlayer sender = ctx.getSource().requirePlayer();
        if (sender == null) {
            return 0;
        }

        IPlayer target = resolvePlayerArgument(ctx, "player");
        if (target == null) {
            platform.sendSystemMessage(sender, services.getLang().translate("pm.player_not_found"));
            return 0;
        }

        if (target.getUUID() != null && target.getUUID().equals(sender.getUUID())) {
            platform.sendSystemMessage(sender, services.getLang().translate("pm.cannot_message_self"));
            return 0;
        }

        String message = ctx.getStringArgument("message");
        if (message == null || message.trim().isEmpty()) {
            platform.sendSystemMessage(sender, services.getLang().translate("pm.empty_message"));
            return 0;
        }

        sendPrivateMessage(sender, target, message.trim());
        return 1;
    }

    private int executeReplyCommand(ICommandContext ctx) {
        IPlayer sender = ctx.getSource().requirePlayer();
        if (sender == null) {
            return 0;
        }

        String message = ctx.getStringArgument("message");
        if (message == null || message.trim().isEmpty()) {
            platform.sendSystemMessage(sender, services.getLang().translate("pm.empty_message"));
            return 0;
        }

        String senderUuid = sender.getUUID();
        if (senderUuid == null || senderUuid.isBlank()) {
            platform.sendSystemMessage(sender, services.getLang().translate("pm.no_reply_target"));
            return 0;
        }

        String targetUuid = lastContactByUuid.get(senderUuid);
        if (targetUuid == null || targetUuid.isBlank()) {
            platform.sendSystemMessage(sender, services.getLang().translate("pm.no_reply_target"));
            return 0;
        }

        IPlayer target = platform.getPlayerByUuid(targetUuid);
        if (target == null) {
            clearConversationLinksByUuid(targetUuid);
            platform.sendSystemMessage(sender, services.getLang().translate("pm.reply_target_offline"));
            return 0;
        }

        sendPrivateMessage(sender, target, message.trim());
        return 1;
    }

    private IPlayer resolvePlayerArgument(ICommandContext ctx, String argumentName) {
        IPlayer target = ctx.getPlayerArgument(argumentName);
        if (target != null) {
            return target;
        }

        try {
            String rawName = ctx.getStringArgument(argumentName);
            if (rawName == null || rawName.isBlank()) {
                return null;
            }
            return platform.getPlayerByName(rawName);
        } catch (Throwable ignored) {
            return null;
        }
    }

    private void sendPrivateMessage(IPlayer sender, IPlayer target, String message) {
        ChatConfigHandler.Config chatConfig = services.getChatConfig();

        String toTemplate = chatConfig.privateMessageToFormat.value;
        String fromTemplate = chatConfig.privateMessageFromFormat.value;

        String targetName = platform.getPlayerName(target);
        String senderName = platform.getPlayerName(sender);

        String toFormatted = String.format(toTemplate, targetName, message);
        String fromFormatted = String.format(fromTemplate, senderName, message);

        platform.sendSystemMessage(sender, services.getMessageParser().parseMessage(toFormatted, sender));
        platform.sendSystemMessage(target, services.getMessageParser().parseMessage(fromFormatted, target));

        updateLastContact(sender, target);
        services.getLogger().info("[MSG] {} -> {}: {}", senderName, targetName, message);
    }

    private void updateLastContact(IPlayer sender, IPlayer target) {
        if (sender == null || target == null || sender.getUUID() == null || target.getUUID() == null) {
            return;
        }
        lastContactByUuid.put(sender.getUUID(), target.getUUID());
        lastContactByUuid.put(target.getUUID(), sender.getUUID());
    }

    private void clearConversationLinks(IPlayer player) {
        if (player == null || player.getUUID() == null) {
            return;
        }
        clearConversationLinksByUuid(player.getUUID());
    }

    private void clearConversationLinksByUuid(String playerUuid) {
        if (playerUuid == null || playerUuid.isBlank()) {
            return;
        }
        lastContactByUuid.remove(playerUuid);
        lastContactByUuid.entrySet().removeIf(entry -> playerUuid.equals(entry.getValue()));
    }
}




