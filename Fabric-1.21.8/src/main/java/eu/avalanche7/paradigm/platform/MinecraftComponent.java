package eu.avalanche7.paradigm.platform;

import eu.avalanche7.paradigm.platform.Interfaces.IComponent;
import net.minecraft.text.ClickEvent;
import net.minecraft.text.HoverEvent;
import net.minecraft.text.MutableText;
import net.minecraft.text.Style;
import net.minecraft.text.Text;
import net.minecraft.text.TextColor;
import net.minecraft.util.Formatting;

import java.util.List;
import java.util.function.UnaryOperator;

public class MinecraftComponent implements IComponent {
    private final MutableText component;

    public MinecraftComponent(MutableText component) {
        this.component = component;
    }

    public MinecraftComponent(Text component) {
        this.component = component.copy();
    }

    public MutableText getHandle() {
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
        }
        return this;
    }

    @Override
    public List<IComponent> getSiblings() {
        return component.getSiblings().stream()
                .filter(c -> c instanceof MutableText)
                .map(c -> new MinecraftComponent((MutableText) c))
                .map(c -> (IComponent) c)
                .toList();
    }

    @Override
    public IComponent copy() {
        return new MinecraftComponent(component.copy());
    }

    @Override
    public IComponent withStyle(Formatting formatting) {
        return new MinecraftComponent(component.copy().styled(s -> s.withFormatting(formatting)));
    }

    @Override
    public IComponent withStyle(Style style) {
        return new MinecraftComponent(component.copy().styled(s -> style));
    }

    @Override
    public IComponent withStyle(UnaryOperator<Style> styleUpdater) {
        return new MinecraftComponent(component.copy().styled(styleUpdater));
    }

    @Override
    public IComponent withColor(int rgb) {
        return new MinecraftComponent(component.copy().styled(s -> s.withColor(TextColor.fromRgb(rgb))));
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
    public IComponent withFormatting(Formatting formatting) {
        if (formatting == null) return copy();
        if (formatting.isColor()) {
            TextColor color = TextColor.fromFormatting(formatting);
            if (color != null) {
                return new MinecraftComponent(component.copy().styled(s -> s.withColor(color)));
            }
        }
        return new MinecraftComponent(component.copy().styled(s -> s.withFormatting(formatting)));
    }

    @Override
    public IComponent withColor(String hexOrFormatCode) {
        if (hexOrFormatCode == null || hexOrFormatCode.isEmpty()) return copy();
        if (hexOrFormatCode.startsWith("#")) {
            return withColorHex(hexOrFormatCode.substring(1));
        }
        try {
            int rgb = Integer.parseInt(hexOrFormatCode, 16);
            return withColor(rgb);
        } catch (NumberFormatException e) {
            Formatting format = Formatting.byName(hexOrFormatCode);
            if (format != null && format.isColor()) {
                return withFormatting(format);
            }
            return copy();
        }
    }

    @Override
    public IComponent resetStyle() {
        return new MinecraftComponent(component.copy().styled(s -> Style.EMPTY));
    }

    @Override
    public IComponent onClickRunCommand(String command) {
        ClickEvent ce = new ClickEvent(ClickEvent.Action.RUN_COMMAND, command != null ? command : "");
        MutableText styled = deepStyledCopy(component, s -> s.withClickEvent(ce));
        return new MinecraftComponent(styled);
    }

    @Override
    public IComponent onClickSuggestCommand(String command) {
        ClickEvent ce = new ClickEvent(ClickEvent.Action.SUGGEST_COMMAND, command != null ? command : "");
        MutableText styled = deepStyledCopy(component, s -> s.withClickEvent(ce));
        return new MinecraftComponent(styled);
    }

    @Override
    public IComponent onClickOpenUrl(String url) {
        String u = url == null ? "" : (url.startsWith("http://") || url.startsWith("https://") ? url : "https://" + url);
        ClickEvent ce = new ClickEvent(ClickEvent.Action.OPEN_URL, u);
        MutableText styled = deepStyledCopy(component, s -> s.withClickEvent(ce));
        return new MinecraftComponent(styled);
    }

    @Override
    public IComponent onClickCopyToClipboard(String text) {
        ClickEvent ce = new ClickEvent(ClickEvent.Action.COPY_TO_CLIPBOARD, text != null ? text : "");
        MutableText styled = deepStyledCopy(component, s -> s.withClickEvent(ce));
        return new MinecraftComponent(styled);
    }

    @Override
    public IComponent onHoverText(String text) {
        Text hover = Text.literal(text != null ? text : "");
        HoverEvent he = new HoverEvent(HoverEvent.Action.SHOW_TEXT, hover);
        MutableText styled = deepStyledCopy(component, s -> s.withHoverEvent(he));
        return new MinecraftComponent(styled);
    }

    @Override
    public IComponent onHoverComponent(IComponent comp) {
        Text hover;
        if (comp instanceof MinecraftComponent mc) {
            hover = mc.getHandle();
        } else {
            hover = Text.literal(comp != null ? comp.getRawText() : "");
        }
        HoverEvent he = new HoverEvent(HoverEvent.Action.SHOW_TEXT, hover);
        MutableText styled = deepStyledCopy(component, s -> s.withHoverEvent(he));
        return new MinecraftComponent(styled);
    }

    @Override
    public Text getOriginalText() {
        if (component.getString().isEmpty()) {
            List<Text> siblings = component.getSiblings();
            if (siblings.size() == 1) {
                Text only = siblings.get(0);
                return only;
            }
        }
        return component;
    }

    private static MutableText deepStyledCopy(Text source, java.util.function.UnaryOperator<Style> styler) {
        MutableText rootCopy = source.copy();
        Style updated = styler.apply(rootCopy.getStyle());
        rootCopy.setStyle(updated);
        List<Text> originalChildren = source.getSiblings();
        if (!originalChildren.isEmpty()) {
            rootCopy.getSiblings().clear();
            for (Text child : originalChildren) {
                MutableText styledChild = deepStyledCopy(child, styler);
                rootCopy.append(styledChild);
            }
        }
        return rootCopy;
    }
}
