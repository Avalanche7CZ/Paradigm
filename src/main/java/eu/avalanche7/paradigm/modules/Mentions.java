package eu.avalanche7.paradigm.modules;

import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.context.CommandContext;
import eu.avalanche7.paradigm.core.ParadigmModule;
import eu.avalanche7.paradigm.core.Services;
import eu.avalanche7.paradigm.configs.MentionConfigHandler;
import eu.avalanche7.paradigm.platform.Interfaces.IComponent;
import eu.avalanche7.paradigm.platform.Interfaces.IPlatformAdapter;
import eu.avalanche7.paradigm.platform.Interfaces.IPlayer;
import eu.avalanche7.paradigm.platform.Interfaces.IEventSystem;
import eu.avalanche7.paradigm.platform.Interfaces.ICommandSource;
import eu.avalanche7.paradigm.utils.PermissionsHandler;
import net.minecraft.commands.Commands;
import net.minecraftforge.event.server.ServerStartingEvent;
import net.minecraftforge.event.server.ServerStoppingEvent;
import net.minecraftforge.eventbus.api.IEventBus;
import net.minecraftforge.fml.event.lifecycle.FMLCommonSetupEvent;

import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

public class Mentions implements ParadigmModule, IEventSystem.ChatEventListener {

    private static final String NAME = "Mentions";
    // Per-sender cooldown tracking
    private final HashMap<String, Long> lastIndividualMentionBySender = new HashMap<>();
    private final HashMap<String, Long> lastEveryoneMentionBySender = new HashMap<>();
    private Services services;
    private IPlatformAdapter platform;

    @Override
    public String getName() {
        return NAME;
    }

    @Override
    public boolean isEnabled(Services services) {
        return services.getMainConfig().mentionsEnable.get();
    }

    @Override
    public void onLoad(FMLCommonSetupEvent event, Services services, IEventBus modEventBus) {
        this.services = services;
        this.platform = services.getPlatformAdapter();
        services.getDebugLogger().debugLog(NAME + " module loaded.");
    }

    @Override
    public void onServerStarting(ServerStartingEvent event, Services services) {}

    @Override
    public void onEnable(Services services) {}

    @Override
    public void onDisable(Services services) {}

    @Override
    public void onServerStopping(ServerStoppingEvent event, Services services) {}

    @Override
    public void registerCommands(CommandDispatcher<?> dispatcher, Services services) {
        CommandDispatcher<net.minecraft.commands.CommandSourceStack> dispatcherCS = (CommandDispatcher<net.minecraft.commands.CommandSourceStack>) dispatcher;
        dispatcherCS.register(Commands.literal("mention")
                .requires(source -> source.hasPermission(0))
                .then(Commands.argument("message", StringArgumentType.greedyString())
                        .executes(this::executeMentionCommand)));
    }

    @Override
    public void registerEventListeners(IEventBus forgeEventBus, Services services) {
        platform.getEventSystem().registerChatListener(this);
    }

    @Override
    public void onPlayerChat(IEventSystem.ChatEvent event) {
        if (this.services == null || !isEnabled(this.services)) return;

        String rawMessage = event.getMessage();
        IPlayer sender = event.getPlayer();
        String mentionSymbol = MentionConfigHandler.CONFIG.MENTION_SYMBOL.get();
        String everyoneMentionPlaceholder = mentionSymbol + "everyone";
        Pattern everyonePattern = Pattern.compile(Pattern.quote(everyoneMentionPlaceholder), Pattern.CASE_INSENSITIVE);

        Matcher everyoneMatcher = everyonePattern.matcher(rawMessage);
        if (everyoneMatcher.find()) {
            if (!platform.hasPermission(sender, PermissionsHandler.MENTION_EVERYONE_PERMISSION, PermissionsHandler.MENTION_EVERYONE_PERMISSION_LEVEL)) {
                platform.sendSystemMessage(sender, services.getLang().translate("mention.no_permission_everyone"));
                event.setCancelled(true);
                return;
            }
            if (!canMentionEveryoneNow(sender)) {
                platform.sendSystemMessage(sender, services.getLang().translate("mention.too_frequent_mention_everyone"));
                event.setCancelled(true);
                return;
            }
            event.setCancelled(true);
            notifyEveryone(platform.getOnlinePlayers(), sender, rawMessage, false, everyoneMatcher.group(0));
            markMentionEveryoneUsed(sender);
            sendSenderFeedbackEveryone(sender, rawMessage, everyoneMatcher.group(0));
            return;
        }

        // Individual mentions
        Pattern allPlayersPattern = buildAllPlayersMentionPattern(platform.getOnlinePlayers(), mentionSymbol);
        Matcher mentionMatcher = allPlayersPattern.matcher(rawMessage);
        if (!mentionMatcher.find()) return; // no mentions at all
        mentionMatcher.reset();

        if (!platform.hasPermission(sender, PermissionsHandler.MENTION_PLAYER_PERMISSION, PermissionsHandler.MENTION_PLAYER_PERMISSION_LEVEL)) return;
        if (!canMentionIndividualNow(sender)) {
            platform.sendSystemMessage(sender, services.getLang().translate("mention.too_frequent_mention_player"));
            event.setCancelled(true);
            return;
        }

        IComponent finalMessageComponent = platform.createLiteralComponent("");
        int lastEnd = 0;
        boolean mentionedSomeone = false;
        Set<String> mentionedNames = new LinkedHashSet<>();
        while (mentionMatcher.find()) {
            String playerName = mentionMatcher.group(1);
            IPlayer targetPlayer = platform.getPlayerByName(playerName);
            if (targetPlayer == null) continue;
            mentionedSomeone = true;
            mentionedNames.add(targetPlayer.getName());
            finalMessageComponent.append(platform.createLiteralComponent(rawMessage.substring(lastEnd, mentionMatcher.start())));
            notifyPlayer(targetPlayer, sender, rawMessage, false, mentionMatcher.group(0));
            finalMessageComponent.append(platform.getPlayerDisplayName(targetPlayer));
            lastEnd = mentionMatcher.end();
        }
        if (mentionedSomeone) {
            event.setCancelled(true);
            finalMessageComponent.append(platform.createLiteralComponent(rawMessage.substring(lastEnd)));
            IComponent finalMessage = platform.createTranslatableComponent("chat.type.text", platform.getPlayerDisplayName(sender), finalMessageComponent);
            platform.broadcastChatMessage(finalMessage);
            markMentionIndividualUsed(sender);
            sendSenderFeedbackPlayers(sender, rawMessage, mentionedNames, allPlayersPattern);
        }
    }

    private int executeMentionCommand(CommandContext<?> context) {
        ICommandSource source = platform.wrapCommandSource(context.getSource());
        String message = StringArgumentType.getString(context, "message");
        boolean isConsole = source.isConsole();
        IPlayer sender = source.getPlayer();
        String everyoneMentionPlaceholder = MentionConfigHandler.CONFIG.MENTION_SYMBOL.get() + "everyone";

        Matcher everyoneMatcher = Pattern.compile(Pattern.quote(everyoneMentionPlaceholder), Pattern.CASE_INSENSITIVE).matcher(message);
        if (everyoneMatcher.find()) {
            if (sender != null) {
                if (!platform.hasPermission(sender, PermissionsHandler.MENTION_EVERYONE_PERMISSION, PermissionsHandler.MENTION_EVERYONE_PERMISSION_LEVEL)) {
                    platform.sendSystemMessage(sender, services.getLang().translate("mention.no_permission_everyone"));
                    return 0;
                }
                if (!canMentionEveryoneNow(sender)) {
                    platform.sendSystemMessage(sender, services.getLang().translate("mention.too_frequent_mention_everyone"));
                    return 0;
                }
            }
            notifyEveryone(platform.getOnlinePlayers(), sender, message, isConsole, everyoneMatcher.group(0));
            if (sender != null) {
                markMentionEveryoneUsed(sender);
                sendSenderFeedbackEveryone(sender, message, everyoneMatcher.group(0));
            }
            platform.sendSuccess(source, platform.createLiteralComponent("Mentioned everyone successfully."), !isConsole);
            return 1;
        }

        List<IPlayer> players = platform.getOnlinePlayers();
        Pattern allPlayersPattern = buildAllPlayersMentionPattern(players, MentionConfigHandler.CONFIG.MENTION_SYMBOL.get());
        Matcher m = allPlayersPattern.matcher(message);
        boolean containsAnyMention = m.find();
        if (containsAnyMention && sender != null) {
            if (!platform.hasPermission(sender, PermissionsHandler.MENTION_PLAYER_PERMISSION, PermissionsHandler.MENTION_PLAYER_PERMISSION_LEVEL)) {
                platform.sendFailure(source, platform.createLiteralComponent("No permission to mention players."));
                return 0;
            }
            if (!canMentionIndividualNow(sender)) {
                platform.sendFailure(source, services.getLang().translate("mention.too_frequent_mention_player"));
                return 0;
            }
        }

        int mentionedCount = 0;
        Set<String> mentionedNames = new LinkedHashSet<>();
        for (IPlayer targetPlayer : players) {
            String playerMentionPlaceholder = MentionConfigHandler.CONFIG.MENTION_SYMBOL.get() + targetPlayer.getName();
            Pattern playerMentionPattern = Pattern.compile(Pattern.quote(playerMentionPlaceholder), Pattern.CASE_INSENSITIVE);
            if (playerMentionPattern.matcher(message).find()) {
                notifyPlayer(targetPlayer, sender, message, isConsole, playerMentionPlaceholder);
                mentionedCount++;
                mentionedNames.add(targetPlayer.getName());
            }
        }

        if (mentionedCount > 0) {
            if (sender != null) {
                markMentionIndividualUsed(sender);
                sendSenderFeedbackPlayers(sender, message, mentionedNames, allPlayersPattern);
            }
            platform.sendSuccess(source, platform.createLiteralComponent("Mentioned " + mentionedCount + " player(s) successfully."), !isConsole);
        } else {
            platform.sendFailure(source, platform.createLiteralComponent("No valid mentions found in the message."));
        }
        return mentionedCount > 0 ? 1 : 0;
    }

    private void notifyEveryone(List<IPlayer> players, IPlayer sender, String originalMessage, boolean isConsole, String matchedEveryoneMention) {
        String senderName = isConsole || sender == null ? "Console" : sender.getName();
        String chatFormat = MentionConfigHandler.CONFIG.EVERYONE_MENTION_MESSAGE.get();
        String titleFormat = MentionConfigHandler.CONFIG.EVERYONE_TITLE_MESSAGE.get();
        String content = extractContentAfterToken(originalMessage, matchedEveryoneMention);
        String chatMessageText = String.format(chatFormat, senderName);
        String titleMessageText = String.format(titleFormat, senderName);
        for (IPlayer targetPlayer : players) {
            sendMentionNotification(targetPlayer, chatMessageText, titleMessageText, content);
        }
    }

    private void notifyPlayer(IPlayer targetPlayer, IPlayer sender, String originalMessage, boolean isConsole, String matchedPlayerMention) {
        String senderName = isConsole || sender == null ? "Console" : sender.getName();
        String chatFormat = MentionConfigHandler.CONFIG.INDIVIDUAL_MENTION_MESSAGE.get();
        String titleFormat = MentionConfigHandler.CONFIG.INDIVIDUAL_TITLE_MESSAGE.get();
        String content = extractContentAfterToken(originalMessage, matchedPlayerMention);
        String chatMessageText = String.format(chatFormat, senderName);
        String titleMessageText = String.format(titleFormat, senderName);
        sendMentionNotification(targetPlayer, chatMessageText, titleMessageText, content);
    }

    private void sendMentionNotification(IPlayer targetPlayer, String chatMessage, String titleMessage, String contentMessage) {
        IComponent finalChatMessage;
        boolean enableChat = MentionConfigHandler.CONFIG.enableChatNotification.get();
        boolean enableTitle = MentionConfigHandler.CONFIG.enableTitleNotification.get();
        boolean enableSubtitle = MentionConfigHandler.CONFIG.enableSubtitleNotification.get();
        if (enableChat) {
            IComponent mainComponent = services.getMessageParser().parseMessage(chatMessage, targetPlayer);
            if (contentMessage != null && !contentMessage.isEmpty()) {
                String prefix = MentionConfigHandler.CONFIG.CHAT_APPEND_PREFIX.get();
                IComponent contentComponent = services.getMessageParser().parseMessage(contentMessage, targetPlayer);
                mainComponent.append(platform.createLiteralComponent("\n" + prefix)).append(contentComponent);
            }
            finalChatMessage = mainComponent;
            platform.sendSystemMessage(targetPlayer, finalChatMessage);
        }
        if (enableTitle) {
            IComponent parsedTitleMessage = services.getMessageParser().parseMessage(titleMessage, targetPlayer);
            IComponent parsedSubtitleMessage = platform.createLiteralComponent("");
            if (enableSubtitle && contentMessage != null && !contentMessage.isEmpty()) {
                parsedSubtitleMessage = services.getMessageParser().parseMessage(contentMessage, targetPlayer);
            }
            platform.sendTitle(targetPlayer, parsedTitleMessage, parsedSubtitleMessage);
        }
        platform.playSound(targetPlayer, "minecraft:entity.player.levelup", IPlatformAdapter.SoundCategory.PLAYERS, 1.0F, 1.0F);
    }

    private Pattern buildAllPlayersMentionPattern(List<IPlayer> players, String mentionSymbol) {
        if (players.isEmpty()) {
            return Pattern.compile("a^");
        }
        String allPlayerNames = players.stream()
                .map(IPlayer::getName)
                .map(Pattern::quote)
                .collect(Collectors.joining("|"));
        return Pattern.compile(Pattern.quote(mentionSymbol) + "(" + allPlayerNames + ")", Pattern.CASE_INSENSITIVE);
    }

    private boolean canMentionEveryoneNow(IPlayer sender) {
        if (sender == null) return true;
        if (platform.hasPermission(sender, "minecraft.command.op", 2)) return true;
        int rateLimit = MentionConfigHandler.CONFIG.EVERYONE_MENTION_RATE_LIMIT.get();
        if (rateLimit <= 0) return true;
        long currentTime = System.currentTimeMillis();
        String key = sender.getUUID();
        Long last = lastEveryoneMentionBySender.get(key);
        return !(last != null && currentTime - last < rateLimit * 1000L);
    }

    private void markMentionEveryoneUsed(IPlayer sender) {
        if (sender == null) return;
        lastEveryoneMentionBySender.put(sender.getUUID(), System.currentTimeMillis());
    }

    private boolean canMentionIndividualNow(IPlayer sender) {
        if (sender == null) return true;
        if (platform.hasPermission(sender, "minecraft.command.op", 2)) return true;
        int rateLimit = MentionConfigHandler.CONFIG.INDIVIDUAL_MENTION_RATE_LIMIT.get();
        if (rateLimit <= 0) return true;
        long currentTime = System.currentTimeMillis();
        String key = sender.getUUID();
        Long last = lastIndividualMentionBySender.get(key);
        return !(last != null && currentTime - last < rateLimit * 1000L);
    }

    private void markMentionIndividualUsed(IPlayer sender) {
        if (sender == null) return;
        lastIndividualMentionBySender.put(sender.getUUID(), System.currentTimeMillis());
    }

    private void sendSenderFeedbackEveryone(IPlayer sender, String fullMessage, String matchedToken) {
        if (sender == null) return;
        String base = MentionConfigHandler.CONFIG.SENDER_FEEDBACK_EVERYONE_MESSAGE.get();
        IComponent feedback = services.getMessageParser().parseMessage(base, sender);
        String appended = extractContentAfterToken(fullMessage, matchedToken);
        if (!appended.isEmpty()) {
            String prefix = MentionConfigHandler.CONFIG.CHAT_APPEND_PREFIX.get();
            IComponent nl = platform.createLiteralComponent("\n" + prefix);
            IComponent appendedComp = services.getMessageParser().parseMessage(appended, sender);
            feedback = feedback.append(nl).append(appendedComp);
        }
        platform.sendSystemMessage(sender, feedback);
    }

    private void sendSenderFeedbackPlayers(IPlayer sender, String fullMessage, Set<String> mentionedNames, Pattern firstMentionPattern) {
        if (sender == null || mentionedNames.isEmpty()) return;
        String list = String.join(", ", mentionedNames);
        String formatted = String.format(MentionConfigHandler.CONFIG.SENDER_FEEDBACK_PLAYER_MESSAGE.get(), list);
        IComponent feedback = services.getMessageParser().parseMessage(formatted, sender);
        String appended = extractContentAfterFirstMatch(fullMessage, firstMentionPattern);
        if (!appended.isEmpty()) {
            String prefix = MentionConfigHandler.CONFIG.CHAT_APPEND_PREFIX.get();
            IComponent nl = platform.createLiteralComponent("\n" + prefix);
            IComponent appendedComp = services.getMessageParser().parseMessage(appended, sender);
            feedback = feedback.append(nl).append(appendedComp);
        }
        platform.sendSystemMessage(sender, feedback);
    }

    private String extractContentAfterToken(String originalMessage, String matchedToken) {
        if (originalMessage == null || matchedToken == null) return "";
        int idx = originalMessage.toLowerCase().indexOf(matchedToken.toLowerCase());
        if (idx < 0) return "";
        int end = idx + matchedToken.length();
        if (end >= originalMessage.length()) return "";
        return originalMessage.substring(end).replaceAll("[\\r\\n]+", " ").trim();
        }

    private String extractContentAfterFirstMatch(String message, Pattern pattern) {
        if (message == null || pattern == null) return "";
        Matcher m = pattern.matcher(message);
        if (!m.find()) return "";
        int end = m.end();
        if (end >= message.length()) return "";
        return message.substring(end).replaceAll("[\\r\\n]+", " ").trim();
    }
}
