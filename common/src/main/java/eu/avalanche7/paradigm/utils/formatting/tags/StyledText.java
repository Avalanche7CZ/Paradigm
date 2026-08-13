package eu.avalanche7.paradigm.utils.formatting.tags;

import java.util.ArrayList;
import java.util.List;
import java.util.function.IntBinaryOperator;

import eu.avalanche7.paradigm.platform.Interfaces.IComponent;
import eu.avalanche7.paradigm.platform.Interfaces.IPlatformAdapter;
import eu.avalanche7.paradigm.utils.formatting.FormattingContext;

final class StyledText {

    record Span(char character, Object style) {
    }

    private StyledText() {
    }

    static void appendRecolored(FormattingContext context, IPlatformAdapter platformAdapter, IComponent content,
                                Object baseStyle, IntBinaryOperator colorAt) {
        List<Span> spans = flatten(content);
        if (spans.isEmpty()) {
            context.popComponent();
            return;
        }

        IComponent result = platformAdapter.createComponentFromLiteral("");
        for (int index = 0; index < spans.size(); index++) {
            Span span = spans.get(index);
            IComponent part = platformAdapter.createComponentFromLiteral(String.valueOf(span.character()));
            Object style = span.style() != null ? span.style() : baseStyle;
            if (style != null) {
                part.setStyle(style);
            }
            result.append(part.withColor(colorAt.applyAsInt(index, spans.size())));
        }

        context.popComponent();
        context.getCurrentComponent().append(result);
    }

    static List<Span> flatten(IComponent root) {
        List<Span> spans = new ArrayList<>();
        collect(root, spans);
        return spans;
    }

    private static void collect(IComponent node, List<Span> spans) {
        if (node == null) {
            return;
        }
        String raw = node.getRawText();
        if (raw == null) {
            raw = "";
        }
        List<IComponent> children = node.getSiblings();

        int childLength = 0;
        for (IComponent child : children) {
            String childRaw = child != null ? child.getRawText() : null;
            if (childRaw != null) {
                childLength += childRaw.length();
            }
        }

        int ownLength = Math.max(0, raw.length() - childLength);
        Object style = node.getStyle();
        for (int i = 0; i < ownLength; i++) {
            spans.add(new Span(raw.charAt(i), style));
        }

        for (IComponent child : children) {
            collect(child, spans);
        }
    }
}
