package eu.avalanche7.paradigm.platform;

import eu.avalanche7.paradigm.platform.Interfaces.IComponent;
import net.minecraft.network.chat.*;
import net.minecraft.ChatFormatting;

import java.util.List;
import java.util.function.UnaryOperator;
import java.util.stream.Collectors;

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
    public IComponent setStyle(Object style) {
        if (style instanceof Style s) {
            component.setStyle(s);
        }
        return this;
    }

    @Override
    public Object getStyle() {
        return component.getStyle();
    }

    @Override
    public IComponent append(IComponent sibling) {
        if (sibling instanceof MinecraftComponent mc) {
            component.append(mc.getHandle());
        } else if (sibling != null) {
            component.append(Component.literal(sibling.getRawText()));
        }
        return this;
    }

    @Override
    public List<IComponent> getSiblings() {
        return component.getSiblings().stream()
                .map(c -> (IComponent) new MinecraftComponent(c.copy()))
                .collect(Collectors.toList());
    }

    @Override
    public IComponent copy() {
        return new MinecraftComponent(component.copy());
    }

    @Override
    public IComponent withStyle(String formattingCode) {
        if (formattingCode == null || formattingCode.isEmpty()) return copy();
        ChatFormatting fmt;
        try {
            fmt = ChatFormatting.getByName(formattingCode.replace("§", "").replace("&", ""));
        } catch (Throwable t) {
            fmt = null;
        }
        if (fmt == null) return copy();
        return new MinecraftComponent(component.copy().withStyle(fmt));
    }

    @Override
    public IComponent withStyle(Object style) {
        if (style instanceof Style s) {
            return new MinecraftComponent(component.copy().withStyle(s));
        }
        return copy();
    }

    @Override
    public IComponent withStyle(UnaryOperator<Object> styleUpdater) {
        if (styleUpdater == null) return copy();
        Object updated = styleUpdater.apply(component.getStyle());
        return withStyle(updated);
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
    public IComponent withFormatting(String formattingCode) {
        return withStyle(formattingCode);
    }

    @Override
    public IComponent withColor(String hexOrFormatCode) {
        if (hexOrFormatCode == null) return copy();
        if (hexOrFormatCode.startsWith("#")) return withColorHex(hexOrFormatCode);
        return withFormatting(hexOrFormatCode);
    }

    @Override
    public IComponent resetStyle() {
        return new MinecraftComponent(component.copy().setStyle(Style.EMPTY));
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
    public IComponent onClickCopyToClipboard(String text) {
        return new MinecraftComponent(component.copy().withStyle(s -> s.withClickEvent(new ClickEvent(ClickEvent.Action.COPY_TO_CLIPBOARD, text))));
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

    @Override
    public Object getOriginalText() {
        return component;
    }
}
