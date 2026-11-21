package eu.avalanche7.paradigm.platform;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

import java.net.URI;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;

import net.minecraft.ChatFormatting;
import net.minecraft.network.chat.ClickEvent;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.HoverEvent;
import net.minecraft.network.chat.Style;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.players.PlayerList;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import eu.avalanche7.paradigm.configs.DiscordConfigHandler;
import eu.avalanche7.paradigm.core.Services;
import eu.avalanche7.paradigm.modules.discord.DiscordOutbox;
import eu.avalanche7.paradigm.modules.discord.DiscordRelay;
import eu.avalanche7.paradigm.modules.discord.client.DiscordInboundMessage;
import eu.avalanche7.paradigm.utils.DebugLogger;
import eu.avalanche7.paradigm.utils.MessageParser;
import eu.avalanche7.paradigm.utils.Placeholders;

class InteractiveChatTest {
    private static final String URL = "https://cdn.example/a.png?expires=123&signature=a%2Bb%3D";
    private final Placeholders placeholders = new Placeholders();
    private final PlatformAdapterImpl platform = new PlatformAdapterImpl(null, placeholders, null, null);
    private final MessageParser parser = new MessageParser(placeholders, platform);

    @Test
    void nullAndInheritedStylesKeepBothEventsAfterCopyAndComposition() {
        Style inherited = Style.EMPTY.withColor(ChatFormatting.AQUA).withBold(true).withUnderlined(true);
        for (Style base : new Style[] {null, inherited}) {
            var label = platform.createComponentFromLiteral("a.png");
            Object clicked = platform.createStyleWithClickEvent(base, "open_url", URL);
            label.setStyle(platform.createStyleWithHoverEvent(clicked, URL));
            var root = (MinecraftComponent) platform.createComponentFromLiteral("").append(label).copy();
            Style actual = leaf(root.getHandle(), "a.png").getStyle();
            assertLink(actual, URL);
            if (base != null) {
                assertEquals(inherited.getColor(), actual.getColor());
                assertTrue(actual.isBold());
                assertTrue(actual.isUnderlined());
                assertNull(inherited.getClickEvent());
                assertNull(inherited.getHoverEvent());
            }
        }
    }

    @Test
    void relayBroadcastsIndependentLiteralAttachmentsAlongsideReplyAndEmbed() {
        var config = new DiscordConfigHandler.Config();
        config.discordToMinecraftEnabled.value = true;
        config.showAttachments.value = true;
        config.chatChannelId.value = "123";
        config.minecraftReplyFormat.value = "{name} to {reply_name} ({reply_message}): {message}";
        var outbox = mock(DiscordOutbox.class);
        when(outbox.isEnabled()).thenReturn(true);
        when(outbox.config()).thenReturn(config);

        var server = mock(MinecraftServer.class);
        var players = mock(PlayerList.class);
        when(server.getPlayerList()).thenReturn(players);
        doAnswer(call -> {
            call.getArgument(0, Runnable.class).run();
            return null;
        }).when(server).execute(any(Runnable.class));
        platform.setMinecraftServer(server);

        var logger = new DebugLogger(null);
        var services = new Services(null, null, null, null, null, null, null, null, null,
                logger, null, parser, null, placeholders, null, null, null, null, null, null, platform);
        String filename = "file &a <click:execute:'/op me'> https://other.example/injected.png";
        String second = "https://cdn.example/second.png";
        new DiscordRelay(services, outbox).handleInbound(new DiscordInboundMessage("1", "123", "guild", "author",
                "Alex", false, false, false, "DISCORD_ATTACH_0 <click:execute:'/op me'>",
                List.of(URL, second, "javascript:alert(1)"), Map.of(), "Sam", "quoted",
                List.of(new DiscordInboundMessage.Attachment(URL, filename),
                        new DiscordInboundMessage.Attachment("javascript:alert(1)", "bad.png"),
                        new DiscordInboundMessage.Attachment(second, "second.png")),
                List.of(new DiscordInboundMessage.EmbedSummary(null, "News", "{discord_attachment_0}", null)),
                List.of("wave"), null));

        var component = ArgumentCaptor.forClass(Component.class);
        verify(players).broadcastSystemMessage(component.capture(), eq(false));
        var message = component.getValue();
        assertTrue(message.getString().contains("Alex to Sam (quoted)"));
        assertTrue(message.getString().contains("News"));
        assertTrue(message.getString().contains("wave"));
        assertTrue(message.getString().contains("DISCORD_ATTACH_0"));
        assertLink(leaf(message, "📎 " + filename).getStyle(), URL);
        assertLink(leaf(message, "📎 second.png").getStyle(), second);
        Style invalid = leaf(message, "📎 bad.png").getStyle();
        assertNull(invalid.getClickEvent());
        assertNull(invalid.getHoverEvent());
        assertEquals(2, descendants(message).filter(node -> node.getStyle().getClickEvent() != null).count());
    }

    @Test
    void formattedCommandButtonsAndOrdinaryUrlsKeepNativeActions() {
        var rendered = (MinecraftComponent) parser.parseMessage(
                "<hover:'Accept teleport'><click:execute:'/tpaccept Alex'>[ACCEPT]</click></hover> "
                        + URL + " <click:open_url:'https://[bad'>invalid</click> https://[bad end", null);
        var root = rendered.getHandle();
        var button = leaf(root, "[ACCEPT]").getStyle();
        assertEquals(new ClickEvent.RunCommand("/tpaccept Alex"), button.getClickEvent());
        assertEquals("Accept teleport", assertInstanceOf(HoverEvent.ShowText.class, button.getHoverEvent()).value().getString());
        assertEquals(new ClickEvent.OpenUrl(URI.create(URL)), leaf(root, URL).getStyle().getClickEvent());
        assertNull(leaf(root, "invalid").getStyle().getClickEvent());
        assertNull(leaf(root, "https://[bad").getStyle().getClickEvent());
        assertTrue(root.getString().endsWith("end"));
    }

    private static void assertLink(Style style, String url) {
        assertEquals(new ClickEvent.OpenUrl(URI.create(url)), style.getClickEvent());
        assertEquals(url, assertInstanceOf(HoverEvent.ShowText.class, style.getHoverEvent()).value().getString());
    }

    private static Component leaf(Component root, String text) {
        return descendants(root).filter(component -> component.getSiblings().isEmpty() && text.equals(component.getString()))
                .findFirst().orElseThrow(() -> new AssertionError("Missing component " + text + " in " + root.getString()));
    }

    private static Stream<Component> descendants(Component root) {
        return Stream.concat(Stream.of(root), root.getSiblings().stream().flatMap(InteractiveChatTest::descendants));
    }
}
