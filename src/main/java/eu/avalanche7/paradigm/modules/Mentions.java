package eu.avalanche7.paradigm.modules;

import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.context.CommandContext;
import eu.avalanche7.paradigm.configs.MentionConfigHandler;
import eu.avalanche7.paradigm.platform.Interfaces.IComponent;
import eu.avalanche7.paradigm.platform.Interfaces.IPlayer;
import eu.avalanche7.paradigm.core.ParadigmModule;
import eu.avalanche7.paradigm.core.Services;
import eu.avalanche7.paradigm.platform.Interfaces.IPlatformAdapter;
import eu.avalanche7.paradigm.utils.PermissionsHandler;
import net.fabricmc.fabric.api.message.v1.ServerMessageEvents;
import net.minecraft.command.CommandRegistryAccess;
import net.minecraft.network.message.SignedMessage;
import net.minecraft.server.command.CommandManager;
import net.minecraft.server.command.ServerCommandSource;
import net.minecraft.server.network.ServerPlayerEntity;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

public class Mentions implements ParadigmModule {

    private static final String NAME = "Mentions";
    private final Map<UUID, Long> lastIndividualMentionBySender = new HashMap<>();
    private final Map<UUID, Long> lastEveryoneMentionBySender = new HashMap<>();
    private Services services;
    private IPlatformAdapter platform;

    @Override
    public String getName() {
        return NAME;
    }

    @Override
    public boolean isEnabled(Services services) {
        return services != null && services.getMainConfig() != null && services.getMainConfig().mentionsEnable.value;
    }

    @Override
    public void onLoad(Object event, Services services, Object modEventBus) {
        this.services = services;
        this.platform = services.getPlatformAdapter();
        if (this.services != null && this.services.getDebugLogger() != null) {
            this.services.getDebugLogger().debugLog(NAME + " module loaded.");
        }
    }

    @Override
    public void onServerStarting(Object event, Services services) {
        if (this.services != null && this.services.getDebugLogger() != null) {
            this.services.getDebugLogger().debugLog(NAME + " module: Server starting.");
        }
    }

    @Override
    public void onEnable(Services services) {
        if (this.services != null && this.services.getDebugLogger() != null) {
            this.services.getDebugLogger().debugLog(NAME + " module enabled.");
        }
    }

    @Override
    public void onDisable(Services services) {
        if (this.services != null && this.services.getDebugLogger() != null) {
            this.services.getDebugLogger().debugLog(NAME + " module disabled.");
        }
    }

    @Override
    public void onServerStopping(Object event, Services services) {
        if (this.services != null && this.services.getDebugLogger() != null) {
            this.services.getDebugLogger().debugLog(NAME + " module: Server stopping.");
        }
    }

    @Override
    public void registerCommands(CommandDispatcher<ServerCommandSource> dispatcher, CommandRegistryAccess registryAccess, Services services) {
        dispatcher.register(CommandManager.literal("mention")
                .requires(source -> source.hasPermissionLevel(0))
                .then(CommandManager.argument("message", StringArgumentType.greedyString())
                        .executes(context -> executeMentionCommand(context, services))));
    }

    @Override
    public void registerEventListeners(Object eventBus, Services services) {
        ServerMessageEvents.ALLOW_CHAT_MESSAGE.register(this::shouldAllowChatMessage);
    }

    private boolean shouldAllowChatMessage(SignedMessage message, ServerPlayerEntity sender, net.minecraft.network.message.MessageType.Parameters params) {
        if (this.services == null || !isEnabled(this.services)) return true;

        MentionConfigHandler.Config mentionConfig = MentionConfigHandler.CONFIG;
        String rawMessage = message.getContent().getString();

        String everyoneMentionPlaceholder = mentionConfig.MENTION_SYMBOL.value + "everyone";
        Pattern everyonePattern = Pattern.compile(Pattern.quote(everyoneMentionPlaceholder), Pattern.CASE_INSENSITIVE);
        Matcher everyoneMatcher = everyonePattern.matcher(rawMessage);

        if (everyoneMatcher.find()) {
            return handleEveryoneMention(sender, rawMessage, mentionConfig, everyoneMatcher.group(0));
        } else {
            return handleIndividualMentions(sender, rawMessage, mentionConfig);
        }
    }


    private boolean handleEveryoneMention(ServerPlayerEntity sender, String rawMessage, MentionConfigHandler.Config mentionConfig, String matchedEveryoneMention) {
        if (!hasPermission(sender, PermissionsHandler.MENTION_EVERYONE_PERMISSION, PermissionsHandler.MENTION_EVERYONE_PERMISSION_LEVEL)) {
            platform.sendSystemMessage(sender, this.services.getLang().translate("mention.no_permission_everyone"));
            return false;
        }
        if (!canMentionEveryoneNow(sender, mentionConfig)) {
            platform.sendSystemMessage(sender, this.services.getLang().translate("mention.too_frequent_mention_everyone"));
            return false;
        }

        this.services.getDebugLogger().debugLog("Mention everyone detected in chat by " + platform.getPlayerName(sender));
        notifyEveryone(platform.getOnlinePlayers(), sender, rawMessage, false, mentionConfig, matchedEveryoneMention);
        markMentionEveryoneUsed(sender);
        return false;
    }

    private boolean handleIndividualMentions(ServerPlayerEntity sender, String rawMessage, MentionConfigHandler.Config mentionConfig) {
        String mentionSymbol = mentionConfig.MENTION_SYMBOL.value;
        List<ServerPlayerEntity> players = platform.getOnlinePlayers();

        Pattern allPlayersPattern = buildAllPlayersMentionPattern(players, mentionSymbol);
        Matcher mentionMatcher = allPlayersPattern.matcher(rawMessage);

        if (!mentionMatcher.find()) {
            return true;
        }
        mentionMatcher.reset();

        if (!hasPermission(sender, PermissionsHandler.MENTION_PLAYER_PERMISSION, PermissionsHandler.MENTION_PLAYER_PERMISSION_LEVEL)) {
            return true;
        }

        if (!canMentionIndividualNow(sender, mentionConfig)) {
            platform.sendSystemMessage(sender, this.services.getLang().translate("mention.too_frequent_mention_player"));
            return false;
        }

        boolean mentionedSomeone = false;
        while (mentionMatcher.find()) {
            String playerName = mentionMatcher.group(1);
            ServerPlayerEntity targetPlayer = platform.getPlayerByName(playerName);
            if (targetPlayer == null) continue;

            this.services.getDebugLogger().debugLog("Mention player detected in chat: " + platform.getPlayerName(targetPlayer) + " by " + platform.getPlayerName(sender));
            notifyPlayer(targetPlayer, sender, rawMessage, false, mentionConfig, mentionMatcher.group(0));
            mentionedSomeone = true;
        }

        if (mentionedSomeone) {
            markMentionIndividualUsed(sender);
        }
        return !mentionedSomeone;
    }

    private int executeMentionCommand(CommandContext<ServerCommandSource> context, Services services) {
        ServerCommandSource source = context.getSource();
        String message = StringArgumentType.getString(context, "message");
        List<ServerPlayerEntity> players = platform.getOnlinePlayers();
        boolean isConsole = source.getEntity() == null;
        ServerPlayerEntity sender = isConsole ? null : source.getPlayer();
        MentionConfigHandler.Config mentionConfig = MentionConfigHandler.CONFIG;

        String everyoneMentionPlaceholder = mentionConfig.MENTION_SYMBOL.value + "everyone";
        Pattern everyonePattern = Pattern.compile(Pattern.quote(everyoneMentionPlaceholder), Pattern.CASE_INSENSITIVE);
        Matcher everyoneMatcher = everyonePattern.matcher(message);

        if (everyoneMatcher.find()) {
            if (sender != null) {
                if (!hasPermission(sender, PermissionsHandler.MENTION_EVERYONE_PERMISSION, PermissionsHandler.MENTION_EVERYONE_PERMISSION_LEVEL)) {
                    platform.sendSystemMessage(sender, services.getLang().translate("mention.no_permission_everyone"));
                    return 0;
                }
                if (!canMentionEveryoneNow(sender, mentionConfig)) {
                    platform.sendSystemMessage(sender, services.getLang().translate("mention.too_frequent_mention_everyone"));
                    return 0;
                }
            }
            notifyEveryone(players, sender, message, isConsole, mentionConfig, everyoneMatcher.group(0));
            if (sender != null) {
                markMentionEveryoneUsed(sender);
            }
            platform.sendSuccess(source, platform.createLiteralComponent("Mentioned everyone successfully."), !isConsole);
            return 1;
        }

        int mentionedCount = 0;
        boolean containsAnyMention = false;
        {
            Pattern allPlayersPattern = buildAllPlayersMentionPattern(players, mentionConfig.MENTION_SYMBOL.value);
            Matcher m = allPlayersPattern.matcher(message);
            containsAnyMention = m.find();
        }
        if (containsAnyMention && sender != null) {
            if (!hasPermission(sender, PermissionsHandler.MENTION_PLAYER_PERMISSION, PermissionsHandler.MENTION_PLAYER_PERMISSION_LEVEL)) {
                platform.sendFailure(source, platform.createLiteralComponent("No permission to mention players."));
                return 0;
            }
            if (!canMentionIndividualNow(sender, mentionConfig)) {
                platform.sendFailure(source, platform.createLiteralComponent(services.getLang().translate("mention.too_frequent_mention_player").getString()));
                return 0;
            }
        }

        for (ServerPlayerEntity targetPlayer : players) {
            String playerMentionPlaceholder = mentionConfig.MENTION_SYMBOL.value + platform.getPlayerName(targetPlayer);
            Pattern playerMentionPattern = Pattern.compile(Pattern.quote(playerMentionPlaceholder), Pattern.CASE_INSENSITIVE);

            if (playerMentionPattern.matcher(message).find()) {
                if (sender != null) {
                    if (!hasPermission(sender, PermissionsHandler.MENTION_PLAYER_PERMISSION, PermissionsHandler.MENTION_PLAYER_PERMISSION_LEVEL)) continue;
                }

                notifyPlayer(targetPlayer, sender, message, isConsole, mentionConfig, playerMentionPlaceholder);
                mentionedCount++;
            }
        }

        if (mentionedCount > 0) {
            if (sender != null) {
                markMentionIndividualUsed(sender);
            }
            platform.sendSuccess(source, platform.createLiteralComponent("Mentioned " + mentionedCount + " player(s) successfully."), !isConsole);
        } else {
            platform.sendFailure(source, platform.createLiteralComponent("No valid mentions found in the message."));
        }
        return mentionedCount > 0 ? 1 : 0;
    }

    private void notifyEveryone(List<ServerPlayerEntity> players, ServerPlayerEntity sender, String originalMessage, boolean isConsole, MentionConfigHandler.Config config, String matchedEveryoneMention) {
        String senderName = isConsole || sender == null ? "Console" : platform.getPlayerName(sender);
        String chatFormat = config.EVERYONE_MENTION_MESSAGE.value;
        String titleFormat = config.EVERYONE_TITLE_MESSAGE.value;

        int mentionIndex = originalMessage.indexOf(matchedEveryoneMention);
        String content = "";
        if (mentionIndex >= 0) {
            int contentStartIndex = mentionIndex + matchedEveryoneMention.length();
            if (contentStartIndex < originalMessage.length()) {
                content = originalMessage.substring(contentStartIndex).trim();
            }
        }

        this.services.getDebugLogger().debugLog("MENTION DEBUG - Original message: '" + originalMessage + "'");
        this.services.getDebugLogger().debugLog("MENTION DEBUG - Matched mention: '" + matchedEveryoneMention + "'");
        this.services.getDebugLogger().debugLog("MENTION DEBUG - Extracted content: '" + content + "'");

        String chatMessageText = String.format(chatFormat, senderName);
        String titleMessageText = String.format(titleFormat, senderName);

        for (ServerPlayerEntity targetPlayer : players) {
            sendMentionNotification(targetPlayer, chatMessageText, titleMessageText, content, this.services);
        }
    }

    private void notifyPlayer(ServerPlayerEntity targetPlayer, ServerPlayerEntity sender, String originalMessage, boolean isConsole, MentionConfigHandler.Config config, String matchedPlayerMention) {
        String senderName = isConsole || sender == null ? "Console" : platform.getPlayerName(sender);
        String chatFormat = config.INDIVIDUAL_MENTION_MESSAGE.value;
        String titleFormat = config.INDIVIDUAL_TITLE_MESSAGE.value;

        int mentionIndex = originalMessage.indexOf(matchedPlayerMention);
        String content = "";
        if (mentionIndex >= 0) {
            int contentStartIndex = mentionIndex + matchedPlayerMention.length();
            if (contentStartIndex < originalMessage.length()) {
                content = originalMessage.substring(contentStartIndex).trim();
            }
        }

        this.services.getDebugLogger().debugLog("MENTION DEBUG - Original message: '" + originalMessage + "'");
        this.services.getDebugLogger().debugLog("MENTION DEBUG - Matched mention: '" + matchedPlayerMention + "'");
        this.services.getDebugLogger().debugLog("MENTION DEBUG - Extracted content: '" + content + "'");

        String chatMessageText = String.format(chatFormat, senderName);
        String titleMessageText = String.format(titleFormat, senderName);

        sendMentionNotification(targetPlayer, chatMessageText, titleMessageText, content, this.services);
    }

    private void sendMentionNotification(ServerPlayerEntity targetPlayer, String chatMessage, String titleMessage, String contentMessage, Services services) {
        MentionConfigHandler.Config config = MentionConfigHandler.CONFIG;

        IPlayer iTarget = services.getPlatformAdapter().wrapPlayer(targetPlayer);

        long timestamp = System.currentTimeMillis();
        this.services.getDebugLogger().debugLog("NOTIFY DEBUG [" + timestamp + "] - Target: " + platform.getPlayerName(targetPlayer));
        this.services.getDebugLogger().debugLog("NOTIFY DEBUG [" + timestamp + "] - Chat message format: '" + chatMessage + "'");
        this.services.getDebugLogger().debugLog("NOTIFY DEBUG [" + timestamp + "] - Content message: '" + contentMessage + "'");

        if (config.enableChatNotification.value) {
            IComponent finalChatMessage = services.getMessageParser().parseMessage(chatMessage, iTarget);
            if (contentMessage != null && !contentMessage.isEmpty()) {
                IComponent newlineComponent = services.getMessageParser().parseMessage("\n- " + contentMessage, iTarget);
                finalChatMessage = finalChatMessage.append(newlineComponent);
            }
            this.services.getDebugLogger().debugLog("NOTIFY DEBUG [" + timestamp + "] - Sending chat notification to: " + platform.getPlayerName(targetPlayer));
            platform.sendSystemMessage(targetPlayer, finalChatMessage.getOriginalText());
        }

        if (config.enableTitleNotification.value) {
            IComponent parsedTitleMessage = services.getMessageParser().parseMessage(titleMessage, iTarget);
            IComponent parsedSubtitleMessage = platform.createEmptyComponent();
            this.services.getDebugLogger().debugLog("NOTIFY DEBUG [" + timestamp + "] - Sending title notification (no subtitle content) to: " + platform.getPlayerName(targetPlayer));
            platform.sendTitle(targetPlayer, parsedTitleMessage.getOriginalText(), parsedSubtitleMessage.getOriginalText());
        }

        platform.playSound(targetPlayer, "minecraft:entity.experience_orb.pickup", net.minecraft.sound.SoundCategory.PLAYERS, 1.0f, 1.0f);
        this.services.getDebugLogger().debugLog("NOTIFY DEBUG [" + timestamp + "] - Notification complete for: " + platform.getPlayerName(targetPlayer));
    }

    private boolean hasPermission(ServerPlayerEntity player, String permissionNode, int permissionLevel) {
        return platform.hasPermission(player, permissionNode, permissionLevel);
    }

    private Pattern buildAllPlayersMentionPattern(List<ServerPlayerEntity> players, String mentionSymbol) {
        if (players.isEmpty()) {
            return Pattern.compile("(?!x)x");
        }
        String allPlayerNames = players.stream()
                .map(p -> Pattern.quote(platform.getPlayerName(p)))
                .collect(Collectors.joining("|"));

        return Pattern.compile(Pattern.quote(mentionSymbol) + "(" + allPlayerNames + ")", Pattern.CASE_INSENSITIVE);
    }

    private boolean canMentionEveryoneNow(ServerPlayerEntity sender, MentionConfigHandler.Config config) {
        if (sender == null) return true;
        if (sender.hasPermissionLevel(2)) return true;
        int rateLimit = config.EVERYONE_MENTION_RATE_LIMIT.get();
        if (rateLimit <= 0) return true;
        long currentTime = System.currentTimeMillis();
        Long last = lastEveryoneMentionBySender.get(sender.getUuid());
        if (last != null && currentTime - last < rateLimit * 1000L) return false;
        return true;
    }

    private void markMentionEveryoneUsed(ServerPlayerEntity sender) {
        if (sender == null) return;
        lastEveryoneMentionBySender.put(sender.getUuid(), System.currentTimeMillis());
    }

    private boolean canMentionIndividualNow(ServerPlayerEntity sender, MentionConfigHandler.Config config) {
        if (sender == null) return true;
        if (sender.hasPermissionLevel(2)) return true;
        int rateLimit = config.INDIVIDUAL_MENTION_RATE_LIMIT.get();
        if (rateLimit <= 0) return true;
        long currentTime = System.currentTimeMillis();
        Long last = lastIndividualMentionBySender.get(sender.getUuid());
        if (last != null && currentTime - last < rateLimit * 1000L) return false;
        return true;
    }

    private void markMentionIndividualUsed(ServerPlayerEntity sender) {
        if (sender == null) return;
        lastIndividualMentionBySender.put(sender.getUuid(), System.currentTimeMillis());
    }
}

