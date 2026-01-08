package eu.avalanche7.paradigm.utils.formatting.tags;

import eu.avalanche7.paradigm.utils.formatting.FormattingContext;
import net.minecraft.util.Formatting;
import net.minecraft.text.Style;

public class DecorationTag implements Tag {
    public enum Decoration {
        BOLD("bold", "b", Formatting.BOLD),
        ITALIC("italic", "i", Formatting.ITALIC),
        UNDERLINE("underline", "underlined", Formatting.UNDERLINE),
        STRIKETHROUGH("strikethrough", "st", Formatting.STRIKETHROUGH),
        OBFUSCATED("obfuscated", "obf", Formatting.OBFUSCATED);

        private final String primaryName;
        private final String alias;
        private final Formatting format;

        Decoration(String primaryName, String alias, Formatting format) {
            this.primaryName = primaryName;
            this.alias = alias;
            this.format = format;
        }

        public boolean matches(String name) {
            return primaryName.equalsIgnoreCase(name) || alias.equalsIgnoreCase(name);
        }

        public Formatting getFormat() {
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
        Style newStyle = context.getCurrentStyle().withFormatting(decoration.format);
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

