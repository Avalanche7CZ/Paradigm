package eu.avalanche7.paradigm.utils.formatting.tags;

import eu.avalanche7.paradigm.platform.Interfaces.IComponent;
import eu.avalanche7.paradigm.platform.Interfaces.IPlatformAdapter;
import eu.avalanche7.paradigm.utils.formatting.FormattingContext;
import eu.avalanche7.paradigm.utils.formatting.FormattingParser;

public class HoverTag implements Tag {
    private final IPlatformAdapter platformAdapter;

    public HoverTag(IPlatformAdapter platformAdapter) {
        this.platformAdapter = platformAdapter;
    }

    @Override
    public String getName() {
        return "hover";
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
        if (arguments == null || arguments.isEmpty()) {
            return;
        }

        String hoverText = arguments;
        if (hoverText.startsWith("'") && hoverText.endsWith("'")) {
            hoverText = hoverText.substring(1, hoverText.length() - 1);
        } else if (hoverText.startsWith("\"") && hoverText.endsWith("\"")) {
            hoverText = hoverText.substring(1, hoverText.length() - 1);
        }

        FormattingParser parser = context.getParser();
        Object hoverComponent;

        if (parser != null) {
            IComponent parsed = parser.parse(hoverText, context.getPlayer());
            hoverComponent = parsed.getOriginalText();
        } else {
            hoverComponent = hoverText;
        }

        Object newStyle = platformAdapter.createStyleWithHoverEvent(context.getCurrentStyle(), hoverComponent);
        context.pushStyle(newStyle);
        context.getCurrentComponent().setStyle(newStyle);
    }

    @Override
    public void close(FormattingContext context) {
        context.popStyle();
        context.getCurrentComponent().setStyle(context.getCurrentStyle());
    }

    @Override
    public boolean matchesTagName(String name) {
        return name.equalsIgnoreCase("hover") || name.equalsIgnoreCase("show_text");
    }
}
