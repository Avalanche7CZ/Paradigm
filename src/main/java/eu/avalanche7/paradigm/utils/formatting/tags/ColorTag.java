package eu.avalanche7.paradigm.utils.formatting.tags;

import eu.avalanche7.paradigm.utils.formatting.FormattingContext;
import net.minecraft.ChatFormatting;
import net.minecraft.network.chat.Style;
import net.minecraft.network.chat.TextColor;

public class ColorTag implements Tag {
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
        TextColor color = parseColor(arguments);
        if (color != null) {
            Style newStyle = context.getCurrentStyle().withColor(color);
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
        for (ChatFormatting format : ChatFormatting.values()) {
            if (format.isColor() && format.getName() != null && name.equalsIgnoreCase(format.getName())) {
                return true;
            }
        }
        return false;
    }

    private TextColor parseColor(String colorArg) {
        if (colorArg == null || colorArg.isEmpty()) {
            return null;
        }

        if (colorArg.startsWith("#")) {
            try {
                int rgb = Integer.parseInt(colorArg.substring(1), 16);
                return TextColor.fromRgb(rgb);
            } catch (NumberFormatException e) {
                return null;
            }
        }

        for (ChatFormatting format : ChatFormatting.values()) {
            if (format.isColor() && format.getName() != null && format.getName().equalsIgnoreCase(colorArg)) {
                return TextColor.fromLegacyFormat(format);
            }
        }

        return null;
    }
}

