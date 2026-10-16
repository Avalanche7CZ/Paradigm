package org.bukkit.craftbukkit.v1_7_R4.command;

/** Minimal fixture for the public Bukkit wrapper API; this is not a Crucible runtime. */
public final class ModCustomCommand {
    private final String name;
    private String permission;
    public boolean failOpen;
    public boolean failRestore;

    public ModCustomCommand(String name, String permission) {
        this.name = name;
        this.permission = permission;
    }

    public String getName() { return name; }
    public String getPermission() { return permission; }

    public void setPermission(String permission) {
        if (permission.isEmpty() ? failOpen : failRestore) {
            throw new IllegalStateException("simulated wrapper failure");
        }
        this.permission = permission;
    }
}
