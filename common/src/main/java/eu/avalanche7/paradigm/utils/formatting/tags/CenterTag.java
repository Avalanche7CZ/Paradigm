package eu.avalanche7.paradigm.utils.formatting.tags;

import eu.avalanche7.paradigm.platform.Interfaces.IComponent;
import eu.avalanche7.paradigm.platform.Interfaces.IPlatformAdapter;
import eu.avalanche7.paradigm.utils.formatting.FormattingContext;

public class CenterTag implements Tag {
    private final IPlatformAdapter platformAdapter;
    private IComponent centeredContent;
    private int approximateChatWidthChars = 53;

    public CenterTag(IPlatformAdapter platformAdapter) {
        this.platformAdapter = platformAdapter;
    }

    @Override
    public String getName() {
        return "center";
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
        centeredContent = platformAdapter.createEmptyComponent();
        context.pushComponent(centeredContent);
        context.pushStyle(context.getCurrentStyle());
    }

    @Override
    public void close(FormattingContext context) {
        if (centeredContent != null) {
            String text = extractPlainText(centeredContent);
            text = text.trim();
            int textLength = text.length();

            String paddingSpaces = "";
            if (textLength > 0 && textLength < approximateChatWidthChars) {
                int totalPadding = approximateChatWidthChars - textLength;
                int leftPadding = totalPadding / 2;
                if (leftPadding > 0) {
                    paddingSpaces = " ".repeat(leftPadding);
                }
            }

            context.popComponent();
            IComponent rootComponent = context.getCurrentComponent();

            if (!paddingSpaces.isEmpty()) {
                IComponent paddingComponent = platformAdapter.createComponentFromLiteral(paddingSpaces);
                rootComponent.append(paddingComponent);
            }
            rootComponent.append(centeredContent);
        }

        context.popStyle();
    }

    private String extractPlainText(IComponent component) {
        return component.getRawText();
    }

    @Override
    public boolean matchesTagName(String name) {
        return name.equalsIgnoreCase("center");
    }

    public IComponent getCenteredContent() {
        return centeredContent;
    }

    public void setCenteredContent(IComponent content) {
        this.centeredContent = content;
    }
}

