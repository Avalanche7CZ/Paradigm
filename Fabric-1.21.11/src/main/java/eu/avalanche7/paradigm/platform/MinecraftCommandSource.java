package eu.avalanche7.paradigm.platform;

import eu.avalanche7.paradigm.platform.Interfaces.ICommandSource;
import eu.avalanche7.paradigm.platform.Interfaces.IPlayer;
import net.minecraft.command.permission.LeveledPermissionPredicate;
import net.minecraft.command.permission.Permission;
import net.minecraft.command.permission.PermissionLevel;
import net.minecraft.server.command.ServerCommandSource;

public class MinecraftCommandSource implements ICommandSource {
    private final ServerCommandSource source;
    private final IPlayer player;

    public MinecraftCommandSource(ServerCommandSource source) {
        this.source = source;
        var sp = source.getPlayer();
        this.player = sp != null ? MinecraftPlayer.of(sp) : null;
    }

    @Override
    public IPlayer getPlayer() {
        return player;
    }

    @Override
    public String getSourceName() {
        if (player != null) {
            return player.getName();
        }
        return "Console";
    }

    @Override
    public boolean hasPermissionLevel(int level) {
        try {
            PermissionLevel permLevel = PermissionLevel.fromLevel(level);
            return source.getPermissions().hasPermission(new Permission.Level(permLevel));
        } catch (Exception e) {
            return player == null;
        }
    }

    @Override
    public boolean isConsole() {
        return player == null;
    }

    @Override
    public Object getOriginalSource() {
        return source;
    }

    public ServerCommandSource getHandle() {
        return source;
    }

    public static ICommandSource of(ServerCommandSource source) {
        return new MinecraftCommandSource(source);
    }
}
