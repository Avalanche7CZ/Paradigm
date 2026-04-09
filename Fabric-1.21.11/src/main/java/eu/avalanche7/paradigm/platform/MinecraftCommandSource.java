package eu.avalanche7.paradigm.platform;

import eu.avalanche7.paradigm.platform.Interfaces.ICommandSource;
import eu.avalanche7.paradigm.platform.Interfaces.IPlayer;
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
        Boolean sourceResult = invokeBooleanIntMethod(source, level, "hasPermissionLevel", "hasPermission", "hasPermissions");
        if (sourceResult != null) {
            return sourceResult;
        }

        Object originalPlayer = player != null ? player.getOriginalPlayer() : null;
        if (originalPlayer != null) {
            Boolean playerResult = invokeBooleanIntMethod(originalPlayer, level, "hasPermissionLevel", "hasPermission", "hasPermissions");
            if (playerResult != null) {
                return playerResult;
            }

            Boolean opResult = checkServerOpList(originalPlayer);
            if (opResult != null) {
                return opResult;
            }
        }

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

    private static Boolean invokeBooleanIntMethod(Object target, int arg, String... methodNames) {
        if (target == null || methodNames == null) {
            return null;
        }
        for (String methodName : methodNames) {
            try {
                java.lang.reflect.Method m = findMethod(target.getClass(), methodName, int.class);
                if (m == null) {
                    continue;
                }
                m.setAccessible(true);
                Object result = m.invoke(target, arg);
                if (result instanceof Boolean b) {
                    return b;
                }
            } catch (Throwable ignored) {
            }
        }
        return null;
    }

    private static Boolean checkServerOpList(Object originalPlayer) {
        try {
            Object server = invokeNoArg(originalPlayer, "getServer");
            if (server == null) {
                return null;
            }
            Object profile = invokeNoArg(originalPlayer, "getGameProfile");
            if (profile == null) {
                return null;
            }
            Object playerManager = invokeNoArg(server, "getPlayerList");
            if (playerManager == null) {
                playerManager = invokeNoArg(server, "getPlayerManager");
            }
            if (playerManager == null) {
                return null;
            }
            return invokeBooleanSingleArgMethod(playerManager, profile, "isOp", "isOperator");
        } catch (Throwable ignored) {
            return null;
        }
    }

    private static Boolean invokeBooleanSingleArgMethod(Object target, Object arg, String... methodNames) {
        if (target == null || arg == null || methodNames == null) {
            return null;
        }
        Class<?> argClass = arg.getClass();
        for (String methodName : methodNames) {
            try {
                for (java.lang.reflect.Method m : target.getClass().getMethods()) {
                    if (!m.getName().equals(methodName) || m.getParameterCount() != 1) {
                        continue;
                    }
                    if (!m.getParameterTypes()[0].isAssignableFrom(argClass)) {
                        continue;
                    }
                    m.setAccessible(true);
                    Object result = m.invoke(target, arg);
                    if (result instanceof Boolean b) {
                        return b;
                    }
                }
            } catch (Throwable ignored) {
            }
        }
        return null;
    }

    private static Object invokeNoArg(Object target, String methodName) {
        if (target == null || methodName == null || methodName.isBlank()) {
            return null;
        }
        try {
            java.lang.reflect.Method m = findMethod(target.getClass(), methodName);
            if (m == null) {
                return null;
            }
            m.setAccessible(true);
            return m.invoke(target);
        } catch (Throwable ignored) {
            return null;
        }
    }

    private static java.lang.reflect.Method findMethod(Class<?> type, String name, Class<?>... params) {
        if (type == null || name == null || name.isBlank()) {
            return null;
        }
        try {
            return type.getMethod(name, params);
        } catch (Throwable ignored) {
        }
        for (Class<?> c = type; c != null; c = c.getSuperclass()) {
            try {
                return c.getDeclaredMethod(name, params);
            } catch (Throwable ignored) {
            }
        }
        return null;
    }
}
