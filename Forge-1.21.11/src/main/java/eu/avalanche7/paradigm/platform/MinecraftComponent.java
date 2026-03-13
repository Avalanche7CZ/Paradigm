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
import java.util.function.UnaryOperator;

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
        }
        return this;
    }

    @Override
    public List<IComponent> getSiblings() {
        return component.getSiblings().stream()
                .map(c -> (IComponent) new MinecraftComponent(c.copy()))
                .toList();
    }

    @Override
    public IComponent copy() {
        return new MinecraftComponent(component.copy());
    }

    @Override
    public IComponent withStyle(String formattingCode) {
        if (formattingCode == null || formattingCode.isEmpty()) return copy();
        String t = formattingCode.trim();
        if (t.startsWith("#")) {
            return withColor(t);
        }
        ChatFormatting fmt = ChatFormatting.getByName(t);
        if (fmt == null && t.length() == 1) {
            fmt = ChatFormatting.getByCode(t.charAt(0));
        }
        if (fmt != null) {
            return new MinecraftComponent(component.copy().withStyle(fmt));
        }
        return copy();
    }

    @Override
    public IComponent withStyle(Object style) {
        if (style instanceof Style s) {
            return new MinecraftComponent(component.copy().setStyle(s));
        }
        return copy();
    }

    @Override
    public IComponent withStyle(UnaryOperator<Object> styleUpdater) {
        if (styleUpdater == null) return copy();
        Style base = component.getStyle();
        Object out = styleUpdater.apply(base);
        Style next = out instanceof Style s ? s : base;
        return new MinecraftComponent(component.copy().setStyle(next));
    }

    @Override
    public IComponent withColor(int rgb) {
        return new MinecraftComponent(component.copy().withStyle(s -> s.withColor(TextColor.fromRgb(rgb))));
    }

    @Override
    public IComponent withColorHex(String hex) {
        if (hex == null || hex.isEmpty()) return copy();
        String cleaned = hex.startsWith("#") ? hex.substring(1) : hex;
        try {
            int rgb = Integer.parseInt(cleaned, 16);
            return withColor(rgb);
        } catch (NumberFormatException e) {
            return copy();
        }
    }

    @Override
    public IComponent withFormatting(String formattingCode) {
        return withStyle(formattingCode);
    }

    @Override
    public IComponent withColor(String hexOrFormatCode) {
        if (hexOrFormatCode == null || hexOrFormatCode.isEmpty()) return copy();
        String t = hexOrFormatCode.trim();
        if (t.startsWith("#")) {
            return withColorHex(t.substring(1));
        }
        if (t.length() == 6) {
            try {
                return withColor(Integer.parseInt(t, 16));
            } catch (NumberFormatException ignored) {}
        }
        ChatFormatting fmt = ChatFormatting.getByName(t);
        if (fmt != null && fmt.isColor() && fmt.getColor() != null) {
            return withColor(fmt.getColor());
        }
        return copy();
    }

    @Override
    public IComponent resetStyle() {
        return new MinecraftComponent(component.copy().setStyle(Style.EMPTY));
    }

    @Override
    public IComponent onClickRunCommand(String command) {
        ClickEvent clickEvent = createClickEvent("RunCommand", command != null ? command : "");
        return new MinecraftComponent(component.copy().withStyle(s -> clickEvent != null ? s.withClickEvent(clickEvent) : s));
    }

    @Override
    public IComponent onClickSuggestCommand(String command) {
        ClickEvent clickEvent = createClickEvent("SuggestCommand", command != null ? command : "");
        return new MinecraftComponent(component.copy().withStyle(s -> clickEvent != null ? s.withClickEvent(clickEvent) : s));
    }

    @Override
    public IComponent onClickOpenUrl(String url) {
        String u = url == null ? "" : (url.startsWith("http://") || url.startsWith("https://") ? url : "https://" + url);
        ClickEvent clickEvent = createClickEvent("OpenUrl", u);
        return new MinecraftComponent(component.copy().withStyle(s -> clickEvent != null ? s.withClickEvent(clickEvent) : s));
    }

    @Override
    public IComponent onClickCopyToClipboard(String text) {
        String val = text != null ? text : "";
        ClickEvent clickEvent = createClickEvent("CopyToClipboard", val);
        return new MinecraftComponent(component.copy().withStyle(s -> clickEvent != null ? s.withClickEvent(clickEvent) : s));
    }

    @Override
    public IComponent onHoverText(String text) {
        Component hover = Component.literal(text != null ? text : "");
        HoverEvent hoverEvent = createShowTextHoverEvent(hover);
        return new MinecraftComponent(component.copy().withStyle(s -> hoverEvent != null ? s.withHoverEvent(hoverEvent) : s));
    }

    @Override
    public IComponent onHoverComponent(IComponent comp) {
        Component hover;
        if (comp instanceof MinecraftComponent mc) {
            hover = mc.getHandle();
        } else {
            hover = Component.literal(comp != null ? comp.getRawText() : "");
        }
        HoverEvent hoverEvent = createShowTextHoverEvent(hover);
        return new MinecraftComponent(component.copy().withStyle(s -> hoverEvent != null ? s.withHoverEvent(hoverEvent) : s));
    }

    private static ClickEvent createClickEvent(String simpleName, String value) {
        try {
            for (Class<?> nested : ClickEvent.class.getDeclaredClasses()) {
                if (!nested.getSimpleName().equals(simpleName)) continue;
                try {
                    var ctor = nested.getDeclaredConstructor(String.class);
                    ctor.setAccessible(true);
                    Object instance = ctor.newInstance(value);
                    if (instance instanceof ClickEvent clickEvent) {
                        return clickEvent;
                    }
                } catch (ReflectiveOperationException ignored) {
                }
                try {
                    var ctor = nested.getDeclaredConstructor(java.net.URI.class);
                    ctor.setAccessible(true);
                    Object instance = ctor.newInstance(java.net.URI.create(value));
                    if (instance instanceof ClickEvent clickEvent) {
                        return clickEvent;
                    }
                } catch (ReflectiveOperationException ignored) {
                }
            }
        } catch (Throwable ignored) {
        }
        return null;
    }

    private static HoverEvent createShowTextHoverEvent(Component hover) {
        try {
            for (Class<?> nested : HoverEvent.class.getDeclaredClasses()) {
                if (!nested.getSimpleName().equals("ShowText")) continue;
                var ctor = nested.getDeclaredConstructor(Component.class);
                ctor.setAccessible(true);
                Object instance = ctor.newInstance(hover);
                if (instance instanceof HoverEvent hoverEvent) {
                    return hoverEvent;
                }
            }
        } catch (Throwable ignored) {
        }
        return null;
    }

    @Override
    public Object getOriginalText() {
        return component;
    }
}
