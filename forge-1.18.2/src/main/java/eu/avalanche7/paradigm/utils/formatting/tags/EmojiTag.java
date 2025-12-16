package eu.avalanche7.paradigm.utils.formatting.tags;

import eu.avalanche7.paradigm.configs.EmojiConfigHandler;
import eu.avalanche7.paradigm.platform.Interfaces.IComponent;
import eu.avalanche7.paradigm.platform.MinecraftComponent;
import eu.avalanche7.paradigm.utils.formatting.FormattingContext;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.TextComponent;

public class EmojiTag implements Tag {

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
            System.out.println("[Paradigm-EmojiTag] Empty arguments!");
            return;
        }

        String emojiName = arguments.trim().toLowerCase();
        String emoji = EmojiConfigHandler.CONFIG.getEmoji(emojiName);

        System.out.println("[Paradigm-EmojiTag] Processing emoji: " + emojiName + " -> " + emoji + " (empty=" + emoji.isEmpty() + ")");

        if (!emoji.isEmpty()) {
            Component emojiComponent = new TextComponent(emoji);
            IComponent emojiWrapper = new MinecraftComponent(emojiComponent);
            context.getCurrentComponent().append(emojiWrapper);
            System.out.println("[Paradigm-EmojiTag] Successfully added emoji: " + emoji);
        } else {
            System.out.println("[Paradigm-EmojiTag] Emoji not found for: " + emojiName);
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

