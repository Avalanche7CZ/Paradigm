package eu.avalanche7.paradigm.utils.formatting.tags;

import eu.avalanche7.paradigm.platform.Interfaces.IPlatformAdapter;
import eu.avalanche7.paradigm.utils.formatting.FormattingContext;
import net.minecraft.ChatFormatting;
import net.minecraft.network.chat.Style;
import net.minecraft.network.chat.TextColor;

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
        Integer colorRgb = platformAdapter.parseColorToRgb(arguments);
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
        for (ChatFormatting format : ChatFormatting.values()) {
            if (format.isColor() && format.getName() != null && name.equalsIgnoreCase(format.getName())) {
                return true;
            }
        }
        return false;
    }
}

