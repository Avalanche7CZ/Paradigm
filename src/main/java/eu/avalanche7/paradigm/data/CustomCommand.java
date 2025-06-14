package eu.avalanche7.paradigm.data;

import java.util.List;

public class CustomCommand {
    private String name;
    private String description;
    private boolean requirePermission;
    private String permission;
    private String permissionErrorMessage;
    private List<Action> actions;

    public String getName() { return name; }
    public void setName(String name) { this.name = name; }
    public String getDescription() { return description; }
    public void setDescription(String description) { this.description = description; }
    public boolean isRequirePermission() { return requirePermission; }
    public void setRequirePermission(boolean requirePermission) { this.requirePermission = requirePermission; }
    public String getPermission() { return permission; }
    public void setPermission(String permission) { this.permission = permission; }
    public String getPermissionErrorMessage() { return permissionErrorMessage; }
    public void setPermissionErrorMessage(String message) { this.permissionErrorMessage = message; }
    public List<Action> getActions() { return actions; }
    public void setActions(List<Action> actions) { this.actions = actions; }

    public static class Action {
        private String type;
        private List<String> text;
        private List<String> commands;
        private Double x;
        private Double y;
        private Double z;

        public String getType() { return type; }
        public void setType(String type) { this.type = type; }
        public List<String> getText() { return text; }
        public void setText(List<String> text) { this.text = text; }
        public List<String> getCommands() { return commands; }
        public void setCommands(List<String> commands) { this.commands = commands; }
        public Double getX() { return x; }
        public void setX(Double x) { this.x = x; }
        public Double getY() { return y; }
        public void setY(Double y) { this.y = y; }
        public Double getZ() { return z; }
        public void setZ(Double z) { this.z = z; }
    }
}
