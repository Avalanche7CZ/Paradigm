package eu.avalanche7.paradigm.platform;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.function.Consumer;
import java.util.function.UnaryOperator;

import net.minecraft.event.ClickEvent;
import net.minecraft.event.HoverEvent;
import net.minecraft.util.ChatComponentText;
import net.minecraft.util.ChatStyle;
import net.minecraft.util.EnumChatFormatting;
import net.minecraft.util.IChatComponent;

import eu.avalanche7.paradigm.platform.Interfaces.IComponent;

public final class MinecraftComponent implements IComponent {
    private static final String COLOR_CODES = "0123456789abcdef";
    private static final int[] PALETTE = {0x000000, 0x0000AA, 0x00AA00, 0x00AAAA, 0xAA0000,
            0xAA00AA, 0xFFAA00, 0xAAAAAA, 0x555555, 0x5555FF, 0x55FF55, 0x55FFFF, 0xFF5555,
            0xFF55FF, 0xFFFF55, 0xFFFFFF};

    private final IChatComponent component;

    public MinecraftComponent(String text) {
        this(new ChatComponentText(text != null ? text : ""), false);
    }

    public MinecraftComponent(IChatComponent component) {
        this(component, true);
    }

    private MinecraftComponent(IChatComponent component, boolean copy) {
        this.component = copy ? deepCopy(component) : component;
    }

    public IChatComponent getHandle() {
        return component;
    }

    @Override
    public String getRawText() {
        return component.getUnformattedText();
    }

    @Override
    public IComponent setStyle(Object style) {
        if (!(style instanceof ChatStyle)) {
            throw new IllegalArgumentException("Expected a Minecraft ChatStyle");
        }
        component.setChatStyle(deepCopyStyle((ChatStyle) style));
        return this;
    }

    @Override
    public Object getStyle() {
        return deepCopyStyle(component.getChatStyle());
    }

    @Override
    public IComponent append(IComponent sibling) {
        if (!(sibling instanceof MinecraftComponent)) {
            throw new IllegalArgumentException("Expected a MinecraftComponent sibling");
        }
        component.appendSibling(deepCopy(((MinecraftComponent) sibling).component));
        return this;
    }

    @Override
    public List<IComponent> getSiblings() {
        List<IComponent> siblings = new ArrayList<>();
        for (Object sibling : component.getSiblings()) {
            siblings.add(new MinecraftComponent((IChatComponent) sibling));
        }
        return siblings;
    }

    @Override
    public IComponent copy() {
        return new MinecraftComponent(component);
    }

    @Override
    public IComponent withStyle(String formattingCode) {
        if (formattingCode == null || formattingCode.isBlank()) {
            return copy();
        }
        String value = formattingCode.trim();
        if (value.startsWith("#")) {
            return withColorHex(value);
        }
        String normalized = value.replace("§", "").replace("&", "").toLowerCase(Locale.ROOT);
        EnumChatFormatting formatting = null;
        if (normalized.length() == 1) {
            int color = COLOR_CODES.indexOf(normalized.charAt(0));
            if (color >= 0) {
                formatting = EnumChatFormatting.values()[color];
            } else {
                formatting = switch (normalized.charAt(0)) {
                    case 'k' -> EnumChatFormatting.OBFUSCATED;
                    case 'l' -> EnumChatFormatting.BOLD;
                    case 'm' -> EnumChatFormatting.STRIKETHROUGH;
                    case 'n' -> EnumChatFormatting.UNDERLINE;
                    case 'o' -> EnumChatFormatting.ITALIC;
                    case 'r' -> EnumChatFormatting.RESET;
                    default -> null;
                };
            }
        } else {
            formatting = EnumChatFormatting.getValueByName(normalized);
            if (formatting == null && "underlined".equals(normalized)) {
                formatting = EnumChatFormatting.UNDERLINE;
            }
        }
        if (formatting == null) {
            return copy();
        }
        if (formatting == EnumChatFormatting.RESET) {
            return resetStyle();
        }
        EnumChatFormatting selected = formatting;
        return copyWithStyle(style -> {
            if (selected.isColor()) {
                style.setColor(selected);
            } else {
                switch (selected) {
                    case BOLD -> style.setBold(true);
                    case ITALIC -> style.setItalic(true);
                    case UNDERLINE -> style.setUnderlined(true);
                    case STRIKETHROUGH -> style.setStrikethrough(true);
                    case OBFUSCATED -> style.setObfuscated(true);
                    default -> {
                    }
                }
            }
        });
    }

    @Override
    public IComponent withStyle(Object style) {
        MinecraftComponent copy = (MinecraftComponent) copy();
        if (style instanceof ChatStyle) {
            copy.setStyle(style);
        }
        return copy;
    }

    @Override
    public IComponent withStyle(UnaryOperator<Object> styleUpdater) {
        if (styleUpdater == null) {
            return copy();
        }
        MinecraftComponent copy = (MinecraftComponent) copy();
        Object updated = styleUpdater.apply(copy.getStyle());
        if (updated instanceof ChatStyle) {
            copy.setStyle(updated);
        }
        return copy;
    }

    @Override
    public IComponent withItalic(boolean italic) {
        return copyWithStyle(style -> style.setItalic(italic));
    }

    @Override
    public IComponent withColor(int rgb) {
        int best = 0;
        long distance = Long.MAX_VALUE;
        for (int i = 0; i < PALETTE.length; i++) {
            int color = PALETTE[i];
            int dr = ((rgb >> 16) & 255) - ((color >> 16) & 255);
            int dg = ((rgb >> 8) & 255) - ((color >> 8) & 255);
            int db = (rgb & 255) - (color & 255);
            long next = (long) dr * dr + (long) dg * dg + (long) db * db;
            if (next < distance) {
                distance = next;
                best = i;
            }
        }
        EnumChatFormatting color = EnumChatFormatting.values()[best];
        return copyWithStyle(style -> style.setColor(color));
    }

    @Override
    public IComponent withColorHex(String hex) {
        if (hex == null || hex.isBlank()) {
            return copy();
        }
        String value = hex.startsWith("#") ? hex.substring(1) : hex;
        if (!value.matches("[0-9a-fA-F]{6}")) {
            return copy();
        }
        return withColor(Integer.parseInt(value, 16));
    }

    @Override
    public IComponent withFormatting(String formattingCode) {
        return withStyle(formattingCode);
    }

    @Override
    public IComponent withColor(String value) {
        if (value == null || value.isBlank()) {
            return copy();
        }
        if (value.startsWith("#")) {
            return withColorHex(value);
        }
        String normalized = value.replace("§", "").replace("&", "").toLowerCase(Locale.ROOT);
        EnumChatFormatting color =
                normalized.length() == 1 && COLOR_CODES.indexOf(normalized.charAt(0)) >= 0
                ? EnumChatFormatting.values()[COLOR_CODES.indexOf(normalized.charAt(0))]
                : EnumChatFormatting.getValueByName(normalized);
        return color != null && color.isColor() ? copyWithStyle(style -> style.setColor(color))
                                                : copy();
    }

    @Override
    public IComponent resetStyle() {
        MinecraftComponent copy = (MinecraftComponent) copy();
        copy.component.setChatStyle(new ChatStyle());
        return copy;
    }

    @Override
    public IComponent onClickRunCommand(String command) {
        return copyWithStyle(style
                -> style.setChatClickEvent(new ClickEvent(ClickEvent.Action.RUN_COMMAND, command)));
    }

    @Override
    public IComponent onClickSuggestCommand(String command) {
        return copyWithStyle(style
                -> style.setChatClickEvent(
                        new ClickEvent(ClickEvent.Action.SUGGEST_COMMAND, command)));
    }

    @Override
    public IComponent onClickOpenUrl(String url) {
        return copyWithStyle(
                style -> style.setChatClickEvent(new ClickEvent(ClickEvent.Action.OPEN_URL, url)));
    }

    @Override
    public IComponent onClickCopyToClipboard(String text) {
        throw new UnsupportedOperationException(
                "Minecraft 1.7.10 has no copy-to-clipboard click action");
    }

    @Override
    public IComponent onHoverText(String text) {
        return onHoverComponent(new MinecraftComponent(text));
    }

    @Override
    public IComponent onHoverComponent(IComponent value) {
        IChatComponent hover = value instanceof MinecraftComponent
                ? deepCopy(((MinecraftComponent) value).component)
                : new ChatComponentText(value != null ? value.getRawText() : "");
        return copyWithStyle(style
                -> style.setChatHoverEvent(new HoverEvent(HoverEvent.Action.SHOW_TEXT, hover)));
    }

    @Override
    public Object getOriginalText() {
        return component;
    }

    private IComponent copyWithStyle(Consumer<ChatStyle> update) {
        MinecraftComponent copy = (MinecraftComponent) copy();
        update.accept(copy.component.getChatStyle());
        return copy;
    }

    private static IChatComponent deepCopy(IChatComponent original) {
        IChatComponent result = original.createCopy();
        result.setChatStyle(deepCopyStyle(original.getChatStyle()));
        deepCopySiblings(original, result);
        return result;
    }

    private static void deepCopySiblings(IChatComponent original, IChatComponent copy) {
        List originalSiblings = original.getSiblings();
        List copiedSiblings = copy.getSiblings();
        for (int i = 0; i < originalSiblings.size(); i++) {
            IChatComponent sourceSibling = (IChatComponent) originalSiblings.get(i);
            IChatComponent copiedSibling = (IChatComponent) copiedSiblings.get(i);
            ChatStyle style = sourceSibling.getChatStyle().createShallowCopy();
            style.setParentStyle(copy.getChatStyle());
            HoverEvent hover = sourceSibling.getChatStyle().getChatHoverEvent();
            if (hover != null
                    && hover != original.getChatStyle().getChatHoverEvent()) {
                style.setChatHoverEvent(new HoverEvent(hover.getAction(), deepCopy(hover.getValue())));
            }
            copiedSibling.setChatStyle(style);
            deepCopySiblings(sourceSibling, copiedSibling);
        }
    }

    private static ChatStyle deepCopyStyle(ChatStyle original) {
        ChatStyle style = original.createDeepCopy();
        HoverEvent hover = original.getChatHoverEvent();
        if (hover != null) {
            style.setChatHoverEvent(new HoverEvent(hover.getAction(), deepCopy(hover.getValue())));
        }
        return style;
    }
}
