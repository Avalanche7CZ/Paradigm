package eu.avalanche7.paradigm.utils.formatting.tags;

import eu.avalanche7.paradigm.utils.formatting.FormattingContext;
import net.minecraft.ChatFormatting;
import net.minecraft.network.chat.Style;

public class DecorationTag implements Tag {
    public enum Decoration {
        BOLD("bold", "b", ChatFormatting.BOLD),
        ITALIC("italic", "i", ChatFormatting.ITALIC),
        UNDERLINE("underline", "underlined", ChatFormatting.UNDERLINE),
        STRIKETHROUGH("strikethrough", "st", ChatFormatting.STRIKETHROUGH),
        OBFUSCATED("obfuscated", "obf", ChatFormatting.OBFUSCATED);

        private final String primaryName;
        private final String alias;
        private final ChatFormatting format;

        Decoration(String primaryName, String alias, ChatFormatting format) {
            this.primaryName = primaryName;
            this.alias = alias;
            this.format = format;
        }

        public boolean matches(String name) {
            return primaryName.equalsIgnoreCase(name) || alias.equalsIgnoreCase(name);
        }

        public ChatFormatting getFormat() {
            return format;
        }
    }

    private final Decoration decoration;

    public DecorationTag(Decoration decoration) {
        this.decoration = decoration;
    }

    @Override
    public String getName() {
        return decoration.primaryName;
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
        Style newStyle = context.getCurrentStyle().applyFormat(decoration.format);
        context.pushStyle(newStyle);
    }

    @Override
    public void close(FormattingContext context) {
        context.popStyle();
    }

    @Override
    public boolean matchesTagName(String name) {
        return decoration.matches(name);
    }
}

