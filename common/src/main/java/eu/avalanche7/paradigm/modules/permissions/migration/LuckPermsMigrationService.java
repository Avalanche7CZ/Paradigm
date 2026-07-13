package eu.avalanche7.paradigm.modules.permissions.migration;

import eu.avalanche7.paradigm.core.Services;
import eu.avalanche7.paradigm.modules.permissions.PermissionAPI;
import eu.avalanche7.paradigm.modules.permissions.PermissionAssignment;
import eu.avalanche7.paradigm.modules.permissions.PermissionsHandler;
import eu.avalanche7.paradigm.modules.permissions.context.PermissionContextSet;
import net.luckperms.api.LuckPerms;
import net.luckperms.api.LuckPermsProvider;
import net.luckperms.api.context.ImmutableContextSet;
import net.luckperms.api.model.PermissionHolder;
import net.luckperms.api.model.group.Group;
import net.luckperms.api.model.user.User;
import net.luckperms.api.node.Node;
import net.luckperms.api.node.NodeBuilder;
import net.luckperms.api.node.NodeType;
import net.luckperms.api.node.types.InheritanceNode;
import net.luckperms.api.node.types.PermissionNode;
import net.luckperms.api.node.types.PrefixNode;
import net.luckperms.api.node.types.SuffixNode;
import net.luckperms.api.node.types.WeightNode;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;

/** Explicit, report-oriented LuckPerms migration. Unsupported contexts are never flattened. */
public final class LuckPermsMigrationService {
    public enum Direction { IMPORT, EXPORT }
    public enum Mode { DRY_RUN, MERGE, REPLACE }

    private final Services services;

    public LuckPermsMigrationService(Services services) {
        this.services = services;
    }

    public CompletableFuture<LuckPermsMigrationReport> migrate(Direction direction, Mode mode, boolean confirmed) {
        if (services == null || services.getPermissionsHandler() == null) {
            return CompletableFuture.completedFuture(failure(mode, "Paradigm permission service is unavailable."));
        }
        if (mode == Mode.REPLACE && !confirmed) {
            return CompletableFuture.completedFuture(failure(mode, "Replace mode requires explicit confirmation."));
        }
        final LuckPerms api;
        try {
            api = LuckPermsProvider.get();
        } catch (Throwable unavailable) {
            return CompletableFuture.completedFuture(failure(mode, "LuckPerms is not installed or not ready."));
        }

        if (direction == Direction.IMPORT) {
            return CompletableFuture.supplyAsync(() -> captureLuckPerms(api))
                    .thenCompose(snapshot -> onServerThread(() -> applyImport(snapshot, mode)));
        }
        return CompletableFuture.supplyAsync(() -> applyExport(api, mode));
    }

    private Snapshot captureLuckPerms(LuckPerms api) {
        api.getGroupManager().loadAllGroups().join();
        List<GroupSnapshot> groups = new ArrayList<>();
        for (Group group : api.getGroupManager().getLoadedGroups()) groups.add(captureGroup(group));

        List<UserSnapshot> users = new ArrayList<>();
        for (UUID uuid : api.getUserManager().getUniqueUsers().join()) {
            User user = api.getUserManager().loadUser(uuid).join();
            users.add(new UserSnapshot(uuid, captureNodes(user)));
            api.getUserManager().cleanupUser(user);
        }
        return new Snapshot(List.copyOf(groups), List.copyOf(users));
    }

    private GroupSnapshot captureGroup(Group group) {
        int weight = group.getNodes(NodeType.WEIGHT).stream().mapToInt(WeightNode::getWeight).max().orElse(0);
        String prefix = group.getNodes(NodeType.PREFIX).stream()
                .filter(node -> node.getContexts().isEmpty() && !node.hasExpiry())
                .max(Comparator.comparingInt(PrefixNode::getPriority)).map(PrefixNode::getMetaValue).orElse("");
        String suffix = group.getNodes(NodeType.SUFFIX).stream()
                .filter(node -> node.getContexts().isEmpty() && !node.hasExpiry())
                .max(Comparator.comparingInt(SuffixNode::getPriority)).map(SuffixNode::getMetaValue).orElse("");
        return new GroupSnapshot(group.getName(), weight, prefix, suffix, captureNodes(group));
    }

    private List<NodeSnapshot> captureNodes(PermissionHolder holder) {
        List<NodeSnapshot> result = new ArrayList<>();
        for (Node node : holder.getNodes()) {
            Kind kind;
            String value;
            if (node.getType() == NodeType.PERMISSION) {
                kind = Kind.PERMISSION;
                value = NodeType.PERMISSION.cast(node).getPermission();
            } else if (node.getType() == NodeType.INHERITANCE) {
                kind = Kind.INHERITANCE;
                value = NodeType.INHERITANCE.cast(node).getGroupName();
            } else {
                continue;
            }
            LuckPermsMigrationMapper.ContextResult contexts = LuckPermsMigrationMapper.mapContexts(node.getContexts().toMap());
            result.add(new NodeSnapshot(kind, value, !node.getValue(), contexts.supported() ? contexts.contexts() : null,
                    node.hasExpiry() ? node.getExpiry().toEpochMilli() : null,
                    contexts.supported() ? "" : contexts.reason()));
        }
        return List.copyOf(result);
    }

    private LuckPermsMigrationReport applyImport(Snapshot snapshot, Mode mode) {
        MutableReport report = new MutableReport(mode == Mode.DRY_RUN);
        PermissionsHandler handler = services.getPermissionsHandler();
        if (mode == Mode.REPLACE) handler.resetInternalPermissionsForMigration();

        for (GroupSnapshot group : snapshot.groups()) {
            report.groups++;
            if (mode != Mode.DRY_RUN) handler.createPermissionGroup(group.name());
            if (group.weight() != 0) applyMetadata(handler, group.name(), "weight", Integer.toString(group.weight()), mode, report);
            if (!group.prefix().isEmpty()) applyMetadata(handler, group.name(), "prefix", group.prefix(), mode, report);
            if (!group.suffix().isEmpty()) applyMetadata(handler, group.name(), "suffix", group.suffix(), mode, report);
            for (NodeSnapshot node : group.nodes()) applyGroupNode(handler, group.name(), node, mode, report);
        }
        for (UserSnapshot user : snapshot.users()) {
            report.users++;
            for (NodeSnapshot node : user.nodes()) applyUserNode(handler, user.uuid(), node, mode, report);
        }
        return report.finish();
    }

    private void applyMetadata(PermissionsHandler handler, String group, String field, String value, Mode mode, MutableReport report) {
        report.metadata++;
        if (mode != Mode.DRY_RUN && !handler.setPermissionGroupMetadata(group, field, value)) report.conflicts++;
    }

    private void applyGroupNode(PermissionsHandler handler, String group, NodeSnapshot node, Mode mode, MutableReport report) {
        if (!node.supported()) {
            report.skip(group + ": " + node.unsupportedReason());
            return;
        }
        if (node.kind() == Kind.INHERITANCE) {
            if (!node.contexts().isEmpty() || node.expiresAtMs() != null) {
                report.skip(group + ": contextual or temporary parent " + node.value());
                return;
            }
            report.parents++;
            if (mode != Mode.DRY_RUN && !handler.addPermissionGroupParent(group, node.value())) report.conflicts++;
            return;
        }
        report.permissions++;
        if (mode != Mode.DRY_RUN && !handler.addPermissionToGroup(group, node.value(), node.denied(), node.contexts(), node.expiresAtMs())) report.conflicts++;
    }

    private void applyUserNode(PermissionsHandler handler, UUID uuid, NodeSnapshot node, Mode mode, MutableReport report) {
        if (!node.supported()) {
            report.skip(uuid + ": " + node.unsupportedReason());
            return;
        }
        if (node.kind() == Kind.INHERITANCE) {
            report.memberships++;
            if (mode != Mode.DRY_RUN && !handler.assignPlayerGroup(uuid, node.value(), node.contexts(), node.expiresAtMs(), "luckperms-import")) report.conflicts++;
            return;
        }
        report.permissions++;
        if (mode != Mode.DRY_RUN && !handler.addPermissionToPlayer(uuid, node.value(), node.denied(), node.contexts(), node.expiresAtMs())) report.conflicts++;
    }

    private LuckPermsMigrationReport applyExport(LuckPerms api, Mode mode) {
        MutableReport report = new MutableReport(mode == Mode.DRY_RUN);
        PermissionsHandler handler = services.getPermissionsHandler();
        if (mode == Mode.REPLACE && mode != Mode.DRY_RUN) {
            api.getGroupManager().loadAllGroups().join();
            for (Group group : api.getGroupManager().getLoadedGroups()) {
                group.data().clear();
                api.getGroupManager().saveGroup(group).join();
            }
            for (UUID uuid : api.getUserManager().getUniqueUsers().join()) {
                User user = api.getUserManager().loadUser(uuid).join();
                user.data().clear();
                api.getUserManager().saveUser(user).join();
                api.getUserManager().cleanupUser(user);
            }
        }

        for (String name : handler.listPermissionGroups()) {
            PermissionAPI.GroupInfo info = handler.getPermissionGroupInfo(name);
            if (info == null) continue;
            report.groups++;
            Group group = api.getGroupManager().getGroup(name);
            if (group == null) group = api.getGroupManager().createAndLoadGroup(name).join();
            if (mode != Mode.DRY_RUN) {
                if (info.weight() != 0) group.data().add(WeightNode.builder(info.weight()).build());
                if (!info.prefix().isEmpty()) group.data().add(PrefixNode.builder(info.prefix(), info.weight()).build());
                if (!info.suffix().isEmpty()) group.data().add(SuffixNode.builder(info.suffix(), info.weight()).build());
            }
            report.metadata += (info.weight() != 0 ? 1 : 0) + (!info.prefix().isEmpty() ? 1 : 0) + (!info.suffix().isEmpty() ? 1 : 0);
            for (String parent : info.inherits()) {
                report.parents++;
                if (mode != Mode.DRY_RUN) group.data().add(InheritanceNode.builder(parent).build());
            }
            for (PermissionAssignment assignment : info.assignments()) {
                report.permissions++;
                if (mode != Mode.DRY_RUN) group.data().add(permissionNode(assignment));
            }
            if (mode != Mode.DRY_RUN) api.getGroupManager().saveGroup(group).join();
        }

        for (UUID uuid : handler.listPermissionUsers()) {
            PermissionAPI.UserInfo info = handler.getPlayerPermissionInfo(uuid);
            if (info == null) continue;
            report.users++;
            User user = api.getUserManager().loadUser(uuid).join();
            for (PermissionAssignment assignment : info.assignments()) {
                report.permissions++;
                if (mode != Mode.DRY_RUN) user.data().add(permissionNode(assignment));
            }
            for (PermissionAssignment assignment : info.groupAssignments()) {
                report.memberships++;
                if (mode != Mode.DRY_RUN) user.data().add(inheritanceNode(assignment));
            }
            if (mode != Mode.DRY_RUN) api.getUserManager().saveUser(user).join();
            api.getUserManager().cleanupUser(user);
        }
        return report.finish();
    }

    private static PermissionNode permissionNode(PermissionAssignment assignment) {
        NodeBuilder<?, ?> builder = PermissionNode.builder(assignment.value()).value(!assignment.denied());
        configure(builder, assignment.contexts(), assignment.expiresAtMs());
        return (PermissionNode) builder.build();
    }

    private static InheritanceNode inheritanceNode(PermissionAssignment assignment) {
        InheritanceNode.Builder builder = InheritanceNode.builder(assignment.value());
        configure(builder, assignment.contexts(), assignment.expiresAtMs());
        return builder.build();
    }

    private static void configure(NodeBuilder<?, ?> builder, PermissionContextSet contexts, Long expiresAtMs) {
        ImmutableContextSet.Builder contextBuilder = ImmutableContextSet.builder();
        for (Map.Entry<String, String> entry : contexts.asMap().entrySet()) contextBuilder.add(entry.getKey(), entry.getValue());
        builder.context(contextBuilder.build());
        if (expiresAtMs != null) builder.expiry(Instant.ofEpochMilli(expiresAtMs));
    }

    private <T> CompletableFuture<T> onServerThread(java.util.concurrent.Callable<T> action) {
        CompletableFuture<T> future = new CompletableFuture<>();
        Runnable task = () -> {
            try {
                future.complete(action.call());
            } catch (Throwable failure) {
                future.completeExceptionally(failure);
            }
        };
        services.getPlatformAdapter().executeOnServerThread(task);
        return future;
    }

    private static LuckPermsMigrationReport failure(Mode mode, String detail) {
        return new LuckPermsMigrationReport(false, mode == Mode.DRY_RUN, 0, 0, 0, 0, 0, 0, 0, 0, List.of(detail));
    }

    private enum Kind { PERMISSION, INHERITANCE }
    private record NodeSnapshot(Kind kind, String value, boolean denied, PermissionContextSet contexts, Long expiresAtMs, String unsupportedReason) {
        boolean supported() { return contexts != null; }
    }
    private record GroupSnapshot(String name, int weight, String prefix, String suffix, List<NodeSnapshot> nodes) { }
    private record UserSnapshot(UUID uuid, List<NodeSnapshot> nodes) { }
    private record Snapshot(List<GroupSnapshot> groups, List<UserSnapshot> users) { }

    private static final class MutableReport {
        private final boolean dryRun;
        private int groups, users, permissions, memberships, parents, metadata, conflicts, skipped;
        private final List<String> details = new ArrayList<>();
        private MutableReport(boolean dryRun) { this.dryRun = dryRun; }
        private void skip(String detail) { skipped++; if (details.size() < 100) details.add(detail); }
        private LuckPermsMigrationReport finish() {
            return new LuckPermsMigrationReport(true, dryRun, groups, users, permissions, memberships, parents, metadata, conflicts, skipped, details);
        }
    }
}
