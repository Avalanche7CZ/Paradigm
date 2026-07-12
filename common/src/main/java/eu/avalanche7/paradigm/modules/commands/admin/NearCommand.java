package eu.avalanche7.paradigm.modules.commands.admin;

import eu.avalanche7.paradigm.core.Services;
import eu.avalanche7.paradigm.modules.commands.shared.PlayerReflection;
import eu.avalanche7.paradigm.platform.Interfaces.ICommandBuilder;
import eu.avalanche7.paradigm.platform.Interfaces.ICommandSource;
import eu.avalanche7.paradigm.platform.Interfaces.IPlayer;
import eu.avalanche7.paradigm.modules.permissions.PermissionsHandler;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;

public class NearCommand extends AbstractAdminCommand {
    @Override
    public String getName() {
        return "Near";
    }

    @Override
    public void registerCommands(Object dispatcher, Object registryAccess, Services services) {
        this.services = services;
        ICommandBuilder cmd = builder()
                .literal("near")
                .requires(src -> allowed(src, "near", PermissionsHandler.NEAR_PERMISSION, PermissionsHandler.NEAR_PERMISSION_LEVEL)
                        && src.getPlayer() != null)
                .executes(ctx -> near(ctx.getSource(), 100))
                .then(builder()
                        .argument("radius", ICommandBuilder.ArgumentType.INTEGER)
                        .executes(ctx -> near(ctx.getSource(), ctx.getIntArgument("radius"))));
        services.getPlatformAdapter().registerCommand(cmd);
    }

    private int near(ICommandSource source, int radius) {
        IPlayer actor = source.getPlayer();
        if (actor == null) {
            send(source, "admin.player_only", "This command can only be used by a player.");
            return 0;
        }
        int clamped = Math.max(1, Math.min(radius, 10000));
        double maxDistanceSq = (double) clamped * clamped;
        List<Entry> nearby = new ArrayList<>();
        for (IPlayer player : services.getPlatformAdapter().getOnlinePlayers()) {
            if (player == null || player.getUUID() == null || player.getUUID().equals(actor.getUUID())) {
                continue;
            }
            if (!sameWorld(actor, player)) {
                continue;
            }
            double distanceSq = PlayerReflection.distanceSquared(actor, player);
            if (distanceSq <= maxDistanceSq) {
                nearby.add(new Entry(player.getName(), Math.sqrt(distanceSq)));
            }
        }
        nearby.sort(Comparator.comparingDouble(Entry::distance));
        if (nearby.isEmpty()) {
            send(source, "admin.near_none", "No players within {radius} blocks.", "{radius}", String.valueOf(clamped));
            return 0;
        }
        StringBuilder list = new StringBuilder();
        for (Entry entry : nearby) {
            if (list.length() > 0) {
                list.append(", ");
            }
            list.append(entry.name()).append(" (").append(String.format(Locale.US, "%.1f", entry.distance())).append("m)");
        }
        send(source, "admin.near_list", "Nearby: {players}", "{players}", list.toString());
        return 1;
    }

    private boolean sameWorld(IPlayer a, IPlayer b) {
        String aw = a.getWorldId();
        String bw = b.getWorldId();
        return aw == null || bw == null || aw.equalsIgnoreCase(bw);
    }

    private record Entry(String name, double distance) {
    }
}
