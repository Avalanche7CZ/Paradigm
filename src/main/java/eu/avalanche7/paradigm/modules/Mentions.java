package eu.avalanche7.paradigm.modules;

import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.context.CommandContext;
import eu.avalanche7.paradigm.core.ParadigmModule;
import eu.avalanche7.paradigm.core.Services;
import eu.avalanche7.paradigm.configs.MentionConfigHandler;
import eu.avalanche7.paradigm.utils.PermissionsHandler;
import net.minecraft.Util;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.network.chat.MutableComponent;
import net.minecraft.network.chat.TextComponent;
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
        return services.getMainConfig().mentionsEnable.get();
    }

    @Override
    public void onLoad(FMLCommonSetupEvent event, Services services, IEventBus modEventBus) {
        this.services = services;
        services.getDebugLogger().debugLog(NAME + " module loaded.");
    }

    @Override
    public void onServerStarting(ServerStartingEvent event, Services services) {
        services.getDebugLogger().debugLog(NAME + " module: Server starting.");
    }

    @Override
    public void onEnable(Services services) {
        services.getDebugLogger().debugLog(NAME + " module enabled.");
    }

    @Override
    public void onDisable(Services services) {
        services.getDebugLogger().debugLog(NAME + " module disabled.");
    }

    @Override
    public void onServerStopping(ServerStoppingEvent event, Services services) {
        services.getDebugLogger().debugLog(NAME + " module: Server stopping.");
    }

    @Override
    public void registerCommands(CommandDispatcher<CommandSourceStack> dispatcher, Services services) {
        dispatcher.register(Commands.literal("mention")
                .requires(source -> source.hasPermission(0)) // Basic permission, more specific checks inside
                .then(Commands.argument("message", StringArgumentType.greedyString())
                        .executes(context -> executeMentionCommand(context, services))));
    }

    @Override
    public void registerEventListeners(IEventBus forgeEventBus, Services services) {
        forgeEventBus.register(this);
    }

    @SubscribeEvent
    public void onChatMessage(ServerChatEvent event) {
        if (!isEnabled(this.services)) {
            return;
        }

        MentionConfigHandler mentionConfig = this.services.getMentionConfig();
        String mentionSymbol = mentionConfig.MENTION_SYMBOL.get();
        String message = event.getMessage();
        ServerPlayer sender = event.getPlayer();
        Level world = sender.getLevel();
        List<ServerPlayer> players = world.getServer().getPlayerList().getPlayers();

        boolean mentionEveryone = message.contains(mentionSymbol + "everyone");

        if (mentionEveryone) {
            if (!canMentionEveryone(sender, mentionConfig, this.services)) {
                sender.sendMessage(this.services.getLang().translate("mention.too_frequent_mention_everyone"), Util.NIL_UUID);
                event.setCanceled(true);
                return;
            }
            boolean hasPermission = this.services.getPermissionsHandler().hasPermission(sender, PermissionsHandler.MENTION_EVERYONE_PERMISSION);
            boolean hasPermissionLevel = sender.hasPermissions(PermissionsHandler.MENTION_EVERYONE_PERMISSION_LEVEL);

            if (!hasPermission && !hasPermissionLevel) {
                sender.sendMessage(this.services.getLang().translate("mention.no_permission_everyone"), Util.NIL_UUID);
                event.setCanceled(true);
                return;
            }
            this.services.getDebugLogger().debugLog("Mention everyone detected in chat by " + sender.getName().getString());
            notifyEveryone(players, sender, message, false, mentionConfig, this.services);
            event.setCanceled(true); // Original message is replaced by the mention notification
        } else {
            for (ServerPlayer targetPlayer : players) {
                String mention = mentionSymbol + targetPlayer.getName().getString();
                if (message.contains(mention)) {
                    if (!canMentionPlayer(sender, targetPlayer, mentionConfig, this.services)) {
                        sender.sendMessage(this.services.getLang().translate("mention.too_frequent_mention_player"), Util.NIL_UUID);
                        event.setCanceled(true); // Cancel if rate limited
                        return;
                    }
                    boolean hasPermission = this.services.getPermissionsHandler().hasPermission(sender, PermissionsHandler.MENTION_PLAYER_PERMISSION);
                    boolean hasPermissionLevel = sender.hasPermissions(PermissionsHandler.MENTION_PLAYER_PERMISSION_LEVEL);

                    if (!hasPermission && !hasPermissionLevel) {
                        sender.sendMessage(this.services.getLang().translate("mention.no_permission_player"), Util.NIL_UUID);
                        event.setCanceled(true);
                        return;
                    }
                    this.services.getDebugLogger().debugLog("Mention player detected in chat: " + targetPlayer.getName().getString() + " by " + sender.getName().getString());
                    notifyPlayer(targetPlayer, sender, message, false, mentionConfig, this.services);
                    // Don't cancel the event here, let the original message go through, the notification is extra.
                    // Or, if you want to replace it, you'd cancel and resend a formatted message.
                    // For now, let's assume notification is additional.
                }
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

        if (message.contains(mentionConfig.MENTION_SYMBOL.get() + "everyone")) {
            if (!isConsole && sender != null) {
                if (!canMentionEveryone(sender, mentionConfig, services)) {
                    sender.sendMessage(services.getLang().translate("mention.too_frequent_mention_everyone"), Util.NIL_UUID);
                    return 0;
                }
                boolean hasPermission = services.getPermissionsHandler().hasPermission(sender, PermissionsHandler.MENTION_EVERYONE_PERMISSION);
                boolean hasPermissionLevel = sender.hasPermissions(PermissionsHandler.MENTION_EVERYONE_PERMISSION_LEVEL);
                if (!hasPermission && !hasPermissionLevel) {
                    sender.sendMessage(services.getLang().translate("mention.no_permission_everyone"), Util.NIL_UUID);
                    return 0;
                }
            } else if (isConsole) {
                // Allow console to bypass rate limit for @everyone
                lastEveryoneMentionTime = System.currentTimeMillis();
            }
            notifyEveryone(players, sender, message, isConsole, mentionConfig, services);
            source.sendSuccess(new TextComponent("Mentioned everyone successfully."), !isConsole);
            return 1;
        }

        boolean mentionedSomeone = false;
        for (ServerPlayer targetPlayer : players) {
            String mention = mentionConfig.MENTION_SYMBOL.get() + targetPlayer.getName().getString();
            if (message.contains(mention)) {
                if (!isConsole && sender != null) {
                    if (!canMentionPlayer(sender, targetPlayer, mentionConfig, services)) {
                        sender.sendMessage(services.getLang().translate("mention.too_frequent_mention_player"), Util.NIL_UUID);
                        continue; // Skip this player, maybe mention others
                    }
                    boolean hasPermission = services.getPermissionsHandler().hasPermission(sender, PermissionsHandler.MENTION_PLAYER_PERMISSION);
                    boolean hasPermissionLevel = sender.hasPermissions(PermissionsHandler.MENTION_PLAYER_PERMISSION_LEVEL);
                    if (!hasPermission && !hasPermissionLevel) {
                        sender.sendMessage(services.getLang().translate("mention.no_permission_player"), Util.NIL_UUID);
                        continue;
                    }
                } else if (isConsole) {
                    // Allow console to bypass rate limit for individual players
                    lastIndividualMentionTime.put(targetPlayer.getUUID(), System.currentTimeMillis());
                }
                notifyPlayer(targetPlayer, sender, message, isConsole, mentionConfig, services);
                mentionedSomeone = true;
            }
        }

        if (mentionedSomeone) {
            source.sendSuccess(new TextComponent("Mentioned player(s) successfully."), !isConsole);
        } else {
            source.sendFailure(new TextComponent("No valid mentions found in the message. Use " + mentionConfig.MENTION_SYMBOL.get() + "playername or " + mentionConfig.MENTION_SYMBOL.get() + "everyone."));
        }
        return mentionedSomeone ? 1 : 0;
    }

    private boolean canMentionEveryone(ServerPlayer sender, MentionConfigHandler config, Services services) {
        if (sender != null && sender.hasPermissions(2)) { // Ops bypass rate limits
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

    private boolean canMentionPlayer(ServerPlayer sender, ServerPlayer targetPlayer, MentionConfigHandler config, Services services) {
        if (sender != null && sender.hasPermissions(2)) { // Ops bypass rate limits
            return true;
        }
        int rateLimit = config.INDIVIDUAL_MENTION_RATE_LIMIT.get();
        long currentTime = System.currentTimeMillis();
        UUID targetUUID = targetPlayer.getUUID(); // Use target's UUID for their specific cooldown
        if (lastIndividualMentionTime.containsKey(targetUUID) && currentTime - lastIndividualMentionTime.get(targetUUID) < rateLimit * 1000L) {
            return false;
        }
        lastIndividualMentionTime.put(targetUUID, currentTime);
        return true;
    }

    private void notifyEveryone(List<ServerPlayer> players, ServerPlayer sender, String originalMessage, boolean isConsole, MentionConfigHandler config, Services services) {
        String senderName = isConsole || sender == null ? "Console" : sender.getName().getString();
        String chatFormat = config.EVERYONE_MENTION_MESSAGE.get();
        String titleFormat = config.EVERYONE_TITLE_MESSAGE.get();

        String chatMessageText = String.format(chatFormat, senderName);
        String titleMessageText = String.format(titleFormat, senderName);
        String subtitleText = originalMessage.replace(config.MENTION_SYMBOL.get() + "everyone", "").trim();

        for (ServerPlayer targetPlayer : players) {
            sendMentionNotification(targetPlayer, chatMessageText, titleMessageText, subtitleText, services);
        }
    }

    private void notifyPlayer(ServerPlayer targetPlayer, ServerPlayer sender, String originalMessage, boolean isConsole, MentionConfigHandler config, Services services) {
        String senderName = isConsole || sender == null ? "Console" : sender.getName().getString();
        String chatFormat = config.INDIVIDUAL_MENTION_MESSAGE.get();
        String titleFormat = config.INDIVIDUAL_TITLE_MESSAGE.get();

        String chatMessageText = String.format(chatFormat, senderName);
        String titleMessageText = String.format(titleFormat, senderName);
        String subtitleText = originalMessage.replace(config.MENTION_SYMBOL.get() + targetPlayer.getName().getString(), "").trim();

        sendMentionNotification(targetPlayer, chatMessageText, titleMessageText, subtitleText, services);
    }

    private void sendMentionNotification(ServerPlayer targetPlayer, String chatMessage, String titleMessage, String subtitleMessage, Services services) {
        MutableComponent formattedChatMessage = services.getMessageParser().parseMessage(chatMessage, targetPlayer);
        if (subtitleMessage != null && !subtitleMessage.isEmpty()) {
            MutableComponent formattedSubtitle = services.getMessageParser().parseMessage("- " + subtitleMessage, targetPlayer);
            formattedChatMessage.append("\n").append(formattedSubtitle);
        }
        targetPlayer.displayClientMessage(formattedChatMessage, false);

        MutableComponent titleComp = services.getMessageParser().parseMessage(titleMessage, targetPlayer);
        targetPlayer.connection.send(new ClientboundSetTitleTextPacket(titleComp));

        if (subtitleMessage != null && !subtitleMessage.isEmpty()) {
            MutableComponent subtitleComp = services.getMessageParser().parseMessage(subtitleMessage, targetPlayer);
            targetPlayer.connection.send(new ClientboundSetSubtitleTextPacket(subtitleComp));
        }
        targetPlayer.playNotifySound(SoundEvents.PLAYER_LEVELUP, SoundSource.PLAYERS, 1.0F, 1.0F);
    }
}
