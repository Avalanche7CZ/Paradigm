package eu.avalanche7.paradigm.modules.discord;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import eu.avalanche7.paradigm.configs.DiscordConfigHandler;
import eu.avalanche7.paradigm.core.Services;
import eu.avalanche7.paradigm.modules.discord.client.DiscordInboundMessage;
import eu.avalanche7.paradigm.support.TestPlayer;
import eu.avalanche7.paradigm.utils.Placeholders;

class DiscordRelayLoopPreventionTest {
    @TempDir
    Path tempDir;

    private DiscordConfigHandler.Config config;
    private DiscordTestSupport.RecordingOutbox outbox;
    private DiscordTestSupport.RecordingPlatform platform;
    private DiscordRelay relay;

    @BeforeEach
    void setUp() {
        config = DiscordTestSupport.enabledConfig(tempDir);
        outbox = new DiscordTestSupport.RecordingOutbox(config);
        platform = new DiscordTestSupport.RecordingPlatform(new Placeholders());
        Services services = DiscordTestSupport.services(platform.adapter, new Placeholders());
        relay = new DiscordRelay(services, outbox);
    }

    @Test
    void relaysAnOrdinaryDiscordMessageIntoMinecraft() {
        relay.handleInbound(message("1", "Alex", "hello from discord"));

        assertEquals(1, platform.broadcasts.size(), "an ordinary Discord message should reach Minecraft");
        assertTrue(platform.broadcasts.get(0).contains("hello from discord"),
                "the relayed line should carry the message body");
        assertTrue(platform.broadcasts.get(0).contains("Alex"), "the relayed line should carry the author name");
    }

    @Test
    void dropsMessagesAuthoredByOurOwnBot() {
        DiscordInboundMessage own = new DiscordInboundMessage("2", config.chatChannelId.get(), null,
                outbox.botUserId, "Paradigm", true, false, false, "echo of a relayed line",
                List.of(), DiscordTestSupport.noMentions());

        relay.handleInbound(own);

        assertTrue(platform.broadcasts.isEmpty(),
                "our own bot's messages must never be relayed back, or chat would loop");
    }

    @Test
    void dropsWebhookAndSystemMessages() {
        relay.handleInbound(new DiscordInboundMessage("3", config.chatChannelId.get(), null, "555", "Hook",
                false, true, false, "webhook text", List.of(), DiscordTestSupport.noMentions()));
        relay.handleInbound(new DiscordInboundMessage("4", config.chatChannelId.get(), null, "556", "System",
                false, false, true, "member joined", List.of(), DiscordTestSupport.noMentions()));

        assertTrue(platform.broadcasts.isEmpty(), "webhook and system messages are not user chat");
    }

    @Test
    void dropsOtherBotsUnlessExplicitlyAllowed() {
        DiscordInboundMessage botMessage = new DiscordInboundMessage("5", config.chatChannelId.get(), null,
                "777", "OtherBot", true, false, false, "bot chatter", List.of(), DiscordTestSupport.noMentions());

        relay.handleInbound(botMessage);
        assertTrue(platform.broadcasts.isEmpty(), "other bots are ignored by default");

        config.allowOtherBots.value = true;
        relay.handleInbound(new DiscordInboundMessage("6", config.chatChannelId.get(), null,
                "777", "OtherBot", true, false, false, "bot chatter", List.of(), DiscordTestSupport.noMentions()));
        assertEquals(1, platform.broadcasts.size(), "enabling allowOtherBots should let other bots through");
    }

    @Test
    void dropsMessagesFromOtherChannelsAndGuilds() {
        relay.handleInbound(new DiscordInboundMessage("7", "200000000000000009", null, "555", "Alex",
                false, false, false, "wrong channel", List.of(), DiscordTestSupport.noMentions()));
        assertTrue(platform.broadcasts.isEmpty(), "only the configured chat channel is relayed");

        config.guildId.value = "300000000000000001";
        relay.handleInbound(new DiscordInboundMessage("8", config.chatChannelId.get(), "300000000000000002",
                "555", "Alex", false, false, false, "wrong guild", List.of(), DiscordTestSupport.noMentions()));
        assertTrue(platform.broadcasts.isEmpty(), "messages from another guild are ignored");
    }

    @Test
    void dropsARepeatedMessageIdSoResumeReplayCannotDoublePost() {
        DiscordInboundMessage first = message("9", "Alex", "only once please");

        relay.handleInbound(first);
        relay.handleInbound(first);
        relay.handleInbound(message("9", "Alex", "only once please"));

        assertEquals(1, platform.broadcasts.size(),
                "a RESUME replay repeats recent events, so a seen message ID must be dropped");
    }

    @Test
    void relaysGenuinePlayerChatEvenWhenItMatchesEarlierDiscordText() {
        relay.handleInbound(message("10", "Alex", "hello"));
        assertEquals(1, platform.broadcasts.size());

        relay.relayPlayerChat(TestPlayer.named("Steve"), "hello");

        assertEquals(1, outbox.to(DiscordDestination.CHAT).size(),
                "a system-message broadcast does not emit player chat, so matching text is genuine player traffic");
    }

    @Test
    void relaysGenuinePlayerChatToTheChatDestination() {
        relay.relayPlayerChat(TestPlayer.named("Steve"), "hello discord");

        List<DiscordMessage> chat = outbox.to(DiscordDestination.CHAT);
        assertEquals(1, chat.size(), "public player chat should reach the Discord chat channel");
        assertTrue(chat.get(0).content().contains("hello discord"));
        assertTrue(chat.get(0).content().contains("Steve"));
    }

    @Test
    void honoursTheRelayDirectionToggles() {
        config.minecraftToDiscordEnabled.value = false;
        relay.relayPlayerChat(TestPlayer.named("Steve"), "should not leave minecraft");
        assertTrue(outbox.messages.isEmpty(), "the Minecraft to Discord toggle must suppress outbound chat");

        config.discordToMinecraftEnabled.value = false;
        relay.handleInbound(message("11", "Alex", "should not enter minecraft"));
        assertTrue(platform.broadcasts.isEmpty(), "the Discord to Minecraft toggle must suppress inbound chat");
    }

    @Test
    void relaysNothingWhileTheIntegrationIsDisabled() {
        config.enabled.value = false;

        relay.relayPlayerChat(TestPlayer.named("Steve"), "quiet please");
        relay.handleInbound(message("12", "Alex", "quiet please"));

        assertTrue(outbox.messages.isEmpty());
        assertTrue(platform.broadcasts.isEmpty());
    }

    @Test
    void resolvesMentionsFromThePayloadWithoutAnyGuildCache() {
        DiscordInboundMessage mention = new DiscordInboundMessage("13", config.chatChannelId.get(), null,
                "555", "Alex", false, false, false,
                "hi <@444555666777888999> and <@&444555666777888111> in <#444555666777888222>",
                List.of(), Map.of("444555666777888999", "Notch"));

        relay.handleInbound(mention);

        String broadcast = platform.broadcasts.get(0);
        assertTrue(broadcast.contains("@Notch"), "a user mention resolves from the payload's own mentions array");
        assertTrue(broadcast.contains("@role"), "role mentions render generically rather than needing a cache");
        assertTrue(broadcast.contains("#channel"), "channel mentions render generically");
        assertFalse(broadcast.contains("@444555666777888999"), "raw mention syntax should not survive into Minecraft");
    }

    @Test
    void representsAttachmentsAsClickableLinksOrAMarker() {
        relay.handleInbound(new DiscordInboundMessage("14", config.chatChannelId.get(), null, "555", "Alex",
                false, false, false, "", List.of("https://cdn.example/image.png"), DiscordTestSupport.noMentions()));
        assertTrue(platform.broadcasts.get(0).contains("attachment"),
                "attachments are represented as a clickable link, not raw text");
        assertFalse(platform.broadcasts.get(0).contains("https://cdn.example/image.png"),
                "the raw URL must live only in the click event, not in the visible text");

        platform.broadcasts.clear();
        config.showAttachments.value = false;
        relay.handleInbound(new DiscordInboundMessage("15", config.chatChannelId.get(), null, "555", "Alex",
                false, false, false, "", List.of("https://cdn.example/image.png"), DiscordTestSupport.noMentions()));
        assertTrue(platform.broadcasts.get(0).contains("[attachment]"),
                "with links off, an attachment indicator is used instead");
    }

    @Test
    void ignoresAnEmptyMessageWithNoAttachments() {
        relay.handleInbound(new DiscordInboundMessage("16", config.chatChannelId.get(), null, "555", "Alex",
                false, false, false, "   ", List.of(), DiscordTestSupport.noMentions()));

        assertTrue(platform.broadcasts.isEmpty(), "an empty Discord message has nothing to relay");
    }

    private DiscordInboundMessage message(String id, String author, String content) {
        return new DiscordInboundMessage(id, config.chatChannelId.get(), null, "555", author,
                false, false, false, content, List.of(), DiscordTestSupport.noMentions());
    }
}
