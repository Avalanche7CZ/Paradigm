package eu.avalanche7.paradigm.modules.commands.permissions;

import java.time.ZoneOffset;
import java.time.ZonedDateTime;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import eu.avalanche7.paradigm.core.Services;
import eu.avalanche7.paradigm.modules.dashboard.auth.DashboardPrincipal;
import eu.avalanche7.paradigm.modules.permissions.ParadigmPermissions;
import eu.avalanche7.paradigm.modules.permissions.PermissionAPI;
import eu.avalanche7.paradigm.modules.permissions.PermissionAssignment;
import eu.avalanche7.paradigm.modules.permissions.PermissionDisplayFormatter;
import eu.avalanche7.paradigm.modules.permissions.PermissionMutationRequest;
import eu.avalanche7.paradigm.modules.permissions.PermissionMutationResult;
import eu.avalanche7.paradigm.modules.permissions.context.PermissionContextSet;
import eu.avalanche7.paradigm.modules.permissions.context.PermissionMutationArgumentParser;
import eu.avalanche7.paradigm.modules.permissions.migration.LuckPermsMigrationReport;
import eu.avalanche7.paradigm.modules.permissions.migration.LuckPermsMigrationService;
import eu.avalanche7.paradigm.platform.Interfaces.ICommandBuilder;
import eu.avalanche7.paradigm.platform.Interfaces.ICommandSource;
import eu.avalanche7.paradigm.platform.Interfaces.IPlatformAdapter;
import eu.avalanche7.paradigm.platform.Interfaces.IPlayer;

public final class PermissionCommands {
    private PermissionCommands() {
    }

    public static ICommandBuilder register(ICommandBuilder root, IPlatformAdapter platform, Services services) {
        return root
                .then(buildHomeBranch(platform, services))
                .then(buildPermissionBranch(platform, services))
                .then(buildGroupBranch(platform, services))
                .then(buildTrackBranch(platform, services))
                .then(buildRankBranch(platform, services, "promote"))
                .then(buildRankBranch(platform, services, "demote"));
    }

    private static ICommandBuilder buildTrackBranch(IPlatformAdapter platform, Services services) {
        ICommandBuilder root = platform.createCommandBuilder().literal("track")
                .requires(source -> hasGroupManagePermission(source, services))
                .executes(context -> {
                    String names = services.getPermissionsHandler().listPermissionTracks().stream().map(track -> track.name()).collect(java.util.stream.Collectors.joining(", "));
                    platform.sendSuccess(context.getSource(), platform.createLiteralComponent(names.isBlank() ? "No permission tracks." : "Tracks: " + names), false);
                    return 1;
                });
        root.then(platform.createCommandBuilder().literal("create").then(platform.createCommandBuilder().argument("track", ICommandBuilder.ArgumentType.WORD)
                .executes(context -> mutateTrack(context.getSource(), services, "track_create", context.getStringArgument("track"), null, null, null))));
        root.then(platform.createCommandBuilder().literal("delete").then(platform.createCommandBuilder().argument("track", ICommandBuilder.ArgumentType.WORD)
                .suggests((context, input) -> trackSuggestions(services, input))
                .executes(context -> mutateTrack(context.getSource(), services, "track_delete", context.getStringArgument("track"), null, null, null))));
        root.then(platform.createCommandBuilder().literal("info").then(platform.createCommandBuilder().argument("track", ICommandBuilder.ArgumentType.WORD)
                .suggests((context, input) -> trackSuggestions(services, input))
                .executes(context -> showTrack(context.getSource(), platform, services, context.getStringArgument("track")))));
        root.then(platform.createCommandBuilder().literal("clear").then(platform.createCommandBuilder().argument("track", ICommandBuilder.ArgumentType.WORD)
                .suggests((context, input) -> trackSuggestions(services, input))
                .executes(context -> mutateTrack(context.getSource(), services, "track_clear", context.getStringArgument("track"), null, null, null))));
        root.then(platform.createCommandBuilder().literal("append").then(platform.createCommandBuilder().argument("track", ICommandBuilder.ArgumentType.WORD)
                .suggests((context, input) -> trackSuggestions(services, input))
                .then(platform.createCommandBuilder().argument("group", ICommandBuilder.ArgumentType.WORD)
                        .suggests((context, input) -> trackCandidateSuggestions(services, context.getStringArgument("track"), input))
                        .executes(context -> mutateTrack(context.getSource(), services, "track_append", context.getStringArgument("track"), context.getStringArgument("group"), null, null)))));
        root.then(platform.createCommandBuilder().literal("insert").then(platform.createCommandBuilder().argument("track", ICommandBuilder.ArgumentType.WORD)
                .suggests((context, input) -> trackSuggestions(services, input))
                .then(platform.createCommandBuilder().argument("group", ICommandBuilder.ArgumentType.WORD)
                        .suggests((context, input) -> trackCandidateSuggestions(services, context.getStringArgument("track"), input))
                        .then(platform.createCommandBuilder().argument("position", ICommandBuilder.ArgumentType.INTEGER)
                                .executes(context -> mutateTrack(context.getSource(), services, "track_insert", context.getStringArgument("track"), context.getStringArgument("group"), null, context.getIntArgument("position")))))));
        root.then(platform.createCommandBuilder().literal("rename").then(platform.createCommandBuilder().argument("track", ICommandBuilder.ArgumentType.WORD)
                .suggests((context, input) -> trackSuggestions(services, input))
                .then(platform.createCommandBuilder().argument("newName", ICommandBuilder.ArgumentType.WORD)
                        .executes(context -> mutateTrack(context.getSource(), services, "track_rename", context.getStringArgument("track"), null, context.getStringArgument("newName"), null)))));
        root.then(platform.createCommandBuilder().literal("clone").then(platform.createCommandBuilder().argument("track", ICommandBuilder.ArgumentType.WORD)
                .suggests((context, input) -> trackSuggestions(services, input))
                .then(platform.createCommandBuilder().argument("newName", ICommandBuilder.ArgumentType.WORD)
                        .executes(context -> mutateTrack(context.getSource(), services, "track_clone", context.getStringArgument("track"), null, context.getStringArgument("newName"), null)))));
        root.then(platform.createCommandBuilder().literal("remove").then(platform.createCommandBuilder().argument("track", ICommandBuilder.ArgumentType.WORD)
                .suggests((context, input) -> trackSuggestions(services, input))
                .then(platform.createCommandBuilder().argument("group", ICommandBuilder.ArgumentType.WORD)
                        .suggests((context, input) -> trackGroupSuggestions(services, context.getStringArgument("track"), input))
                        .executes(context -> mutateTrack(context.getSource(), services, "track_remove", context.getStringArgument("track"), context.getStringArgument("group"), null, null)))));
        root.then(platform.createCommandBuilder().literal("move").then(platform.createCommandBuilder().argument("track", ICommandBuilder.ArgumentType.WORD)
                .suggests((context, input) -> trackSuggestions(services, input))
                .then(platform.createCommandBuilder().argument("group", ICommandBuilder.ArgumentType.WORD)
                        .suggests((context, input) -> trackGroupSuggestions(services, context.getStringArgument("track"), input))
                        .then(platform.createCommandBuilder().argument("position", ICommandBuilder.ArgumentType.INTEGER)
                                .executes(context -> mutateTrack(context.getSource(), services, "track_move", context.getStringArgument("track"), context.getStringArgument("group"), null, context.getIntArgument("position")))))));
        return root;
    }

    private static ICommandBuilder buildRankBranch(IPlatformAdapter platform, Services services, String action) {
        return platform.createCommandBuilder().literal(action)
                .requires(source -> hasGroupManagePermission(source, services))
                .then(platform.createCommandBuilder().argument("player", ICommandBuilder.ArgumentType.WORD)
                        .then(platform.createCommandBuilder().argument("track", ICommandBuilder.ArgumentType.WORD)
                                .suggests((context, input) -> trackSuggestions(services, input))
                                .executes(context -> mutateRank(context.getSource(), services, action,
                                        context.getStringArgument("player"), context.getStringArgument("track"), ""))
                                .then(platform.createCommandBuilder().argument("flags", ICommandBuilder.ArgumentType.GREEDY_STRING)
                                        .executes(context -> mutateRank(context.getSource(), services, action,
                                                context.getStringArgument("player"), context.getStringArgument("track"), context.getStringArgument("flags"))))));
    }

    private static ICommandBuilder buildHomeBranch(IPlatformAdapter platform, Services services) {
        return platform.createCommandBuilder()
                .literal("perms")
                .requires(source -> hasGroupManagePermission(source, services))
                .executes(context -> {
                    PermissionPanelRenderer.sendPermissionsHome(context.getSource(), services);
                    return 1;
                });
    }

    private static ICommandBuilder buildPermissionBranch(IPlatformAdapter platform, Services services) {
        return platform.createCommandBuilder()
                .literal("permission")
                .requires(source -> hasGroupManagePermission(source, services))
                .then(platform.createCommandBuilder().literal("check").then(buildPermissionCheckArguments(platform, services)))
                .then(platform.createCommandBuilder().literal("explain").then(buildPermissionCheckArguments(platform, services)))
                .then(buildPermissionNodesBranch(platform, services))
                .then(buildLuckPermsMigrationBranch(platform, services));
    }

    private static ICommandBuilder buildLuckPermsMigrationBranch(IPlatformAdapter platform, Services services) {
        ICommandBuilder luckPerms = platform.createCommandBuilder().literal("luckperms");
        for (LuckPermsMigrationService.Direction direction : LuckPermsMigrationService.Direction.values()) {
            ICommandBuilder directionBranch = platform.createCommandBuilder().literal(direction.name().toLowerCase(Locale.ROOT))
                    .then(platform.createCommandBuilder().literal("dry-run")
                            .executes(ctx -> startMigration(ctx.getSource(), platform, services, direction, LuckPermsMigrationService.Mode.DRY_RUN, false)))
                    .then(platform.createCommandBuilder().literal("merge")
                            .executes(ctx -> startMigration(ctx.getSource(), platform, services, direction, LuckPermsMigrationService.Mode.MERGE, false)))
                    .then(platform.createCommandBuilder().literal("replace")
                            .then(platform.createCommandBuilder().literal("confirm")
                                    .executes(ctx -> startMigration(ctx.getSource(), platform, services, direction, LuckPermsMigrationService.Mode.REPLACE, true))));
            luckPerms.then(directionBranch);
        }
        return platform.createCommandBuilder().literal("migrate").then(luckPerms);
    }

    private static int startMigration(ICommandSource source, IPlatformAdapter platform, Services services,
                                      LuckPermsMigrationService.Direction direction, LuckPermsMigrationService.Mode mode,
                                      boolean confirmed) {
        platform.sendSuccess(source, platform.createLiteralComponent("LuckPerms migration started: "
                + direction.name().toLowerCase(Locale.ROOT) + " " + mode.name().toLowerCase(Locale.ROOT).replace('_', '-')), false);
        new LuckPermsMigrationService(services).migrate(direction, mode, confirmed).whenComplete((report, failure) -> {
            Runnable feedback = () -> {
                if (failure != null) {
                    platform.sendFailure(source, platform.createLiteralComponent("LuckPerms migration failed. Check the server log."));
                    services.getLogger().error("LuckPerms migration failed", failure);
                    return;
                }
                sendMigrationReport(source, platform, report);
            };
            platform.executeOnServerThread(feedback);
        });
        return 1;
    }

    private static void sendMigrationReport(ICommandSource source, IPlatformAdapter platform, LuckPermsMigrationReport report) {
        String summary = "LuckPerms migration " + (report.ok() ? "complete" : "failed")
                + (report.dryRun() ? " (dry-run)" : "") + ": groups=" + report.groups()
                + ", users=" + report.users() + ", permissions=" + report.permissions()
                + ", memberships=" + report.memberships() + ", parents=" + report.parents()
                + ", tracks=" + report.tracks()
                + ", conflicts=" + report.conflicts() + ", skipped=" + report.skipped();
        if (report.ok()) platform.sendSuccess(source, platform.createLiteralComponent(summary), false);
        else platform.sendFailure(source, platform.createLiteralComponent(summary));
        report.details().stream().limit(10).forEach(detail ->
                platform.sendSuccess(source, platform.createLiteralComponent(" - " + detail), false));
    }

    private static ICommandBuilder buildPermissionCheckArguments(IPlatformAdapter platform, Services services) {
        return platform.createCommandBuilder()
                .argument("player", ICommandBuilder.ArgumentType.WORD)
                .then(platform.createCommandBuilder()
                        .argument("permission", ICommandBuilder.ArgumentType.STRING)
                        .suggests((context, input) -> permissionSuggestions(services, input))
                        .executes(context -> explainPermission(context.getSource(), services,
                                context.getStringArgument("player"), context.getStringArgument("permission"))));
    }

    private static ICommandBuilder buildPermissionNodesBranch(IPlatformAdapter platform, Services services) {
        return platform.createCommandBuilder()
                .literal("nodes")
                .executes(context -> {
                    PermissionPanelRenderer.sendPermissionNodeList(context.getSource(), services, "");
                    return 1;
                })
                .then(platform.createCommandBuilder()
                        .argument("query", ICommandBuilder.ArgumentType.STRING)
                        .suggests((context, input) -> permissionSuggestions(services, input))
                        .executes(context -> {
                            PermissionPanelRenderer.sendPermissionNodeList(context.getSource(), services, context.getStringArgument("query"));
                            return 1;
                        }));
    }

    private static ICommandBuilder buildGroupBranch(IPlatformAdapter platform, Services services) {
        ICommandBuilder root = platform.createCommandBuilder()
                .literal("group")
                .requires(source -> hasGroupManagePermission(source, services))
                .executes(context -> {
                    PermissionPanelRenderer.sendGroupList(context.getSource(), services);
                    return 1;
                });

        ICommandBuilder list = platform.createCommandBuilder()
                .literal("list")
                .executes(context -> {
                    PermissionPanelRenderer.sendGroupList(context.getSource(), services);
                    return 1;
                });

        ICommandBuilder add = platform.createCommandBuilder()
                .literal("add")
                .then(platform.createCommandBuilder()
                        .argument("name", ICommandBuilder.ArgumentType.WORD)
                        .executes(context -> {
                            String name = context.getStringArgument("name");
                            if (!services.getPermissionsHandler().createPermissionGroup(name)) {
                                PermissionCommandMessages.send(context.getSource(), services, "group.manage.add_fail", "Could not create group {group}.", "{group}", name);
                                return 0;
                            }
                            PermissionCommandMessages.send(context.getSource(), services, "group.manage.add_ok", "Created group {group}.", "{group}", name);
                            return 1;
                        }));

        ICommandBuilder remove = platform.createCommandBuilder()
                .literal("remove")
                .then(platform.createCommandBuilder()
                        .argument("name", ICommandBuilder.ArgumentType.WORD)
                        .suggests((context, input) -> services.getPermissionsHandler().listPermissionGroups())
                        .executes(context -> {
                            String name = context.getStringArgument("name");
                            if (!services.getPermissionsHandler().deletePermissionGroup(name)) {
                                PermissionCommandMessages.send(context.getSource(), services, "group.manage.remove_fail", "Could not remove group {group}.", "{group}", name);
                                return 0;
                            }
                            PermissionCommandMessages.send(context.getSource(), services, "group.manage.remove_ok", "Removed group {group}.", "{group}", name);
                            return 1;
                        }));

        ICommandBuilder info = platform.createCommandBuilder()
                .literal("info")
                .then(platform.createCommandBuilder()
                        .argument("name", ICommandBuilder.ArgumentType.WORD)
                        .suggests((context, input) -> services.getPermissionsHandler().listPermissionGroups())
                        .executes(context -> {
                            String name = context.getStringArgument("name");
                            if (services.getPermissionsHandler().getPermissionGroupInfo(name) == null) {
                                PermissionCommandMessages.send(context.getSource(), services, "group.manage.not_found", "Group {group} was not found.", "{group}", name);
                                return 0;
                            }
                            PermissionPanelRenderer.sendGroupInfo(context.getSource(), services, name);
                            return 1;
                        }));

        ICommandBuilder parentAdd = platform.createCommandBuilder()
                .argument("group", ICommandBuilder.ArgumentType.WORD)
                .suggests((context, input) -> services.getPermissionsHandler().listPermissionGroups())
                .then(platform.createCommandBuilder()
                        .argument("parent", ICommandBuilder.ArgumentType.WORD)
                        .suggests((context, input) -> services.getPermissionsHandler().listPermissionGroups())
                        .executes(context -> mutateParent(context.getSource(), services,
                                context.getStringArgument("group"), context.getStringArgument("parent"), true)));

        ICommandBuilder parentRemove = platform.createCommandBuilder()
                .argument("group", ICommandBuilder.ArgumentType.WORD)
                .suggests((context, input) -> services.getPermissionsHandler().listPermissionGroups())
                .then(platform.createCommandBuilder()
                        .argument("parent", ICommandBuilder.ArgumentType.WORD)
                        .suggests((context, input) -> services.getPermissionsHandler().listPermissionGroups())
                        .executes(context -> mutateParent(context.getSource(), services,
                                context.getStringArgument("group"), context.getStringArgument("parent"), false)));

        ICommandBuilder parent = platform.createCommandBuilder()
                .literal("parent")
                .then(platform.createCommandBuilder().literal("add").then(parentAdd))
                .then(platform.createCommandBuilder().literal("remove").then(parentRemove));

        ICommandBuilder groupPermissions = buildGroupPermissionBranch(platform, services);
        ICommandBuilder setWeight = platform.createCommandBuilder()
                .literal("setweight")
                .then(platform.createCommandBuilder()
                        .argument("group", ICommandBuilder.ArgumentType.WORD)
                        .suggests((context, input) -> services.getPermissionsHandler().listPermissionGroups())
                        .then(platform.createCommandBuilder()
                                .argument("weight", ICommandBuilder.ArgumentType.INTEGER)
                                .executes(context -> setGroupMetadata(context.getSource(), services, context.getStringArgument("group"),
                                        "weight", String.valueOf(context.getIntArgument("weight"))))));

        ICommandBuilder setPrefix = buildGroupTextMetadataCommand(platform, services, "setprefix", "prefix");
        ICommandBuilder setSuffix = buildGroupTextMetadataCommand(platform, services, "setsuffix", "suffix");
        ICommandBuilder setDescription = buildGroupTextMetadataCommand(platform, services, "setdescription", "description");
        ICommandBuilder showTracks = platform.createCommandBuilder().literal("showtracks")
                .then(platform.createCommandBuilder().argument("group", ICommandBuilder.ArgumentType.WORD)
                        .suggests((context, input) -> services.getPermissionsHandler().listPermissionGroups())
                        .executes(context -> showGroupTracks(context.getSource(), platform, services, context.getStringArgument("group"))));

        return root.then(list).then(add).then(remove).then(info).then(parent).then(groupPermissions)
                .then(setWeight).then(setPrefix).then(setSuffix).then(setDescription).then(showTracks).then(buildUserBranch(platform, services));
    }

    private static ICommandBuilder buildGroupPermissionBranch(IPlatformAdapter platform, Services services) {
        ICommandBuilder add = groupPermissionArgument(platform, services, false);
        ICommandBuilder deny = groupPermissionArgument(platform, services, true);
        ICommandBuilder remove = platform.createCommandBuilder()
                .argument("group", ICommandBuilder.ArgumentType.WORD)
                .suggests((context, input) -> services.getPermissionsHandler().listPermissionGroups())
                .then(platform.createCommandBuilder()
                        .argument("permission", ICommandBuilder.ArgumentType.STRING)
                        .suggests((context, input) -> groupPermissionSuggestions(services, context.getStringArgument("group"), input))
                        .executes(context -> removeGroupPermission(context.getSource(), services, context.getStringArgument("group"),
                                context.getStringArgument("permission"), ""))
                        .then(platform.createCommandBuilder().argument("flags", ICommandBuilder.ArgumentType.GREEDY_STRING)
                                .executes(context -> removeGroupPermission(context.getSource(), services, context.getStringArgument("group"),
                                        context.getStringArgument("permission"), context.getStringArgument("flags")))));
        ICommandBuilder removeId = platform.createCommandBuilder()
                .argument("group", ICommandBuilder.ArgumentType.WORD)
                .suggests((context, input) -> services.getPermissionsHandler().listPermissionGroups())
                .then(platform.createCommandBuilder().argument("assignmentId", ICommandBuilder.ArgumentType.WORD)
                        .executes(context -> removeGroupPermissionById(context.getSource(), services,
                                context.getStringArgument("group"), context.getStringArgument("assignmentId"))));
        ICommandBuilder list = platform.createCommandBuilder()
                .argument("group", ICommandBuilder.ArgumentType.WORD)
                .suggests((context, input) -> services.getPermissionsHandler().listPermissionGroups())
                .executes(context -> {
                    PermissionPanelRenderer.sendGroupPermissionList(context.getSource(), services, context.getStringArgument("group"));
                    return 1;
                });
        return platform.createCommandBuilder()
                .literal("perm")
                .then(platform.createCommandBuilder().literal("add").then(add))
                .then(platform.createCommandBuilder().literal("deny").then(deny))
                .then(platform.createCommandBuilder().literal("remove").then(remove))
                .then(platform.createCommandBuilder().literal("remove-id").then(removeId))
                .then(platform.createCommandBuilder().literal("list").then(list));
    }

    private static ICommandBuilder groupPermissionArgument(IPlatformAdapter platform, Services services, boolean denied) {
        return platform.createCommandBuilder()
                .argument("group", ICommandBuilder.ArgumentType.WORD)
                .suggests((context, input) -> services.getPermissionsHandler().listPermissionGroups())
                .then(platform.createCommandBuilder()
                        .argument("permission", ICommandBuilder.ArgumentType.STRING)
                        .suggests((context, input) -> permissionSuggestions(services, input))
                        .executes(context -> addGroupPermission(context.getSource(), services, context.getStringArgument("group"),
                                context.getStringArgument("permission"), denied, ""))
                        .then(platform.createCommandBuilder().argument("flags", ICommandBuilder.ArgumentType.GREEDY_STRING)
                                .executes(context -> addGroupPermission(context.getSource(), services, context.getStringArgument("group"),
                                        context.getStringArgument("permission"), denied, context.getStringArgument("flags")))));
    }

    private static ICommandBuilder buildUserBranch(IPlatformAdapter platform, Services services) {
        ICommandBuilder userAddDuration = platform.createCommandBuilder()
                .argument("amount", ICommandBuilder.ArgumentType.INTEGER)
                .then(platform.createCommandBuilder()
                        .argument("unit", ICommandBuilder.ArgumentType.WORD)
                        .suggests(List.of("days", "weeks", "months"))
                        .executes(context -> assignUserGroup(context.getSource(), services,
                                context.getStringArgument("player"), context.getStringArgument("group"),
                                context.getIntArgument("amount"), context.getStringArgument("unit"))));
        ICommandBuilder userAdd = platform.createCommandBuilder()
                .argument("group", ICommandBuilder.ArgumentType.WORD)
                .suggests((context, input) -> services.getPermissionsHandler().listPermissionGroups())
                .executes(context -> assignUserGroup(context.getSource(), services,
                        context.getStringArgument("player"), context.getStringArgument("group"), 0, ""))
                .then(userAddDuration)
                .then(platform.createCommandBuilder().argument("flags", ICommandBuilder.ArgumentType.GREEDY_STRING)
                        .executes(context -> assignUserGroupWithFlags(context.getSource(), services,
                                context.getStringArgument("player"), context.getStringArgument("group"), context.getStringArgument("flags"))));

        ICommandBuilder userRemove = platform.createCommandBuilder()
                .argument("group", ICommandBuilder.ArgumentType.WORD)
                .suggests((context, input) -> services.getPermissionsHandler().listPermissionGroups())
                .executes(context -> removeUserGroup(context.getSource(), services,
                        PermissionCommandArguments.resolvePlayerUuid(services, context.getStringArgument("player")),
                        context.getStringArgument("group"), ""))
                .then(platform.createCommandBuilder().argument("flags", ICommandBuilder.ArgumentType.GREEDY_STRING)
                        .executes(context -> removeUserGroup(context.getSource(), services,
                                PermissionCommandArguments.resolvePlayerUuid(services, context.getStringArgument("player")),
                                context.getStringArgument("group"), context.getStringArgument("flags"))));

        ICommandBuilder userPermission = buildUserPermissionBranch(platform, services);
        ICommandBuilder removeUserGroupId = platform.createCommandBuilder()
                .argument("player", ICommandBuilder.ArgumentType.WORD)
                .then(platform.createCommandBuilder().argument("assignmentId", ICommandBuilder.ArgumentType.WORD)
                        .executes(context -> removeUserGroupById(context.getSource(), services,
                                context.getStringArgument("player"), context.getStringArgument("assignmentId"))));

        return platform.createCommandBuilder()
                .literal("user")
                .then(platform.createCommandBuilder().literal("add").then(platform.createCommandBuilder()
                        .argument("player", ICommandBuilder.ArgumentType.WORD).then(userAdd)))
                .then(platform.createCommandBuilder().literal("remove").then(platform.createCommandBuilder()
                        .argument("player", ICommandBuilder.ArgumentType.WORD).then(userRemove)))
                .then(platform.createCommandBuilder().literal("remove-id").then(removeUserGroupId))
                .then(platform.createCommandBuilder().literal("info").then(platform.createCommandBuilder()
                        .argument("player", ICommandBuilder.ArgumentType.WORD)
                        .executes(context -> showUserInfo(context.getSource(), services, context.getStringArgument("player")))))
                .then(platform.createCommandBuilder().literal("list").then(platform.createCommandBuilder()
                        .argument("player", ICommandBuilder.ArgumentType.WORD)
                        .executes(context -> showUserInfo(context.getSource(), services, context.getStringArgument("player")))))
                .then(platform.createCommandBuilder().literal("showtracks").then(platform.createCommandBuilder()
                        .argument("player", ICommandBuilder.ArgumentType.WORD)
                        .executes(context -> showUserTracks(context.getSource(), platform, services, context.getStringArgument("player")))))
                .then(userTrackOperation(platform, services, "promote"))
                .then(userTrackOperation(platform, services, "demote"))
                .then(userTrackOperation(platform, services, "cleartrack"))
                .then(userTrackOperation(platform, services, "settrack"))
                .then(userPermission);
    }

    private static ICommandBuilder userTrackOperation(IPlatformAdapter platform, Services services, String action) {
        ICommandBuilder track = platform.createCommandBuilder().argument("track", ICommandBuilder.ArgumentType.WORD)
                .suggests((context, input) -> trackSuggestions(services, input));
        if ("settrack".equals(action)) {
            track.then(platform.createCommandBuilder().argument("group", ICommandBuilder.ArgumentType.WORD)
                    .suggests((context, input) -> trackGroupSuggestions(services, context.getStringArgument("track"), input))
                    .executes(context -> mutateUserTrack(context.getSource(), services, action, context.getStringArgument("player"),
                            context.getStringArgument("track"), context.getStringArgument("group"), ""))
                    .then(platform.createCommandBuilder().argument("flags", ICommandBuilder.ArgumentType.GREEDY_STRING)
                            .executes(context -> mutateUserTrack(context.getSource(), services, action, context.getStringArgument("player"),
                                    context.getStringArgument("track"), context.getStringArgument("group"), context.getStringArgument("flags")))));
        } else {
            track.executes(context -> mutateUserTrack(context.getSource(), services, action, context.getStringArgument("player"),
                    context.getStringArgument("track"), null, ""))
                    .then(platform.createCommandBuilder().argument("flags", ICommandBuilder.ArgumentType.GREEDY_STRING)
                            .executes(context -> mutateUserTrack(context.getSource(), services, action, context.getStringArgument("player"),
                                    context.getStringArgument("track"), null, context.getStringArgument("flags"))));
        }
        return platform.createCommandBuilder().literal(action).then(platform.createCommandBuilder()
                .argument("player", ICommandBuilder.ArgumentType.WORD).then(track));
    }

    private static ICommandBuilder buildUserPermissionBranch(IPlatformAdapter platform, Services services) {
        ICommandBuilder add = userPermissionArgument(platform, services, false);
        ICommandBuilder deny = userPermissionArgument(platform, services, true);
        ICommandBuilder remove = platform.createCommandBuilder()
                .argument("player", ICommandBuilder.ArgumentType.WORD)
                .then(platform.createCommandBuilder()
                        .argument("permission", ICommandBuilder.ArgumentType.STRING)
                        .suggests((context, input) -> userPermissionSuggestions(services, context.getStringArgument("player"), input))
                        .executes(context -> removeUserPermission(context.getSource(), services, context.getStringArgument("player"),
                                context.getStringArgument("permission"), ""))
                        .then(platform.createCommandBuilder().argument("flags", ICommandBuilder.ArgumentType.GREEDY_STRING)
                                .executes(context -> removeUserPermission(context.getSource(), services, context.getStringArgument("player"),
                                        context.getStringArgument("permission"), context.getStringArgument("flags")))));
        ICommandBuilder removeId = platform.createCommandBuilder()
                .argument("player", ICommandBuilder.ArgumentType.WORD)
                .then(platform.createCommandBuilder().argument("assignmentId", ICommandBuilder.ArgumentType.WORD)
                        .executes(context -> removeUserPermissionById(context.getSource(), services,
                                context.getStringArgument("player"), context.getStringArgument("assignmentId"))));
        ICommandBuilder list = platform.createCommandBuilder()
                .argument("player", ICommandBuilder.ArgumentType.WORD)
                .executes(context -> showUserInfo(context.getSource(), services, context.getStringArgument("player")));
        return platform.createCommandBuilder()
                .literal("perm")
                .then(platform.createCommandBuilder().literal("add").then(add))
                .then(platform.createCommandBuilder().literal("deny").then(deny))
                .then(platform.createCommandBuilder().literal("remove").then(remove))
                .then(platform.createCommandBuilder().literal("remove-id").then(removeId))
                .then(platform.createCommandBuilder().literal("list").then(list));
    }

    private static ICommandBuilder userPermissionArgument(IPlatformAdapter platform, Services services, boolean denied) {
        return platform.createCommandBuilder()
                .argument("player", ICommandBuilder.ArgumentType.WORD)
                .then(platform.createCommandBuilder()
                        .argument("permission", ICommandBuilder.ArgumentType.STRING)
                        .suggests((context, input) -> permissionSuggestions(services, input))
                        .executes(context -> addUserPermission(context.getSource(), services, context.getStringArgument("player"),
                                context.getStringArgument("permission"), denied, ""))
                        .then(platform.createCommandBuilder().argument("flags", ICommandBuilder.ArgumentType.GREEDY_STRING)
                                .executes(context -> addUserPermission(context.getSource(), services, context.getStringArgument("player"),
                                        context.getStringArgument("permission"), denied, context.getStringArgument("flags")))));
    }

    private static ICommandBuilder buildGroupTextMetadataCommand(IPlatformAdapter platform, Services services, String literal, String field) {
        return platform.createCommandBuilder()
                .literal(literal)
                .then(platform.createCommandBuilder()
                        .argument("group", ICommandBuilder.ArgumentType.WORD)
                        .suggests((context, input) -> services.getPermissionsHandler().listPermissionGroups())
                        .then(platform.createCommandBuilder()
                                .argument("value", ICommandBuilder.ArgumentType.GREEDY_STRING)
                                .executes(context -> setGroupMetadata(context.getSource(), services, context.getStringArgument("group"),
                                        field, context.getStringArgument("value")))));
    }

    private static int mutateParent(ICommandSource source, Services services, String group, String parent, boolean add) {
        boolean changed = add
                ? services.getPermissionsHandler().addPermissionGroupParent(group, parent)
                : services.getPermissionsHandler().removePermissionGroupParent(group, parent);
        if (!changed) {
            PermissionCommandMessages.send(source, services,
                    add ? "group.manage.parent_add_fail" : "group.manage.parent_remove_fail",
                    add ? "Could not add parent {parent} to {group}." : "Could not remove parent {parent} from {group}.",
                    "{parent}", parent, "{group}", group);
            return 0;
        }
        PermissionCommandMessages.send(source, services,
                add ? "group.manage.parent_add_ok" : "group.manage.parent_remove_ok",
                add ? "Added parent {parent} to {group}." : "Removed parent {parent} from {group}.",
                "{parent}", parent, "{group}", group);
        return 1;
    }

    private static int addGroupPermission(ICommandSource source, Services services, String group, String permission, boolean denied, String flags) {
        PermissionMutationArgumentParser.Result arguments = PermissionCommandArguments.parse(source, services, flags, true);
        if (!arguments.valid()) return PermissionCommandMessages.argumentError(source, services, arguments.code(), arguments.message());
        PermissionMutationResult result = mutate(source, services,
                request("group_permission_add", group, null, permission, null, denied, arguments.contexts(), arguments.expiresAtMs()));
        if (!result.applied()) {
            PermissionCommandMessages.send(source, services, "group.manage.perm_add_fail", "Could not add permission {permission} to {group}.",
                    "{permission}", permission, "{group}", group);
            return 0;
        }
        PermissionCommandMessages.send(source, services, "group.manage.perm_add_context_ok", "Added permission {permission} to {group} ({context}, {expiry}).",
                "{permission}", denied ? "-" + stripDeny(permission) : stripDeny(permission), "{group}", group,
                "{context}", contextLabel(services, arguments.contexts()), "{expiry}", expiryLabel(services, arguments.expiresAtMs()));
        PermissionPanelRenderer.sendGroupPermissionList(source, services, group);
        return 1;
    }

    private static int removeGroupPermission(ICommandSource source, Services services, String group, String permission, String flags) {
        PermissionMutationArgumentParser.Result arguments = PermissionCommandArguments.parse(source, services, flags, false);
        if (!arguments.valid()) return PermissionCommandMessages.argumentError(source, services, arguments.code(), arguments.message());
        PermissionMutationResult result = mutate(source, services,
                request("group_permission_remove", group, null, permission, null, false, arguments.contexts(), null));
        if (!result.applied()) {
            if ("assignment_ambiguous".equals(result.code())) {
                PermissionCommandMessages.send(source, services, "group.manage.assignment_ambiguous", result.message());
                return 0;
            }
            PermissionCommandMessages.send(source, services, "group.manage.perm_remove_fail", "Could not remove permission {permission} from {group}.",
                    "{permission}", permission, "{group}", group);
            return 0;
        }
        PermissionCommandMessages.send(source, services, "group.manage.perm_remove_context_ok", "Removed permission {permission} from {group} ({context}).",
                "{permission}", permission, "{group}", group, "{context}", contextLabel(services, arguments.contexts()));
        PermissionPanelRenderer.sendGroupPermissionList(source, services, group);
        return 1;
    }

    private static int removeGroupPermissionById(ICommandSource source, Services services, String group, String assignmentId) {
        PermissionMutationRequest request = request("group_permission_remove", group, null, null, null, false, PermissionContextSet.empty(), null);
        request.assignmentId = assignmentId;
        PermissionMutationResult result = mutate(source, services, request);
        if (!result.applied()) return PermissionCommandMessages.assignmentNotFound(source, services, assignmentId);
        PermissionPanelRenderer.sendGroupPermissionList(source, services, group);
        return 1;
    }

    private static int setGroupMetadata(ICommandSource source, Services services, String group, String field, String value) {
        if (!services.getPermissionsHandler().setPermissionGroupMetadata(group, field, value)) {
            PermissionCommandMessages.send(source, services, "group.manage.metadata_fail", "Could not update {field} for {group}.",
                    "{field}", field, "{group}", group);
            return 0;
        }
        PermissionCommandMessages.send(source, services, "group.manage.metadata_ok", "Updated {field} for {group}.",
                "{field}", field, "{group}", group);
        PermissionPanelRenderer.sendGroupInfo(source, services, group);
        return 1;
    }

    private static int addUserPermission(ICommandSource source, Services services, String playerInput, String permission, boolean denied, String flags) {
        UUID uuid = PermissionCommandArguments.resolvePlayerUuid(services, playerInput);
        if (uuid == null) {
            PermissionCommandMessages.send(source, services, "group.manage.user_invalid", "Player must be online name or UUID.");
            return 0;
        }
        PermissionMutationArgumentParser.Result arguments = PermissionCommandArguments.parse(source, services, flags, true);
        if (!arguments.valid()) return PermissionCommandMessages.argumentError(source, services, arguments.code(), arguments.message());
        PermissionMutationResult result = mutate(source, services,
                request("user_permission_add", null, null, permission, uuid.toString(), denied, arguments.contexts(), arguments.expiresAtMs()));
        if (!result.applied()) {
            PermissionCommandMessages.send(source, services, "group.manage.user_perm_add_fail", "Could not add permission {permission} to player.", "{permission}", permission);
            return 0;
        }
        PermissionCommandMessages.send(source, services, "group.manage.user_perm_add_context_ok", "Added permission {permission} to player ({context}, {expiry}).",
                "{permission}", denied ? "-" + stripDeny(permission) : stripDeny(permission),
                "{context}", contextLabel(services, arguments.contexts()), "{expiry}", expiryLabel(services, arguments.expiresAtMs()));
        return showUserInfo(source, services, playerInput);
    }

    private static int removeUserPermission(ICommandSource source, Services services, String playerInput, String permission, String flags) {
        UUID uuid = PermissionCommandArguments.resolvePlayerUuid(services, playerInput);
        if (uuid == null) {
            PermissionCommandMessages.send(source, services, "group.manage.user_invalid", "Player must be online name or UUID.");
            return 0;
        }
        PermissionMutationArgumentParser.Result arguments = PermissionCommandArguments.parse(source, services, flags, false);
        if (!arguments.valid()) return PermissionCommandMessages.argumentError(source, services, arguments.code(), arguments.message());
        PermissionMutationResult result = mutate(source, services,
                request("user_permission_remove", null, null, permission, uuid.toString(), false, arguments.contexts(), null));
        if (!result.applied()) {
            if ("assignment_ambiguous".equals(result.code())) {
                PermissionCommandMessages.send(source, services, "group.manage.assignment_ambiguous", result.message());
                return 0;
            }
            PermissionCommandMessages.send(source, services, "group.manage.user_perm_remove_fail", "Could not remove permission {permission} from player.", "{permission}", permission);
            return 0;
        }
        PermissionCommandMessages.send(source, services, "group.manage.user_perm_remove_context_ok", "Removed permission {permission} from player ({context}).",
                "{permission}", permission, "{context}", contextLabel(services, arguments.contexts()));
        return showUserInfo(source, services, playerInput);
    }

    private static int removeUserPermissionById(ICommandSource source, Services services, String playerInput, String assignmentId) {
        UUID uuid = PermissionCommandArguments.resolvePlayerUuid(services, playerInput);
        if (uuid == null) {
            PermissionCommandMessages.send(source, services, "group.manage.user_invalid", "Player must be online name or UUID.");
            return 0;
        }
        PermissionMutationRequest request = request("user_permission_remove", null, null, null, uuid.toString(), false, PermissionContextSet.empty(), null);
        request.assignmentId = assignmentId;
        PermissionMutationResult result = mutate(source, services, request);
        if (!result.applied()) return PermissionCommandMessages.assignmentNotFound(source, services, assignmentId);
        return showUserInfo(source, services, playerInput);
    }

    private static int assignUserGroup(ICommandSource source, Services services, String playerInput, String group, int amount, String unit) {
        UUID uuid = PermissionCommandArguments.resolvePlayerUuid(services, playerInput);
        if (uuid == null) {
            PermissionCommandMessages.send(source, services, "group.manage.user_invalid", "Player must be online name or UUID.");
            return 0;
        }
        if (amount <= 0) return applyUserGroupMutation(source, services, uuid, group, PermissionContextSet.empty(), null);

        long expiresAtMs = computeLegacyExpiry(amount, unit);
        if (expiresAtMs <= System.currentTimeMillis()) {
            PermissionCommandMessages.send(source, services, "group.manage.duration_invalid", "Invalid duration. Use days/weeks/months with amount > 0.");
            return 0;
        }
        return applyUserGroupMutation(source, services, uuid, group, PermissionContextSet.empty(), expiresAtMs);
    }

    private static int assignUserGroupWithFlags(ICommandSource source, Services services, String playerInput, String group, String flags) {
        UUID uuid = PermissionCommandArguments.resolvePlayerUuid(services, playerInput);
        if (uuid == null) {
            PermissionCommandMessages.send(source, services, "group.manage.user_invalid", "Player must be online name or UUID.");
            return 0;
        }
        PermissionMutationArgumentParser.Result arguments = PermissionCommandArguments.parse(source, services, flags, true);
        if (!arguments.valid()) return PermissionCommandMessages.argumentError(source, services, arguments.code(), arguments.message());
        return applyUserGroupMutation(source, services, uuid, group, arguments.contexts(), arguments.expiresAtMs());
    }

    private static int applyUserGroupMutation(ICommandSource source, Services services, UUID uuid, String group,
                                              PermissionContextSet contexts, Long expiresAtMs) {
        PermissionMutationResult result = mutate(source, services,
                request("user_group_add", group, null, null, uuid.toString(), false, contexts, expiresAtMs));
        if (!result.applied()) {
            PermissionCommandMessages.send(source, services, "group.manage.user_add_fail", "Could not assign group {group} to player.", "{group}", group);
            return 0;
        }
        PermissionCommandMessages.send(source, services, "group.manage.user_add_context_ok", "Assigned group {group} to player ({context}, {expiry}).",
                "{group}", group, "{context}", contextLabel(services, contexts), "{expiry}", expiryLabel(services, expiresAtMs));
        return 1;
    }

    private static int removeUserGroup(ICommandSource source, Services services, UUID uuid, String group, String flags) {
        if (uuid == null) {
            PermissionCommandMessages.send(source, services, "group.manage.user_invalid", "Player must be online name or UUID.");
            return 0;
        }
        PermissionMutationArgumentParser.Result arguments = PermissionCommandArguments.parse(source, services, flags, false);
        if (!arguments.valid()) return PermissionCommandMessages.argumentError(source, services, arguments.code(), arguments.message());
        PermissionMutationResult result = mutate(source, services,
                request("user_group_remove", group, null, null, uuid.toString(), false, arguments.contexts(), null));
        if (!result.applied()) {
            if ("assignment_ambiguous".equals(result.code())) {
                PermissionCommandMessages.send(source, services, "group.manage.assignment_ambiguous", result.message());
                return 0;
            }
            PermissionCommandMessages.send(source, services, "group.manage.user_remove_fail", "Could not remove group {group} from player.", "{group}", group);
            return 0;
        }
        PermissionCommandMessages.send(source, services, "group.manage.user_remove_context_ok", "Removed group {group} from player ({context}).",
                "{group}", group, "{context}", contextLabel(services, arguments.contexts()));
        return 1;
    }

    private static int removeUserGroupById(ICommandSource source, Services services, String playerInput, String assignmentId) {
        UUID uuid = PermissionCommandArguments.resolvePlayerUuid(services, playerInput);
        if (uuid == null) {
            PermissionCommandMessages.send(source, services, "group.manage.user_invalid", "Player must be online name or UUID.");
            return 0;
        }
        PermissionMutationRequest request = request("user_group_remove", null, null, null, uuid.toString(), false, PermissionContextSet.empty(), null);
        request.assignmentId = assignmentId;
        PermissionMutationResult result = mutate(source, services, request);
        if (!result.applied()) return PermissionCommandMessages.assignmentNotFound(source, services, assignmentId);
        return showUserInfo(source, services, playerInput);
    }

    private static int showUserInfo(ICommandSource source, Services services, String playerInput) {
        UUID uuid = PermissionCommandArguments.resolvePlayerUuid(services, playerInput);
        if (uuid == null) {
            PermissionCommandMessages.send(source, services, "group.manage.user_invalid", "Player must be online name or UUID.");
            return 0;
        }
        return PermissionPanelRenderer.sendUserInfo(source, services, uuid,
                PermissionCommandArguments.resolvePlayerLabel(services, uuid, playerInput));
    }

    private static int explainPermission(ICommandSource source, Services services, String playerInput, String permission) {
        UUID uuid = PermissionCommandArguments.resolvePlayerUuid(services, playerInput);
        if (uuid == null) {
            PermissionCommandMessages.send(source, services, "group.manage.user_invalid", "Player must be online name or UUID.");
            return 0;
        }
        PermissionAPI.PermissionExplain explain = services.getPermissionsHandler().explainPlayerPermission(uuid, permission);
        if (explain == null) {
            PermissionCommandMessages.send(source, services, "group.manage.permission_check_fail", "Could not check permission {permission}.", "{permission}", permission);
            return 0;
        }
        PermissionPanelRenderer.sendPermissionExplain(source, services, playerInput, permission, explain);
        return 1;
    }

    private static PermissionMutationResult mutate(ICommandSource source, Services services, PermissionMutationRequest request) {
        return services.getPermissionAdminService().mutateTrusted(commandPrincipal(source), request);
    }

    private static int mutateTrack(ICommandSource source, Services services, String action, String track, String group, String target, Integer position) {
        PermissionMutationRequest request = request(action, group, null, null, null, false, PermissionContextSet.empty(), null);
        request.track = track;
        request.target = target;
        request.position = position;
        PermissionMutationResult result = mutate(source, services, request);
        if (!result.applied()) {
            PermissionCommandMessages.send(source, services, "group.manage.track_fail", result.message());
            return 0;
        }
        PermissionCommandMessages.send(source, services, "group.manage.track_ok", result.message());
        return 1;
    }

    private static int mutateRank(ICommandSource source, Services services, String action, String player, String track, String flags) {
        UUID uuid = PermissionCommandArguments.resolvePlayerUuid(services, player);
        if (uuid == null) {
            PermissionCommandMessages.send(source, services, "group.manage.user_invalid", "Player must be online name or UUID.");
            return 0;
        }
        PermissionMutationArgumentParser.Result arguments = PermissionCommandArguments.parse(source, services, flags, true, true);
        if (!arguments.valid()) return PermissionCommandMessages.argumentError(source, services, arguments.code(), arguments.message());
        String invalid = invalidTrackArguments(action, arguments);
        if (invalid != null) return PermissionCommandMessages.argumentError(source, services, "invalid_argument", invalid);
        PermissionMutationRequest request = request(action, null, null, null, uuid.toString(), false, arguments.contexts(), arguments.expiresAtMs());
        applyTrackExpiry(request, arguments);
        request.track = track;
        request.dontAddToFirst = arguments.dontAddToFirst();
        request.dontRemoveFromFirst = arguments.dontRemoveFromFirst();
        PermissionMutationResult result = mutate(source, services, request);
        if (!result.applied()) {
            PermissionCommandMessages.send(source, services, "group.manage.track_rank_fail", result.message());
            return 0;
        }
        PermissionCommandMessages.send(source, services, "group.manage.track_rank_ok", result.message());
        return 1;
    }

    private static int mutateUserTrack(ICommandSource source, Services services, String action, String player, String track, String group, String flags) {
        UUID uuid = PermissionCommandArguments.resolvePlayerUuid(services, player);
        if (uuid == null) {
            PermissionCommandMessages.send(source, services, "group.manage.user_invalid", "Player must be online name or UUID.");
            return 0;
        }
        PermissionMutationArgumentParser.Result arguments = PermissionCommandArguments.parse(source, services, flags, true, true);
        if (!arguments.valid()) return PermissionCommandMessages.argumentError(source, services, arguments.code(), arguments.message());
        String invalid = invalidTrackArguments(action, arguments);
        if (invalid != null) return PermissionCommandMessages.argumentError(source, services, "invalid_argument", invalid);
        PermissionMutationRequest request = request(action, group, null, null, uuid.toString(), false, arguments.contexts(), arguments.expiresAtMs());
        applyTrackExpiry(request, arguments);
        request.track = track;
        request.dontAddToFirst = arguments.dontAddToFirst();
        request.dontRemoveFromFirst = arguments.dontRemoveFromFirst();
        PermissionMutationResult result = mutate(source, services, request);
        if (!result.applied()) {
            PermissionCommandMessages.send(source, services, "group.manage.track_rank_fail", result.message());
            return 0;
        }
        PermissionCommandMessages.send(source, services, "group.manage.track_rank_ok", result.message());
        return 1;
    }

    private static int showTrack(ICommandSource source, IPlatformAdapter platform, Services services, String name) {
        var track = services.getPermissionsHandler().getPermissionTrack(name);
        if (track == null) {
            platform.sendFailure(source, platform.createLiteralComponent("Track was not found."));
            return 0;
        }
        String ranks = track.groups().isEmpty() ? "(empty)" : String.join(" -> ", track.groups());
        platform.sendSuccess(source, platform.createLiteralComponent(track.name() + ": " + ranks), false);
        return 1;
    }

    private static int showUserTracks(ICommandSource source, IPlatformAdapter platform, Services services, String player) {
        UUID uuid = PermissionCommandArguments.resolvePlayerUuid(services, player);
        if (uuid == null) {
            platform.sendFailure(source, platform.createLiteralComponent("Player must be online name or UUID."));
            return 0;
        }
        PermissionAPI.UserInfo info = services.getPermissionsHandler().getPlayerPermissionInfo(uuid);
        List<String> direct = info != null ? info.groupAssignments().stream()
                .filter(assignment -> assignment.kind() == PermissionAssignment.Kind.USER_GROUP && !assignment.expired())
                .map(assignment -> assignment.value()).toList() : List.of();
        List<String> lines = new ArrayList<>();
        for (var track : services.getPermissionsHandler().listPermissionTracks()) {
            int position = -1;
            for (int index = 0; index < track.groups().size(); index++) if (direct.contains(track.groups().get(index))) { position = index + 1; break; }
            lines.add(track.name() + ": " + (position > 0 ? track.groups().get(position - 1) + " (" + position + "/" + track.groups().size() + ")" : "not on track"));
        }
        platform.sendSuccess(source, platform.createLiteralComponent(lines.isEmpty() ? "No permission tracks." : String.join("; ", lines)), false);
        return 1;
    }

    private static int showGroupTracks(ICommandSource source, IPlatformAdapter platform, Services services, String group) {
        List<String> tracks = new ArrayList<>();
        for (var track : services.getPermissionsHandler().listPermissionTracks()) {
            int index = track.groups().indexOf(group.toLowerCase(Locale.ROOT));
            if (index >= 0) tracks.add(track.name() + " (" + (index + 1) + "/" + track.groups().size() + ")");
        }
        platform.sendSuccess(source, platform.createLiteralComponent(tracks.isEmpty() ? "Group is not on a track." : String.join("; ", tracks)), false);
        return 1;
    }

    private static List<String> trackSuggestions(Services services, String input) {
        return filterSuggestions(services.getPermissionsHandler().listPermissionTracks().stream().map(track -> track.name()).toList(), input);
    }

    private static List<String> trackGroupSuggestions(Services services, String trackName, String input) {
        var track = services.getPermissionsHandler().getPermissionTrack(trackName);
        return track != null ? filterSuggestions(track.groups(), input) : List.of();
    }

    private static List<String> trackCandidateSuggestions(Services services, String trackName, String input) {
        var track = services.getPermissionsHandler().getPermissionTrack(trackName);
        if (track == null) return List.of();
        return filterSuggestions(services.getPermissionsHandler().listPermissionGroups().stream()
                .filter(group -> !track.groups().contains(group)).toList(), input);
    }

    private static String invalidTrackArguments(String action, PermissionMutationArgumentParser.Result arguments) {
        if (arguments.expirySpecified() && !"promote".equals(action) && !"settrack".equals(action)) {
            return "Expiry is only valid when assigning a track rank.";
        }
        if (arguments.dontAddToFirst() && !"promote".equals(action)) {
            return "--dont-add-to-first is only valid for promote.";
        }
        if (arguments.dontRemoveFromFirst() && !"demote".equals(action)) {
            return "--dont-remove-from-first is only valid for demote.";
        }
        return null;
    }

    private static void applyTrackExpiry(PermissionMutationRequest request, PermissionMutationArgumentParser.Result arguments) {
        if (arguments.expirySpecified() && arguments.expiresAtMs() == null) request.permanent = true;
    }

    private static DashboardPrincipal commandPrincipal(ICommandSource source) {
        IPlayer player = source != null ? source.getPlayer() : null;
        return new DashboardPrincipal(player != null ? player.getUUID() : null,
                player != null ? player.getName() : (source != null ? source.getSourceName() : "console"),
                source == null || source.isConsole());
    }

    private static PermissionMutationRequest request(String action, String group, String parent, String permission, String user,
                                                     boolean denied, PermissionContextSet contexts, Long expiresAtMs) {
        PermissionMutationRequest request = new PermissionMutationRequest();
        request.action = action;
        request.group = group;
        request.parent = parent;
        request.permission = permission;
        request.user = user;
        request.denied = denied;
        request.contexts = contexts != null ? contexts.asMap() : Map.of();
        request.scope = contexts != null && !contexts.isEmpty() ? "custom" : "global";
        request.expiresAtMs = expiresAtMs;
        request.permanent = null;
        request.confirmed = true;
        return request;
    }

    private static String contextLabel(Services services, PermissionContextSet contexts) {
        return PermissionDisplayFormatter.context(services.getLang(), contexts);
    }

    private static String expiryLabel(Services services, Long expiresAtMs) {
        return PermissionDisplayFormatter.expiry(services.getLang(), expiresAtMs);
    }

    private static long computeLegacyExpiry(int amount, String unitRaw) {
        if (amount <= 0 || unitRaw == null || unitRaw.isBlank()) return -1L;
        ZonedDateTime now = ZonedDateTime.now(ZoneOffset.UTC);
        ZonedDateTime expires = switch (unitRaw.trim().toLowerCase(Locale.ROOT)) {
            case "day", "days" -> now.plusDays(amount);
            case "week", "weeks" -> now.plusWeeks(amount);
            case "month", "months" -> now.plusMonths(amount);
            default -> null;
        };
        return expires != null ? expires.toInstant().toEpochMilli() : -1L;
    }

    private static List<String> permissionSuggestions(Services services, String input) {
        Set<String> nodes = new LinkedHashSet<>();
        for (String node : services.getPermissionsHandler().knownPermissionNodes().keySet()) {
            if (node == null || node.isBlank()) continue;
            nodes.add(node.contains("<number>") ? node.replace("<number>", "3") : node);
        }
        nodes.add("paradigm.*");
        nodes.add("*");
        return filterSuggestions(new ArrayList<>(nodes), input);
    }

    private static List<String> groupPermissionSuggestions(Services services, String group, String input) {
        PermissionAPI.GroupInfo info = services.getPermissionsHandler().getPermissionGroupInfo(group);
        if (info == null) return permissionSuggestions(services, input);
        List<String> nodes = new ArrayList<>();
        for (PermissionAssignment assignment : info.assignments()) nodes.add(assignment.value());
        return filterSuggestions(nodes, input);
    }

    private static List<String> userPermissionSuggestions(Services services, String playerInput, String input) {
        UUID uuid = PermissionCommandArguments.resolvePlayerUuid(services, playerInput);
        if (uuid == null) return permissionSuggestions(services, input);
        PermissionAPI.UserInfo info = services.getPermissionsHandler().getPlayerPermissionInfo(uuid);
        if (info == null) return permissionSuggestions(services, input);
        List<String> nodes = new ArrayList<>();
        for (PermissionAssignment assignment : info.assignments()) nodes.add(assignment.value());
        return filterSuggestions(nodes, input);
    }

    private static List<String> filterSuggestions(List<String> values, String input) {
        if (values == null || values.isEmpty()) return List.of();
        String query = input != null ? input.trim().toLowerCase(Locale.ROOT) : "";
        return values.stream()
                .filter(value -> value != null && !value.isBlank())
                .distinct()
                .sorted(String.CASE_INSENSITIVE_ORDER)
                .filter(value -> query.isEmpty() || value.toLowerCase(Locale.ROOT).startsWith(query))
                .toList();
    }

    private static String stripDeny(String permission) {
        if (permission == null) return "";
        String trimmed = permission.trim();
        return trimmed.startsWith("-") ? trimmed.substring(1).trim() : trimmed;
    }

    private static boolean hasGroupManagePermission(ICommandSource source, Services services) {
        if (source == null) return false;
        if (source.isConsole() || source.hasPermissionLevel(2)) return true;
        IPlayer player = source.getPlayer();
        return player != null && services.getPermissionsHandler().hasPermission(
                player, ParadigmPermissions.GROUP_MANAGE);
    }
}
