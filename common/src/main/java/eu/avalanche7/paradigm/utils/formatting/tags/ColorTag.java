package eu.avalanche7.paradigm.utils.formatting.tags;

import java.util.Locale;
import java.util.Set;

import eu.avalanche7.paradigm.platform.Interfaces.IComponent;
import eu.avalanche7.paradigm.platform.Interfaces.IPlatformAdapter;
import eu.avalanche7.paradigm.utils.formatting.FormattingContext;

public class ColorTag implements Tag {
    private static final Set<String> COLORS = Set.of(
            "black", "dark_blue", "dark_green", "dark_aqua", "dark_red", "dark_purple", "gold", "gray",
            "dark_gray", "blue", "green", "aqua", "red", "light_purple", "yellow", "white");
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
            tmp = tmp.withFormatting(arg);
        }

        Object newStyle = tmp.getStyle();
        String color = arg.toLowerCase(Locale.ROOT);
        if (COLORS.contains(color) || color.matches("#[0-9a-f]{6}")) {
            context.pushColorStyle(newStyle, color);
        } else {
            context.pushStyle(newStyle);
        }
    }

    @Override
    public void close(FormattingContext context) {
        context.popStyle();
    }

    @Override
    public boolean matchesTagName(String name) {
        return name.equalsIgnoreCase("color") || name.equalsIgnoreCase("c");
    }
}
