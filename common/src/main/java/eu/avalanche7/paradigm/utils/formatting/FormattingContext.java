package eu.avalanche7.paradigm.utils.formatting;

import eu.avalanche7.paradigm.platform.Interfaces.IComponent;
import eu.avalanche7.paradigm.platform.Interfaces.IPlayer;
import eu.avalanche7.paradigm.utils.formatting.tags.CenterTag;

import java.util.Stack;

public class FormattingContext {
    private final IComponent rootComponent;
    private final IPlayer player;
    private final Object baseStyle;
    private Object currentStyle;
    private int nestingLevel;
    private FormattingParser parser;
    private final Stack<IComponent> componentStack = new Stack<>();
    private final Stack<CenterTag> centerStack = new Stack<>();
    private final Stack<Object> styleStack = new Stack<>();

    public FormattingContext(IComponent rootComponent, IPlayer player, Object baseStyle) {
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

    public Object getBaseStyle() {
        return baseStyle;
    }

    public Object getCurrentStyle() {
        return currentStyle;
    }

    public void setCurrentStyle(Object style) {
        this.currentStyle = style;
    }

    public void pushStyle(Object style) {
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
        this.styleStack.clear();
        this.styleStack.push(baseStyle);
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
