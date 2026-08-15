package eu.avalanche7.paradigm.support;

import java.util.LinkedHashSet;
import java.util.Set;

import eu.avalanche7.paradigm.platform.Interfaces.IComponent;

public record TestStyle(String color, Set<String> tokens, IComponent hover, String clickAction, String clickValue) {

    public static final TestStyle EMPTY = new TestStyle(null, Set.of(), null, null, null);

    public TestStyle withColor(String value) {
        return new TestStyle(value, tokens, hover, clickAction, clickValue);
    }

    public TestStyle withToken(String token) {
        Set<String> merged = new LinkedHashSet<>(tokens);
        if (token != null) {
            merged.add(token.toLowerCase());
        }
        return new TestStyle(color, Set.copyOf(merged), hover, clickAction, clickValue);
    }

    public TestStyle withHover(IComponent value) {
        return new TestStyle(color, tokens, value, clickAction, clickValue);
    }

    public TestStyle withClick(String action, String value) {
        return new TestStyle(color, tokens, hover, action, value);
    }

    public String hoverText() {
        return hover != null ? hover.getRawText() : null;
    }
}
