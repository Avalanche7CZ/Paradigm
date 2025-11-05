package eu.avalanche7.paradigm.platform;

import eu.avalanche7.paradigm.platform.Interfaces.IComponent;
import net.minecraft.network.chat.MutableComponent;
import net.minecraft.network.chat.Style;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.ClickEvent;
import net.minecraft.network.chat.HoverEvent;
import net.minecraft.network.chat.TextColor;
import net.minecraft.ChatFormatting;
import java.util.List;

public class MinecraftComponent implements IComponent {
    private final MutableComponent component;

    public MinecraftComponent(MutableComponent component) {
        this.component = component;
    }

    public MinecraftComponent(Component component) {
        this.component = component.copy();
    }

    public MutableComponent getHandle() {
        return component;
    }

    @Override
    public String getRawText() {
        return component.getString();
    }

    @Override
    public IComponent setStyle(Style style) {
        component.setStyle(style);
        return this;
    }

    @Override
    public Style getStyle() {
        return component.getStyle();
    }

    @Override
    public IComponent append(IComponent sibling) {
        if (sibling instanceof MinecraftComponent mc) {
            component.append(mc.getHandle());
        } else if (sibling != null) {
            System.err.println("[Paradigm] Warning: Tried to append non-MinecraftComponent: " + sibling.getClass().getName());
        }
        return this;
    }

    @Override
    public List<?> getSiblings() {
        return component.getSiblings();
    }

    @Override
    public IComponent copy() {
        return new MinecraftComponent(component.copy());
    }

    @Override
    public IComponent withStyle(ChatFormatting formatting) {
        return new MinecraftComponent(component.copy().withStyle(formatting));
    }

    @Override
    public IComponent withStyle(Style style) {
        return new MinecraftComponent(component.copy().withStyle(style));
    }

    @Override
    public IComponent withStyle(java.util.function.UnaryOperator<Style> styleUpdater) {
        return new MinecraftComponent(component.copy().withStyle(styleUpdater));
    }

    @Override
    public IComponent withColor(int rgb) {
        return new MinecraftComponent(component.copy().withStyle(s -> s.withColor(TextColor.fromRgb(rgb))));
    }

    @Override
    public IComponent withColorHex(String hex) {
        if (hex == null || hex.isEmpty()) return this.copy();
        String cleaned = hex.startsWith("#") ? hex.substring(1) : hex;
        try {
            int rgb = Integer.parseInt(cleaned, 16);
            return withColor(rgb);
        } catch (NumberFormatException e) {
            return this.copy();
        }
    }

    @Override
    public IComponent onClickRunCommand(String command) {
        return new MinecraftComponent(component.copy().withStyle(s -> s.withClickEvent(new ClickEvent(ClickEvent.Action.RUN_COMMAND, command))));
    }

    @Override
    public IComponent onClickSuggestCommand(String command) {
        return new MinecraftComponent(component.copy().withStyle(s -> s.withClickEvent(new ClickEvent(ClickEvent.Action.SUGGEST_COMMAND, command))));
    }

    @Override
    public IComponent onClickOpenUrl(String url) {
        String u = url == null ? "" : (url.startsWith("http://") || url.startsWith("https://") ? url : "https://" + url);
        return new MinecraftComponent(component.copy().withStyle(s -> s.withClickEvent(new ClickEvent(ClickEvent.Action.OPEN_URL, u))));
    }

    @Override
    public IComponent onHoverText(String text) {
        Component hover = Component.literal(text != null ? text : "");
        return new MinecraftComponent(component.copy().withStyle(s -> s.withHoverEvent(new HoverEvent(HoverEvent.Action.SHOW_TEXT, hover))));
    }

    @Override
    public IComponent onHoverComponent(IComponent comp) {
        Component hover;
        if (comp instanceof MinecraftComponent mc) {
            hover = mc.getHandle();
        } else {
            hover = Component.literal(comp != null ? comp.getRawText() : "");
        }
        return new MinecraftComponent(component.copy().withStyle(s -> s.withHoverEvent(new HoverEvent(HoverEvent.Action.SHOW_TEXT, hover))));
    }
}
