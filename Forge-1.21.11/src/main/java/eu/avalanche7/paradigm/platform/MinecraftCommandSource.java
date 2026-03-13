package eu.avalanche7.paradigm.platform;

import eu.avalanche7.paradigm.platform.Interfaces.ICommandSource;
import eu.avalanche7.paradigm.platform.Interfaces.IPlayer;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.permissions.Permission;
import net.minecraft.server.permissions.PermissionLevel;
import javax.annotation.Nullable;

/**
 * Minecraft implementation of ICommandSource
 */
public class MinecraftCommandSource implements ICommandSource {
    private final CommandSourceStack source;
    private final IPlayer player;

    public MinecraftCommandSource(CommandSourceStack source) {
        this.source = source;
        this.player = source.getEntity() instanceof ServerPlayer sp ? MinecraftPlayer.of(sp) : null;
    }

    @Override
    @Nullable
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
            PermissionLevel permLevel = PermissionLevel.byId(level);
            Permission required = new Permission.HasCommandLevel(permLevel);
            return source.permissions().hasPermission(required);
        } catch (Throwable t) {
            return player == null; // console has full permissions
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

    public CommandSourceStack getHandle() {
        return source;
    }

    public static ICommandSource of(CommandSourceStack source) {
        return new MinecraftCommandSource(source);
    }
}
