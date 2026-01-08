package eu.avalanche7.paradigm.utils.formatting.tags;

import eu.avalanche7.paradigm.platform.Interfaces.IPlatformAdapter;
import eu.avalanche7.paradigm.utils.formatting.FormattingContext;
import net.minecraft.text.ClickEvent;
import net.minecraft.text.Style;

public class ClickTag implements Tag {
    private final IPlatformAdapter platformAdapter;

    public ClickTag(IPlatformAdapter platformAdapter) {
        this.platformAdapter = platformAdapter;
    }

    public enum ClickAction {
        OPEN_URL("open_url", ClickEvent.Action.OPEN_URL),
        RUN_COMMAND("run_cmd", ClickEvent.Action.RUN_COMMAND),
        RUN_COMMAND_ALT("run_command", ClickEvent.Action.RUN_COMMAND),
        EXECUTE("execute", ClickEvent.Action.RUN_COMMAND),
        EXECUTE_ALT("exec", ClickEvent.Action.RUN_COMMAND),
        SUGGEST_COMMAND("suggest_command", ClickEvent.Action.SUGGEST_COMMAND),
        SUGGEST_COMMAND_ALT("cmd", ClickEvent.Action.SUGGEST_COMMAND),
        SUGGEST_COMMAND_ALT2("suggest", ClickEvent.Action.SUGGEST_COMMAND),
        COPY_TO_CLIPBOARD("copy_to_clipboard", ClickEvent.Action.COPY_TO_CLIPBOARD),
        COPY_ALT("copy", ClickEvent.Action.COPY_TO_CLIPBOARD),
        CHANGE_PAGE("change_page", ClickEvent.Action.CHANGE_PAGE),
        PAGE_ALT("page", ClickEvent.Action.CHANGE_PAGE);

        private final String name;
        private final ClickEvent.Action action;

        ClickAction(String name, ClickEvent.Action action) {
            this.name = name;
            this.action = action;
        }

        public static ClickAction fromString(String str) {
            for (ClickAction action : ClickAction.values()) {
                if (action.name.equalsIgnoreCase(str)) {
                    return action;
                }
            }
            return null;
        }

        public ClickEvent.Action getAction() {
            return action;
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

        ClickEvent clickEvent = new ClickEvent(action.getAction(), value);
        Style newStyle = context.getCurrentStyle().withClickEvent(clickEvent);
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

