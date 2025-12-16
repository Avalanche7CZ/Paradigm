package eu.avalanche7.paradigm.utils.formatting.tags;

import eu.avalanche7.paradigm.utils.formatting.FormattingContext;

public interface Tag {
    String getName();

    default boolean canOpen() {
        return true;
    }

    default boolean canClose() {
        return true;
    }

    default boolean isSelfClosing() {
        return false;
    }

    void process(FormattingContext context, String arguments);

    default void close(FormattingContext context) {
    }

    default boolean matchesTagName(String name) {
        return name.equalsIgnoreCase(getName());
    }
}

