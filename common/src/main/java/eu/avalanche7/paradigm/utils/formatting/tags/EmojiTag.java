package eu.avalanche7.paradigm.utils.formatting.tags;

import eu.avalanche7.paradigm.configs.EmojiConfigHandler;
import eu.avalanche7.paradigm.platform.Interfaces.IComponent;
import eu.avalanche7.paradigm.platform.Interfaces.IPlatformAdapter;
import eu.avalanche7.paradigm.utils.formatting.FormattingContext;

public class EmojiTag implements Tag {
    private final IPlatformAdapter platformAdapter;

    public EmojiTag(IPlatformAdapter platformAdapter) {
        this.platformAdapter = platformAdapter;
    }

    @Override
    public String getName() {
        return "emoji";
    }

    @Override
    public boolean canOpen() {
        return true;
    }

    @Override
    public boolean canClose() {
        return false;
    }

    @Override
    public boolean isSelfClosing() {
        return false;
    }

    @Override
    public void process(FormattingContext context, String arguments) {
        if (arguments == null || arguments.isEmpty()) {
            return;
        }

        String emojiName = arguments.trim().toLowerCase();
        String emoji = EmojiConfigHandler.getEmoji(emojiName);

        if (emoji != null && !emoji.isEmpty()) {
            IComponent emojiComponent = platformAdapter.createComponentFromLiteral(emoji);
            context.getCurrentComponent().append(emojiComponent);
        }
    }

    @Override
    public void close(FormattingContext context) {
    }

    @Override
    public boolean matchesTagName(String name) {
        return name.equalsIgnoreCase("emoji") || name.equalsIgnoreCase("e");
    }
}
