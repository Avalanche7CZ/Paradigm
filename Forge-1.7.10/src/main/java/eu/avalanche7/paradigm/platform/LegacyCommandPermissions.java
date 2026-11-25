package eu.avalanche7.paradigm.platform;

import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.Collection;
import java.util.IdentityHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;

import net.minecraft.command.CommandBase;
import net.minecraft.command.CommandHandler;
import net.minecraft.command.ICommand;
import net.minecraft.command.ICommandSender;
import net.minecraft.entity.player.EntityPlayerMP;
import net.minecraft.util.ChatComponentTranslation;
import net.minecraft.util.EnumChatFormatting;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import eu.avalanche7.paradigm.modules.permissions.PermissionNodeRegistry.CommandNode;
import eu.avalanche7.paradigm.modules.permissions.PermissionsHandler;
import eu.avalanche7.paradigm.modules.permissions.PermissionsHandler.CommandGuardResult;

final class LegacyCommandPermissions {
    private static final Logger LOGGER = LoggerFactory.getLogger(LegacyCommandPermissions.class);
    private static final String SOURCE = "legacy_command";
    private static final int MAX_DEPTH = 12;
    private static final String SERVER_UTILITIES_TREE = "serverutils.lib.command.CommandTreeBase";

    private static final ClassValue<TreeMethods> TREES = new ClassValue<>() {
        @Override
        protected TreeMethods computeValue(Class<?> type) {
            for (Class<?> base = type; base != null; base = base.getSuperclass()) {
                if (!base.getName().equals(SERVER_UTILITIES_TREE)) continue;
                try {
                    Method child = type.getMethod("getSubCommand", String.class);
                    Method children = type.getMethod("getSubCommands");
                    Method process = commandExecutor(type);
                    if (process.getDeclaringClass() == base && child.getDeclaringClass() == base
                            && children.getDeclaringClass() == base) {
                        return new TreeMethods(child, children);
                    }
                } catch (ReflectiveOperationException | LinkageError failure) {
                    LOGGER.warn("Could not inspect the ServerUtilities command tree {}", type.getName(), failure);
                }
                break;
            }
            return new TreeMethods(null, null);
        }
    };

    private LegacyCommandPermissions() {
    }

    private static Method commandExecutor(Class<?> type) throws NoSuchMethodException {
        try {
            return type.getMethod("func_71515_b", ICommandSender.class, String[].class);
        } catch (NoSuchMethodException unmapped) {
            return type.getMethod("processCommand", ICommandSender.class, String[].class);
        }
    }

    static CommandGuardResult evaluate(PlatformAdapterImpl platform, CommandHandler manager, EntityPlayerMP player,
            ICommand command, String[] args, String wrapperNode) {
        PermissionsHandler permissions = platform.commandPermissions();
        String root = segment(command.getCommandName());
        if (permissions == null || !permissions.isExternalCommandPermissionsEnabled()) {
            return CommandGuardResult.allowed(command.getCommandName(), null, "disabled");
        }

        Object normalized = root == null ? null : manager.getCommands().get(root);
        boolean canonical = manager.getCommands().get(command.getCommandName()) == command
                && (normalized == null || normalized == command);
        List<String> path = new ArrayList<>();
        if (root != null) path.add(root);
        if (root != null && command instanceof ForgeCommand own) {
            path.addAll(own.literalPath(args, MAX_DEPTH - 1));
        } else if (root != null) {
            ICommand selected = command;
            for (int i = 0; i < args.length && path.size() < MAX_DEPTH; i++) {
                ICommand parent = selected;
                selected = child(parent, args[i]);
                if (selected == null) break;
                String name = segment(selected.getCommandName());
                if (name == null) {
                    canonical = false;
                    break;
                }
                ICommand normalizedChild = child(parent, name);
                canonical &= child(parent, selected.getCommandName()) == selected
                        && (normalizedChild == null || normalizedChild == selected);
                path.add(name);
            }
        }

        Set<String> candidates = new LinkedHashSet<>();
        for (int length = path.size(); length > 0; length--) {
            String name = String.join(".", path.subList(0, length));
            candidates.add("command." + name);
            candidates.add(name);
        }
        candidates.addAll(originalNodes(wrapperNode));
        String line = command.getCommandName() + (args.length == 0 ? "" : " " + String.join(" ", args));
        return permissions.evaluateCommandPermission(new MinecraftPlayer(player), line, candidates,
                () -> command instanceof ForgeCommand own && own.ownedBy(platform), canonical);
    }

    static void deny(EntityPlayerMP player, String node) {
        ChatComponentTranslation message = new ChatComponentTranslation("commands.generic.permission");
        message.getChatStyle().setColor(EnumChatFormatting.RED);
        if (node != null) message.appendText(" §7(" + node + ")");
        player.addChatMessage(message);
    }

    static int discover(PermissionsHandler permissions, Map<String, ICommand> commands) {
        Map<String, CommandNode> nodes = new TreeMap<>();
        Map<ICommand, Set<String>> labels = new IdentityHashMap<>();
        commands.forEach((label, command) -> {
            if (command != null && label != null) labels.computeIfAbsent(command, ignored -> new TreeSet<>()).add(label);
        });
        labels.forEach((command, aliases) -> {
            String name = command.getCommandName();
            String root = segment(name);
            if (commands.containsKey(name) && commands.get(name) != command) return;
            for (String node : originalNodes(CrucibleCommandBridge.originalNode(command))) {
                nodes.putIfAbsent(node, new CommandNode(node, "Crucible wrapper permission for /" + name, -1));
            }
            if (root == null || (commands.containsKey(root) && commands.get(root) != command)) return;
            aliases.remove(name);
            aliases.addAll(CrucibleCommandBridge.namespacedLabels(command));
            put(nodes, root, aliases, level(command));
            if (command instanceof ForgeCommand own) {
                for (String path : own.literalPaths(MAX_DEPTH - 1)) {
                    put(nodes, root + "." + path, Set.of(), -1);
                }
            } else {
                discoverChildren(nodes, command, root, 1);
            }
        });
        return permissions.replaceDiscoveredCommandNodes(SOURCE, nodes.values());
    }

    private static void discoverChildren(Map<String, CommandNode> nodes, ICommand parent, String prefix, int depth) {
        if (depth >= MAX_DEPTH) return;
        Method children = TREES.get(parent.getClass()).children();
        if (children == null) return;
        try {
            for (Object entry : (Collection<?>) children.invoke(parent)) {
                if (!(entry instanceof ICommand command)) continue;
                String name = segment(command.getCommandName());
                if (name == null || child(parent, command.getCommandName()) != command) continue;
                ICommand normalized = child(parent, name);
                if (normalized != null && normalized != command) continue;
                Set<String> aliases = new TreeSet<>();
                if (command.getCommandAliases() != null) {
                    for (Object alias : command.getCommandAliases()) {
                        if (alias instanceof String label && segment(label) != null && child(parent, label) == command) {
                            aliases.add(label);
                        }
                    }
                }
                String path = prefix + "." + name;
                put(nodes, path, aliases, level(command));
                discoverChildren(nodes, command, path, depth + 1);
            }
        } catch (ReflectiveOperationException | IllegalStateException | LinkageError failure) {
            LOGGER.warn("Could not discover subcommands of /{}", prefix, failure);
        }
    }

    private static ICommand child(ICommand parent, String label) {
        Method lookup = TREES.get(parent.getClass()).child();
        if (lookup == null) return null;
        try {
            return (ICommand) lookup.invoke(parent, label);
        } catch (ReflectiveOperationException | LinkageError failure) {
            throw new IllegalStateException("Could not resolve the subcommand of /" + parent.getCommandName(), failure);
        }
    }

    private static void put(Map<String, CommandNode> nodes, String path, Set<String> aliases, int level) {
        String node = "command." + path;
        nodes.put(node, new CommandNode(node, description(path, aliases), level));
    }

    private static String description(String path, Set<String> aliases) {
        return "Legacy command permission for /" + path.replace('.', ' ')
                + (aliases.isEmpty() ? "" : " (aliases: " + String.join(", ", aliases) + ")");
    }

    private static int level(ICommand command) {
        if (!(command instanceof CommandBase base)) return -1;
        try {
            return base.getRequiredPermissionLevel();
        } catch (RuntimeException | LinkageError failure) {
            LOGGER.debug("Could not read the native permission level of /{}", command.getCommandName(), failure);
            return -1;
        }
    }

    private static List<String> originalNodes(String value) {
        if (value == null || value.isEmpty()) return List.of();
        return java.util.Arrays.stream(value.split(";"))
                .map(part -> part.toLowerCase(Locale.ROOT))
                .filter(node -> node.matches("[a-z0-9_.$:-]+")).toList();
    }

    private static String segment(String label) {
        return label != null && label.matches("[a-zA-Z0-9_:-]+") ? label.toLowerCase(Locale.ROOT) : null;
    }

    private record TreeMethods(Method child, Method children) {}
}
