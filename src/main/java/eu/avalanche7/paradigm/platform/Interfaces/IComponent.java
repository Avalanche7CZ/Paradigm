package eu.avalanche7.paradigm.platform.Interfaces;

import net.minecraft.network.chat.Style;
import net.minecraft.ChatFormatting;
import java.util.List;

public interface IComponent {
    String getRawText();
    IComponent setStyle(Style style);
    Style getStyle();
    IComponent append(IComponent sibling);
    List<?> getSiblings();
    IComponent copy();
    IComponent withStyle(ChatFormatting formatting);
    IComponent withStyle(Style style);
    IComponent withStyle(java.util.function.UnaryOperator<Style> styleUpdater);
    IComponent withColor(int rgb);
    IComponent withColorHex(String hex);
    IComponent onClickRunCommand(String command);
    IComponent onClickSuggestCommand(String command);
    IComponent onClickOpenUrl(String url);
    IComponent onHoverText(String text);
    IComponent onHoverComponent(IComponent component);
}
