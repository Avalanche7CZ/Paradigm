package eu.avalanche7.paradigm.support;

import java.util.ArrayList;
import java.util.List;
import java.util.function.UnaryOperator;

import eu.avalanche7.paradigm.platform.Interfaces.IComponent;

public final class TestComponent implements IComponent {

    private String text;
    private TestStyle style = TestStyle.EMPTY;
    private final List<IComponent> siblings = new ArrayList<>();

    public TestComponent(String text) {
        this.text = text != null ? text : "";
    }

    @Override
    public String getRawText() {
        StringBuilder builder = new StringBuilder(text);
        for (IComponent sibling : siblings) {
            builder.append(sibling.getRawText());
        }
        return builder.toString();
    }

    @Override
    public IComponent setStyle(Object style) {
        this.style = style instanceof TestStyle typed ? typed : TestStyle.EMPTY;
        return this;
    }

    @Override
    public Object getStyle() {
        return style;
    }

    @Override
    public IComponent append(IComponent sibling) {
        if (sibling != null) {
            siblings.add(sibling);
        }
        return this;
    }

    @Override
    public List<IComponent> getSiblings() {
        return List.copyOf(siblings);
    }

    @Override
    public IComponent copy() {
        TestComponent copy = new TestComponent(text);
        copy.style = style;
        for (IComponent sibling : siblings) {
            copy.siblings.add(sibling.copy());
        }
        return copy;
    }

    @Override
    public IComponent withStyle(String formattingCode) {
        return withFormatting(formattingCode);
    }

    @Override
    public IComponent withStyle(Object style) {
        return setStyle(style);
    }

    @Override
    public IComponent withStyle(UnaryOperator<Object> styleUpdater) {
        return setStyle(styleUpdater.apply(style));
    }

    @Override
    public IComponent withColor(int rgb) {
        return withColorHex(String.format("#%06X", rgb));
    }

    @Override
    public IComponent withColorHex(String hex) {
        this.style = style.withColor(hex);
        return this;
    }

    @Override
    public IComponent withFormatting(String formattingCode) {
        this.style = style.withToken(formattingCode);
        return this;
    }

    @Override
    public IComponent withColor(String hexOrFormatCode) {
        return hexOrFormatCode != null && hexOrFormatCode.startsWith("#")
                ? withColorHex(hexOrFormatCode)
                : withFormatting(hexOrFormatCode);
    }

    @Override
    public IComponent resetStyle() {
        this.style = TestStyle.EMPTY;
        return this;
    }

    @Override
    public IComponent onClickRunCommand(String command) {
        this.style = style.withClick("RUN_COMMAND", command);
        return this;
    }

    @Override
    public IComponent onClickSuggestCommand(String command) {
        this.style = style.withClick("SUGGEST_COMMAND", command);
        return this;
    }

    @Override
    public IComponent onClickOpenUrl(String url) {
        this.style = style.withClick("OPEN_URL", url);
        return this;
    }

    @Override
    public IComponent onClickCopyToClipboard(String text) {
        this.style = style.withClick("COPY_TO_CLIPBOARD", text);
        return this;
    }

    @Override
    public IComponent onHoverText(String text) {
        this.style = style.withHover(new TestComponent(text));
        return this;
    }

    @Override
    public IComponent onHoverComponent(IComponent component) {
        this.style = style.withHover(component);
        return this;
    }

    @Override
    public Object getOriginalText() {
        return this;
    }

    public String ownText() {
        return text;
    }

    public TestStyle testStyle() {
        return style;
    }

    public List<TestComponent> flatten() {
        List<TestComponent> flat = new ArrayList<>();
        collect(flat);
        return flat;
    }

    private void collect(List<TestComponent> target) {
        target.add(this);
        for (IComponent sibling : siblings) {
            if (sibling instanceof TestComponent typed) {
                typed.collect(target);
            }
        }
    }
}
