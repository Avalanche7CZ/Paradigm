package eu.avalanche7.paradigm.modules.commands.admin;

import eu.avalanche7.paradigm.core.Services;
import eu.avalanche7.paradigm.platform.Interfaces.ICommandBuilder;
import eu.avalanche7.paradigm.platform.Interfaces.ICommandSource;
import eu.avalanche7.paradigm.platform.Interfaces.IPlayer;
import eu.avalanche7.paradigm.modules.permissions.PermissionAPI;
import eu.avalanche7.paradigm.modules.permissions.PermissionsHandler;

import java.util.List;
import java.util.Locale;
import java.util.UUID;

public class WhoisCommand extends AbstractAdminCommand {
    @Override
    public String getName() {
        return "Whois";
    }

    @Override
    public void registerCommands(Object dispatcher, Object registryAccess, Services services) {
        this.services = services;
        ICommandBuilder cmd = builder()
                .literal("whois")
                .requires(src -> allowed(src, "whois", PermissionsHandler.WHOIS_PERMISSION, PermissionsHandler.WHOIS_PERMISSION_LEVEL))
                .then(builder()
                        .argument("player", ICommandBuilder.ArgumentType.PLAYER)
                        .executes(ctx -> whois(ctx.getSource(), ctx.getPlayerArgument("player"))));
        services.getPlatformAdapter().registerCommand(cmd);
    }

    private int whois(ICommandSource source, IPlayer target) {
        if (target == null) {
            send(source, "admin.player_not_found", "Player not found.");
            return 0;
        }
        send(source, "admin.whois_header", "Whois: {player}", "{player}", target.getName());
        send(source, "admin.whois_uuid", "UUID: {uuid}", "{uuid}", target.getUUID() != null ? target.getUUID() : "unknown");
        send(source, "admin.whois_world", "World: {world}", "{world}", target.getWorldId() != null ? target.getWorldId() : "unknown");
        send(source, "admin.whois_pos", "Position: {x}, {y}, {z}",
                "{x}", fmt(target.getX()), "{y}", fmt(target.getY()), "{z}", fmt(target.getZ()));
        send(source, "admin.whois_health", "Health: {health}/{max}",
                "{health}", fmt(target.getHealth()), "{max}", fmt(target.getMaxHealth()));
        send(source, "admin.whois_level", "XP level: {level}", "{level}", target.getLevel() != null ? String.valueOf(target.getLevel()) : "unknown");
        String groups = groups(target.getUUID());
        if (groups != null) {
            send(source, "admin.whois_groups", "Groups: {groups}", "{groups}", groups);
        }
        return 1;
    }

    private String groups(String uuidRaw) {
        try {
            UUID uuid = UUID.fromString(uuidRaw);
            PermissionAPI.UserGroupsInfo info = services.getPermissionsHandler().getPlayerGroups(uuid);
            if (info == null) {
                return null;
            }
            List<String> permanent = info.permanentGroups() != null ? info.permanentGroups() : List.of();
            long tempCount = info.temporaryGroups() != null ? info.temporaryGroups().size() : 0L;
            String base = permanent.isEmpty() ? "none" : String.join(", ", permanent);
            return tempCount > 0L ? base + " (+" + tempCount + " temporary)" : base;
        } catch (Throwable ignored) {
            return null;
        }
    }

    private String fmt(Number value) {
        return value == null ? "unknown" : String.format(Locale.US, "%.2f", value.doubleValue());
    }
}
