package org.bukkit;
import org.bukkit.command.CommandSender;
public final class Bukkit {
    public static Object server;
    public static Object getServer() { return server; }
    public static boolean handled;
    public static RuntimeException failure;
    public static int calls;
    public static CommandSender getConsoleSender() { return new CommandSender() {}; }
    public static boolean dispatchCommand(CommandSender sender, String command) {
        calls++;
        if (failure != null) throw failure;
        return handled;
    }
}
