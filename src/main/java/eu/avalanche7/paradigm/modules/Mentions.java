package eu.avalanche7.paradigm.modules;

import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.context.CommandContext;
import eu.avalanche7.paradigm.configs.MentionConfigHandler;
import eu.avalanche7.paradigm.core.ParadigmModule;
import eu.avalanche7.paradigm.core.Services;
import eu.avalanche7.paradigm.utils.PermissionsHandler;
import net.fabricmc.fabric.api.message.v1.ServerMessageEvents;
import net.minecraft.command.CommandRegistryAccess;
import net.minecraft.network.message.SignedMessage;
import net.minecraft.network.packet.s2c.play.SubtitleS2CPacket;
import net.minecraft.network.packet.s2c.play.TitleS2CPacket;
import net.minecraft.server.command.CommandManager;
import net.minecraft.server.command.ServerCommandSource;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.sound.SoundEvents;
import net.minecraft.text.MutableText;
import net.minecraft.text.Text;
import net.minecraft.world.World;

import java.util.HashMap;
import java.util.List;
import java.util.UUID;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

public class Mentions implements ParadigmModule {

    private static final String NAME = "Mentions";
    private final HashMap<UUID, Long> lastIndividualMentionTime = new HashMap<>();
    private long lastEveryoneMentionTime = 0;
    private Services services;

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
        ServerMessageEvents.CHAT_MESSAGE.register(this::onChatMessage);
    }

    private boolean onChatMessage(SignedMessage signedMessage, ServerPlayerEntity sender, net.minecraft.network.message.MessageType.Parameters params) {
        if (this.services == null || !isEnabled(this.services)) return true;

        MentionConfigHandler.Config mentionConfig = MentionConfigHandler.CONFIG;
        String rawMessage = signedMessage.getContent().getString();
        String mentionSymbol = mentionConfig.MENTION_SYMBOL.value;

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
            sender.sendMessage(this.services.getLang().translate("mention.no_permission_everyone"));
            return false;
        }
        if (!canMentionEveryone(sender, mentionConfig)) {
            sender.sendMessage(this.services.getLang().translate("mention.too_frequent_mention_everyone"));
            return false;
        }

        this.services.getDebugLogger().debugLog("Mention everyone detected in chat by " + sender.getName().getString());
        notifyEveryone(sender.getServer().getPlayerManager().getPlayerList(), sender, rawMessage, false, mentionConfig, matchedEveryoneMention);
        return false;
    }

    private boolean handleIndividualMentions(ServerPlayerEntity sender, String rawMessage, MentionConfigHandler.Config mentionConfig) {
        String mentionSymbol = mentionConfig.MENTION_SYMBOL.value;
        List<ServerPlayerEntity> players = sender.getServer().getPlayerManager().getPlayerList();
        boolean mentionedSomeone = false;

        Pattern allPlayersPattern = buildAllPlayersMentionPattern(players, mentionSymbol);
        Matcher mentionMatcher = allPlayersPattern.matcher(rawMessage);

        while (mentionMatcher.find()) {
            String playerName = mentionMatcher.group(1);
            ServerPlayerEntity targetPlayer = sender.getServer().getPlayerManager().getPlayer(playerName);

            if (targetPlayer == null) continue;

            if (hasPermission(sender, PermissionsHandler.MENTION_PLAYER_PERMISSION, PermissionsHandler.MENTION_PLAYER_PERMISSION_LEVEL)
                    && canMentionPlayer(sender, targetPlayer, mentionConfig)) {
                this.services.getDebugLogger().debugLog("Mention player detected in chat: " + targetPlayer.getName().getString() + " by " + sender.getName().getString());
                notifyPlayer(targetPlayer, sender, rawMessage, false, mentionConfig, mentionMatcher.group(0));
                mentionedSomeone = true;
            }
        }
        return mentionedSomeone;
    }

    private int executeMentionCommand(CommandContext<ServerCommandSource> context, Services services) {
        ServerCommandSource source = context.getSource();
        String message = StringArgumentType.getString(context, "message");
        World world = source.getWorld();
        List<ServerPlayerEntity> players = world.getServer().getPlayerManager().getPlayerList();
        boolean isConsole = source.getEntity() == null;
        ServerPlayerEntity sender = isConsole ? null : source.getPlayer();
        MentionConfigHandler.Config mentionConfig = MentionConfigHandler.CONFIG;
        Text senderDisplayName = source.getDisplayName();

        String everyoneMentionPlaceholder = mentionConfig.MENTION_SYMBOL.value + "everyone";
        Pattern everyonePattern = Pattern.compile(Pattern.quote(everyoneMentionPlaceholder), Pattern.CASE_INSENSITIVE);
        Matcher everyoneMatcher = everyonePattern.matcher(message);

        if (everyoneMatcher.find()) {
            if (sender != null) {
                if (!hasPermission(sender, PermissionsHandler.MENTION_EVERYONE_PERMISSION, PermissionsHandler.MENTION_EVERYONE_PERMISSION_LEVEL)) {
                    sender.sendMessage(services.getLang().translate("mention.no_permission_everyone"));
                    return 0;
                }
                if (!canMentionEveryone(sender, mentionConfig)) {
                    sender.sendMessage(services.getLang().translate("mention.too_frequent_mention_everyone"));
                    return 0;
                }
            } else {
                lastEveryoneMentionTime = System.currentTimeMillis();
            }
            notifyEveryone(players, sender, message, isConsole, mentionConfig, everyoneMatcher.group(0));
            source.sendFeedback(() -> Text.literal("Mentioned everyone successfully."), !isConsole);
            return 1;
        }

        int mentionedCount = 0;
        for (ServerPlayerEntity targetPlayer : players) {
            String playerMentionPlaceholder = mentionConfig.MENTION_SYMBOL.value + targetPlayer.getName().getString();
            Pattern playerMentionPattern = Pattern.compile(Pattern.quote(playerMentionPlaceholder), Pattern.CASE_INSENSITIVE);

            if (playerMentionPattern.matcher(message).find()) {
                if (sender != null) {
                    if (!hasPermission(sender, PermissionsHandler.MENTION_PLAYER_PERMISSION, PermissionsHandler.MENTION_PLAYER_PERMISSION_LEVEL)) continue;
                    if (!canMentionPlayer(sender, targetPlayer, mentionConfig)) continue;
                } else {
                    lastIndividualMentionTime.put(targetPlayer.getUuid(), System.currentTimeMillis());
                }

                notifyPlayer(targetPlayer, sender, message, isConsole, mentionConfig, playerMentionPlaceholder);
                mentionedCount++;
            }
        }

        if (mentionedCount > 0) {
            final int finalMentionedCount = mentionedCount;
            source.sendFeedback(() -> Text.literal("Mentioned " + finalMentionedCount + " player(s) successfully."), !isConsole);
        } else {
            source.sendError(Text.literal("No valid mentions found in the message."));
        }
        return mentionedCount > 0 ? 1 : 0;
    }

    private void notifyEveryone(List<ServerPlayerEntity> players, ServerPlayerEntity sender, String originalMessage, boolean isConsole, MentionConfigHandler.Config config, String matchedEveryoneMention) {
        String senderName = isConsole || sender == null ? "Console" : sender.getName().getString();
        String chatFormat = config.EVERYONE_MENTION_MESSAGE.value;
        String titleFormat = config.EVERYONE_TITLE_MESSAGE.value;
        String content = originalMessage.substring(originalMessage.toLowerCase().indexOf(matchedEveryoneMention.toLowerCase()) + matchedEveryoneMention.length()).trim();

        String chatMessageText = String.format(chatFormat, senderName);
        String titleMessageText = String.format(titleFormat, senderName);

        for (ServerPlayerEntity targetPlayer : players) {
            sendMentionNotification(targetPlayer, chatMessageText, titleMessageText, content, this.services);
        }
    }

    private void notifyPlayer(ServerPlayerEntity targetPlayer, ServerPlayerEntity sender, String originalMessage, boolean isConsole, MentionConfigHandler.Config config, String matchedPlayerMention) {
        String senderName = isConsole || sender == null ? "Console" : sender.getName().getString();
        String chatFormat = config.INDIVIDUAL_MENTION_MESSAGE.value;
        String titleFormat = config.INDIVIDUAL_TITLE_MESSAGE.value;
        String content = originalMessage.substring(originalMessage.toLowerCase().indexOf(matchedPlayerMention.toLowerCase()) + matchedPlayerMention.length()).trim();

        String chatMessageText = String.format(chatFormat, senderName);
        String titleMessageText = String.format(titleFormat, senderName);

        sendMentionNotification(targetPlayer, chatMessageText, titleMessageText, content, this.services);
    }

    private void sendMentionNotification(ServerPlayerEntity targetPlayer, String chatMessage, String titleMessage, String contentMessage, Services services) {
        MentionConfigHandler.Config config = MentionConfigHandler.CONFIG;

        if (config.enableChatNotification.value) {
            MutableText finalChatMessage = services.getMessageParser().parseMessage(chatMessage, targetPlayer);
            if (contentMessage != null && !contentMessage.isEmpty()) {
                MutableText contentComponent = services.getMessageParser().parseMessage("- " + contentMessage, targetPlayer);
                finalChatMessage.append(Text.literal("\n")).append(contentComponent);
            }
            targetPlayer.sendMessage(finalChatMessage, false);
        }

        if (config.enableTitleNotification.value) {
            Text parsedTitleMessage = services.getMessageParser().parseMessage(titleMessage, targetPlayer);
            targetPlayer.networkHandler.sendPacket(new TitleS2CPacket(parsedTitleMessage));
        }

        if (config.enableSubtitleNotification.value && contentMessage != null && !contentMessage.isEmpty()) {
            Text parsedSubtitleMessage = services.getMessageParser().parseMessage(contentMessage, targetPlayer);
            targetPlayer.networkHandler.sendPacket(new SubtitleS2CPacket(parsedSubtitleMessage));
        }

        this.services.getPlatformAdapter().playSound(targetPlayer, "minecraft:entity.experience_orb.pickup", net.minecraft.sound.SoundCategory.PLAYERS, 1.0f, 1.0f);
    }

    private boolean hasPermission(ServerPlayerEntity player, String permissionNode, int permissionLevel) {
        return this.services.getPermissionsHandler().hasPermission(player, permissionNode) || player.hasPermissionLevel(permissionLevel);
    }

    private Pattern buildAllPlayersMentionPattern(List<ServerPlayerEntity> players, String mentionSymbol) {
        if (players.isEmpty()) {
            return Pattern.compile("a^");
        }
        String allPlayerNames = players.stream()
                .map(p -> Pattern.quote(p.getName().getString()))
                .collect(Collectors.joining("|"));

        return Pattern.compile(Pattern.quote(mentionSymbol) + "(" + allPlayerNames + ")", Pattern.CASE_INSENSITIVE);
    }

    private boolean canMentionEveryone(ServerPlayerEntity sender, MentionConfigHandler.Config config) {
        if (sender != null && sender.hasPermissionLevel(2)) return true;
        int rateLimit = config.EVERYONE_MENTION_RATE_LIMIT.get();
        if (rateLimit <= 0) return true;
        long currentTime = System.currentTimeMillis();
        if (currentTime - lastEveryoneMentionTime < rateLimit * 1000L) return false;
        lastEveryoneMentionTime = currentTime;
        return true;
    }

    private boolean canMentionPlayer(ServerPlayerEntity sender, ServerPlayerEntity targetPlayer, MentionConfigHandler.Config config) {
        if (sender != null && sender.hasPermissionLevel(2)) return true;
        int rateLimit = config.INDIVIDUAL_MENTION_RATE_LIMIT.get();
        if (rateLimit <= 0) return true;
        long currentTime = System.currentTimeMillis();
        UUID targetUUID = targetPlayer.getUuid();
        if (lastIndividualMentionTime.containsKey(targetUUID) && currentTime - lastIndividualMentionTime.get(targetUUID) < rateLimit * 1000L) return false;
        lastIndividualMentionTime.put(targetUUID, currentTime);
        return true;
    }
}
