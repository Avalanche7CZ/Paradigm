package eu.avalanche7.paradigm.platform;

import eu.avalanche7.paradigm.platform.Interfaces.IComponent;
import net.minecraft.ChatFormatting;
import net.minecraft.network.chat.*;

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
        return component.getSiblings().stream().map(c -> (IComponent) new MinecraftComponent(c.copy())).toList();
    }

    @Override
    public IComponent copy() {
        return new MinecraftComponent(component.copy());
    }

    @Override
    public IComponent withStyle(String formattingCode) {
        if (formattingCode == null || formattingCode.isEmpty()) return copy();
        String t = formattingCode.trim();
        if (t.startsWith("#")) return withColor(t);

        ChatFormatting fmt = ChatFormatting.getByName(t);
        if (fmt == null && t.length() == 1) fmt = ChatFormatting.getByCode(t.charAt(0));
        if (fmt != null) return new MinecraftComponent(component.copy().withStyle(fmt));
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
            return withColor(Integer.parseInt(cleaned, 16));
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
        if (hexOrFormatCode == null) return copy();
        if (hexOrFormatCode.startsWith("#")) return withColorHex(hexOrFormatCode);
        ChatFormatting fmt = ChatFormatting.getByName(hexOrFormatCode);
        if (fmt != null && fmt.getColor() != null) {
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
        return new MinecraftComponent(component.copy().withStyle(s -> s.withClickEvent(new ClickEvent(ClickEvent.Action.RUN_COMMAND, command))));
    }

    @Override
    public IComponent onClickSuggestCommand(String command) {
        return new MinecraftComponent(component.copy().withStyle(s -> s.withClickEvent(new ClickEvent(ClickEvent.Action.SUGGEST_COMMAND, command))));
    }

    @Override
    public IComponent onClickOpenUrl(String url) {
        return new MinecraftComponent(component.copy().withStyle(s -> s.withClickEvent(new ClickEvent(ClickEvent.Action.OPEN_URL, url))));
    }

    @Override
    public IComponent onClickCopyToClipboard(String text) {
        // Not available in 1.19.2 ClickEvent actions; fallback to suggest.
        return onClickSuggestCommand(text);
    }

    @Override
    public IComponent onHoverText(String text) {
        return new MinecraftComponent(component.copy().withStyle(s -> s.withHoverEvent(new HoverEvent(HoverEvent.Action.SHOW_TEXT, Component.literal(text != null ? text : "")))));
    }

    @Override
    public IComponent onHoverComponent(IComponent component) {
        Object orig = component != null ? component.getOriginalText() : null;
        Component hover = orig instanceof Component c ? c : Component.literal(component != null ? component.getRawText() : "");
        return new MinecraftComponent(this.component.copy().withStyle(s -> s.withHoverEvent(new HoverEvent(HoverEvent.Action.SHOW_TEXT, hover))));
    }

    @Override
    public Object getOriginalText() {
        return component;
    }
}
