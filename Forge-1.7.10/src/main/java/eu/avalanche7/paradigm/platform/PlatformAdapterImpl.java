package eu.avalanche7.paradigm.platform;

import java.net.InetSocketAddress;
import java.net.SocketAddress;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.WeakHashMap;
import java.util.concurrent.ConcurrentLinkedQueue;

import cpw.mods.fml.relauncher.ReflectionHelper;
import net.minecraft.block.Block;
import net.minecraft.command.CommandBase;
import net.minecraft.command.CommandHandler;
import net.minecraft.command.ICommand;
import net.minecraft.command.ICommandSender;
import net.minecraft.enchantment.Enchantment;
import net.minecraft.entity.Entity;
import net.minecraft.entity.SharedMonsterAttributes;
import net.minecraft.entity.player.EntityPlayerMP;
import net.minecraft.inventory.IInventory;
import net.minecraft.item.Item;
import net.minecraft.item.ItemStack;
import net.minecraft.nbt.NBTTagCompound;
import net.minecraft.nbt.NBTTagList;
import net.minecraft.network.play.server.S29PacketSoundEffect;
import net.minecraft.server.MinecraftServer;
import net.minecraft.util.AxisAlignedBB;
import net.minecraft.util.ChatComponentTranslation;
import net.minecraft.util.ChatStyle;
import net.minecraft.util.IChatComponent;
import net.minecraft.util.Vec3;
import net.minecraft.world.Teleporter;
import net.minecraft.world.WorldServer;
import net.minecraft.world.WorldSettings.GameType;
import net.minecraftforge.common.DimensionManager;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import eu.avalanche7.paradigm.data.CustomCommand;
import eu.avalanche7.paradigm.data.PlayerDataStore;
import eu.avalanche7.paradigm.modules.commands.shared.CommandCatalog;
import eu.avalanche7.paradigm.modules.permissions.PermissionsHandler;
import eu.avalanche7.paradigm.platform.Interfaces.ICommandBuilder;
import eu.avalanche7.paradigm.platform.Interfaces.ICommandSource;
import eu.avalanche7.paradigm.platform.Interfaces.IComponent;
import eu.avalanche7.paradigm.platform.Interfaces.IConfig;
import eu.avalanche7.paradigm.platform.Interfaces.IEventSystem;
import eu.avalanche7.paradigm.platform.Interfaces.IPlatformAdapter;
import eu.avalanche7.paradigm.platform.Interfaces.IPlayer;
import eu.avalanche7.paradigm.utils.CommandPriority;
import eu.avalanche7.paradigm.utils.CommandToggleStore;
import eu.avalanche7.paradigm.utils.DebugLogger;
import eu.avalanche7.paradigm.utils.MessageParser;
import eu.avalanche7.paradigm.utils.Placeholders;
import eu.avalanche7.paradigm.utils.TaskScheduler;

public final class PlatformAdapterImpl implements IPlatformAdapter {
    private static final Logger LOGGER = LoggerFactory.getLogger(PlatformAdapterImpl.class);
    private final ForgeConfig config;
    private final MinecraftEventSystem events = new MinecraftEventSystem();
    private final TaskScheduler scheduler;
    private final DebugLogger debugLogger;
    private Placeholders placeholders;
    private PermissionsHandler permissionsHandler;
    private final ConcurrentLinkedQueue<Runnable> mainTasks = new ConcurrentLinkedQueue<>();
    private final Set<String> priorityWarnings = new HashSet<>();
    private final Set<String> commandCollisions = new HashSet<>();
    private final Set<ForgeCommand> ownedCommands = new LinkedHashSet<>();
    private final Map<Object, Runnable> commandContributors = new LinkedHashMap<>();
    private final Map<String, ICommand> displacedCommands = new LinkedHashMap<>();
    private Object registeringContributor;
    private final Set<EntityPlayerMP> invulnerablePlayers = Collections.newSetFromMap(new WeakHashMap<>());
    private final Set<UUID> invulnerablePlayerIds = new HashSet<>();
    private CommandToggleStore commandToggles;
    private MinecraftServer server;
    private volatile Thread serverThread;
    private MessageParser parser;
    private final MinecraftMenuPlatform menuPlatform = new MinecraftMenuPlatform(this);
    private final MinecraftHologramPlatform hologramPlatform = new MinecraftHologramPlatform(this);
    private eu.avalanche7.paradigm.platform.visual.LegacyVisualController visuals;

    public PlatformAdapterImpl(PermissionsHandler permissionsHandler, Placeholders placeholders,
            TaskScheduler scheduler, DebugLogger debugLogger, ForgeConfig config) {
        this.permissionsHandler = permissionsHandler;
        this.placeholders = placeholders;
        this.scheduler = scheduler;
        this.debugLogger = debugLogger;
        this.config = config;
    }

    public void setPermissionsHandler(PermissionsHandler permissionsHandler) {
        this.permissionsHandler = permissionsHandler;
    }

    public void setCommandToggleStore(CommandToggleStore commandToggles) {
        this.commandToggles = commandToggles;
    }

    public void setPlaceholders(Placeholders placeholders) {
        this.placeholders = placeholders;
    }

    public void tick() {
        if (visuals != null) visuals.tick();
        hologramPlatform.tick();
        for (EntityPlayerMP player : invulnerablePlayers) {
            if (!player.capabilities.disableDamage) {
                player.capabilities.disableDamage = true;
                player.sendPlayerAbilities();
            }
        }
        for (int i = 0; i < 100; i++) {
            Runnable task = mainTasks.poll();
            if (task == null)
                break;
            try {
                task.run();
            } catch (RuntimeException failure) {
                LOGGER.error("Paradigm server task failed", failure);
            }
        }
    }

    public void clear() {
        menuPlatform.stopped();
        hologramPlatform.clear();
        if (visuals != null) visuals.clear();
        visuals = null;
        mainTasks.clear();
        invulnerablePlayers.clear();
        invulnerablePlayerIds.clear();
        commandContributors.clear();
        ownedCommands.clear();
        displacedCommands.clear();
        priorityWarnings.clear();
        registeringContributor = null;
        server = null;
        serverThread = null;
    }

    public void registerCommandContributor(Object contributor, Runnable registration) {
        if (contributor == null || registration == null) {
            throw new IllegalArgumentException("Command contributor and registration are required");
        }
        Runnable previous = commandContributors.put(contributor, registration);
        if (previous == null) {
            registerContributor(contributor, registration);
        } else {
            refreshRegisteredCommandContributors();
        }
    }

    private void registerContributor(Object contributor, Runnable registration) {
        Object previous = registeringContributor;
        registeringContributor = contributor;
        try {
            registration.run();
        } finally {
            registeringContributor = previous;
        }
    }

    public MinecraftEventSystem events() {
        return events;
    }

    private static EntityPlayerMP nativePlayer(IPlayer player) {
        if (player == null || !(player.getOriginalPlayer() instanceof EntityPlayerMP))
            throw new IllegalArgumentException("Expected an online 1.7.10 player");
        return (EntityPlayerMP) player.getOriginalPlayer();
    }

    private static IChatComponent nativeText(IComponent text) {
        if (text == null || !(text.getOriginalText() instanceof IChatComponent))
            throw new IllegalArgumentException("Expected a 1.7.10 component");
        return (IChatComponent) text.getOriginalText();
    }

    private static UnsupportedOperationException unsupported(String capability) {
        return new UnsupportedOperationException(
                "Forge 1.7.10 milestone does not implement " + capability);
    }

    @Override
    public void provideMessageParser(MessageParser value) {
        parser = value;
    }

    @Override
    public Object getMinecraftServer() {
        return server;
    }

    @Override
    public void setMinecraftServer(Object value) {
        if (server == value && visuals != null) return;
        server = (MinecraftServer) value;
        serverThread = value != null ? Thread.currentThread() : null;
        if (server != null) {
            visuals = new eu.avalanche7.paradigm.platform.visual.LegacyVisualController(server);
            visuals.bind();
            hologramPlatform.bind();
        }
    }

    @Override
    public List<IPlayer> getOnlinePlayers() {
        List<IPlayer> players = new ArrayList<>();
        if (server != null)
            for (Object player : server.getConfigurationManager().playerEntityList)
                players.add(new MinecraftPlayer((EntityPlayerMP) player));
        return players;
    }

    @Override
    public IPlayer getPlayerByName(String name) {
        EntityPlayerMP player =
                server == null ? null : server.getConfigurationManager().func_152612_a(name);
        return player == null ? null : new MinecraftPlayer(player);
    }

    @Override
    public IPlayer getPlayerByUuid(String uuid) {
        for (IPlayer player : getOnlinePlayers())
            if (player.getUUID().equalsIgnoreCase(uuid))
                return player;
        return null;
    }

    @Override
    public String getPlayerName(IPlayer player) {
        return player.getName();
    }

    @Override
    public IComponent getPlayerDisplayName(IPlayer player) {
        return new MinecraftComponent(nativePlayer(player).getDisplayName());
    }

    @Override
    public IComponent createLiteralComponent(String text) {
        return new MinecraftComponent(text);
    }

    @Override
    public IComponent createTranslatableComponent(String key, Object... args) {
        return new MinecraftComponent(new ChatComponentTranslation(key, args));
    }

    @Override
    public Object createItemStack(String itemId) {
        Item item = (Item) Item.itemRegistry.getObject(itemId);
        return item == null ? null : new ItemStack(item);
    }

    @Override
    public boolean hasPermission(IPlayer player, String node) {
        if (permissionsHandler == null) {
            debugLogger.debugLog("Forge 1.7.10 permission check before runtime binding: " + node);
            return false;
        }
        return permissionsHandler.hasPermission(player, node);
    }

    @Override
    public boolean hasPermission(IPlayer player, String node, int vanillaLevel) {
        if (permissionsHandler == null) {
            debugLogger.debugLog("Forge 1.7.10 permission check before runtime binding: " + node);
            return false;
        }
        return permissionsHandler.hasPermission(player, node, vanillaLevel);
    }

    @Override
    public boolean hasVanillaPermissionLevel(IPlayer player, int level) {
        EntityPlayerMP handle = operationPlayer(player);
        return handle != null && level >= 0
                && (level == 0 || handle.canCommandSenderUseCommand(level, "paradigm"));
    }

    @Override
    public void sendSystemMessage(IPlayer player, IComponent message) {
        nativePlayer(player).addChatMessage(nativeText(message));
    }

    @Override
    public void broadcastSystemMessage(IComponent message) {
        if (server != null)
            server.getConfigurationManager().sendChatMsg(nativeText(message));
    }

    @Override
    public void broadcastChatMessage(IComponent message) {
        broadcastSystemMessage(message);
    }

    @Override
    public void broadcastSystemMessage(
            IComponent message, String header, String footer, IPlayer player) {
        if (parser == null)
            throw new IllegalStateException("Message parser is not initialized");
        IComponent h = parser.parseMessage(header, player);
        IComponent f = parser.parseMessage(footer, player);
        for (IPlayer recipient : getOnlinePlayers()) {
            sendSystemMessage(recipient, h);
            sendSystemMessage(recipient, message);
            sendSystemMessage(recipient, f);
        }
    }

    @Override
    public void sendTitle(IPlayer player, IComponent title, IComponent subtitle) {
        executeOnServerThread(() -> { if (visuals != null) visuals.title(operationPlayer(player), nativeText(title), nativeText(subtitle)); });
    }

    @Override
    public void sendSubtitle(IPlayer player, IComponent subtitle) {
        executeOnServerThread(() -> { if (visuals != null) visuals.feedback(operationPlayer(player), nativeText(subtitle)); });
    }

    @Override
    public void sendActionBar(IPlayer player, IComponent message) {
        executeOnServerThread(() -> { if (visuals != null) visuals.actionbar(operationPlayer(player), nativeText(message)); });
    }

    @Override
    public void sendBossBar(List<IPlayer> players, IComponent message, int duration,
            BossBarColor color, float progress) {
        executeOnServerThread(() -> {
            if (visuals != null) for (IPlayer player : players) visuals.feedback(operationPlayer(player), nativeText(message));
        });
    }

    @Override
    public void showPersistentBossBar(
            IPlayer player, IComponent message, BossBarColor color, BossBarOverlay overlay) {
        executeOnServerThread(() -> { if (visuals != null) visuals.persistent(operationPlayer(player), nativeText(message)); });
    }

    @Override
    public void removePersistentBossBar(IPlayer player) {
        executeOnServerThread(() -> { if (visuals != null) visuals.removePersistent(operationPlayer(player)); });
    }

    @Override
    public void createOrUpdateRestartBossBar(
            IComponent message, BossBarColor color, float progress) {
        executeOnServerThread(() -> { if (visuals != null) visuals.restart(nativeText(message)); });
    }

    @Override
    public void removeRestartBossBar() {
        executeOnServerThread(() -> { if (visuals != null) visuals.removeRestart(); });
    }

    @Override
    public void clearTitles(IPlayer player) {
        executeOnServerThread(() -> { if (visuals != null) visuals.clearTitles(operationPlayer(player)); });
    }

    @Override
    public void playSound(
            IPlayer player, String sound, String category, float volume, float pitch) {
        EntityPlayerMP handle = operationPlayer(player);
        String nativeSound = soundName(sound);
        if (handle == null || nativeSound == null || !Float.isFinite(volume) || volume < 0.0f
                || !Float.isFinite(pitch) || pitch < 0.0f || pitch > 255.0f / 63.0f) return;
        handle.playerNetServerHandler.sendPacket(new S29PacketSoundEffect(nativeSound,
                handle.posX, handle.posY, handle.posZ, volume, pitch));
    }

    @Override
    public void executeCommandAs(ICommandSource source, String command) {
        if (server == null || !(source.getOriginalSource() instanceof ICommandSender))
            throw new IllegalStateException("No active command source");
        server.getCommandManager().executeCommand(
                (ICommandSender) source.getOriginalSource(), stripSlash(command));
    }

    @Override
    public void executeCommandAsConsole(String command) {
        if (server == null)
            throw new IllegalStateException("Server has not started");
        server.getCommandManager().executeCommand(server, stripSlash(command));
    }
    private static String stripSlash(String command) {
        return command.startsWith("/") ? command.substring(1) : command;
    }

    @Override
    public TaskScheduler getTaskScheduler() {
        return scheduler;
    }

    @Override
    public void executeOnServerThread(Runnable task) {
        if (task == null)
            return;
        if (Thread.currentThread() == serverThread)
            task.run();
        else
            mainTasks.add(task);
    }

    public boolean isServerThread() { return server != null && Thread.currentThread() == serverThread; }

    @Override public eu.avalanche7.paradigm.platform.Interfaces.IMenuPlatform getMenuPlatform() { return menuPlatform; }
    @Override public eu.avalanche7.paradigm.platform.Interfaces.IHologramPlatform getHologramPlatform() { return hologramPlatform; }

    @Override
    public Object getConsoleCommandSource() {
        return server;
    }

    @Override
    public ICommandSource createCommandSourceForPlayer(IPlayer player) {
        return new MinecraftCommandSource(nativePlayer(player));
    }

    private static EntityPlayerMP operationPlayer(IPlayer player) {
        return player != null && player.getOriginalPlayer() instanceof EntityPlayerMP handle ? handle : null;
    }

    private static GameType gameType(String mode) {
        if (mode == null) {
            return null;
        }
        return switch (mode.trim().toLowerCase(Locale.ROOT)) {
            case "0", "s", "survival" -> GameType.SURVIVAL;
            case "1", "c", "creative" -> GameType.CREATIVE;
            case "2", "a", "adventure" -> GameType.ADVENTURE;
            default -> null;
        };
    }

    @Override
    public boolean supportsGameMode(String mode) {
        return gameType(mode) != null;
    }

    @Override
    public boolean setGameMode(IPlayer player, String mode) {
        EntityPlayerMP handle = operationPlayer(player);
        GameType type = gameType(mode);
        if (handle == null || type == null) {
            return false;
        }
        handle.setGameType(type);
        if (invulnerablePlayers.contains(handle)) {
            handle.capabilities.disableDamage = true;
            handle.sendPlayerAbilities();
        }
        return true;
    }

    private static boolean validSpeed(double speed) {
        return Double.isFinite(speed) && speed >= 0.0;
    }

    @Override
    public boolean setMovementSpeed(IPlayer player, double speed) {
        EntityPlayerMP handle = operationPlayer(player);
        if (handle == null || !validSpeed(speed)) {
            return false;
        }
        var attribute = handle.getEntityAttribute(SharedMonsterAttributes.movementSpeed);
        if (attribute == null) {
            return false;
        }
        attribute.setBaseValue(speed);
        return true;
    }

    @Override
    public boolean setTimeOfDay(long time) {
        if (server == null || server.worldServers == null) {
            return false;
        }
        boolean changed = false;
        for (WorldServer world : server.worldServers) {
            if (world != null) {
                world.setWorldTime(time);
                changed = true;
            }
        }
        return changed;
    }

    @Override
    public boolean setWeather(String weather) {
        if (server == null || server.worldServers == null || weather == null) {
            return false;
        }
        String mode = weather.trim().toLowerCase(Locale.ROOT);
        if (!Set.of("clear", "sun", "rain", "thunder").contains(mode)) {
            return false;
        }
        boolean changed = false;
        for (WorldServer world : server.worldServers) {
            if (world != null) {
                var info = world.getWorldInfo();
                info.setRainTime(6000);
                info.setThunderTime(6000);
                info.setRaining(mode.equals("rain") || mode.equals("thunder"));
                info.setThundering(mode.equals("thunder"));
                changed = true;
            }
        }
        return changed;
    }

    @Override
    public boolean healPlayer(IPlayer player) {
        EntityPlayerMP handle = operationPlayer(player);
        if (handle == null) {
            return false;
        }
        handle.setHealth(handle.getMaxHealth());
        return true;
    }

    @Override
    public boolean feedPlayer(IPlayer player) {
        EntityPlayerMP handle = operationPlayer(player);
        if (handle == null) {
            return false;
        }
        NBTTagCompound food = new NBTTagCompound();
        food.setInteger("foodLevel", 20);
        food.setFloat("foodSaturationLevel", 20.0f);
        food.setFloat("foodExhaustionLevel", 0.0f);
        food.setInteger("foodTickTimer", 0);
        handle.getFoodStats().readNBT(food);
        return true;
    }

    @Override
    public Boolean toggleFlight(IPlayer player) {
        EntityPlayerMP handle = operationPlayer(player);
        if (handle == null) {
            return null;
        }
        boolean enabled = !handle.capabilities.allowFlying;
        handle.capabilities.allowFlying = enabled;
        if (!enabled) {
            handle.capabilities.isFlying = false;
        }
        handle.sendPlayerAbilities();
        return enabled;
    }

    @Override
    public boolean setPlayerSpeed(IPlayer player, float walk, float fly, double base) {
        EntityPlayerMP handle = operationPlayer(player);
        if (handle == null || !validSpeed(walk) || !validSpeed(fly) || !validSpeed(base)
                || !setMovementSpeed(player, base)) {
            return false;
        }
        NBTTagCompound state = new NBTTagCompound();
        handle.capabilities.writeCapabilitiesToNBT(state);
        state.getCompoundTag("abilities").setFloat("walkSpeed", walk);
        state.getCompoundTag("abilities").setFloat("flySpeed", fly);
        handle.capabilities.readCapabilitiesFromNBT(state);
        handle.sendPlayerAbilities();
        return true;
    }

    @Override
    public boolean clearPlayerInventory(IPlayer player) {
        EntityPlayerMP handle = operationPlayer(player);
        if (handle == null) {
            return false;
        }
        handle.inventory.clearInventory(null, -1);
        handle.inventory.markDirty();
        handle.inventoryContainer.detectAndSendChanges();
        handle.updateHeldItem();
        if (handle.openContainer != handle.inventoryContainer) {
            handle.openContainer.detectAndSendChanges();
        }
        return true;
    }

    @Override
    public boolean setPlayerInvulnerable(IPlayer player, boolean enabled) {
        EntityPlayerMP handle = operationPlayer(player);
        if (handle == null) {
            return false;
        }
        if (enabled) {
            invulnerablePlayers.add(handle);
            invulnerablePlayerIds.add(handle.getUniqueID());
        } else {
            invulnerablePlayers.remove(handle);
            invulnerablePlayerIds.remove(handle.getUniqueID());
        }
        handle.capabilities.disableDamage = enabled || handle.capabilities.isCreativeMode;
        handle.sendPlayerAbilities();
        return true;
    }

    @Override
    public boolean setPlayerVanished(IPlayer player, boolean enabled) {
        EntityPlayerMP handle = operationPlayer(player);
        if (handle == null || visuals == null) return false;
        executeOnServerThread(() -> visuals.setVanished(handle, enabled));
        return true;
    }

    @Override
    public List<InventoryItem> inspectPlayerInventory(IPlayer player, boolean enderChest) {
        EntityPlayerMP handle = operationPlayer(player);
        if (handle == null) {
            return List.of();
        }
        IInventory inventory = enderChest ? handle.getInventoryEnderChest() : handle.inventory;
        List<InventoryItem> items = new ArrayList<>();
        for (int slot = 0; slot < inventory.getSizeInventory(); slot++) {
            ItemStack stack = inventory.getStackInSlot(slot);
            if (!occupied(stack)) {
                continue;
            }
            String name;
            try {
                name = stack.getDisplayName();
            } catch (RuntimeException failure) {
                LOGGER.warn("Cannot read inventory display name for {} slot {}", handle.getCommandSenderName(), slot, failure);
                name = String.valueOf(Item.itemRegistry.getNameForObject(stack.getItem()));
            }
            items.add(new InventoryItem(slot, stack.stackSize, name));
        }
        return List.copyOf(items);
    }

    @Override
    public int repairPlayerItems(IPlayer player, boolean all) {
        EntityPlayerMP handle = operationPlayer(player);
        if (handle == null) {
            return 0;
        }
        int changed = 0;
        if (all) {
            for (int slot = 0; slot < handle.inventory.getSizeInventory(); slot++) {
                if (repair(handle.inventory.getStackInSlot(slot))) {
                    changed++;
                }
            }
        } else if (repair(handle.inventory.getCurrentItem())) {
            changed = 1;
        }
        if (changed > 0) {
            synchronizeInventory(handle);
        }
        return changed;
    }

    @Override
    public boolean enchantMainHand(IPlayer player, String enchantment, int level) {
        EntityPlayerMP handle = operationPlayer(player);
        if (handle == null || enchantment == null || level < 1 || level > 255) {
            return false;
        }
        String id = enchantment.trim().toLowerCase(Locale.ROOT);
        if (!id.contains(":")) {
            id = "minecraft:" + id;
        }
        Enchantment effect = enchantments().get(id);
        ItemStack stack = handle.inventory.getCurrentItem();
        if (effect == null || effect.effectId < 0 || effect.effectId > Short.MAX_VALUE
                || !occupied(stack) || !effect.canApply(stack)) {
            return false;
        }
        NBTTagList tags = stack.getEnchantmentTagList();
        if (tags == null) {
            tags = new NBTTagList();
            stack.setTagInfo("ench", tags);
        }
        for (int i = 0; i < tags.tagCount(); i++) {
            NBTTagCompound tag = tags.getCompoundTagAt(i);
            if (tag.getShort("id") == effect.effectId) {
                tag.setShort("lvl", (short) level);
                synchronizeInventory(handle);
                return true;
            }
        }
        NBTTagCompound tag = new NBTTagCompound();
        tag.setShort("id", (short) effect.effectId);
        tag.setShort("lvl", (short) level);
        tags.appendTag(tag);
        stack.setTagInfo("ench", tags);
        synchronizeInventory(handle);
        return true;
    }

    @Override
    public List<String> getAvailableEnchantmentIds() {
        return List.copyOf(enchantments().keySet());
    }

    private static Map<String, Enchantment> enchantments() {
        Map<String, Enchantment> result = new LinkedHashMap<>();
        Set<String> ambiguous = new HashSet<>();
        for (Enchantment effect : Enchantment.enchantmentsList) {
            if (effect == null || effect.effectId < 0 || effect.effectId > Short.MAX_VALUE) {
                continue;
            }
            result.put("enchantment:" + effect.effectId, effect);
            String name = effect.getName();
            if (name != null && !name.isBlank()) {
                String key = "enchantment:" + name.replaceFirst("^enchantment\\.", "").toLowerCase(Locale.ROOT);
                if (key.matches("enchantment:[a-z_][a-z0-9_.:/-]*")) {
                    Enchantment existing = result.putIfAbsent(key, effect);
                    if (existing != null && existing != effect) {
                        ambiguous.add(key);
                    }
                }
            }
        }
        ambiguous.forEach(result::remove);
        Map<String, Enchantment> vanilla = new LinkedHashMap<>();
        vanilla.put("minecraft:protection", Enchantment.protection);
        vanilla.put("minecraft:fire_protection", Enchantment.fireProtection);
        vanilla.put("minecraft:feather_falling", Enchantment.featherFalling);
        vanilla.put("minecraft:blast_protection", Enchantment.blastProtection);
        vanilla.put("minecraft:projectile_protection", Enchantment.projectileProtection);
        vanilla.put("minecraft:respiration", Enchantment.respiration);
        vanilla.put("minecraft:aqua_affinity", Enchantment.aquaAffinity);
        vanilla.put("minecraft:thorns", Enchantment.thorns);
        vanilla.put("minecraft:sharpness", Enchantment.sharpness);
        vanilla.put("minecraft:smite", Enchantment.smite);
        vanilla.put("minecraft:bane_of_arthropods", Enchantment.baneOfArthropods);
        vanilla.put("minecraft:knockback", Enchantment.knockback);
        vanilla.put("minecraft:fire_aspect", Enchantment.fireAspect);
        vanilla.put("minecraft:looting", Enchantment.looting);
        vanilla.put("minecraft:efficiency", Enchantment.efficiency);
        vanilla.put("minecraft:silk_touch", Enchantment.silkTouch);
        vanilla.put("minecraft:unbreaking", Enchantment.unbreaking);
        vanilla.put("minecraft:fortune", Enchantment.fortune);
        vanilla.put("minecraft:power", Enchantment.power);
        vanilla.put("minecraft:punch", Enchantment.punch);
        vanilla.put("minecraft:flame", Enchantment.flame);
        vanilla.put("minecraft:infinity", Enchantment.infinity);
        vanilla.put("minecraft:luck_of_the_sea", Enchantment.field_151370_z);
        vanilla.put("minecraft:lure", Enchantment.field_151369_A);
        vanilla.forEach((id, effect) -> {
            if (effect != null && result.containsValue(effect)) {
                result.put(id, effect);
            }
        });
        return result;
    }

    private static boolean occupied(ItemStack stack) {
        return stack != null && stack.getItem() != null && stack.stackSize > 0;
    }

    private static boolean repair(ItemStack stack) {
        if (!occupied(stack) || !stack.isItemStackDamageable() || !stack.isItemDamaged()) {
            return false;
        }
        stack.setItemDamage(0);
        return !stack.isItemDamaged();
    }

    private static void synchronizeInventory(EntityPlayerMP player) {
        player.inventory.markDirty();
        player.inventoryContainer.detectAndSendChanges();
        player.updateHeldItem();
        if (player.openContainer != player.inventoryContainer) {
            player.openContainer.detectAndSendChanges();
        }
    }

    private static AxisAlignedBB playerBounds(double x, double y, double z) {
        return AxisAlignedBB.getBoundingBox(x - 0.3, y, z - 0.3, x + 0.3, y + 1.8, z + 0.3);
    }

    @Override
    public Integer getHighestBlockY(IPlayer player) {
        EntityPlayerMP handle = operationPlayer(player);
        return handle != null && handle.worldObj instanceof WorldServer world
                ? safeSurface(world, handle.posX, handle.posZ).map(y -> y.intValue() - 1).orElse(null)
                : null;
    }

    @Override
    public boolean jumpPlayerForward(IPlayer player, int distance) {
        EntityPlayerMP handle = operationPlayer(player);
        if (handle == null || !(handle.worldObj instanceof WorldServer world) || distance < 1 || distance > 128) {
            return false;
        }
        Vec3 look = handle.getLook(1.0f);
        double x = handle.posX + look.xCoord * distance;
        double y = handle.posY + look.yCoord * distance;
        double z = handle.posZ + look.zCoord * distance;
        if (!Double.isFinite(x) || !Double.isFinite(y) || !Double.isFinite(z)
                || Math.abs(x) >= 29999872 || Math.abs(z) >= 29999872) {
            return false;
        }
        AxisAlignedBB previous = playerBounds(handle.posX, handle.posY, handle.posZ);
        for (int step = 1; step <= distance * 2; step++) {
            double progress = step * 0.5;
            AxisAlignedBB next = playerBounds(handle.posX + look.xCoord * progress,
                    handle.posY + look.yCoord * progress, handle.posZ + look.zCoord * progress);
            AxisAlignedBB swept = AxisAlignedBB.getBoundingBox(
                    Math.min(previous.minX, next.minX), Math.min(previous.minY, next.minY), Math.min(previous.minZ, next.minZ),
                    Math.max(previous.maxX, next.maxX), Math.max(previous.maxY, next.maxY), Math.max(previous.maxZ, next.maxZ));
            if (swept.minY < 0 || swept.maxY > Math.min(world.getHeight(), world.getActualHeight())
                    || !world.checkChunksExist((int) Math.floor(swept.minX), (int) Math.floor(swept.minY), (int) Math.floor(swept.minZ),
                            (int) Math.floor(swept.maxX), (int) Math.floor(swept.maxY), (int) Math.floor(swept.maxZ))
                    || world.isAnyLiquid(swept) || !world.func_147461_a(swept).isEmpty()) {
                return false;
            }
            previous = next;
        }
        return teleportPlayer(player, new PlayerDataStore.StoredLocation(player.getWorldId(), x, y, z,
                handle.rotationYaw, handle.rotationPitch));
    }

    @Override
    public String replacePlaceholders(String text, IPlayer player) {
        if (placeholders == null)
            throw new IllegalStateException("Placeholders are not initialized");
        return placeholders.replacePlaceholders(text, player);
    }

    @Override
    public boolean hasPermissionForCustomCommand(ICommandSource source, CustomCommand command) {
        if (!command.isRequirePermission())
            return true;
        IPlayer player = source.getPlayer();
        return player == null ? source.isConsole() : hasPermission(player, command.getPermission());
    }

    @Override
    public void shutdownServer(IComponent message) {
        executeOnServerThread(() -> {
            if (server == null) return;
            if (visuals != null) visuals.shutdown(((MinecraftComponent) message).toLegacyText());
            server.initiateShutdown();
        });
    }

    @Override
    public void sendSuccess(ICommandSource source, IComponent message, boolean toOps) {
        ICommandSender sender = (ICommandSender) source.getOriginalSource();
        sender.addChatMessage(nativeText(message));
        if (toOps) CommandBase.func_152374_a(sender, COMMAND_FEEDBACK, 1, "%s", nativeText(message).createCopy());
    }

    @Override
    public void sendFailure(ICommandSource source, IComponent message) {
        sendSuccess(source, message, false);
    }

    @Override
    public void teleportPlayer(IPlayer player, double x, double y, double z) {
        EntityPlayerMP handle = operationPlayer(player);
        if (handle != null) {
            teleportPlayer(player, new PlayerDataStore.StoredLocation(String.valueOf(handle.dimension),
                    x, y, z, handle.rotationYaw, handle.rotationPitch));
        }
    }

    @Override
    public boolean teleportPlayer(IPlayer player, PlayerDataStore.StoredLocation location) {
        return teleport(server, operationPlayer(player), location);
    }

    @Override
    public Optional<Double> findSafeRtpY(IPlayer player, double x, double z) {
        EntityPlayerMP handle = operationPlayer(player);
        return handle != null && handle.worldObj instanceof WorldServer world
                ? safeSurface(world, x, z) : Optional.empty();
    }

    public void playerTransferred(EntityPlayerMP player) {
        if (player.openContainer instanceof eu.avalanche7.paradigm.platform.menu.ParadigmMenuContainer) player.closeScreen();
        hologramPlatform.transferred(player);
        if (visuals != null) visuals.transferred(player);
    }

    @Override
    public boolean setPlayerListDisplayName(IPlayer player, IComponent displayName) {
        EntityPlayerMP handle = operationPlayer(player);
        if (handle == null || visuals == null || Thread.currentThread() != serverThread) return false;
        return visuals.tablist().display(handle, displayName == null ? null : ((MinecraftComponent) displayName).toLegacyText());
    }

    @Override
    public void resetPlayerListState(IPlayer player) {
        executeOnServerThread(() -> { if (visuals != null && operationPlayer(player) != null) visuals.tablist().reset(operationPlayer(player)); });
    }

    public void playerRespawned(EntityPlayerMP replacement) {
        menuPlatform.respawned(replacement);
        hologramPlatform.transferred(replacement);
        if (visuals != null) {
            if (!visuals.vanish().visible(replacement, null)) visuals.setVanished(replacement, true);
            visuals.transferred(replacement);
        }
        invulnerablePlayers.removeIf(previous ->
                replacement.getUniqueID().equals(previous.getUniqueID()));
        if (invulnerablePlayerIds.contains(replacement.getUniqueID())) {
            setPlayerInvulnerable(new MinecraftPlayer(replacement), true);
        }
    }

    public void playerDisconnected(EntityPlayerMP player) {
        menuPlatform.disconnected(player);
        hologramPlatform.disconnected(player);
        if (visuals != null) visuals.disconnected(player);
        invulnerablePlayers.remove(player);
        invulnerablePlayerIds.remove(player.getUniqueID());
    }

    @Override
    public boolean playerHasItem(IPlayer player, String itemId, int amount) {
        if (player == null || !(player.getOriginalPlayer() instanceof EntityPlayerMP handle)) return false;
        if (handle == null || handle.inventory == null || itemId == null || itemId.isBlank()) return false;
        Item item = (Item) Item.itemRegistry.getObject(itemId);
        if (item == null) return false;
        long count = 0;
        for (ItemStack stack : handle.inventory.mainInventory) {
            if (stack != null && stack.getItem() == item) count += Math.max(0, stack.stackSize);
            if (count >= amount) return true;
        }
        return count >= amount;
    }

    @Override
    public boolean isPlayerInArea(
            IPlayer player, String worldId, List<Integer> a, List<Integer> b) {
        if (player == null || !(player.getOriginalPlayer() instanceof EntityPlayerMP handle)) return false;
        if (handle == null || worldId == null || a == null || b == null
                || a.size() != 3 || b.size() != 3
                || a.stream().anyMatch(java.util.Objects::isNull) || b.stream().anyMatch(java.util.Objects::isNull)) return false;
        int dimension;
        try {
            dimension = Integer.parseInt(worldId);
        } catch (NumberFormatException invalid) {
            return false;
        }
        return handle.dimension == dimension
                && handle.posX >= Math.min(a.get(0), b.get(0)) && handle.posX <= Math.max(a.get(0), b.get(0))
                && handle.posY >= Math.min(a.get(1), b.get(1)) && handle.posY <= Math.max(a.get(1), b.get(1))
                && handle.posZ >= Math.min(a.get(2), b.get(2)) && handle.posZ <= Math.max(a.get(2), b.get(2));
    }

    @Override
    public List<String> getOnlinePlayerNames() {
        List<String> names = new ArrayList<>();
        for (IPlayer player : getOnlinePlayers()) names.add(player.getName());
        return names;
    }

    @Override
    public List<String> getWorldNames() {
        List<String> names = new ArrayList<>();
        if (server != null)
            for (net.minecraft.world.WorldServer world : server.worldServers)
                if (world != null)
                    names.add(String.valueOf(world.provider.dimensionId));
        return names;
    }

    @Override
    public int getPlayerPing(IPlayer player) {
        return nativePlayer(player).ping;
    }

    @Override
    public int getMaxPlayers() {
        return server == null ? 0 : server.getConfigurationManager().getMaxPlayers();
    }

    @Override
    public IPlayer wrapPlayer(Object player) {
        return new MinecraftPlayer((EntityPlayerMP) player);
    }

    @Override
    public ICommandSource wrapCommandSource(Object source) {
        return new MinecraftCommandSource((ICommandSender) source);
    }

    @Override
    public IComponent createEmptyComponent() {
        return new MinecraftComponent("");
    }

    @Override
    public IComponent parseFormattingCode(String code, IComponent current) {
        return current.withFormatting(code);
    }

    @Override
    public IComponent parseHexColor(String hex, IComponent current) {
        return current.withColorHex(hex);
    }

    @Override
    public IComponent wrap(Object text) {
        return new MinecraftComponent((IChatComponent) text);
    }

    @Override
    public IComponent createComponentFromLiteral(String text) {
        return createLiteralComponent(text);
    }

    @Override
    public String getMinecraftVersion() {
        return "1.7.10";
    }

    @Override
    public String getLoaderName() {
        return "forge";
    }

    @Override
    public String getPlayerRemoteAddress(IPlayer player) {
        EntityPlayerMP nativePlayer = nativePlayer(player);
        if (nativePlayer.playerNetServerHandler == null
                || nativePlayer.playerNetServerHandler.netManager == null) {
            return null;
        }
        SocketAddress address = nativePlayer.playerNetServerHandler.netManager.getSocketAddress();
        if (address instanceof InetSocketAddress inet && inet.getAddress() != null) {
            return inet.getAddress().getHostAddress();
        }
        return null;
    }

    @Override
    public boolean disconnectPlayer(IPlayer player, IComponent reason) {
        EntityPlayerMP nativePlayer = nativePlayer(player);
        if (nativePlayer.playerNetServerHandler == null) {
            return false;
        }
        nativePlayer.playerNetServerHandler.kickPlayerFromServer(
                reason instanceof MinecraftComponent component ? component.toLegacyText()
                        : reason != null ? reason.getRawText() : "Disconnected");
        return true;
    }

    @Override
    public Object createStyleWithClickEvent(Object base, String action, String value) {
        MinecraftComponent component = new MinecraftComponent("");
        component.setStyle(base == null ? new ChatStyle() : base);
        IComponent updated = switch (action.toLowerCase(java.util.Locale.ROOT)) {
            case "run_command" -> component.onClickRunCommand(value);
            case "suggest_command" -> component.onClickSuggestCommand(value);
            case "copy_to_clipboard" -> component.onClickSuggestCommand(value);
            case "open_url" -> component.onClickOpenUrl(value);
            default -> throw unsupported("click action " + action);
        };
        return updated.getStyle();
    }

    @Override
    public Object createStyleWithHoverEvent(Object base, Object hover) {
        MinecraftComponent component = new MinecraftComponent("");
        component.setStyle(base == null ? new ChatStyle() : base);
        IComponent text = hover instanceof IComponent wrapped
                ? wrapped
                : hover instanceof IChatComponent nativeText
                        ? wrap(nativeText)
                        : createLiteralComponent(String.valueOf(hover));
        IComponent updated = component.onHoverComponent(text);
        return updated.getStyle();
    }

    @Override
    public IConfig getConfig() {
        return config;
    }

    @Override
    public IEventSystem getEventSystem() {
        return events;
    }

    @Override
    public ICommandBuilder createCommandBuilder() {
        return new ForgeCommandBuilder();
    }

    @Override
    public void registerCommand(ICommandBuilder builder) {
        if (server == null || !(server.getCommandManager() instanceof CommandHandler manager)) {
            throw new IllegalStateException("Native command manager is not available");
        }
        if (!(builder.build() instanceof ForgeCommandBuilder root) || root.literalName() == null) {
            throw new IllegalArgumentException("Expected a Forge 1.7.10 literal command root");
        }
        String name = root.literalName();
        CommandCatalog.Entry entry = CommandCatalog.findByRoot(name);
        if (commandToggles != null && entry != null && !commandToggles.isEnabled(entry.id())) {
            return;
        }
        ICommand occupied = (ICommand) manager.getCommands().get(name);
        Object contributor = registeringContributor == null ? this : registeringContributor;
        if (occupied instanceof ForgeCommand own && own.ownedBy(this)) {
            commandCollisions.remove(name);
            own.addRoot(root, contributor);
            return;
        }
        if (occupied != null) {
            if (commandToggles != null && CommandPriority.shouldOwnRoot(name)) {
                displacedCommands.put(name, occupied);
                nativeCommandSet(manager).remove(occupied);
                if (priorityWarnings.add(name)) {
                    LOGGER.warn("Paradigm command /{} takes priority over {} because forceCommandPriorityEnable is enabled",
                            name, occupied.getClass().getName());
                }
            } else {
                if (commandCollisions.add(name)) {
                    LOGGER.warn("Paradigm command /{} was not registered: already owned by {}",
                            name, occupied.getClass().getName());
                }
                return;
            }
        }
        ForgeCommand command = new ForgeCommand(root, this, contributor);
        manager.registerCommand(command);
        commandCollisions.remove(name);
        ownedCommands.add(command);
    }

    @Override
    public boolean hasRegisteredCommandRoot(String rootLiteral) {
        return server != null && server.getCommandManager() instanceof CommandHandler manager
                && manager.getCommands().containsKey(rootLiteral);
    }

    @Override
    public Object getCommandDispatcher() {
        return server == null ? null : server.getCommandManager();
    }

    @Override
    public long getLogEventTime(Object event) {
        return ((org.apache.logging.log4j.core.LogEvent) event).getMillis();
    }

    @Override
    public void registerCommandsForContributor(Object contributor, Runnable registration) {
        registerContributor(contributor, registration);
    }

    @Override
    public boolean unregisterCommandRoot(String rootLiteral, Object contributor) {
        if (server == null || !(server.getCommandManager() instanceof CommandHandler manager)) return false;
        ICommand occupied = (ICommand) manager.getCommands().get(rootLiteral);
        if (!(occupied instanceof ForgeCommand command) || !command.ownedBy(this)
                || !command.hasContributor(contributor)) return false;
        if (command.removeContributor(contributor)) {
            removeNativeCommand(manager, rootLiteral, command);
            ownedCommands.remove(command);
        }
        return true;
    }

    @Override
    public boolean ownsRegisteredCommandRoot(String rootLiteral) {
        if (server == null || !(server.getCommandManager() instanceof CommandHandler manager)) {
            return false;
        }
        ICommand command = (ICommand) manager.getCommands().get(rootLiteral);
        return command instanceof ForgeCommand own && own.ownedBy(this);
    }

    @Override
    public boolean unregisterCommandRoot(String rootLiteral) {
        if (server == null || !(server.getCommandManager() instanceof CommandHandler manager)) {
            return false;
        }
        ICommand occupied = (ICommand) manager.getCommands().get(rootLiteral);
        if (occupied instanceof ForgeCommand command && command.ownedBy(this)) {
            removeNativeCommand(manager, rootLiteral, command);
            ownedCommands.remove(command);
            return true;
        }
        for (ForgeCommand command : new ArrayList<>(ownedCommands)) {
            if (command.getCommandName().equals(rootLiteral)) {
                removeNativeCommand(manager, rootLiteral, command);
                ownedCommands.remove(command);
                return true;
            }
        }
        return false;
    }

    @Override
    public boolean refreshRegisteredCommandContributors() {
        if (server == null || !(server.getCommandManager() instanceof CommandHandler manager)) {
            return false;
        }
        for (ForgeCommand command : new ArrayList<>(ownedCommands)) {
            for (Object contributor : commandContributors.keySet()) {
                command.removeContributor(contributor);
            }
            if (command.isEmpty()) {
                removeNativeCommand(manager, command.getCommandName(), command);
                ownedCommands.remove(command);
            }
        }
        commandContributors.forEach(this::registerContributor);
        return true;
    }

    @SuppressWarnings("unchecked")
    private static Set<ICommand> nativeCommandSet(CommandHandler manager) {
        return ReflectionHelper.getPrivateValue(CommandHandler.class, manager,
                "commandSet", "field_71561_b", "c");
    }

    private void removeNativeCommand(CommandHandler manager, String root, ForgeCommand command) {
        boolean removed = manager.getCommands().remove(root, command);
        nativeCommandSet(manager).remove(command);
        ICommand displaced = displacedCommands.remove(root);
        if (removed && displaced != null) {
            manager.getCommands().put(root, displaced);
            nativeCommandSet(manager).add(displaced);
        }
    }

    @Override
    public void rewireCommandTreePermissions() {
    }

    @Override
    public void refreshPlayerCommandTree(IPlayer player) {
    }

    @Override
    public void refreshAllPlayerCommandTrees() {
    }

    @Override
    public boolean isFirstJoin(IPlayer player) {
        EntityPlayerMP handle = operationPlayer(player);
        return handle != null && MinecraftEventSystem.firstJoin(handle);
    }
    @Override
    public boolean supportsTitles() {
        return false;
    }

    @Override
    public boolean supportsPersistentBossBar() {
        return false;
    }

    static String soundName(String id) {
        if (id == null || id.isBlank() || id.length() > 256) return null;
        String name = id.toLowerCase(Locale.ROOT);
        if (!name.matches("[a-z0-9_.-]+(:[a-z0-9_./-]+)?")) return null;
        return switch (name) {
            case "minecraft:entity.experience_orb.pickup" -> "random.orb";
            case "minecraft:block.note_block.pling" -> "note.pling";
            default -> name.startsWith("minecraft:") ? name.substring(10) : name;
        };
    }

    private static final ICommand COMMAND_FEEDBACK = new CommandBase() {
        @Override
        public String getCommandName() {
            return "paradigm";
        }

        @Override
        public String getCommandUsage(ICommandSender sender) {
            return "";
        }

        @Override
        public int getRequiredPermissionLevel() {
            return 0;
        }

        @Override
        public void processCommand(ICommandSender sender, String[] arguments) {
            throw new UnsupportedOperationException("Feedback descriptor is not an executable command");
        }
    };

    private static WorldServer resolve(MinecraftServer server, String worldId) {
        if (server == null || server.worldServers == null || worldId == null) {
            return null;
        }
        final int id;
        try {
            id = Integer.parseInt(worldId);
        } catch (NumberFormatException invalid) {
            return null;
        }
        for (WorldServer world : server.worldServers) {
            if (world != null && world.provider.dimensionId == id) {
                return world;
            }
        }
        if (!DimensionManager.isDimensionRegistered(id)) {
            return null;
        }
        WorldServer world = server.worldServerForDimension(id);
        return world != null && world.provider.dimensionId == id ? world : null;
    }

    private static boolean teleport(MinecraftServer server, EntityPlayerMP player,
            PlayerDataStore.StoredLocation location) {
        if (player == null || location == null || player.playerNetServerHandler == null
                || !player.isEntityAlive() || !validHorizontal(location.getX(), location.getZ())
                || !Double.isFinite(location.getY()) || !Float.isFinite(location.getYaw())
                || !Float.isFinite(location.getPitch())) {
            return false;
        }
        WorldServer destination = resolve(server, location.getWorldId());
        if (destination == null || location.getY() < 0 || location.getY() >= destination.getHeight()) {
            return false;
        }
        destination.getChunkFromChunkCoords((int) Math.floor(location.getX()) >> 4,
                (int) Math.floor(location.getZ()) >> 4);
        if (player.ridingEntity != null) {
            player.mountEntity(null);
        }
        if (player.dimension != destination.provider.dimensionId) {
            int previousDimension = player.dimension;
            player.setLocationAndAngles(location.getX(), location.getY(), location.getZ(),
                    location.getYaw(), location.getPitch());
            server.getConfigurationManager().transferPlayerToDimension(player,
                    destination.provider.dimensionId, new DestinationTeleporter(destination, location));
            if (previousDimension == 1) {
                destination.spawnEntityInWorld(player);
                destination.updateEntityWithOptionalForce(player, false);
            }
        }
        player.motionX = player.motionY = player.motionZ = 0;
        player.fallDistance = 0;
        player.playerNetServerHandler.setPlayerLocation(location.getX(), location.getY(), location.getZ(),
                location.getYaw(), location.getPitch());
        return true;
    }

    private static Optional<Double> safeSurface(WorldServer world, double x, double z) {
        if (world == null || !validHorizontal(x, z) || world.provider.hasNoSky) {
            return Optional.empty();
        }
        int blockX = (int) Math.floor(x);
        int blockZ = (int) Math.floor(z);
        world.getChunkFromChunkCoords(blockX >> 4, blockZ >> 4);
        int height = Math.min(world.getHeight(), world.getActualHeight());
        for (int y = height - 1; y >= 0; y--) {
            Block ground = world.getBlock(blockX, y, blockZ);
            if (world.isAirBlock(blockX, y, blockZ)) {
                continue;
            }
            if (y + 2 >= height || ground.getMaterial().isLiquid() || !ground.isNormalCube(world, blockX, y, blockZ)
                    || !world.isAirBlock(blockX, y + 1, blockZ)
                    || !world.isAirBlock(blockX, y + 2, blockZ)) {
                return Optional.empty();
            }
            AxisAlignedBB clearance = AxisAlignedBB.getBoundingBox(x - 0.3, y + 1, z - 0.3,
                    x + 0.3, y + 3, z + 0.3);
            if (world.isAnyLiquid(clearance)
                    || !world.func_147461_a(clearance).isEmpty()) {
                return Optional.empty();
            }
            return Optional.of((double) y + 1);
        }
        return Optional.empty();
    }

    private static boolean validHorizontal(double x, double z) {
        return Double.isFinite(x) && Double.isFinite(z)
                && Math.abs(x) < 29999872 && Math.abs(z) < 29999872;
    }

    private static final class DestinationTeleporter extends Teleporter {
        private final PlayerDataStore.StoredLocation location;

        private DestinationTeleporter(WorldServer world, PlayerDataStore.StoredLocation location) {
            super(world);
            this.location = location;
        }

        @Override
        public void placeInPortal(Entity entity, double x, double y, double z, float yaw) {
            entity.setLocationAndAngles(location.getX(), location.getY(), location.getZ(),
                    location.getYaw(), location.getPitch());
        }
    }
}
