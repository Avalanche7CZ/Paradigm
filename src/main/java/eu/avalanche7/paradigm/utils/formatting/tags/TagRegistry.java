package eu.avalanche7.paradigm.utils.formatting.tags;

import eu.avalanche7.paradigm.platform.Interfaces.IPlatformAdapter;
import java.util.ArrayList;
import java.util.List;

public class TagRegistry {
    private final List<Tag> tags = new ArrayList<>();
    private final IPlatformAdapter platformAdapter;

    public TagRegistry(IPlatformAdapter platformAdapter) {
        this.platformAdapter = platformAdapter;
        registerDefaultTags();
    }

    private void registerDefaultTags() {
        registerTag(new ColorTag(platformAdapter));
        registerTag(new DecorationTag(DecorationTag.Decoration.BOLD));
        registerTag(new DecorationTag(DecorationTag.Decoration.ITALIC));
        registerTag(new DecorationTag(DecorationTag.Decoration.UNDERLINE));
        registerTag(new DecorationTag(DecorationTag.Decoration.STRIKETHROUGH));
        registerTag(new DecorationTag(DecorationTag.Decoration.OBFUSCATED));
        registerTag(new ClickTag(platformAdapter));
        registerTag(new HoverTag(platformAdapter));
        registerTag(new ResetTag());
        registerTag(new GradientTag(platformAdapter));
        registerTag(new RainbowTag(platformAdapter));
        registerTag(new CenterTag(platformAdapter));
        registerTag(new EmojiTag());
    }

    public void registerTag(Tag tag) {
        tags.add(tag);
    }

    public Tag getTag(String tagName) {
        for (Tag tag : tags) {
            if (tag.matchesTagName(tagName)) {
                return tag;
            }
        }
        return null;
    }

    public List<Tag> getAllTags() {
        return new ArrayList<>(tags);
    }

    public boolean hasTag(String tagName) {
        return getTag(tagName) != null;
    }
}

