package eu.avalanche7.paradigm.platform;

import java.lang.ref.WeakReference;
import java.util.Arrays;
import java.util.Collections;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.TreeSet;
import java.util.WeakHashMap;
import java.util.function.BooleanSupplier;

import cpw.mods.fml.common.eventhandler.EventBus;
import net.minecraft.command.CommandHandler;
import net.minecraft.command.ICommand;
import net.minecraft.command.ICommandSender;
import net.minecraft.entity.player.EntityPlayerMP;
import net.minecraftforge.event.CommandEvent;

import eu.avalanche7.paradigm.modules.permissions.PermissionsHandler.CommandGuardResult;

public final class CrucibleCommandBridge {
    private static final String CRAFT_PLAYER = "org.bukkit.craftbukkit.v1_7_R4.entity.CraftPlayer";
    private static final Map<Object, Binding> BINDINGS = Collections.synchronizedMap(new WeakHashMap<>());
    private static final ThreadLocal<Forwarding> FORWARDING = new ThreadLocal<>();

    private CrucibleCommandBridge() {
    }

    public static void bind(Object wrapper, ICommand command, String permission, String namespace) {
        String ownedRoot = command instanceof ForgeCommand ? command.getCommandName() : null;
        BINDINGS.put(wrapper, new Binding(new WeakReference<>(command), permission, namespace, ownedRoot));
    }

    static String originalNode(ICommand command) {
        synchronized (BINDINGS) {
            return BINDINGS.values().stream().filter(binding -> binding.command().get() == command)
                    .map(Binding::permission).filter(node -> node != null && !node.isEmpty())
                    .distinct().sorted().collect(java.util.stream.Collectors.joining(";"));
        }
    }

    static Set<String> namespacedLabels(ICommand command) {
        Set<String> labels = new TreeSet<>();
        synchronized (BINDINGS) {
            BINDINGS.values().stream().filter(binding -> binding.command().get() == command)
                    .forEach(binding -> labels.add((binding.namespace() + ":" + command.getCommandName())
                            .toLowerCase(Locale.ROOT)));
        }
        return labels;
    }

    public static boolean testWrapper(Object wrapper, Object sender, String line) {
        try {
            Class<?> senderType = Class.forName("org.bukkit.command.CommandSender");
            var testPermission = wrapper.getClass().getMethod("testPermission", senderType);
            BooleanSupplier nativeGate = () -> {
                try {
                    return (Boolean) testPermission.invoke(wrapper, sender);
                } catch (ReflectiveOperationException failure) {
                    throw new IllegalStateException("Could not check the Crucible wrapper permission", failure);
                }
            };
            PlatformAdapterImpl platform = MinecraftEventSystem.activePlatform();
            if (platform == null || !(platform.getCommandDispatcher() instanceof CommandHandler manager)
                    || !Class.forName(CRAFT_PLAYER).isInstance(sender)) return nativeGate.getAsBoolean();
            EntityPlayerMP player = (EntityPlayerMP) sender.getClass().getMethod("getHandle").invoke(sender);
            String name = (String) wrapper.getClass().getMethod("getName").invoke(wrapper);
            String permission = (String) wrapper.getClass().getMethod("getPermission").invoke(wrapper);
            return testWrapper(platform, manager, wrapper, player, name, permission, line, nativeGate);
        } catch (ReflectiveOperationException failure) {
            throw new IllegalStateException("Could not resolve the Crucible command sender", failure);
        }
    }

    static boolean testWrapper(PlatformAdapterImpl platform, CommandHandler manager, Object wrapper,
            EntityPlayerMP player, String wrapperName, String wrapperPermission, String line, BooleanSupplier nativeGate) {
        int separator = line.indexOf(' ');
        String label = separator < 0 ? line : line.substring(0, separator);
        boolean namespaced = label.indexOf(':') >= 0;
        String forwardedLine = namespaced ? wrapperName + line.substring(label.length()) : line;
        String nativeLine = forwardedLine.trim();
        if (nativeLine.startsWith("/")) nativeLine = nativeLine.substring(1);
        String[] tokens = nativeLine.split(" ");
        if (tokens.length == 0) return nativeGate.getAsBoolean();
        ICommand command = (ICommand) manager.getCommands().get(tokens[0]);
        if (command == null) return nativeGate.getAsBoolean();

        Binding binding = BINDINGS.get(wrapper);
        ICommand registered = binding == null ? null : binding.command().get();
        boolean matches = registered == command;
        boolean owned = command instanceof ForgeCommand own && own.ownedBy(platform);
        boolean displaced = owned && !namespaced && label.equals(command.getCommandName())
                && platform.displacedCommand(label, registered);
        // Crucible may retain a bare wrapper after the replaced Forge command is collected.
        boolean retainedOwnedRoot = registered == null && binding != null && owned
                && label.equals(binding.ownedRoot()) && label.equals(wrapperName) && label.equals(command.getCommandName());
        boolean staleOwnWrapper = !namespaced && !matches && (registered instanceof ForgeCommand || retainedOwnedRoot);
        boolean enabled = platform.commandPermissions() != null
                && platform.commandPermissions().isExternalCommandPermissionsEnabled();
        if (enabled && binding != null && !matches && !displaced && !staleOwnWrapper) {
            LegacyCommandPermissions.deny(player, null);
            return false;
        }

        String[] args = Arrays.copyOfRange(tokens, 1, tokens.length);
        String node = owned ? null : matches ? binding.permission() : originalNode(command);
        CommandGuardResult result = LegacyCommandPermissions.evaluate(platform, manager, player, command, args, node);
        if (!result.allowed()) {
            LegacyCommandPermissions.deny(player, result.node());
            return false;
        }

        boolean canDelegate = matches && !owned && Objects.equals(binding.permission(), wrapperPermission)
                && "explicit_allow".equals(result.reason()) && command.canCommandSenderUseCommand(player);
        boolean claimedRoot = enabled && displaced && Objects.equals(binding.permission(), wrapperPermission);
        if (!(canDelegate || claimedRoot) && !nativeGate.getAsBoolean()) return false;
        if ((!matches && !owned && !command.canCommandSenderUseCommand(player))
                || manager.getCommands().get(tokens[0]) != command) {
            LegacyCommandPermissions.deny(player, null);
            return false;
        }

        Forwarding previous = FORWARDING.get();
        FORWARDING.set(new Forwarding(manager, player, command, args, node, canDelegate));
        try {
            manager.executeCommand(player, forwardedLine);
        } finally {
            if (previous == null) FORWARDING.remove();
            else FORWARDING.set(previous);
        }
        return false;
    }

    public static void bindInvocation(CommandHandler manager, ICommandSender sender, ICommand command,
            String[] parameters) {
        Forwarding scope = FORWARDING.get();
        if (scope != null && scope.manager == manager && scope.player == sender && scope.command == command
                && scope.parameters == null && Arrays.equals(scope.expected, parameters)) {
            scope.parameters = parameters;
        }
    }

    public static boolean post(CommandHandler manager, EventBus bus, CommandEvent event) {
        return post(MinecraftEventSystem.activePlatform(), manager, event, () -> bus.post(event));
    }

    static boolean post(PlatformAdapterImpl platform, CommandHandler manager, CommandEvent event, BooleanSupplier post) {
        if (platform == null || platform.getCommandDispatcher() != manager
                || !(event.sender instanceof EntityPlayerMP player)) return post.getAsBoolean();

        Forwarding scope = FORWARDING.get();
        boolean forwarded = scope != null && scope.parameters != null && scope.parameters == event.parameters;
        if (!forwarded && platform.isCrucibleCommandManager()
                && !(event.command instanceof ForgeCommand own && own.ownedBy(platform))
                && !event.command.canCommandSenderUseCommand(player)) {
            LegacyCommandPermissions.deny(player, null);
            return true;
        }
        String node = forwarded ? scope.node : originalNode(event.command);
        String[] parameters = event.parameters;
        if (!forwarded || !Arrays.equals(scope.expected, parameters)) {
            if (!allowed(platform, manager, player, event.command, parameters, node, forwarded && scope.requiresExplicit)) return true;
        }
        if (platform.commandPermissions() == null || !platform.commandPermissions().isExternalCommandPermissionsEnabled()) {
            return post.getAsBoolean();
        }

        String[] original = parameters.clone();
        if (post.getAsBoolean()) return true;
        return !Arrays.equals(original, parameters) && !allowed(platform, manager, player, event.command, parameters, node, forwarded && scope.requiresExplicit);
    }

    private static boolean allowed(PlatformAdapterImpl platform, CommandHandler manager, EntityPlayerMP player,
            ICommand command, String[] parameters, String node, boolean requiresExplicit) {
        CommandGuardResult result = LegacyCommandPermissions.evaluate(platform, manager, player, command, parameters, node);
        boolean allowed = result.allowed() && (!requiresExplicit || "explicit_allow".equals(result.reason()));
        if (!allowed) LegacyCommandPermissions.deny(player, result.node());
        return allowed;
    }

    private record Binding(WeakReference<ICommand> command, String permission, String namespace, String ownedRoot) {}

    private static final class Forwarding {
        final CommandHandler manager;
        final EntityPlayerMP player;
        final ICommand command;
        final String[] expected;
        final String node;
        final boolean requiresExplicit;
        String[] parameters;

        Forwarding(CommandHandler manager, EntityPlayerMP player, ICommand command, String[] expected, String node, boolean requiresExplicit) {
            this.manager = manager;
            this.player = player;
            this.command = command;
            this.expected = expected;
            this.node = node;
            this.requiresExplicit = requiresExplicit;
        }
    }
}
