package eu.avalanche7.paradigm.modules;

import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.context.CommandContext;
import eu.avalanche7.paradigm.core.ParadigmModule;
import eu.avalanche7.paradigm.core.Services;
import eu.avalanche7.paradigm.configs.MentionConfigHandler;
import eu.avalanche7.paradigm.utils.PermissionsHandler;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.MutableComponent;
import net.minecraft.network.protocol.game.ClientboundSetSubtitleTextPacket;
import net.minecraft.network.protocol.game.ClientboundSetTitleTextPacket;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.level.Level;
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

    @Override
    public String getName() {
        return NAME;
    }

    @Override
    public boolean isEnabled(Services services) {
        return services != null && services.getMainConfig() != null && services.getMainConfig().mentionsEnable.get();
    }

    @Override
    public void onLoad(FMLCommonSetupEvent event, Services services, IEventBus modEventBus) {
        this.services = services;
        if (this.services != null && this.services.getDebugLogger() != null) {
            this.services.getDebugLogger().debugLog(NAME + " module loaded.");
        } else {
            System.out.println(NAME + " module loaded, but services or debug logger was null during onLoad.");
        }
    }

    @Override
    public void onServerStarting(ServerStartingEvent event, Services services) {
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
    public void onServerStopping(ServerStoppingEvent event, Services services) {
        if (this.services != null && this.services.getDebugLogger() != null) {
            this.services.getDebugLogger().debugLog(NAME + " module: Server stopping.");
        }
    }

    @Override
    public void registerCommands(CommandDispatcher<CommandSourceStack> dispatcher, Services services) {
        dispatcher.register(Commands.literal("mention")
                .requires(source -> source.hasPermission(0))
                .then(Commands.argument("message", StringArgumentType.greedyString())
                        .executes(context -> executeMentionCommand(context, services))));
    }

    @Override
    public void registerEventListeners(IEventBus forgeEventBus, Services services) {
        forgeEventBus.register(this);
    }

    @SubscribeEvent
    public void onChatMessage(ServerChatEvent event) {
        if (this.services == null || !isEnabled(this.services)) return;

        MentionConfigHandler mentionConfig = this.services.getMentionConfig();
        if (mentionConfig == null || mentionConfig.MENTION_SYMBOL == null) return;

        String rawMessage = event.getRawText();
        ServerPlayer sender = event.getPlayer();
        String mentionSymbol = mentionConfig.MENTION_SYMBOL.get();

        String everyoneMentionPlaceholder = mentionSymbol + "everyone";
        Pattern everyonePattern = Pattern.compile(Pattern.quote(everyoneMentionPlaceholder), Pattern.CASE_INSENSITIVE);
        Matcher everyoneMatcher = everyonePattern.matcher(rawMessage);

        if (everyoneMatcher.find()) {
            handleEveryoneMention(event, sender, rawMessage, mentionConfig, everyoneMatcher.group(0));
        } else {
            handleIndividualMentions(event, sender, rawMessage, mentionConfig);
        }
    }

    private void handleEveryoneMention(ServerChatEvent event, ServerPlayer sender, String rawMessage, MentionConfigHandler mentionConfig, String matchedEveryoneMention) {
        if (!hasPermission(sender, PermissionsHandler.MENTION_EVERYONE_PERMISSION, PermissionsHandler.MENTION_EVERYONE_PERMISSION_LEVEL)) {
            sender.sendSystemMessage(this.services.getLang().translate("mention.no_permission_everyone"));
            event.setCanceled(true);
            return;
        }
        if (!canMentionEveryone(sender, mentionConfig)) {
            sender.sendSystemMessage(this.services.getLang().translate("mention.too_frequent_mention_everyone"));
            event.setCanceled(true);
            return;
        }

        this.services.getDebugLogger().debugLog("Mention everyone detected in chat by " + sender.getName().getString());
        event.setCanceled(true);
        notifyEveryone(sender.getServer().getPlayerList().getPlayers(), sender, rawMessage, false, mentionConfig, matchedEveryoneMention);
    }

    private void handleIndividualMentions(ServerChatEvent event, ServerPlayer sender, String rawMessage, MentionConfigHandler mentionConfig) {
        String mentionSymbol = mentionConfig.MENTION_SYMBOL.get();
        MutableComponent finalMessageComponent = Component.literal("");
        int lastEnd = 0;
        boolean wasMentionFound = false;
        List<ServerPlayer> players = sender.getServer().getPlayerList().getPlayers();

        Pattern allPlayersPattern = buildAllPlayersMentionPattern(players, mentionSymbol);
        Matcher mentionMatcher = allPlayersPattern.matcher(rawMessage);

        while(mentionMatcher.find()){
            wasMentionFound = true;
            String playerName = mentionMatcher.group(1);
            ServerPlayer targetPlayer = sender.getServer().getPlayerList().getPlayerByName(playerName);

            if (targetPlayer == null) continue;

            finalMessageComponent.append(Component.literal(rawMessage.substring(lastEnd, mentionMatcher.start())));

            if (hasPermission(sender, PermissionsHandler.MENTION_PLAYER_PERMISSION, PermissionsHandler.MENTION_PLAYER_PERMISSION_LEVEL)
                    && canMentionPlayer(sender, targetPlayer, mentionConfig)) {
                this.services.getDebugLogger().debugLog("Mention player detected in chat: " + targetPlayer.getName().getString() + " by " + sender.getName().getString());
                notifyPlayer(targetPlayer, sender, rawMessage, false, mentionConfig, mentionMatcher.group(0));
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
            sender.getServer().getPlayerList().broadcastSystemMessage(finalMessage, false);
        }
    }

    private int executeMentionCommand(CommandContext<CommandSourceStack> context, Services services) {
        CommandSourceStack source = context.getSource();
        String message = StringArgumentType.getString(context, "message");
        Level world = source.getLevel();
        List<ServerPlayer> players = world.getServer().getPlayerList().getPlayers();
        boolean isConsole = source.getEntity() == null;
        ServerPlayer sender = isConsole ? null : source.getPlayer();
        MentionConfigHandler mentionConfig = services.getMentionConfig();
        Component senderDisplayName = source.getDisplayName();

        String everyoneMentionPlaceholder = mentionConfig.MENTION_SYMBOL.get() + "everyone";
        Pattern everyonePattern = Pattern.compile(Pattern.quote(everyoneMentionPlaceholder), Pattern.CASE_INSENSITIVE);
        Matcher everyoneMatcher = everyonePattern.matcher(message);

        if (everyoneMatcher.find()) {
            if (sender != null) {
                if (!hasPermission(sender, PermissionsHandler.MENTION_EVERYONE_PERMISSION, PermissionsHandler.MENTION_EVERYONE_PERMISSION_LEVEL)) {
                    sender.sendSystemMessage(services.getLang().translate("mention.no_permission_everyone"));
                    return 0;
                }
                if (!canMentionEveryone(sender, mentionConfig)) {
                    sender.sendSystemMessage(services.getLang().translate("mention.too_frequent_mention_everyone"));
                    return 0;
                }
            } else { lastEveryoneMentionTime = System.currentTimeMillis(); }
            notifyEveryone(players, sender, message, isConsole, mentionConfig, everyoneMatcher.group(0));
            source.sendSuccess(Component.literal("Mentioned everyone successfully."), !isConsole);
            return 1;
        }

        boolean mentionedSomeone = false;
        for (ServerPlayer targetPlayer : players) {
            String playerMentionPlaceholder = mentionConfig.MENTION_SYMBOL.get() + targetPlayer.getName().getString();
            Pattern playerMentionPattern = Pattern.compile(Pattern.quote(playerMentionPlaceholder), Pattern.CASE_INSENSITIVE);

            if (playerMentionPattern.matcher(message).find()) {
                if (sender != null) {
                    if (!hasPermission(sender, PermissionsHandler.MENTION_PLAYER_PERMISSION, PermissionsHandler.MENTION_PLAYER_PERMISSION_LEVEL)) continue;
                    if (!canMentionPlayer(sender, targetPlayer, mentionConfig)) continue;
                } else { lastIndividualMentionTime.put(targetPlayer.getUUID(), System.currentTimeMillis()); }

                notifyPlayer(targetPlayer, sender, message, isConsole, mentionConfig, playerMentionPlaceholder);
                mentionedSomeone = true;
            }
        }

        if (mentionedSomeone) {
            Component formattedChat = services.getMessageParser().parseMessage(message, sender);
            Component finalMessage = Component.translatable("chat.type.text", senderDisplayName, formattedChat);
            world.getServer().getPlayerList().broadcastSystemMessage(finalMessage, false);

            source.sendSuccess(Component.literal("Mentioned player(s) successfully."), !isConsole);
        } else {
            source.sendFailure(Component.literal("No valid mentions found in the message."));
        }
        return mentionedSomeone ? 1 : 0;
    }

    private void notifyEveryone(List<ServerPlayer> players, ServerPlayer sender, String originalMessage, boolean isConsole, MentionConfigHandler config, String matchedEveryoneMention) {
        String senderName = isConsole || sender == null ? "Console" : sender.getName().getString();
        String chatFormat = config.EVERYONE_MENTION_MESSAGE.get();
        String titleFormat = config.EVERYONE_TITLE_MESSAGE.get();
        String content = originalMessage.substring(originalMessage.toLowerCase().indexOf(matchedEveryoneMention.toLowerCase()) + matchedEveryoneMention.length()).trim();

        String chatMessageText = String.format(chatFormat, senderName);
        String titleMessageText = String.format(titleFormat, senderName);

        for (ServerPlayer targetPlayer : players) {
            sendMentionNotification(targetPlayer, chatMessageText, titleMessageText, content, this.services);
        }
    }

    private void notifyPlayer(ServerPlayer targetPlayer, ServerPlayer sender, String originalMessage, boolean isConsole, MentionConfigHandler config, String matchedPlayerMention) {
        String senderName = isConsole || sender == null ? "Console" : sender.getName().getString();
        String chatFormat = config.INDIVIDUAL_MENTION_MESSAGE.get();
        String titleFormat = config.INDIVIDUAL_TITLE_MESSAGE.get();
        String content = originalMessage.substring(originalMessage.toLowerCase().indexOf(matchedPlayerMention.toLowerCase()) + matchedPlayerMention.length()).trim();

        String chatMessageText = String.format(chatFormat, senderName);
        String titleMessageText = String.format(titleFormat, senderName);

        sendMentionNotification(targetPlayer, chatMessageText, titleMessageText, content, this.services);
    }

    private void sendMentionNotification(ServerPlayer targetPlayer, String chatMessage, String titleMessage, String contentMessage, Services services) {
        MutableComponent finalChatMessage = services.getMessageParser().parseMessage(chatMessage, targetPlayer);
        if (contentMessage != null && !contentMessage.isEmpty()) {
            MutableComponent contentComponent = services.getMessageParser().parseMessage("- " + contentMessage, targetPlayer);
            finalChatMessage.append(Component.literal("\n")).append(contentComponent);
        }
        targetPlayer.displayClientMessage(finalChatMessage, false);
        Component parsedTitleMessage = services.getMessageParser().parseMessage(titleMessage, targetPlayer);
        targetPlayer.connection.send(new ClientboundSetTitleTextPacket(parsedTitleMessage));
        if (contentMessage != null && !contentMessage.isEmpty()) {
            Component parsedSubtitleMessage = services.getMessageParser().parseMessage(contentMessage, targetPlayer);
            targetPlayer.connection.send(new ClientboundSetSubtitleTextPacket(parsedSubtitleMessage));
        }

        targetPlayer.playNotifySound(SoundEvents.PLAYER_LEVELUP, SoundSource.PLAYERS, 1.0F, 1.0F);
    }

    private boolean hasPermission(ServerPlayer player, String permissionNode, int permissionLevel) {
        return this.services.getPermissionsHandler().hasPermission(player, permissionNode) || player.hasPermissions(permissionLevel);
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

    private boolean canMentionEveryone(ServerPlayer sender, MentionConfigHandler config) {
        if (sender != null && sender.hasPermissions(2)) return true;
        int rateLimit = config.EVERYONE_MENTION_RATE_LIMIT.get();
        if (rateLimit <= 0) return true;
        long currentTime = System.currentTimeMillis();
        if (currentTime - lastEveryoneMentionTime < rateLimit * 1000L) return false;
        lastEveryoneMentionTime = currentTime;
        return true;
    }

    private boolean canMentionPlayer(ServerPlayer sender, ServerPlayer targetPlayer, MentionConfigHandler config) {
        if (sender != null && sender.hasPermissions(2)) return true;
        int rateLimit = config.INDIVIDUAL_MENTION_RATE_LIMIT.get();
        if (rateLimit <= 0) return true;
        long currentTime = System.currentTimeMillis();
        UUID targetUUID = targetPlayer.getUUID();
        if (lastIndividualMentionTime.containsKey(targetUUID) && currentTime - lastIndividualMentionTime.get(targetUUID) < rateLimit * 1000L) return false;
        lastIndividualMentionTime.put(targetUUID, currentTime);
        return true;
    }
}