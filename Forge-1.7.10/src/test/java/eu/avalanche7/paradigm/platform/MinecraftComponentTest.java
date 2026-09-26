package eu.avalanche7.paradigm.platform;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import net.minecraft.event.ClickEvent;
import net.minecraft.event.HoverEvent;
import net.minecraft.util.ChatStyle;
import net.minecraft.util.EnumChatFormatting;
import net.minecraft.util.IChatComponent;
import org.junit.jupiter.api.Test;

import eu.avalanche7.paradigm.platform.Interfaces.IComponent;

class MinecraftComponentTest {
    @Test
    void formattingReturnsDetachedCopies() {
        MinecraftComponent original = new MinecraftComponent("Base");
        IComponent italic = original.withItalic(true);
        IComponent colored = original.withColor("red");

        assertFalse(style(original).getItalic());
        assertEquals(null, style(original).getColor());
        assertTrue(style(italic).getItalic());
        assertEquals(null, style(italic).getColor());
        assertFalse(style(colored).getItalic());
        assertEquals(EnumChatFormatting.RED, style(colored).getColor());
        assertNotSame(original.getOriginalText(), italic.getOriginalText());
        assertFalse(style(italic.copy().withItalic(false)).getItalic());
        assertTrue(style(italic).getItalic());
    }

    @Test
    void siblingsAndInheritedStylesSurviveCopyWithoutBleeding() {
        MinecraftComponent original = new MinecraftComponent("Root");
        original.append(new MinecraftComponent(" child"));
        IComponent copy = original.copy();
        IComponent colored = copy.withColor("blue");

        assertEquals("Root child", original.getRawText());
        assertEquals("Root child", colored.getRawText());
        assertEquals(1, colored.getSiblings().size());
        assertEquals(EnumChatFormatting.BLUE, style(colored.getSiblings().get(0)).getColor());
        assertEquals(null, style(original.getSiblings().get(0)).getColor());
    }

    @Test
    void interactionsSurviveCopyAndDoNotMutateOriginal() {
        MinecraftComponent original = new MinecraftComponent("Open");
        IComponent decorated = original.withFormatting("underline")
                                       .withFormatting("bold")
                                       .onClickRunCommand("/help")
                                       .onHoverText("Details");
        IComponent copy = decorated.copy();

        assertFalse(style(original).getUnderlined());
        assertEquals(null, style(original).getChatClickEvent());
        assertTrue(style(copy).getUnderlined());
        assertTrue(style(copy).getBold());
        assertEquals(ClickEvent.Action.RUN_COMMAND, style(copy).getChatClickEvent().getAction());
        assertEquals("/help", style(copy).getChatClickEvent().getValue());
        assertEquals(HoverEvent.Action.SHOW_TEXT, style(copy).getChatHoverEvent().getAction());
        assertEquals("Details", style(copy).getChatHoverEvent().getValue().getUnformattedText());
        assertNotSame(style(decorated).getChatHoverEvent().getValue(),
                style(copy).getChatHoverEvent().getValue());
    }

    @Test
    void inheritedHoverTracksCopiedParentWithoutChangingOriginal() {
        MinecraftComponent original = new MinecraftComponent("Root");
        original.append(new MinecraftComponent(" child"));
        IComponent first = original.onHoverText("First");
        IComponent second = first.onHoverText("Second");

        assertEquals("First",
                style(first.getSiblings().get(0)).getChatHoverEvent().getValue().getUnformattedText());
        assertEquals("Second",
                style(second.getSiblings().get(0)).getChatHoverEvent().getValue().getUnformattedText());
        assertEquals(null, style(original).getChatHoverEvent());
    }

    @Test
    void rgbDegradesToNearestLegacyColorAndClipboardFailsExplicitly() {
        MinecraftComponent original = new MinecraftComponent("Color");
        IComponent colored = original.withColorHex("#FF5554");

        assertEquals(EnumChatFormatting.RED, style(colored).getColor());
        assertEquals(null, style(original).getColor());
        assertThrows(
                UnsupportedOperationException.class, () -> original.onClickCopyToClipboard("test"));
    }

    private static ChatStyle style(IComponent component) {
        return ((IChatComponent) component.getOriginalText()).getChatStyle();
    }
}
