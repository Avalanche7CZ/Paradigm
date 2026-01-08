package eu.avalanche7.paradigm.utils.formatting.tags;

import eu.avalanche7.paradigm.configs.EmojiConfigHandler;
import eu.avalanche7.paradigm.platform.Interfaces.IComponent;
import eu.avalanche7.paradigm.platform.Interfaces.IPlatformAdapter;
import eu.avalanche7.paradigm.utils.formatting.FormattingContext;
import net.minecraft.text.Text;

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
        String emoji = EmojiConfigHandler.CONFIG.getEmoji(emojiName);

        if (!emoji.isEmpty()) {
            Text emojiComponent = Text.literal(emoji);
            IComponent emojiWrapper = platformAdapter.wrap(emojiComponent);
            context.getCurrentComponent().append(emojiWrapper);
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

