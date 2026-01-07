package eu.avalanche7.paradigm.platform.Interfaces;

import net.minecraft.text.Style;
import net.minecraft.text.Text;
import net.minecraft.util.Formatting;
import java.util.List;
import java.util.function.UnaryOperator;

public interface IComponent {
    String getRawText();
    IComponent setStyle(Style style);
    Style getStyle();
    IComponent append(IComponent sibling);
    List<IComponent> getSiblings();
    IComponent copy();
    IComponent withStyle(Formatting formatting);
    IComponent withStyle(Style style);
    IComponent withStyle(UnaryOperator<Style> styleUpdater);
    IComponent withColor(int rgb);
    IComponent withColorHex(String hex);
    IComponent withFormatting(Formatting formatting);
    IComponent withColor(String hexOrFormatCode);
    IComponent resetStyle();
    IComponent onClickRunCommand(String command);
    IComponent onClickSuggestCommand(String command);
    IComponent onClickOpenUrl(String url);
    IComponent onClickCopyToClipboard(String text);
    IComponent onHoverText(String text);
    IComponent onHoverComponent(IComponent component);
    Text getOriginalText();
}
