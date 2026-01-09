package eu.avalanche7.paradigm.utils.formatting.tags;

import eu.avalanche7.paradigm.platform.Interfaces.IComponent;
import eu.avalanche7.paradigm.platform.Interfaces.IPlatformAdapter;
import eu.avalanche7.paradigm.utils.formatting.FormattingContext;

public class ColorTag implements Tag {
    private final IPlatformAdapter platformAdapter;

    public ColorTag(IPlatformAdapter platformAdapter) {
        this.platformAdapter = platformAdapter;
    }

    @Override
    public String getName() {
        return "color";
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
        if (arguments == null || arguments.isEmpty()) return;
        String arg = arguments.trim();
        Object base = context.getCurrentStyle();
        IComponent tmp = platformAdapter.createEmptyComponent();
        if (base != null) tmp.setStyle(base);

        if (arg.startsWith("#")) {
            tmp = tmp.withColor(arg);
        } else {
            // named color / legacy token
            tmp = tmp.withFormatting(arg);
        }

        Object newStyle = tmp.getStyle();
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
        return name.equalsIgnoreCase("color") || name.equalsIgnoreCase("c");
    }
}
