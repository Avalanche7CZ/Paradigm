package eu.avalanche7.paradigm.modules;

import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.context.CommandContext;
import eu.avalanche7.paradigm.core.ParadigmModule;
import eu.avalanche7.paradigm.core.Services;
import eu.avalanche7.paradigm.configs.MentionConfigHandler;
import eu.avalanche7.paradigm.platform.IPlatformAdapter;
import eu.avalanche7.paradigm.utils.PermissionsHandler;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.MutableComponent;
import net.minecraft.server.level.ServerPlayer;
import net.minecraftforge.event.ServerChatEvent;
import net.minecraftforge.event.server.ServerStartingEvent;
import net.minecraftforge.event.server.ServerStoppingEvent;
import net.minecraftforge.eventbus.api.IEventBus;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.event.lifecycle.FMLCommonSetupEvent;

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
    public void registerCommands(CommandDispatcher<CommandSourceStack> dispatcher, Services services) {
        dispatcher.register(Commands.literal("mention")
                .requires(source -> source.hasPermission(0))
                .then(Commands.argument("message", StringArgumentType.greedyString())
                        .executes(this::executeMentionCommand)));
    }

    @Override
    public void registerEventListeners(IEventBus forgeEventBus, Services services) {
        forgeEventBus.register(this);
    }

    @SubscribeEvent
    public void onChatMessage(ServerChatEvent event) {
        if (this.services == null || !isEnabled(this.services)) return;

        String rawMessage = event.getRawText();
        ServerPlayer sender = event.getPlayer();
        String mentionSymbol = MentionConfigHandler.CONFIG.MENTION_SYMBOL.get();
        String everyoneMentionPlaceholder = mentionSymbol + "everyone";
        Pattern everyonePattern = Pattern.compile(Pattern.quote(everyoneMentionPlaceholder), Pattern.CASE_INSENSITIVE);

        if (everyonePattern.matcher(rawMessage).find()) {
            handleEveryoneMention(event, sender, rawMessage, everyoneMentionPlaceholder);
        } else {
            handleIndividualMentions(event, sender, rawMessage);
        }
    }

    private void handleEveryoneMention(ServerChatEvent event, ServerPlayer sender, String rawMessage, String matchedEveryoneMention) {
        if (!platform.hasPermission(sender, PermissionsHandler.MENTION_EVERYONE_PERMISSION, PermissionsHandler.MENTION_EVERYONE_PERMISSION_LEVEL)) {
            platform.sendSystemMessage(sender, services.getLang().translate("mention.no_permission_everyone"));
            event.setCanceled(true);
            return;
        }
        if (!canMentionEveryone(sender)) {
            platform.sendSystemMessage(sender, services.getLang().translate("mention.too_frequent_mention_everyone"));
            event.setCanceled(true);
            return;
        }
        event.setCanceled(true);
        notifyEveryone(platform.getOnlinePlayers(), sender, rawMessage, false, matchedEveryoneMention);
    }

    private void handleIndividualMentions(ServerChatEvent event, ServerPlayer sender, String rawMessage) {
        String mentionSymbol = MentionConfigHandler.CONFIG.MENTION_SYMBOL.get();
        MutableComponent finalMessageComponent = Component.literal("");
        int lastEnd = 0;
        boolean wasMentionFound = false;

        Pattern allPlayersPattern = buildAllPlayersMentionPattern(platform.getOnlinePlayers(), mentionSymbol);
        Matcher mentionMatcher = allPlayersPattern.matcher(rawMessage);

        while (mentionMatcher.find()) {
            wasMentionFound = true;
            String playerName = mentionMatcher.group(1);
            ServerPlayer targetPlayer = platform.getPlayerByName(playerName);

            if (targetPlayer == null) continue;

            finalMessageComponent.append(Component.literal(rawMessage.substring(lastEnd, mentionMatcher.start())));

            if (platform.hasPermission(sender, PermissionsHandler.MENTION_PLAYER_PERMISSION, PermissionsHandler.MENTION_PLAYER_PERMISSION_LEVEL)
                    && canMentionPlayer(sender, targetPlayer)) {
                notifyPlayer(targetPlayer, sender, rawMessage, false, mentionMatcher.group(0));
                finalMessageComponent.append(targetPlayer.getDisplayName());
            } else {
                finalMessageComponent.append(Component.literal(mentionMatcher.group(0)));
            }
            lastEnd = mentionMatcher.end();
        }

        if (wasMentionFound) {
            event.setCanceled(true);
            finalMessageComponent.append(Component.literal(rawMessage.substring(lastEnd)));
            Component finalMessage = Component.translatable("chat.type.text", sender.getDisplayName(), finalMessageComponent);
            platform.broadcastChatMessage(finalMessage);
        }
    }

    private int executeMentionCommand(CommandContext<CommandSourceStack> context) {
        CommandSourceStack source = context.getSource();
        String message = StringArgumentType.getString(context, "message");
        boolean isConsole = source.getEntity() == null;
        ServerPlayer sender = isConsole ? null : source.getPlayer();

        String everyoneMentionPlaceholder = MentionConfigHandler.CONFIG.MENTION_SYMBOL.get() + "everyone";
        if (Pattern.compile(Pattern.quote(everyoneMentionPlaceholder), Pattern.CASE_INSENSITIVE).matcher(message).find()) {
            if (sender != null) {
                if (!platform.hasPermission(sender, PermissionsHandler.MENTION_EVERYONE_PERMISSION, PermissionsHandler.MENTION_EVERYONE_PERMISSION_LEVEL)) {
                    platform.sendSystemMessage(sender, services.getLang().translate("mention.no_permission_everyone"));
                    return 0;
                }
                if (!canMentionEveryone(sender)) {
                    platform.sendSystemMessage(sender, services.getLang().translate("mention.too_frequent_mention_everyone"));
                    return 0;
                }
            } else { lastEveryoneMentionTime = System.currentTimeMillis(); }

            notifyEveryone(platform.getOnlinePlayers(), sender, message, isConsole, everyoneMentionPlaceholder);
            platform.sendSuccess(source, Component.literal("Mentioned everyone successfully."), !isConsole);
            return 1;
        }

        boolean mentionedSomeone = false;
        for (ServerPlayer targetPlayer : platform.getOnlinePlayers()) {
            String playerMentionPlaceholder = MentionConfigHandler.CONFIG.MENTION_SYMBOL.get() + targetPlayer.getName().getString();
            if (Pattern.compile(Pattern.quote(playerMentionPlaceholder), Pattern.CASE_INSENSITIVE).matcher(message).find()) {
                if (sender != null) {
                    if (!platform.hasPermission(sender, PermissionsHandler.MENTION_PLAYER_PERMISSION, PermissionsHandler.MENTION_PLAYER_PERMISSION_LEVEL)) continue;
                    if (!canMentionPlayer(sender, targetPlayer)) continue;
                } else { lastIndividualMentionTime.put(targetPlayer.getUUID(), System.currentTimeMillis()); }

                notifyPlayer(targetPlayer, sender, message, isConsole, playerMentionPlaceholder);
                mentionedSomeone = true;
            }
        }

        if (mentionedSomeone) {
            Component formattedChat = services.getMessageParser().parseMessage(message, sender);
            Component finalMessage = Component.translatable("chat.type.text", source.getDisplayName(), formattedChat);
            platform.broadcastChatMessage(finalMessage);
            platform.sendSuccess(source, Component.literal("Mentioned player(s) successfully."), !isConsole);
        } else {
            platform.sendFailure(source, Component.literal("No valid mentions found in the message."));
        }
        return mentionedSomeone ? 1 : 0;
    }

    private void notifyEveryone(List<ServerPlayer> players, ServerPlayer sender, String originalMessage, boolean isConsole, String matchedEveryoneMention) {
        String senderName = isConsole || sender == null ? "Console" : sender.getName().getString();
        String chatFormat = MentionConfigHandler.CONFIG.EVERYONE_MENTION_MESSAGE.get();
        String titleFormat = MentionConfigHandler.CONFIG.EVERYONE_TITLE_MESSAGE.get();
        String content = originalMessage.substring(originalMessage.toLowerCase().indexOf(matchedEveryoneMention.toLowerCase()) + matchedEveryoneMention.length()).trim();
        String chatMessageText = String.format(chatFormat, senderName);
        String titleMessageText = String.format(titleFormat, senderName);

        for (ServerPlayer targetPlayer : players) {
            sendMentionNotification(targetPlayer, chatMessageText, titleMessageText, content);
        }
    }

    private void notifyPlayer(ServerPlayer targetPlayer, ServerPlayer sender, String originalMessage, boolean isConsole, String matchedPlayerMention) {
        String senderName = isConsole || sender == null ? "Console" : sender.getName().getString();
        String chatFormat = MentionConfigHandler.CONFIG.INDIVIDUAL_MENTION_MESSAGE.get();
        String titleFormat = MentionConfigHandler.CONFIG.INDIVIDUAL_TITLE_MESSAGE.get();
        String content = originalMessage.substring(originalMessage.toLowerCase().indexOf(matchedPlayerMention.toLowerCase()) + matchedPlayerMention.length()).trim();
        String chatMessageText = String.format(chatFormat, senderName);
        String titleMessageText = String.format(titleFormat, senderName);

        sendMentionNotification(targetPlayer, chatMessageText, titleMessageText, content);
    }

    private void sendMentionNotification(ServerPlayer targetPlayer, String chatMessage, String titleMessage, String contentMessage) {
        MutableComponent finalChatMessage = services.getMessageParser().parseMessage(chatMessage, targetPlayer);
        if (contentMessage != null && !contentMessage.isEmpty()) {
            MutableComponent contentComponent = services.getMessageParser().parseMessage("- " + contentMessage, targetPlayer);
            finalChatMessage.append(Component.literal("\n")).append(contentComponent);
        }
        platform.sendSystemMessage(targetPlayer, finalChatMessage);

        Component parsedTitleMessage = services.getMessageParser().parseMessage(titleMessage, targetPlayer);
        Component parsedSubtitleMessage = (contentMessage != null && !contentMessage.isEmpty())
                ? services.getMessageParser().parseMessage(contentMessage, targetPlayer)
                : Component.empty();

        platform.sendTitle(targetPlayer, parsedTitleMessage, parsedSubtitleMessage);
        platform.playSound(targetPlayer, "minecraft:entity.player.levelup", 1.0F, 1.0F);
    }

    private Pattern buildAllPlayersMentionPattern(List<ServerPlayer> players, String mentionSymbol) {
        if (players.isEmpty()) {
            return Pattern.compile("a^");
        }
        String allPlayerNames = players.stream()
                .map(p -> Pattern.quote(p.getName().getString()))
                .collect(Collectors.joining("|"));
        return Pattern.compile(Pattern.quote(mentionSymbol) + "(" + allPlayerNames + ")", Pattern.CASE_INSENSITIVE);
    }

    private boolean canMentionEveryone(ServerPlayer sender) {
        if (sender != null && sender.hasPermissions(2)) return true;
        int rateLimit = MentionConfigHandler.CONFIG.EVERYONE_MENTION_RATE_LIMIT.get();
        if (rateLimit <= 0) return true;
        long currentTime = System.currentTimeMillis();
        if (currentTime - lastEveryoneMentionTime < rateLimit * 1000L) return false;
        lastEveryoneMentionTime = currentTime;
        return true;
    }

    private boolean canMentionPlayer(ServerPlayer sender, ServerPlayer targetPlayer) {
        if (sender != null && sender.hasPermissions(2)) return true;
        int rateLimit = MentionConfigHandler.CONFIG.INDIVIDUAL_MENTION_RATE_LIMIT.get();
        if (rateLimit <= 0) return true;
        long currentTime = System.currentTimeMillis();
        UUID targetUUID = targetPlayer.getUUID();
        if (lastIndividualMentionTime.containsKey(targetUUID) && currentTime - lastIndividualMentionTime.get(targetUUID) < rateLimit * 1000L) return false;
        lastIndividualMentionTime.put(targetUUID, currentTime);
        return true;
    }
}