package eu.avalanche7.paradigm.modules;

import eu.avalanche7.paradigm.configs.MentionConfigHandler;
import eu.avalanche7.paradigm.core.ParadigmModule;
import eu.avalanche7.paradigm.core.Services;
import eu.avalanche7.paradigm.platform.Interfaces.*;
import eu.avalanche7.paradigm.utils.PermissionsHandler;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;
import java.util.LinkedHashSet;
import java.util.Set;

public class Mentions implements ParadigmModule {

    private static final String NAME = "Mentions";
    private final Map<String, Long> lastIndividualMentionBySender = new HashMap<>();
    private final Map<String, Long> lastEveryoneMentionBySender = new HashMap<>();
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
    public void registerCommands(Object dispatcher, Object registryAccess, Services services) {
        ICommandBuilder cmd = platform.createCommandBuilder()
                .literal("mention")
                .requires(source -> true)
                .then(platform.createCommandBuilder()
                        .argument("message", ICommandBuilder.ArgumentType.GREEDY_STRING)
                        .executes(ctx -> executeMentionCommand(ctx, services)));

        platform.registerCommand(cmd);
    }

    @Override
    public void registerEventListeners(Object eventBus, Services services) {
        IEventSystem events = services.getPlatformAdapter().getEventSystem();
        if (events != null) {
            events.onPlayerChat(event -> {
                handleChatMessage(event.getPlayer(), event.getMessage(), services);
             });
         }
     }

    public boolean handleChatMessage(IPlayer sender, String rawMessage, Services services) {
        if (this.services == null || !isEnabled(this.services)) return true;
        if (rawMessage == null) return true;

        MentionConfigHandler.Config mentionConfig = MentionConfigHandler.getConfig();

        String everyoneMentionPlaceholder = mentionConfig.MENTION_SYMBOL.value + "everyone";
        Pattern everyonePattern = Pattern.compile(Pattern.quote(everyoneMentionPlaceholder), Pattern.CASE_INSENSITIVE);
        Matcher everyoneMatcher = everyonePattern.matcher(rawMessage);

        if (everyoneMatcher.find()) {
            handleEveryoneMention(sender, rawMessage, mentionConfig, everyoneMatcher.group(0), services);
            return true;
        }

        handleIndividualMentions(sender, rawMessage, mentionConfig, services);
        return true;
    }

    private void handleEveryoneMention(IPlayer sender, String rawMessage, MentionConfigHandler.Config mentionConfig, String matchedEveryoneMention, Services services) {
        if (sender != null) {
            if (!platform.hasPermission(sender, PermissionsHandler.MENTION_EVERYONE_PERMISSION, PermissionsHandler.MENTION_EVERYONE_PERMISSION_LEVEL)) {
                platform.sendSystemMessage(sender, services.getLang().translate("mention.no_permission_everyone"));
                return;
            }
            if (!canMentionEveryoneNow(sender, mentionConfig)) {
                platform.sendSystemMessage(sender, services.getLang().translate("mention.too_frequent_mention_everyone"));
                return;
            }
        }

        services.getDebugLogger().debugLog("Mention everyone detected in chat by " + (sender != null ? platform.getPlayerName(sender) : "Console"));
        notifyEveryone(platform.getOnlinePlayers(), sender, rawMessage, sender == null, mentionConfig, matchedEveryoneMention);

        if (sender != null) {
             markMentionEveryoneUsed(sender);
        }
    }

    private void handleIndividualMentions(IPlayer sender, String rawMessage, MentionConfigHandler.Config mentionConfig, Services services) {
        String mentionSymbol = mentionConfig.MENTION_SYMBOL.value;
        List<IPlayer> players = platform.getOnlinePlayers();

        Pattern allPlayersPattern = buildAllPlayersMentionPattern(players, mentionSymbol);
        Matcher mentionMatcher = allPlayersPattern.matcher(rawMessage);

        if (!mentionMatcher.find()) {
            return;
        }
        mentionMatcher.reset();

        if (sender != null) {
            if (!platform.hasPermission(sender, PermissionsHandler.MENTION_PLAYER_PERMISSION, PermissionsHandler.MENTION_PLAYER_PERMISSION_LEVEL)) {
                return;
            }
            if (!canMentionIndividualNow(sender, mentionConfig)) {
                platform.sendSystemMessage(sender, services.getLang().translate("mention.too_frequent_mention_player"));
                return;
            }
        }

        boolean mentionedSomeone = false;
         while (mentionMatcher.find()) {
             String playerName = mentionMatcher.group(1);
             IPlayer targetPlayer = platform.getPlayerByName(playerName);
             if (targetPlayer == null) continue;
            if (sender != null && targetPlayer.getUUID() != null && targetPlayer.getUUID().equals(sender.getUUID())) {
                continue;
            }

            services.getDebugLogger().debugLog("Mention player detected in chat: " + platform.getPlayerName(targetPlayer) + " by " + (sender != null ? platform.getPlayerName(sender) : "Console"));
            notifyPlayer(targetPlayer, sender, rawMessage, sender == null, mentionConfig, mentionMatcher.group(0));
            mentionedSomeone = true;
        }

        if (mentionedSomeone && sender != null) {
            markMentionIndividualUsed(sender);
        }
    }

    private int executeMentionCommand(ICommandContext context, Services services) {
        ICommandSource source = context.getSource();
        IPlayer sender = source != null ? source.getPlayer() : null;

        String message = context.getStringArgument("message");
        if (message == null) message = "";
        message = message.trim();

        MentionConfigHandler.Config mentionConfig = MentionConfigHandler.getConfig();
        List<IPlayer> players = platform.getOnlinePlayers();
        boolean isConsole = (sender == null);

        String everyoneMentionPlaceholder = mentionConfig.MENTION_SYMBOL.value + "everyone";
        Pattern everyonePattern = Pattern.compile(Pattern.quote(everyoneMentionPlaceholder), Pattern.CASE_INSENSITIVE);
        Matcher everyoneMatcher = everyonePattern.matcher(message);

        if (everyoneMatcher.find()) {
            if (!isConsole) {
                if (!platform.hasPermission(sender, PermissionsHandler.MENTION_EVERYONE_PERMISSION, PermissionsHandler.MENTION_EVERYONE_PERMISSION_LEVEL)) {
                    platform.sendSystemMessage(sender, services.getLang().translate("mention.no_permission_everyone"));
                    return 0;
                }
                if (!canMentionEveryoneNow(sender, mentionConfig)) {
                    platform.sendSystemMessage(sender, services.getLang().translate("mention.too_frequent_mention_everyone"));
                    return 0;
                }
            }

            notifyEveryone(players, sender, message, isConsole, mentionConfig, everyoneMatcher.group(0));

            if (!isConsole) {
                markMentionEveryoneUsed(sender);
            }

            platform.sendSuccess(source, platform.createLiteralComponent("Mentioned everyone successfully."), !isConsole);
            return 1;
        }
        Pattern allPlayersPattern = buildAllPlayersMentionPattern(players, mentionConfig.MENTION_SYMBOL.value);
        Matcher mentionMatcher = allPlayersPattern.matcher(message);
        boolean containsAnyMention = mentionMatcher.find();
        mentionMatcher.reset();

        if (containsAnyMention && !isConsole) {
            if (!platform.hasPermission(sender, PermissionsHandler.MENTION_PLAYER_PERMISSION, PermissionsHandler.MENTION_PLAYER_PERMISSION_LEVEL)) {
                platform.sendFailure(source, platform.createLiteralComponent("No permission to mention players."));
                return 0;
            }
            if (!canMentionIndividualNow(sender, mentionConfig)) {
                platform.sendFailure(source, services.getLang().translate("mention.too_frequent_mention_player"));
                return 0;
            }
        }

        boolean mentionedSomeone = false;
        Set<String> mentionedNames = new LinkedHashSet<>();
        while (mentionMatcher.find()) {
            String playerName = mentionMatcher.group(1);
            IPlayer targetPlayer = platform.getPlayerByName(playerName);
            if (targetPlayer == null) continue;

            if (!isConsole && sender != null && targetPlayer.getUUID() != null && targetPlayer.getUUID().equals(sender.getUUID())) {
                continue;
            }

            notifyPlayer(targetPlayer, sender, message, isConsole, mentionConfig, mentionMatcher.group(0));
            mentionedSomeone = true;
            mentionedNames.add(platform.getPlayerName(targetPlayer));
        }

        if (mentionedSomeone) {
            if (!isConsole) {
                markMentionIndividualUsed(sender);
            }

            platform.sendSuccess(source, platform.createLiteralComponent("Mentioned " + mentionedNames.size() + " player(s) successfully."), !isConsole);
            return 1;
        }

        platform.sendFailure(source, platform.createLiteralComponent("No valid mentions found in the message."));
        return 0;
    }

    private void notifyEveryone(List<IPlayer> players, IPlayer sender, String originalMessage, boolean isConsole, MentionConfigHandler.Config config, String matchedEveryoneMention) {
        String senderName = isConsole || sender == null ? "Console" : platform.getPlayerName(sender);
        String chatFormat = config.EVERYONE_MENTION_MESSAGE.value;
        String titleFormat = config.EVERYONE_TITLE_MESSAGE.value;

        String content = extractContentAfterToken(originalMessage, matchedEveryoneMention);

        String chatMessageText = String.format(chatFormat, senderName);
        String titleMessageText = String.format(titleFormat, senderName);

        for (IPlayer targetPlayer : players) {
            sendMentionNotification(targetPlayer, chatMessageText, titleMessageText, content, this.services);
        }
    }

    private void notifyPlayer(IPlayer targetPlayer, IPlayer sender, String originalMessage, boolean isConsole, MentionConfigHandler.Config config, String matchedPlayerMention) {
        String senderName = isConsole || sender == null ? "Console" : platform.getPlayerName(sender);
        String chatFormat = config.INDIVIDUAL_MENTION_MESSAGE.value;
        String titleFormat = config.INDIVIDUAL_TITLE_MESSAGE.value;

        String content = extractContentAfterToken(originalMessage, matchedPlayerMention);

        String chatMessageText = String.format(chatFormat, senderName);
        String titleMessageText = String.format(titleFormat, senderName);

        sendMentionNotification(targetPlayer, chatMessageText, titleMessageText, content, this.services);
    }

    private void sendMentionNotification(IPlayer targetPlayer, String chatMessage, String titleMessage, String contentMessage, Services services) {
        MentionConfigHandler.Config config = MentionConfigHandler.getConfig();

        if (config.enableChatNotification.value) {
            IComponent finalChatMessage = services.getMessageParser().parseMessage(chatMessage, targetPlayer);
            if (contentMessage != null && !contentMessage.isEmpty()) {
                String prefix = config.CHAT_APPEND_PREFIX.value;
                IComponent dash = services.getPlatformAdapter().createComponentFromLiteral("\n" + prefix);
                IComponent contentComp = services.getMessageParser().parseMessage(contentMessage, targetPlayer);
                finalChatMessage = finalChatMessage.append(dash).append(contentComp);
            }
            platform.sendSystemMessage(targetPlayer, finalChatMessage);
        }

        if (config.enableTitleNotification.value) {
            IComponent parsedTitleMessage = services.getMessageParser().parseMessage(titleMessage, targetPlayer);
            IComponent parsedSubtitleMessage = platform.createEmptyComponent();
            boolean willShowSubtitle = config.enableSubtitleNotification.value && contentMessage != null && !contentMessage.isEmpty();
            if (willShowSubtitle) {
                parsedSubtitleMessage = services.getMessageParser().parseMessage(contentMessage, targetPlayer);
            }
            platform.sendTitle(targetPlayer, parsedTitleMessage, parsedSubtitleMessage);
        }

        platform.playSound(targetPlayer, "minecraft:entity.experience_orb.pickup", "players", 1.0f, 1.0f);
    }

    private Pattern buildAllPlayersMentionPattern(List<IPlayer> players, String mentionSymbol) {
        if (players.isEmpty()) {
            return Pattern.compile("(?!x)x");
        }
        String allPlayerNames = players.stream()
                .map(p -> Pattern.quote(platform.getPlayerName(p)))
                .collect(Collectors.joining("|"));

        return Pattern.compile(Pattern.quote(mentionSymbol) + "(" + allPlayerNames + ")", Pattern.CASE_INSENSITIVE);
    }

    private boolean canMentionEveryoneNow(IPlayer sender, MentionConfigHandler.Config config) {
        if (sender == null) return true;
        if (platform.hasPermission(sender, PermissionsHandler.MENTION_EVERYONE_PERMISSION, PermissionsHandler.MENTION_EVERYONE_PERMISSION_LEVEL)) return true;

        int rateLimit = config.EVERYONE_MENTION_RATE_LIMIT.get();
        if (rateLimit <= 0) return true;

        long currentTime = System.currentTimeMillis();
        Long last = lastEveryoneMentionBySender.get(sender.getUUID());
        return last == null || currentTime - last >= rateLimit * 1000L;
    }

    private void markMentionEveryoneUsed(IPlayer sender) {
        if (sender == null) return;
        lastEveryoneMentionBySender.put(sender.getUUID(), System.currentTimeMillis());
     }

    private boolean canMentionIndividualNow(IPlayer sender, MentionConfigHandler.Config config) {
        if (sender == null) return true;
        if (platform.hasPermission(sender, PermissionsHandler.MENTION_PLAYER_PERMISSION, PermissionsHandler.MENTION_PLAYER_PERMISSION_LEVEL)) return true;

        int rateLimit = config.INDIVIDUAL_MENTION_RATE_LIMIT.get();
        if (rateLimit <= 0) return true;

        long currentTime = System.currentTimeMillis();
        Long last = lastIndividualMentionBySender.get(sender.getUUID());
        return last == null || currentTime - last >= rateLimit * 1000L;
    }

    private void markMentionIndividualUsed(IPlayer sender) {
        if (sender == null) return;
        lastIndividualMentionBySender.put(sender.getUUID(), System.currentTimeMillis());
    }

    private String extractContentAfterToken(String originalMessage, String matchedToken) {
        if (originalMessage == null || matchedToken == null) return "";
        int idx = originalMessage.indexOf(matchedToken);
        if (idx < 0) return "";
        int end = idx + matchedToken.length();
        if (end >= originalMessage.length()) return "";
        return originalMessage.substring(end).trim();
    }
 }
