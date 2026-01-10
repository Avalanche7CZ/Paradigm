package eu.avalanche7.paradigm.utils.formatting.tags;

import eu.avalanche7.paradigm.utils.formatting.FormattingContext;
import net.minecraft.network.chat.Style;

public class ResetTag implements Tag {
    @Override
    public String getName() {
        return "reset";
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
    public boolean isSelfClosing() {
        return true;
    }

    @Override
    public void process(FormattingContext context, String arguments) {
        context.resetStyle();
    }

    @Override
    public void close(FormattingContext context) {
    }

    @Override
    public boolean matchesTagName(String name) {
        return name.equalsIgnoreCase("reset") || name.equalsIgnoreCase("r");
    }
}

