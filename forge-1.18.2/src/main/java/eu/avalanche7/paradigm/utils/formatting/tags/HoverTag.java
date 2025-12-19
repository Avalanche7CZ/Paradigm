package eu.avalanche7.paradigm.utils.formatting.tags;

import eu.avalanche7.paradigm.platform.Interfaces.IComponent;
import eu.avalanche7.paradigm.platform.Interfaces.IPlatformAdapter;
import eu.avalanche7.paradigm.platform.MinecraftComponent;
import eu.avalanche7.paradigm.utils.formatting.FormattingContext;
import eu.avalanche7.paradigm.utils.formatting.FormattingParser;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.HoverEvent;
import net.minecraft.network.chat.TextComponent;
import net.minecraft.network.chat.Style;

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
        Component hoverComponent;

        if (parser != null) {
            IComponent parsed = parser.parse(hoverText, context.getPlayer());
            if (parsed instanceof MinecraftComponent mc) {
                hoverComponent = mc.getHandle();
            } else {
                hoverComponent = new TextComponent(hoverText);
            }
        } else {
            hoverComponent = new TextComponent(hoverText);
        }

        HoverEvent hoverEvent = new HoverEvent(HoverEvent.Action.SHOW_TEXT, hoverComponent);
        Style newStyle = context.getCurrentStyle().withHoverEvent(hoverEvent);
        context.pushStyle(newStyle);
    }

    @Override
    public void close(FormattingContext context) {
        context.popStyle();
    }

    @Override
    public boolean matchesTagName(String name) {
        return name.equalsIgnoreCase("hover") || name.equalsIgnoreCase("show_text");
    }
}

