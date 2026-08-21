package eu.avalanche7.paradigm.modules.commands.shared;

import eu.avalanche7.paradigm.core.Services;
import eu.avalanche7.paradigm.platform.Interfaces.IComponent;

public final class ChatUi {

    public static final String MUTED = "94A3B8";
    public static final String DIM = "475569";
    public static final String BODY = "E5E7EB";
    public static final String TITLE = "F8FAFC";
    public static final String ACCENT = "A78BFA";
    public static final String INFO = "60A5FA";
    public static final String SUGGEST = "FBBF24";
    public static final String SUCCESS = "34D399";
    public static final String DANGER = "F87171";

    private ChatUi() {
    }

    public static IComponent row(Services services) {
        return services.getPlatformAdapter().createEmptyComponent();
    }

    public static IComponent space(Services services) {
        return services.getPlatformAdapter().createComponentFromLiteral(" ");
    }

    public static IComponent text(Services services, String value, String color) {
        return services.getPlatformAdapter()
                .createComponentFromLiteral(value != null ? value : "")
                .withColorHex(color);
    }

    public static IComponent header(Services services, String title) {
        return services.getPlatformAdapter().createEmptyComponent()
                .append(text(services, "---- ", DIM))
                .append(text(services, "[P] ", ACCENT).withFormatting("bold"))
                .append(text(services, title, TITLE).withFormatting("bold"));
    }

    public static IComponent button(Services services, String label, String command, boolean run,
                                    String hover, String color) {
        IComponent component = services.getPlatformAdapter()
                .createComponentFromLiteral(label)
                .withColorHex(color)
                .withFormatting("bold")
                .onHoverText(hover != null ? hover : command);
        return run ? component.onClickRunCommand(command) : component.onClickSuggestCommand(command);
    }

    public static IComponent button(Services services, String label, String command, boolean run, String hover) {
        return button(services, label, command, run, hover, run ? INFO : SUGGEST);
    }
}
