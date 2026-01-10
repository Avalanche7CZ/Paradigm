package eu.avalanche7.paradigm.utils.formatting.tags;

import eu.avalanche7.paradigm.platform.Interfaces.IComponent;
import eu.avalanche7.paradigm.platform.Interfaces.IPlatformAdapter;
import eu.avalanche7.paradigm.utils.formatting.FormattingContext;

public class DecorationTag implements Tag {
    public enum Decoration {
        BOLD("bold", "b"),
        ITALIC("italic", "i"),
        UNDERLINE("underline", "underlined"),
        STRIKETHROUGH("strikethrough", "st"),
        OBFUSCATED("obfuscated", "obf");

        private final String primaryName;
        private final String alias;

        Decoration(String primaryName, String alias) {
            this.primaryName = primaryName;
            this.alias = alias;
        }

        public boolean matches(String name) {
            return primaryName.equalsIgnoreCase(name) || alias.equalsIgnoreCase(name);
        }

        public String getToken() {
            return primaryName;
        }
    }

    private final Decoration decoration;
    private final IPlatformAdapter platformAdapter;

    public DecorationTag(IPlatformAdapter platformAdapter, Decoration decoration) {
        this.platformAdapter = platformAdapter;
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
        Object base = context.getCurrentStyle();
        IComponent tmp = platformAdapter.createEmptyComponent();
        if (base != null) tmp.setStyle(base);

        tmp = tmp.withFormatting(decoration.getToken());

        Object newStyle = tmp.getStyle();
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
