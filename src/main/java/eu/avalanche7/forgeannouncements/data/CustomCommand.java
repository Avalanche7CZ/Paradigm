package eu.avalanche7.forgeannouncements.data;

import java.util.List;

public class CustomCommand {

    private String name;
    private String description;
    private String permission;
    private boolean requirePermission;
    private String permissionErrorMessage;
    private List<Action> actions;

    public CustomCommand(String name, String description, String permission, boolean requirePermission, List<Action> actions) {
        this.name = name;
        this.description = description;
        this.permission = permission;
        this.requirePermission = requirePermission;
        this.permissionErrorMessage = permissionErrorMessage;
        this.actions = actions;
    }

    public String getName() {
        return name;
    }

    public String getDescription() {
        return description;
    }

    public String getPermission() {
        return permission;
    }

    public boolean isRequirePermission() {
        return requirePermission;
    }

    public List<Action> getActions() {
        return actions;
    }

    public String getPermissionErrorMessage() {
        return permissionErrorMessage != null ? permissionErrorMessage : "&cYou do not have permission to execute this command."; // Default error message
    }

    public static class Action {

        private String type;
        private List<String> text;
        private Integer x, y, z;
        private List<String> commands;

        public Action(String type, List<String> text, Integer x, Integer y, Integer z, List<String> commands) {
            this.type = type;
            this.text = text;
            this.x = x;
            this.y = y;
            this.z = z;
            this.commands = commands;
        }

        public String getType() {
            return type;
        }

        public List<String> getText() {
            return text;
        }

        public Integer getX() {
            return x;
        }

        public Integer getY() {
            return y;
        }

        public Integer getZ() {
            return z;
        }

        public List<String> getCommands() {
            return commands;
        }
    }
}
