package eu.avalanche7.paradigm.utils.formatting.tags;

import eu.avalanche7.paradigm.platform.Interfaces.IPlatformAdapter;
import eu.avalanche7.paradigm.utils.formatting.FormattingContext;
import net.minecraft.util.Formatting;
import net.minecraft.text.Style;
import net.minecraft.text.TextColor;

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
        Integer colorRgb = parseColorToRgb(arguments);
        if (colorRgb != null) {
            Style newStyle = context.getCurrentStyle().withColor(TextColor.fromRgb(colorRgb));
            context.pushStyle(newStyle);
        }
    }

    @Override
    public void close(FormattingContext context) {
        context.popStyle();
    }

    @Override
    public boolean matchesTagName(String name) {
        if (name.equalsIgnoreCase("color") || name.equalsIgnoreCase("c")) {
            return true;
        }
        for (Formatting format : Formatting.values()) {
            if (format.isColor() && name.equalsIgnoreCase(format.getName())) {
                return true;
            }
        }
        return false;
    }

    private Integer parseColorToRgb(String color) {
        if (color == null || color.isEmpty()) {
            return null;
        }

        // Hex color
        if (color.startsWith("#")) {
            try {
                return Integer.parseInt(color.substring(1), 16);
            } catch (NumberFormatException e) {
                return null;
            }
        }

        // Named color
        for (Formatting format : Formatting.values()) {
            if (format.isColor() && color.equalsIgnoreCase(format.getName())) {
                Integer rgb = format.getColorValue();
                return rgb != null ? rgb : 0xFFFFFF;
            }
        }

        return null;
    }
}

