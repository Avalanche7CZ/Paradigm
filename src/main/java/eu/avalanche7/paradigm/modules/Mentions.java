package eu.avalanche7.paradigm.modules;

import eu.avalanche7.paradigm.configs.MentionConfigHandler;
import eu.avalanche7.paradigm.core.ParadigmModule;
import eu.avalanche7.paradigm.core.Services;
import eu.avalanche7.paradigm.utils.PermissionsHandler;
import net.minecraft.command.CommandBase;
import net.minecraft.command.CommandException;
import net.minecraft.command.ICommand;
import net.minecraft.command.ICommandSender;
import net.minecraft.entity.player.EntityPlayerMP;
import net.minecraft.network.play.server.SPacketTitle;
import net.minecraft.server.MinecraftServer;
import net.minecraft.util.ResourceLocation;
import net.minecraft.util.SoundCategory;
import net.minecraft.util.SoundEvent;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.text.ITextComponent;
import net.minecraft.util.text.TextComponentString;
import net.minecraftforge.common.MinecraftForge;
import net.minecraftforge.event.ServerChatEvent;
import net.minecraftforge.fml.common.event.FMLPreInitializationEvent;
import net.minecraftforge.fml.common.event.FMLServerStartingEvent;
import net.minecraftforge.fml.common.event.FMLServerStoppingEvent;
import net.minecraftforge.fml.common.eventhandler.SubscribeEvent;

import javax.annotation.Nullable;
import java.util.Collections;
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
        return services.getMainConfig().mentionsEnable.value;
    }

    @Override
    public void onLoad(FMLPreInitializationEvent event, Services services) {
        this.services = services;
        services.getDebugLogger().debugLog(NAME + " module loaded.");
    }

    @Override
    public void onServerStarting(FMLServerStartingEvent event, Services services) {
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
    public void onServerStopping(FMLServerStoppingEvent event, Services services) {
        services.getDebugLogger().debugLog(NAME + " module: Server stopping.");
    }

    @Override
    public ICommand getCommand() {
        return new MentionCommand(this.services);
    }

    @Override
    public void registerEventListeners(Services services) {
        MinecraftForge.EVENT_BUS.register(this);
    }

    @SubscribeEvent
    public void onChatMessage(ServerChatEvent event) {
        if (this.services == null || !isEnabled(this.services)) return;

        MentionConfigHandler.Config mentionConfig = MentionConfigHandler.CONFIG;
        String rawMessage = event.getMessage();
        EntityPlayerMP sender = event.getPlayer();
        String mentionSymbol = mentionConfig.MENTION_SYMBOL.value;

        String everyoneMentionPlaceholder = mentionSymbol + "everyone";
        Pattern everyonePattern = Pattern.compile(Pattern.quote(everyoneMentionPlaceholder), Pattern.CASE_INSENSITIVE);
        Matcher everyoneMatcher = everyonePattern.matcher(rawMessage);

        if (everyoneMatcher.find()) {
            handleEveryoneMention(event, sender, rawMessage, mentionConfig, everyoneMatcher.group(0));
        } else {
            handleIndividualMentions(event, sender, rawMessage, mentionConfig);
        }
    }

    private void handleEveryoneMention(ServerChatEvent event, EntityPlayerMP sender, String rawMessage, MentionConfigHandler.Config mentionConfig, String matchedEveryoneMention) {
        if (!hasPermission(sender, PermissionsHandler.MENTION_EVERYONE_PERMISSION, PermissionsHandler.MENTION_EVERYONE_PERMISSION_LEVEL)) {
            sender.sendMessage(this.services.getMessageParser().parseMessage("&cYou do not have permission to mention everyone.", null));
            event.setCanceled(true);
            return;
        }
        if (!canMentionEveryone(sender, mentionConfig)) {
            sender.sendMessage(this.services.getMessageParser().parseMessage("&cYou are mentioning everyone too frequently. Please wait.", null));
            event.setCanceled(true);
            return;
        }

        this.services.getDebugLogger().debugLog("Mention everyone detected in chat by " + sender.getName());
        notifyEveryone(sender.getServer().getPlayerList().getPlayers(), sender, rawMessage, false, mentionConfig, matchedEveryoneMention);
        event.setCanceled(true);
    }

    private void handleIndividualMentions(ServerChatEvent event, EntityPlayerMP sender, String rawMessage, MentionConfigHandler.Config mentionConfig) {
        String mentionSymbol = mentionConfig.MENTION_SYMBOL.value;
        List<EntityPlayerMP> players = sender.getServer().getPlayerList().getPlayers();
        Pattern allPlayersPattern = buildAllPlayersMentionPattern(players, mentionSymbol);
        Matcher mentionMatcher = allPlayersPattern.matcher(rawMessage);

        boolean mentionedSomeone = false;
        while (mentionMatcher.find()) {
            String playerName = mentionMatcher.group(1);
            EntityPlayerMP targetPlayer = sender.getServer().getPlayerList().getPlayerByUsername(playerName);

            if (targetPlayer != null) {
                if (hasPermission(sender, PermissionsHandler.MENTION_PLAYER_PERMISSION, PermissionsHandler.MENTION_PLAYER_PERMISSION_LEVEL) && canMentionPlayer(sender, targetPlayer, mentionConfig)) {
                    this.services.getDebugLogger().debugLog("Mention player detected: " + targetPlayer.getName() + " by " + sender.getName());
                    notifyPlayer(targetPlayer, sender, rawMessage, false, mentionConfig, mentionMatcher.group(0));
                    mentionedSomeone = true;
                }
            }
        }
    }

    private int executeMentionCommand(MinecraftServer server, ICommandSender sender, String[] args) throws CommandException {
        if (args.length < 1) {
            throw new CommandException("Usage: /mention <message>");
        }
        String message = String.join(" ", args);
        List<EntityPlayerMP> players = server.getPlayerList().getPlayers();
        boolean isConsole = !(sender instanceof EntityPlayerMP);
        EntityPlayerMP senderPlayer = isConsole ? null : (EntityPlayerMP) sender;
        MentionConfigHandler.Config mentionConfig = MentionConfigHandler.CONFIG;

        if (message.contains(mentionConfig.MENTION_SYMBOL.value + "everyone")) {
            if (senderPlayer != null) {
                if (!hasPermission(senderPlayer, PermissionsHandler.MENTION_EVERYONE_PERMISSION, PermissionsHandler.MENTION_EVERYONE_PERMISSION_LEVEL)) {
                    throw new CommandException("You do not have permission to mention everyone.");
                }
                if (!canMentionEveryone(senderPlayer, mentionConfig)) {
                    throw new CommandException("You are mentioning everyone too frequently.");
                }
            } else {
                lastEveryoneMentionTime = System.currentTimeMillis();
            }
            notifyEveryone(players, senderPlayer, message, isConsole, mentionConfig, mentionConfig.MENTION_SYMBOL.value + "everyone");
            CommandBase.notifyCommandListener(sender, this.getCommand(), "Mentioned everyone successfully.", new Object[0]);
            return 1;
        }

        boolean mentionedSomeone = false;
        for (EntityPlayerMP targetPlayer : players) {
            String mention = mentionConfig.MENTION_SYMBOL.value + targetPlayer.getName();
            if (message.contains(mention)) {
                if (senderPlayer != null) {
                    if (!hasPermission(senderPlayer, PermissionsHandler.MENTION_PLAYER_PERMISSION, PermissionsHandler.MENTION_PLAYER_PERMISSION_LEVEL)) continue;
                    if (!canMentionPlayer(senderPlayer, targetPlayer, mentionConfig)) continue;
                } else {
                    lastIndividualMentionTime.put(targetPlayer.getUniqueID(), System.currentTimeMillis());
                }
                notifyPlayer(targetPlayer, senderPlayer, message, isConsole, mentionConfig, mention);
                mentionedSomeone = true;
            }
        }

        if (mentionedSomeone) {
            CommandBase.notifyCommandListener(sender, this.getCommand(), "Mentioned player(s) successfully.", new Object[0]);
        } else {
            throw new CommandException("No valid mentions found in the message.");
        }
        return mentionedSomeone ? 1 : 0;
    }

    private boolean canMentionEveryone(EntityPlayerMP sender, MentionConfigHandler.Config config) {
        if (sender != null && sender.canUseCommand(2, "")) return true;
        long rateLimit = config.EVERYONE_MENTION_RATE_LIMIT.value;
        if (rateLimit <= 0) return true;
        long currentTime = System.currentTimeMillis();
        if (currentTime - lastEveryoneMentionTime < rateLimit * 1000L) return false;
        lastEveryoneMentionTime = currentTime;
        return true;
    }

    private boolean canMentionPlayer(EntityPlayerMP sender, EntityPlayerMP targetPlayer, MentionConfigHandler.Config config) {
        if (sender != null && sender.canUseCommand(2, "")) return true;
        long rateLimit = config.INDIVIDUAL_MENTION_RATE_LIMIT.value;
        if (rateLimit <= 0) return true;
        long currentTime = System.currentTimeMillis();
        UUID targetUUID = targetPlayer.getUniqueID();
        if (lastIndividualMentionTime.containsKey(targetUUID) && currentTime - lastIndividualMentionTime.get(targetUUID) < rateLimit * 1000L) return false;
        lastIndividualMentionTime.put(targetUUID, currentTime);
        return true;
    }

    private void notifyEveryone(List<EntityPlayerMP> players, EntityPlayerMP sender, String originalMessage, boolean isConsole, MentionConfigHandler.Config config, String matchedEveryoneMention) {
        String senderName = isConsole || sender == null ? "Console" : sender.getName();
        String chatFormat = config.EVERYONE_MENTION_MESSAGE.value;
        String titleFormat = config.EVERYONE_TITLE_MESSAGE.value;
        String content = originalMessage.replace(matchedEveryoneMention, "").trim();

        String chatMessageText = String.format(chatFormat, senderName);
        String titleMessageText = String.format(titleFormat, senderName);

        for (EntityPlayerMP targetPlayer : players) {
            sendMentionNotification(targetPlayer, chatMessageText, titleMessageText, content);
        }
    }

    private void notifyPlayer(EntityPlayerMP targetPlayer, EntityPlayerMP sender, String originalMessage, boolean isConsole, MentionConfigHandler.Config config, String matchedPlayerMention) {
        String senderName = isConsole || sender == null ? "Console" : sender.getName();
        String chatFormat = config.INDIVIDUAL_MENTION_MESSAGE.value;
        String titleFormat = config.INDIVIDUAL_TITLE_MESSAGE.value;
        String content = originalMessage.replace(matchedPlayerMention, "").trim();

        String chatMessageText = String.format(chatFormat, senderName);
        String titleMessageText = String.format(titleFormat, senderName);

        sendMentionNotification(targetPlayer, chatMessageText, titleMessageText, content);
    }

    private void sendMentionNotification(EntityPlayerMP targetPlayer, String chatMessage, String titleMessage, String subtitleMessage) {
        ITextComponent formattedChatMessage = this.services.getMessageParser().parseMessage(chatMessage, targetPlayer);
        if (!subtitleMessage.isEmpty()) {
            ITextComponent formattedSubtitle = this.services.getMessageParser().parseMessage("- " + subtitleMessage, targetPlayer);
            formattedChatMessage.appendSibling(new TextComponentString("\n")).appendSibling(formattedSubtitle);
        }
        targetPlayer.sendMessage(formattedChatMessage);

        ITextComponent titleComp = this.services.getMessageParser().parseMessage(titleMessage, targetPlayer);
        targetPlayer.connection.sendPacket(new SPacketTitle(SPacketTitle.Type.TITLE, titleComp));

        if (!subtitleMessage.isEmpty()) {
            ITextComponent subtitleComp = this.services.getMessageParser().parseMessage(subtitleMessage, targetPlayer);
            targetPlayer.connection.sendPacket(new SPacketTitle(SPacketTitle.Type.SUBTITLE, subtitleComp));
        }
        SoundEvent sound = SoundEvent.REGISTRY.getObject(new ResourceLocation("entity.player.levelup"));
        if (sound != null) {
            targetPlayer.world.playSound(null, targetPlayer.posX, targetPlayer.posY, targetPlayer.posZ, sound, SoundCategory.PLAYERS, 1.0F, 1.0F);
        }
    }

    private boolean hasPermission(EntityPlayerMP player, String permissionNode, int permissionLevel) {
        return this.services.getPermissionsHandler().hasPermission(player, permissionNode) || player.canUseCommand(permissionLevel, "");
    }

    private Pattern buildAllPlayersMentionPattern(List<EntityPlayerMP> players, String mentionSymbol) {
        if (players.isEmpty()) {
            return Pattern.compile("a^");
        }
        String allPlayerNames = players.stream()
                .map(p -> Pattern.quote(p.getName()))
                .collect(Collectors.joining("|"));

        return Pattern.compile(Pattern.quote(mentionSymbol) + "(" + allPlayerNames + ")", Pattern.CASE_INSENSITIVE);
    }

    // --- Inner Command Class for 1.12.2 ---

    public class MentionCommand extends CommandBase {
        private final Services services;

        public MentionCommand(Services services) {
            this.services = services;
        }

        @Override
        public String getName() {
            return "mention";
        }

        @Override
        public String getUsage(ICommandSender sender) {
            return "/mention <message>";
        }

        @Override
        public void execute(MinecraftServer server, ICommandSender sender, String[] args) throws CommandException {
            Mentions.this.executeMentionCommand(server, sender, args);
        }

        @Override
        public List<String> getTabCompletions(MinecraftServer server, ICommandSender sender, String[] args, @Nullable BlockPos targetPos) {
            if (args.length >= 1) {
                return getListOfStringsMatchingLastWord(args, server.getOnlinePlayerNames());
            }
            return Collections.emptyList();
        }
    }
}
