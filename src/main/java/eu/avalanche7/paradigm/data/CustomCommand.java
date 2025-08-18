package eu.avalanche7.paradigm.data;

import java.util.ArrayList;
import java.util.List;

public class CustomCommand {

    private String name;
    private String description;
    private String permission;
    private boolean requirePermission;
    private String permissionErrorMessage;
    private List<Action> actions;

    private Integer cooldown_seconds;
    private String cooldown_message;
    private AreaRestriction area_restriction;
    private List<ArgumentDefinition> arguments;

    private CustomCommand() {}

    public CustomCommand(String name, String description, String permission, boolean requirePermission, String permissionErrorMessage, List<Action> actions) {
        this(name, description, permission, requirePermission, permissionErrorMessage, actions, null, null, null);
    }

    public CustomCommand(String name, String description, String permission, boolean requirePermission, String permissionErrorMessage, List<Action> actions, Integer cooldown_seconds, String cooldown_message) {
        this(name, description, permission, requirePermission, permissionErrorMessage, actions, cooldown_seconds, cooldown_message, null);
    }

    public CustomCommand(String name, String description, String permission, boolean requirePermission, String permissionErrorMessage, List<Action> actions, Integer cooldown_seconds, String cooldown_message, AreaRestriction area_restriction) {
        this(name, description, permission, requirePermission, permissionErrorMessage, actions, cooldown_seconds, cooldown_message, area_restriction, null);
    }

    public CustomCommand(String name, String description, String permission, boolean requirePermission, String permissionErrorMessage, List<Action> actions, Integer cooldown_seconds, String cooldown_message, AreaRestriction area_restriction, List<ArgumentDefinition> arguments) {
        this.name = name;
        this.description = description;
        this.permission = permission;
        this.requirePermission = requirePermission;
        this.permissionErrorMessage = permissionErrorMessage;
        this.actions = actions;
        this.cooldown_seconds = cooldown_seconds;
        this.cooldown_message = cooldown_message;
        this.area_restriction = area_restriction;
        this.arguments = arguments;
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
        return permissionErrorMessage != null ? permissionErrorMessage : "&cYou do not have permission to execute this command.";
    }

    public Integer getCooldownSeconds() {
        return cooldown_seconds;
    }

    public String getCooldownMessage() {
        return cooldown_message;
    }

    public AreaRestriction getAreaRestriction() {
        return area_restriction;
    }

    public List<ArgumentDefinition> getArguments() {
        return arguments != null ? arguments : new ArrayList<>();
    }

    public static class AreaRestriction {
        private String world;
        private List<Integer> corner1;
        private List<Integer> corner2;
        private String restriction_message;

        private AreaRestriction() {}

        public AreaRestriction(String world, List<Integer> corner1, List<Integer> corner2, String restriction_message) {
            this.world = world;
            this.corner1 = corner1;
            this.corner2 = corner2;
            this.restriction_message = restriction_message;
        }

        public String getWorld() {
            return world;
        }

        public List<Integer> getCorner1() {
            return corner1;
        }

        public List<Integer> getCorner2() {
            return corner2;
        }

        public String getRestrictionMessage() {
            return restriction_message != null ? restriction_message : "&cYou are not in the correct area to use this command.";
        }
    }

    public static class Condition {
        private String type;
        private String value;
        private Integer item_amount;
        private boolean negate;

        private Condition() {}

        public Condition(String type, String value, Integer item_amount, boolean negate) {
            this.type = type;
            this.value = value;
            this.item_amount = item_amount;
            this.negate = negate;
        }

        public String getType() {
            return type != null ? type.toLowerCase() : "";
        }

        public String getValue() {
            return value;
        }

        public int getItemAmount() {
            return item_amount != null ? item_amount : 1;
        }

        public boolean isNegate() {
            return negate;
        }
    }

    public static class Action {
        private String type;
        private List<String> text;
        private Integer x, y, z;
        private List<String> commands;

        private List<Condition> conditions;
        private List<Action> on_success;
        private List<Action> on_failure;

        private Action() {}

        public Action(String type, List<String> text, Integer x, Integer y, Integer z, List<String> commands, List<Condition> conditions, List<Action> on_success, List<Action> on_failure) {
            this.type = type;
            this.text = text;
            this.x = x;
            this.y = y;
            this.z = z;
            this.commands = commands;
            this.conditions = conditions;
            this.on_success = on_success;
            this.on_failure = on_failure;
        }

        public String getType() {
            return type != null ? type.toLowerCase() : "";
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

        public List<Condition> getConditions() {
            return conditions != null ? conditions : new ArrayList<>();
        }

        public List<Action> getOnSuccess() {
            return on_success != null ? on_success : new ArrayList<>();
        }

        public List<Action> getOnFailure() {
            return on_failure != null ? on_failure : new ArrayList<>();
        }
    }
    public static class ArgumentDefinition {
        private String name;
        private String type; // "string", "integer", "boolean", "player", "world", "gamemode", "custom"
        private boolean required;
        private String errorMessage;
        private List<String> customCompletions;
        private Integer minValue;
        private Integer maxValue;

        private ArgumentDefinition() {}

        public ArgumentDefinition(String name, String type, boolean required, String errorMessage, List<String> customCompletions, Integer minValue, Integer maxValue) {
            this.name = name;
            this.type = type != null ? type.toLowerCase() : "string";
            this.required = required;
            this.errorMessage = errorMessage;
            this.customCompletions = customCompletions;
            this.minValue = minValue;
            this.maxValue = maxValue;
        }

        public String getName() {
            return name;
        }

        public String getType() {
            return type != null ? type.toLowerCase() : "string";
        }

        public boolean isRequired() {
            return required;
        }

        public String getErrorMessage() {
            return errorMessage != null ? errorMessage : "&cInvalid argument: " + name;
        }

        public List<String> getCustomCompletions() {
            return customCompletions != null ? customCompletions : new ArrayList<>();
        }

        public Integer getMinValue() {
            return minValue;
        }

        public Integer getMaxValue() {
            return maxValue;
        }
    }
}