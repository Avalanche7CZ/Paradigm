package eu.avalanche7.paradigm.utils.formatting.tags;

import eu.avalanche7.paradigm.platform.Interfaces.IPlatformAdapter;
import eu.avalanche7.paradigm.utils.formatting.FormattingContext;
import net.minecraft.text.Style;

public class ClickTag implements Tag {
    private final IPlatformAdapter platformAdapter;

    public ClickTag(IPlatformAdapter platformAdapter) {
        this.platformAdapter = platformAdapter;
    }

    public enum ClickAction {
        OPEN_URL("open_url", "OPEN_URL"),
        RUN_COMMAND("run_cmd", "RUN_COMMAND"),
        RUN_COMMAND_ALT("run_command", "RUN_COMMAND"),
        EXECUTE("execute", "RUN_COMMAND"),
        EXECUTE_ALT("exec", "RUN_COMMAND"),
        SUGGEST_COMMAND("suggest_command", "SUGGEST_COMMAND"),
        SUGGEST_COMMAND_ALT("cmd", "SUGGEST_COMMAND"),
        SUGGEST_COMMAND_ALT2("suggest", "SUGGEST_COMMAND"),
        COPY_TO_CLIPBOARD("copy_to_clipboard", "COPY_TO_CLIPBOARD"),
        COPY_ALT("copy", "COPY_TO_CLIPBOARD"),
        CHANGE_PAGE("change_page", "CHANGE_PAGE"),
        PAGE_ALT("page", "CHANGE_PAGE");

        private final String name;
        private final String actionName;

        ClickAction(String name, String actionName) {
            this.name = name;
            this.actionName = actionName;
        }

        public static ClickAction fromString(String str) {
            for (ClickAction action : ClickAction.values()) {
                if (action.name.equalsIgnoreCase(str)) {
                    return action;
                }
            }
            return null;
        }

        public String getActionName() {
            return actionName;
        }

        public String getName() {
            return name;
        }
    }

    @Override
    public String getName() {
        return "click";
    }

    @Override
    public boolean canOpen() {
        return true;
    }

    @Override
    public boolean canClose() {
        return true;
    }

    @Override
    public void process(FormattingContext context, String arguments) {
        String[] parts = arguments.split(":", 2);
        if (parts.length < 2) {
            return;
        }

        ClickAction action = ClickAction.fromString(parts[0].trim());
        if (action == null) {
            return;
        }

        String value = parts[1].trim();
        if (value.startsWith("'") && value.endsWith("'")) {
            value = value.substring(1, value.length() - 1);
        }

        if (action == ClickAction.RUN_COMMAND || action == ClickAction.RUN_COMMAND_ALT) {
            while (value.startsWith("/")) {
                value = value.substring(1);
            }
        }

        if ((action == ClickAction.EXECUTE || action == ClickAction.EXECUTE_ALT) && !value.startsWith("/")) {
            value = "/" + value;
        }

        Style newStyle = platformAdapter.createStyleWithClickEvent(context.getCurrentStyle(), action.getActionName(), value);
        context.pushStyle(newStyle);
    }

    @Override
    public void close(FormattingContext context) {
        context.popStyle();
    }

    @Override
    public boolean matchesTagName(String name) {
        return name.equalsIgnoreCase("click") || name.equalsIgnoreCase("open_url") ||
               name.equalsIgnoreCase("url") || name.equalsIgnoreCase("run_cmd") ||
               name.equalsIgnoreCase("execute") || name.equalsIgnoreCase("exec") ||
               name.equalsIgnoreCase("suggest_command") || name.equalsIgnoreCase("cmd") ||
               name.equalsIgnoreCase("suggest") || name.equalsIgnoreCase("copy_to_clipboard") ||
               name.equalsIgnoreCase("copy") || name.equalsIgnoreCase("change_page") ||
               name.equalsIgnoreCase("page") || name.equalsIgnoreCase("run_command");
    }
}

