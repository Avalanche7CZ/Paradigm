package eu.avalanche7.paradigm.utils.formatting;

import eu.avalanche7.paradigm.platform.Interfaces.IComponent;
import eu.avalanche7.paradigm.platform.Interfaces.IPlayer;
import eu.avalanche7.paradigm.utils.formatting.tags.CenterTag;
import net.minecraft.text.Style;
import java.util.Stack;

public class FormattingContext {
    private final IComponent rootComponent;
    private final IPlayer player;
    private final Style baseStyle;
    private Style currentStyle;
    private int nestingLevel;
    private FormattingParser parser;
    private Stack<IComponent> componentStack = new Stack<>();
    private Stack<CenterTag> centerStack = new Stack<>();
    private Stack<Style> styleStack = new Stack<>();

    public FormattingContext(IComponent rootComponent, IPlayer player, Style baseStyle) {
        this.rootComponent = rootComponent;
        this.player = player;
        this.baseStyle = baseStyle;
        this.currentStyle = baseStyle;
        this.nestingLevel = 0;
        this.componentStack.push(rootComponent);
        this.styleStack.push(baseStyle);
    }

    public IComponent getRootComponent() {
        return rootComponent;
    }

    public IComponent getCurrentComponent() {
        return componentStack.isEmpty() ? rootComponent : componentStack.peek();
    }

    public void pushComponent(IComponent component) {
        componentStack.push(component);
    }

    public IComponent popComponent() {
        return componentStack.isEmpty() ? rootComponent : componentStack.pop();
    }

    public IPlayer getPlayer() {
        return player;
    }

    public Style getBaseStyle() {
        return baseStyle;
    }

    public Style getCurrentStyle() {
        return currentStyle;
    }

    public void setCurrentStyle(Style style) {
        this.currentStyle = style;
    }

    public void pushStyle(Style style) {
        styleStack.push(style);
        this.currentStyle = style;
        this.nestingLevel++;
    }

    public void popStyle() {
        if (nestingLevel > 0 && styleStack.size() > 1) {
            this.nestingLevel--;
            styleStack.pop();
            this.currentStyle = styleStack.isEmpty() ? baseStyle : styleStack.peek();
        }
    }

    public int getNestingLevel() {
        return nestingLevel;
    }

    public void resetStyle() {
        this.currentStyle = baseStyle;
        this.nestingLevel = 0;
    }

    public void setParser(FormattingParser parser) {
        this.parser = parser;
    }

    public FormattingParser getParser() {
        return parser;
    }

    public void pushCenterTag(CenterTag tag) {
        centerStack.push(tag);
    }

    public CenterTag popCenterTag() {
        return centerStack.isEmpty() ? null : centerStack.pop();
    }

    public CenterTag peekCenterTag() {
        return centerStack.isEmpty() ? null : centerStack.peek();
    }

    public boolean hasActiveCenterTag() {
        return !centerStack.isEmpty();
    }
}

