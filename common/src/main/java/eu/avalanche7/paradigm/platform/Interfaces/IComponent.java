package eu.avalanche7.paradigm.platform.Interfaces;

import java.util.List;
import java.util.function.UnaryOperator;

public interface IComponent {
    String getRawText();
    IComponent setStyle(Object style);
    Object getStyle();
    IComponent append(IComponent sibling);
    List<IComponent> getSiblings();
    IComponent copy();
    IComponent withStyle(String formattingCode);
    IComponent withStyle(Object style);
    IComponent withStyle(UnaryOperator<Object> styleUpdater);
    IComponent withColor(int rgb);
    IComponent withColorHex(String hex);
    IComponent withFormatting(String formattingCode);
    IComponent withColor(String hexOrFormatCode);
    IComponent resetStyle();
    IComponent onClickRunCommand(String command);
    IComponent onClickSuggestCommand(String command);
    IComponent onClickOpenUrl(String url);
    IComponent onClickCopyToClipboard(String text);
    IComponent onHoverText(String text);
    IComponent onHoverComponent(IComponent component);
    Object getOriginalText();
}
