package eu.avalanche7.forgeannouncements.modules;

import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.context.CommandContext;
import eu.avalanche7.forgeannouncements.core.ForgeAnnouncementModule;
import eu.avalanche7.forgeannouncements.core.Services;
import eu.avalanche7.forgeannouncements.configs.MentionConfigHandler;
import eu.avalanche7.forgeannouncements.utils.PermissionsHandler;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.network.chat.Component;
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

public class Mentions implements ForgeAnnouncementModule {

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
        if (this.services == null || !isEnabled(this.services)) {
            return;
        }

        MentionConfigHandler mentionConfig = this.services.getMentionConfig();
        if (mentionConfig == null || mentionConfig.MENTION_SYMBOL == null) {
            this.services.getDebugLogger().debugLog("MentionConfig or MENTION_SYMBOL is null in onChatMessage.");
            return;
        }
        String mentionSymbol = mentionConfig.MENTION_SYMBOL.get();
        String message = event.getMessage();
        ServerPlayer sender = event.getPlayer();
        Level world = sender.getLevel();
        List<ServerPlayer> players = world.getServer().getPlayerList().getPlayers();

        String everyoneMentionPlaceholder = mentionSymbol + "everyone";
        Pattern everyonePattern = Pattern.compile(Pattern.quote(everyoneMentionPlaceholder), Pattern.CASE_INSENSITIVE);
        Matcher everyoneMatcher = everyonePattern.matcher(message);
        boolean mentionEveryone = everyoneMatcher.find();

        if (mentionEveryone) {
            if (!canMentionEveryone(sender, mentionConfig)) {
                sender.sendSystemMessage(this.services.getLang().translate("mention.too_frequent_mention_everyone"));
                event.setCanceled(true);
                return;
            }
            boolean hasPermission = this.services.getPermissionsHandler().hasPermission(sender, PermissionsHandler.MENTION_EVERYONE_PERMISSION);
            boolean hasPermissionLevel = sender.hasPermissions(PermissionsHandler.MENTION_EVERYONE_PERMISSION_LEVEL);

            if (!hasPermission && !hasPermissionLevel) {
                sender.sendSystemMessage(this.services.getLang().translate("mention.no_permission_everyone"));
                event.setCanceled(true);
                return;
            }
            this.services.getDebugLogger().debugLog("Mention everyone detected in chat by " + sender.getName().getString());
            notifyEveryone(players, sender, message, false, mentionConfig, everyoneMatcher.group(0));
            event.setCanceled(true);
        } else {
            String currentMessageContent = message;
            boolean messageModifiedInChat = false;

            for (ServerPlayer targetPlayer : players) {
                String playerMentionPlaceholder = mentionSymbol + targetPlayer.getName().getString();
                Pattern playerMentionPattern = Pattern.compile(Pattern.quote(playerMentionPlaceholder), Pattern.CASE_INSENSITIVE);
                Matcher mentionMatcher = playerMentionPattern.matcher(currentMessageContent);

                if (mentionMatcher.find()) {
                    if (!canMentionPlayer(sender, targetPlayer, mentionConfig)) {
                        sender.sendSystemMessage(this.services.getLang().translate("mention.too_frequent_mention_player"));
                        continue;
                    }
                    boolean hasPermission = this.services.getPermissionsHandler().hasPermission(sender, PermissionsHandler.MENTION_PLAYER_PERMISSION);
                    boolean hasPermissionLevel = sender.hasPermissions(PermissionsHandler.MENTION_PLAYER_PERMISSION_LEVEL);

                    if (!hasPermission && !hasPermissionLevel) {
                        sender.sendSystemMessage(this.services.getLang().translate("mention.no_permission_player"));
                        continue;
                    }
                    this.services.getDebugLogger().debugLog("Mention player detected in chat: " + targetPlayer.getName().getString() + " by " + sender.getName().getString());
                    notifyPlayer(targetPlayer, sender, currentMessageContent, false, mentionConfig, mentionMatcher.group(0));

                    String replacement = targetPlayer.getDisplayName().getString();
                    currentMessageContent = mentionMatcher.replaceFirst(Matcher.quoteReplacement(replacement));
                    messageModifiedInChat = true;
                    mentionMatcher = playerMentionPattern.matcher(currentMessageContent);
                }
            }
            if (messageModifiedInChat) {
                event.setMessage(currentMessageContent);
                event.setComponent(this.services.getMessageParser().parseMessage(currentMessageContent, sender));
            }
        }
    }

    private int executeMentionCommand(CommandContext<CommandSourceStack> context, Services services) {
        CommandSourceStack source = context.getSource();
        String message = StringArgumentType.getString(context, "message");
        Level world = source.getLevel();
        List<ServerPlayer> players = world.getServer().getPlayerList().getPlayers();
        boolean isConsole = source.getEntity() == null;
        ServerPlayer sender = isConsole ? null : (ServerPlayer) source.getEntity();
        MentionConfigHandler mentionConfig = services.getMentionConfig();

        String everyoneMentionPlaceholder = mentionConfig.MENTION_SYMBOL.get() + "everyone";
        Pattern everyonePattern = Pattern.compile(Pattern.quote(everyoneMentionPlaceholder), Pattern.CASE_INSENSITIVE);
        Matcher everyoneMatcher = everyonePattern.matcher(message);

        if (everyoneMatcher.find()) {
            if (!isConsole && sender != null) {
                if (!canMentionEveryone(sender, mentionConfig)) {
                    sender.sendSystemMessage(services.getLang().translate("mention.too_frequent_mention_everyone"));
                    return 0;
                }
                boolean hasPermission = services.getPermissionsHandler().hasPermission(sender, PermissionsHandler.MENTION_EVERYONE_PERMISSION);
                boolean hasPermissionLevel = sender.hasPermissions(PermissionsHandler.MENTION_EVERYONE_PERMISSION_LEVEL);
                if (!hasPermission && !hasPermissionLevel) {
                    sender.sendSystemMessage(services.getLang().translate("mention.no_permission_everyone"));
                    return 0;
                }
            } else if (isConsole) {
                lastEveryoneMentionTime = System.currentTimeMillis();
            }
            notifyEveryone(players, sender, message, isConsole, mentionConfig, everyoneMatcher.group(0));
            source.sendSuccess(Component.literal("Mentioned everyone successfully."), !isConsole);
            return 1;
        }

        boolean mentionedSomeone = false;
        for (ServerPlayer targetPlayer : players) {
            String playerMentionPlaceholder = mentionConfig.MENTION_SYMBOL.get() + targetPlayer.getName().getString();
            Pattern playerMentionPattern = Pattern.compile(Pattern.quote(playerMentionPlaceholder), Pattern.CASE_INSENSITIVE);
            Matcher mentionMatcher = playerMentionPattern.matcher(message);

            if (mentionMatcher.find()) {
                if (!isConsole && sender != null) {
                    if (!canMentionPlayer(sender, targetPlayer, mentionConfig)) {
                        sender.sendSystemMessage(services.getLang().translate("mention.too_frequent_mention_player"));
                        continue;
                    }
                    boolean hasPermission = services.getPermissionsHandler().hasPermission(sender, PermissionsHandler.MENTION_PLAYER_PERMISSION);
                    boolean hasPermissionLevel = sender.hasPermissions(PermissionsHandler.MENTION_PLAYER_PERMISSION_LEVEL);
                    if (!hasPermission && !hasPermissionLevel) {
                        sender.sendSystemMessage(services.getLang().translate("mention.no_permission_player"));
                        continue;
                    }
                } else if (isConsole) {
                    lastIndividualMentionTime.put(targetPlayer.getUUID(), System.currentTimeMillis());
                }
                notifyPlayer(targetPlayer, sender, message, isConsole, mentionConfig, mentionMatcher.group(0));
                mentionedSomeone = true;
            }
        }

        if (mentionedSomeone) {
            source.sendSuccess(Component.literal("Mentioned player(s) successfully."), !isConsole);
        } else {
            source.sendFailure(Component.literal("No valid mentions found in the message. Use " + mentionConfig.MENTION_SYMBOL.get() + "playername or " + mentionConfig.MENTION_SYMBOL.get() + "everyone."));
        }
        return mentionedSomeone ? 1 : 0;
    }

    private boolean canMentionEveryone(ServerPlayer sender, MentionConfigHandler config) {
        if (sender != null && sender.hasPermissions(2)) {
            return true;
        }
        int rateLimit = config.EVERYONE_MENTION_RATE_LIMIT.get();
        long currentTime = System.currentTimeMillis();
        if (currentTime - lastEveryoneMentionTime < rateLimit * 1000L) {
            return false;
        }
        lastEveryoneMentionTime = currentTime;
        return true;
    }

    private boolean canMentionPlayer(ServerPlayer sender, ServerPlayer targetPlayer, MentionConfigHandler config) {
        if (sender != null && sender.hasPermissions(2)) {
            return true;
        }
        int rateLimit = config.INDIVIDUAL_MENTION_RATE_LIMIT.get();
        long currentTime = System.currentTimeMillis();
        UUID targetUUID = targetPlayer.getUUID();
        if (lastIndividualMentionTime.containsKey(targetUUID) && currentTime - lastIndividualMentionTime.get(targetUUID) < rateLimit * 1000L) {
            return false;
        }
        lastIndividualMentionTime.put(targetUUID, currentTime);
        return true;
    }

    private void notifyEveryone(List<ServerPlayer> players, ServerPlayer sender, String originalMessage, boolean isConsole, MentionConfigHandler config, String matchedEveryoneMention) {
        String senderName = isConsole || sender == null ? "Console" : sender.getName().getString();
        String chatFormat = config.EVERYONE_MENTION_MESSAGE.get();
        String titleFormat = config.EVERYONE_TITLE_MESSAGE.get();

        Pattern mentionPattern = Pattern.compile(Pattern.quote(matchedEveryoneMention), Pattern.CASE_INSENSITIVE);
        Matcher matcher = mentionPattern.matcher(originalMessage);
        String actualContent = "";
        if (matcher.find()) {
            actualContent = originalMessage.substring(matcher.end()).trim();
        }

        String chatMessageText = String.format(chatFormat, senderName);
        String titleMessageText = String.format(titleFormat, senderName);

        for (ServerPlayer targetPlayer : players) {
            sendMentionNotification(targetPlayer, chatMessageText, titleMessageText, actualContent, this.services);
        }
    }

    private void notifyPlayer(ServerPlayer targetPlayer, ServerPlayer sender, String originalMessage, boolean isConsole, MentionConfigHandler config, String matchedPlayerMention) {
        String senderName = isConsole || sender == null ? "Console" : sender.getName().getString();
        String chatFormat = config.INDIVIDUAL_MENTION_MESSAGE.get();
        String titleFormat = config.INDIVIDUAL_TITLE_MESSAGE.get();

        Pattern mentionPattern = Pattern.compile(Pattern.quote(matchedPlayerMention), Pattern.CASE_INSENSITIVE);
        Matcher matcher = mentionPattern.matcher(originalMessage);
        String actualContent = "";
        if (matcher.find()) {
            actualContent = originalMessage.substring(matcher.end()).trim();
        }

        String chatMessageText = String.format(chatFormat, senderName);
        String titleMessageText = String.format(titleFormat, senderName);

        sendMentionNotification(targetPlayer, chatMessageText, titleMessageText, actualContent, this.services);
    }

    private void sendMentionNotification(ServerPlayer targetPlayer, String chatMessage, String titleMessage, String subtitleMessage, Services services) {
        Component parsedChatMessage = services.getMessageParser().parseMessage(chatMessage, targetPlayer);
        Component parsedTitleMessage = services.getMessageParser().parseMessage(titleMessage, targetPlayer);
        Component parsedSubtitleMessage = (subtitleMessage != null && !subtitleMessage.isEmpty()) ? services.getMessageParser().parseMessage(subtitleMessage, targetPlayer) : Component.empty();
        targetPlayer.displayClientMessage(parsedChatMessage, false);

        targetPlayer.connection.send(new ClientboundSetTitleTextPacket(parsedTitleMessage));
        if (parsedSubtitleMessage != Component.empty() && !parsedSubtitleMessage.getString().isEmpty()) {
            targetPlayer.connection.send(new ClientboundSetSubtitleTextPacket(parsedSubtitleMessage));
        }
        targetPlayer.playNotifySound(SoundEvents.PLAYER_LEVELUP, SoundSource.PLAYERS, 1.0F, 1.0F);
    }
}