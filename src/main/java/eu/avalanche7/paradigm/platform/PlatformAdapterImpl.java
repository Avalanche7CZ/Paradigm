package eu.avalanche7.paradigm.platform;

import eu.avalanche7.paradigm.data.CustomCommand;
import eu.avalanche7.paradigm.utils.*;
import net.minecraft.advancements.*;
import net.minecraft.advancements.critereon.ImpossibleTrigger;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.MutableComponent;
import net.minecraft.network.protocol.game.ClientboundClearTitlesPacket;
import net.minecraft.network.protocol.game.ClientboundSetActionBarTextPacket;
import net.minecraft.network.protocol.game.ClientboundSetSubtitleTextPacket;
import net.minecraft.network.protocol.game.ClientboundSetTitleTextPacket;
import net.minecraft.network.protocol.game.ClientboundUpdateAdvancementsPacket;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerBossEvent;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.sounds.SoundEvent;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.BossEvent;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraftforge.registries.ForgeRegistries;
import javax.annotation.Nullable;
import java.util.*;
import java.util.concurrent.TimeUnit;

public class PlatformAdapterImpl implements IPlatformAdapter {

    private MinecraftServer server;
    private MessageParser messageParser;
    private final PermissionsHandler permissionsHandler;
    private final Placeholders placeholders;
    private final TaskScheduler taskScheduler;
    private final DebugLogger debugLogger;
    private final Map<UUID, ServerBossEvent> persistentBossBars = new HashMap<>();
    private ServerBossEvent restartBossBar;

    public PlatformAdapterImpl(
            PermissionsHandler permissionsHandler,
            Placeholders placeholders,
            TaskScheduler taskScheduler,
            DebugLogger debugLogger
    ) {
        this.permissionsHandler = permissionsHandler;
        this.placeholders = placeholders;
        this.taskScheduler = taskScheduler;
        this.debugLogger = debugLogger;
    }

    @Override
    public void provideMessageParser(MessageParser messageParser) {
        this.messageParser = messageParser;
    }

    private AdvancementType toMinecraftFrame(AdvancementFrame frame) {
        return AdvancementType.valueOf(frame.name());
    }


    @Override
    public void displayToast(ServerPlayer player, ResourceLocation id, ItemStack icon, @Nullable Component title, Component unused, AdvancementFrame frame) {
        try {
            DisplayInfo displayInfo = new DisplayInfo(
                    icon,
                    title != null ? title : Component.empty(),
                    Component.empty(),
                    Optional.empty(),
                    toMinecraftFrame(frame),
                    true,
                    true,
                    false
            );

            Map<String, Criterion<?>> criteria = Map.of(
                    "trigger", new Criterion<>(new ImpossibleTrigger(), new ImpossibleTrigger.TriggerInstance())
            );
            AdvancementRequirements requirements = AdvancementRequirements.allOf(Collections.singleton("trigger"));

            Advancement advancement = new Advancement(
                    Optional.empty(),
                    Optional.of(displayInfo),
                    AdvancementRewards.EMPTY,
                    criteria,
                    requirements,
                    false
            );
            AdvancementHolder holder = new AdvancementHolder(id, advancement);

            AdvancementProgress progress = new AdvancementProgress();
            progress.update(requirements);
            progress.getCriterion("trigger").grant();

            ClientboundUpdateAdvancementsPacket packet = new ClientboundUpdateAdvancementsPacket(
                    false,
                    List.of(holder),
                    Set.of(),
                    Map.of(id, progress)
            );

            player.connection.send(packet);

            taskScheduler.schedule(() -> revokeToast(player, id), 5, TimeUnit.SECONDS);

        } catch (Exception e) {
            debugLogger.debugLog("Paradigm: Failed to send simplified toast.", e);
        }
    }


    @Override
    public void revokeToast(ServerPlayer player, ResourceLocation id) {
        try {
            if (player.connection != null) {
                ClientboundUpdateAdvancementsPacket removePacket = new ClientboundUpdateAdvancementsPacket(true, List.of(), Set.of(id), Map.of());
                player.connection.send(removePacket);
            }
        } catch (Exception e) {
            debugLogger.debugLog("Paradigm: Failed to revoke toast.", e);
        }
    }

    @Override
    public MinecraftServer getMinecraftServer() {
        return this.server;
    }

    @Override
    public void setMinecraftServer(MinecraftServer server) {
        this.server = server;
    }

    @Override
    public List<ServerPlayer> getOnlinePlayers() {
        return getMinecraftServer().getPlayerList().getPlayers();
    }

    @Override
    @Nullable
    public ServerPlayer getPlayerByName(String name) {
        return getMinecraftServer().getPlayerList().getPlayerByName(name);
    }

    @Override
    @Nullable
    public ServerPlayer getPlayerByUuid(UUID uuid) {
        return getMinecraftServer().getPlayerList().getPlayer(uuid);
    }

    @Override
    public String getPlayerName(ServerPlayer player) {
        return player.getName().getString();
    }

    @Override
    public Component getPlayerDisplayName(ServerPlayer player) {
        return player.getDisplayName();
    }

    @Override
    public MutableComponent createLiteralComponent(String text) {
        return Component.literal(text);
    }

    @Override
    public MutableComponent createTranslatableComponent(String key, Object... args) {
        return Component.translatable(key, args);
    }

    @Override
    public ItemStack createItemStack(String itemId) {
        var item = ForgeRegistries.ITEMS.getValue(ResourceLocation.parse(itemId));
        return item != null ? new ItemStack(item) : new ItemStack(Items.STONE);
    }

    @Override
    public boolean hasPermission(ServerPlayer player, String permissionNode) {
        return permissionsHandler.hasPermission(player, permissionNode);
    }

    @Override
    public boolean hasPermission(ServerPlayer player, String permissionNode, int vanillaLevel) {
        return this.hasPermission(player, permissionNode) || player.hasPermissions(vanillaLevel);
    }

    @Override
    public void sendSystemMessage(ServerPlayer player, Component message) {
        player.sendSystemMessage(message);
    }

    @Override
    public void broadcastSystemMessage(Component message) {
        if (getMinecraftServer() != null) {
            getMinecraftServer().getPlayerList().broadcastSystemMessage(message, false);
        }
    }

    @Override
    public void broadcastChatMessage(Component message) {
        if (getMinecraftServer() != null) {
            getMinecraftServer().getPlayerList().broadcastSystemMessage(message, false);
        }
    }

    @Override
    public void broadcastSystemMessage(Component message, String header, String footer, @Nullable ServerPlayer playerContext) {
        if (messageParser == null) return;
        Component headerComp = messageParser.parseMessage(header, playerContext);
        Component footerComp = messageParser.parseMessage(footer, playerContext);
        getOnlinePlayers().forEach(p -> {
            p.sendSystemMessage(headerComp);
            p.sendSystemMessage(message);
            p.sendSystemMessage(footerComp);
        });
    }

    @Override
    public void sendTitle(ServerPlayer player, Component title, Component subtitle) {
        player.connection.send(new ClientboundSetTitleTextPacket(title));
        if (subtitle != null && !subtitle.getString().isEmpty()) {
            player.connection.send(new ClientboundSetSubtitleTextPacket(subtitle));
        }
    }

    @Override
    public void sendSubtitle(ServerPlayer player, Component subtitle) {
        if (subtitle != null && !subtitle.getString().isEmpty()) {
            player.connection.send(new ClientboundSetSubtitleTextPacket(subtitle));
        }
    }

    @Override
    public void sendActionBar(ServerPlayer player, Component message) {
        player.connection.send(new ClientboundSetActionBarTextPacket(message));
    }

    private BossEvent.BossBarColor toMinecraftColor(BossBarColor color) {
        return BossEvent.BossBarColor.valueOf(color.name());
    }

    private BossEvent.BossBarOverlay toMinecraftOverlay(BossBarOverlay overlay) {
        return BossEvent.BossBarOverlay.valueOf(overlay.name());
    }

    @Override
    public void sendBossBar(List<ServerPlayer> players, Component message, int durationSeconds, BossBarColor color, float progress) {
        ServerBossEvent bossEvent = new ServerBossEvent(message, toMinecraftColor(color), BossEvent.BossBarOverlay.PROGRESS);
        bossEvent.setProgress(progress);
        players.forEach(bossEvent::addPlayer);
        taskScheduler.schedule(() -> {
            bossEvent.removeAllPlayers();
            bossEvent.setVisible(false);
        }, durationSeconds, TimeUnit.SECONDS);
    }

    @Override
    public void showPersistentBossBar(ServerPlayer player, Component message, BossBarColor color, BossBarOverlay overlay) {
        removePersistentBossBar(player);
        ServerBossEvent bossEvent = new ServerBossEvent(message, toMinecraftColor(color), toMinecraftOverlay(overlay));
        bossEvent.addPlayer(player);
        persistentBossBars.put(player.getUUID(), bossEvent);
    }

    @Override
    public void removePersistentBossBar(ServerPlayer player) {
        ServerBossEvent bossBar = persistentBossBars.remove(player.getUUID());
        if (bossBar != null) {
            bossBar.removePlayer(player);
        }
    }

    @Override
    public void createOrUpdateRestartBossBar(Component message, BossBarColor color, float progress) {
        if (restartBossBar == null) {
            restartBossBar = new ServerBossEvent(message, toMinecraftColor(color), BossEvent.BossBarOverlay.PROGRESS);
            restartBossBar.setVisible(true);
            getOnlinePlayers().forEach(restartBossBar::addPlayer);
        }
        restartBossBar.setName(message);
        restartBossBar.setProgress(progress);
    }

    @Override
    public void removeRestartBossBar() {
        if (restartBossBar != null) {
            restartBossBar.setVisible(false);
            restartBossBar.removeAllPlayers();
            restartBossBar = null;
        }
    }

    @Override
    public void clearTitles(ServerPlayer player) {
        player.connection.send(new ClientboundClearTitlesPacket(true));
    }

    @Override
    public void playSound(ServerPlayer player, String soundId, float volume, float pitch) {
        SoundEvent soundEvent = ForgeRegistries.SOUND_EVENTS.getValue(ResourceLocation.parse(soundId));
        if (soundEvent != null) {
            player.playNotifySound(soundEvent, SoundSource.MASTER, volume, pitch);
        }
    }

    @Override
    public void executeCommandAs(CommandSourceStack source, String command) {
        getMinecraftServer().getCommands().performPrefixedCommand(source, command);
    }

    @Override
    public void executeCommandAsConsole(String command) {
        CommandSourceStack consoleSource = getMinecraftServer().createCommandSourceStack().withPermission(4);
        getMinecraftServer().getCommands().performPrefixedCommand(consoleSource, command);
    }

    @Override
    public String replacePlaceholders(String text, @Nullable ServerPlayer player) {
        return placeholders.replacePlaceholders(text, player);
    }

    @Override
    public boolean hasPermissionForCustomCommand(CommandSourceStack source, CustomCommand command) {
        if (!command.isRequirePermission()) {
            return true;
        }
        if (!(source.getEntity() instanceof ServerPlayer player)) {
            return true;
        }
        boolean hasPerm = permissionsHandler.hasPermission(player, command.getPermission());
        if (!hasPerm && messageParser != null) {
            String errorMessage = command.getPermissionErrorMessage();
            player.sendSystemMessage(messageParser.parseMessage(errorMessage, player));
        }
        return hasPerm;
    }

    @Override
    public void shutdownServer(Component kickMessage) {
        if (server != null) {
            try {
                server.getPlayerList().broadcastSystemMessage(kickMessage, false);
                server.saveEverything(true, true, true);
                server.halt(false);
            } catch (Exception e) {}
        }
    }

    @Override
    public void sendSuccess(CommandSourceStack source, Component message, boolean toOps) {
        source.sendSuccess(() -> message, toOps);
    }

    @Override
    public void sendFailure(CommandSourceStack source, Component message) {
        source.sendFailure(message);
    }

    @Override
    public void teleportPlayer(ServerPlayer player, double x, double y, double z) {
        player.teleportTo(x, y, z);
    }
}