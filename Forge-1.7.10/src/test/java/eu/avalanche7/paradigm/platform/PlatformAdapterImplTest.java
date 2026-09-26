package eu.avalanche7.paradigm.platform;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;

import java.nio.file.Path;

import net.minecraft.event.HoverEvent;
import net.minecraft.util.ChatComponentText;
import net.minecraft.util.ChatStyle;
import org.junit.jupiter.api.Test;

import eu.avalanche7.paradigm.utils.DebugLogger;
import eu.avalanche7.paradigm.utils.Placeholders;
import eu.avalanche7.paradigm.utils.TaskScheduler;

class PlatformAdapterImplTest {
    private final ForgeConfig config = new ForgeConfig(Path.of("test-config"));
    private final DebugLogger logger = new DebugLogger(null);
    private final PlatformAdapterImpl adapter =
            new PlatformAdapterImpl(null, new Placeholders(), new TaskScheduler(logger), logger, config);

    @Test
    void hoverStyleAcceptsPlainTextAndNativeComponents() {
        ChatStyle base = new ChatStyle();
        assertHoverText("plain", adapter.createStyleWithHoverEvent(base, "plain"));
        assertHoverText("native", adapter.createStyleWithHoverEvent(base, new ChatComponentText("native")));
        assertHoverText("wrapped", adapter.createStyleWithHoverEvent(base, new MinecraftComponent("wrapped")));
        assertHoverText("42", adapter.createStyleWithHoverEvent(base, 42));
        assertEquals(null, base.getChatHoverEvent());
    }

    @Test
    void unsupportedCommandTreeSynchronizationIsInert() {
        assertDoesNotThrow(adapter::rewireCommandTreePermissions);
        assertDoesNotThrow(() -> adapter.refreshPlayerCommandTree(null));
        assertDoesNotThrow(adapter::refreshAllPlayerCommandTrees);
        assertDoesNotThrow(() -> adapter.resetPlayerListState(null));
        assertEquals(Path.of("test-config"), adapter.getConfig().getConfigDirectory());
    }

    private static void assertHoverText(String expected, Object style) {
        HoverEvent hover = ((ChatStyle) style).getChatHoverEvent();
        assertEquals(HoverEvent.Action.SHOW_TEXT, hover.getAction());
        assertEquals(expected, hover.getValue().getUnformattedText());
    }
}
