package eu.avalanche7.paradigm.modules.chat;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.function.BiPredicate;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.slf4j.LoggerFactory;

import eu.avalanche7.paradigm.configs.ChatConfigHandler;
import eu.avalanche7.paradigm.modules.permissions.PermissionAPI;
import eu.avalanche7.paradigm.modules.permissions.PermissionDataStore;
import eu.avalanche7.paradigm.platform.Interfaces.IComponent;
import eu.avalanche7.paradigm.platform.Interfaces.IConfig;
import eu.avalanche7.paradigm.platform.Interfaces.IPlatformAdapter;
import eu.avalanche7.paradigm.platform.Interfaces.IPlayer;
import eu.avalanche7.paradigm.support.TestComponent;
import eu.avalanche7.paradigm.support.TestPlatformAdapter;
import eu.avalanche7.paradigm.support.TestPlayer;
import eu.avalanche7.paradigm.support.TestStyle;
import eu.avalanche7.paradigm.utils.DebugLogger;
import eu.avalanche7.paradigm.utils.MessageParser;
import eu.avalanche7.paradigm.utils.Placeholders;

class ChatFormatterTest {

    private static final TestPlayer SIMON = TestPlayer.named("Simon");

    private ChatConfigHandler.Config config;
    private Placeholders placeholders;
    private IPlatformAdapter platform;
    private MessageParser parser;
    private Set<String> granted;

    @BeforeEach
    void setUp() {
        config = new ChatConfigHandler.Config();
        config.customChatFormat.value = config.customChatFormat.get()
                .replace("<color:aqua>", "<color:#55FFFF>")
                .replace("<color:dark_gray>", "<color:#555555>")
                .replace("<color:white>", "<color:#FFFFFF>");
        placeholders = new Placeholders();
        platform = TestPlatformAdapter.create(placeholders, Map.of(SIMON.getUUID(), 42));
        Placeholders.setPingResolver(player -> 42);
        parser = new MessageParser(placeholders, platform);
        granted = new java.util.HashSet<>();
        Placeholders.setPermissionMetaResolver(player -> new Placeholders.PermissionMeta(
                "admin", "[Admin] ", "", List.of("admin", "default")));
    }

    @AfterEach
    void tearDown() {
        Placeholders.setNicknameResolver(null);
        Placeholders.setPermissionMetaResolver(null);
        Placeholders.setPingResolver(null);
    }

    private ChatFormatter formatter() {
        BiPredicate<IPlayer, String> permissions = (player, node) -> granted.contains(node);
        return new ChatFormatter(() -> config, () -> parser, () -> platform, permissions);
    }

    private static Optional<TestStyle> interactiveStyle(IComponent rendered) {
        return ((TestComponent) rendered).flatten().stream()
                .map(TestComponent::testStyle)
                .filter(style -> style.hover() != null || style.clickAction() != null)
                .findFirst();
    }

    @Test
    void groupOverridesKeepHoverClickAndEscapeMessageTags() {
        config.customChatFormat.value = "{prefix}{player_name} » {message}";
        config.playerNameClickAction.value = "suggest_command";
        config.playerNameClickValue.value = "/msg {player_name} ";
        var metadata = new eu.avalanche7.paradigm.modules.permissions.PermissionAPI.PermissionMeta(
                "vip", "[Admin] ", "", List.of("vip"),
                "<gradient:#ff0000:#0000ff><bold>{player_name}</bold></gradient>",
                "<color:#abcdef>{message}</color>", 10);
        var formatter = new ChatFormatter(() -> config, () -> parser, () -> platform,
                (player, node) -> granted.contains(node), player -> metadata);
        IComponent rendered = formatter.format(SIMON, "Hello <bold>literal</bold>");
        assertEquals("[Admin] Simon » Hello <bold>literal</bold>", rendered.getRawText());
        TestStyle style = interactiveStyle(rendered).orElseThrow();
        assertEquals("SUGGEST_COMMAND", style.clickAction());
        assertEquals("/msg Simon ", style.clickValue());
        assertNotNull(style.hover());
        assertTrue(((TestComponent) rendered).flatten().stream()
                .anyMatch(part -> "#ABCDEF".equalsIgnoreCase(part.testStyle().color())));
    }

    @Test
    void chatAndPlaceholdersUseTheSameInheritedMetadata(@TempDir Path directory) {
        var configPath = new IConfig() {
            @Override public Path getConfigDirectory() { return directory; }
            @Override public String getModId() { return "paradigm"; }
        };
        var permissions = new PermissionAPI(LoggerFactory.getLogger(getClass()), new DebugLogger(null),
                new PermissionDataStore(null, null, configPath), null);
        permissions.initialize();
        assertTrue(permissions.createGroup("hrac"));
        assertTrue(permissions.setGroupMetadata("default", "weight", "90"));
        assertTrue(permissions.setGroupMetadata("hrac", "weight", "100"));
        assertTrue(permissions.setGroupMetadata("hrac", "prefix", "<color:#44CC00>[H]</color> "));
        assertTrue(permissions.setGroupMetadata("hrac", "suffix", " !"));
        assertTrue(permissions.setGroupMetadata("hrac", "chatNameFormat", "<bold>{player_name}</bold>"));
        assertTrue(permissions.setGroupMetadata("hrac", "chatMessageFormat", "<color:#ABCDEF>{message}</color>"));
        assertTrue(permissions.addGroupParent("default", "hrac"));

        Placeholders.setPermissionMetaResolver(player -> {
            var meta = permissions.resolveMeta(player);
            return new Placeholders.PermissionMeta(meta.primaryGroup(), meta.prefix(), meta.suffix(), meta.groups());
        });
        config.customChatFormat.value = "{prefix}{player_name}{suffix} » {message}";
        var formatter = new ChatFormatter(() -> config, () -> parser, () -> platform,
                (player, node) -> false, permissions::resolveMeta);

        IComponent rendered = formatter.format(SIMON, "Hello");
        assertEquals("[H] Simon ! » Hello", rendered.getRawText());
        assertTrue(((TestComponent) rendered).flatten().stream()
                .anyMatch(part -> "#ABCDEF".equalsIgnoreCase(part.testStyle().color())));
        assertEquals("hrac|hrac|default,hrac|<color:#44CC00>[H]</color> | !",
                placeholders.replacePlaceholders("{player_group}|{player_primary_group}|{player_groups}|{player_prefix}|{player_suffix}", SIMON));
    }

    private ChatFormatter withGroupFormats(String prefix, String suffix, String name, String message) {
        Placeholders.setPermissionMetaResolver(player -> new Placeholders.PermissionMeta(
                "vip", prefix, suffix, List.of("vip")));
        var meta = new eu.avalanche7.paradigm.modules.permissions.PermissionAPI.PermissionMeta(
                "vip", prefix, suffix, List.of("vip"), name, message, 10);
        return new ChatFormatter(() -> config, () -> parser, () -> platform,
                (player, node) -> granted.contains(node), player -> meta);
    }

    private static void assertTextColor(IComponent rendered, String text, String color) {
        var parts = ((TestComponent) rendered).flatten().stream()
                .filter(part -> part.ownText().contains(text)).toList();
        assertTrue(!parts.isEmpty(), text);
        for (var part : parts) assertEquals(color.toUpperCase(), part.testStyle().color().toUpperCase(), text);
    }

    @Test
    void affixColorsOverrideDefaultNameAndMessageColorsWithoutDisplayingSuffix() {
        var rendered = withGroupFormats("&6[VIP] &a", "&d", "", "").format(SIMON, "Hello");
        assertEquals("[VIP] Simon » Hello", rendered.getRawText());
        assertTextColor(rendered, "Simon", "#55FF55");
        assertTextColor(rendered, "Hello", "#FF55FF");
        assertTextColor(rendered, "»", "#555555");
        assertEquals("/msg Simon ", interactiveStyle(rendered).orElseThrow().clickValue());
        assertNotNull(interactiveStyle(rendered).orElseThrow().hover());
    }

    @Test
    void explicitGroupFormatsWinOverAffixColorsAndDefaults() {
        var rendered = withGroupFormats("&a[VIP] ", "&d", "<color:#FF5555>{player_name}</color>",
                "<color:#FFFF55>{message}</color>").format(SIMON, "Hello");
        assertTextColor(rendered, "Simon", "#FF5555");
        assertTextColor(rendered, "Hello", "#FFFF55");
    }

    @Test
    void emptyResetAndClosedAffixColorsKeepConfiguredDefaults() {
        for (String affix : List.of("", "[VIP] ", "&a[VIP]&r ", "<color:red>[VIP]</color> ",
                "<bold><color:red>[VIP]</color></bold> ", "<color:red>[VIP]<reset> ")) {
            var rendered = withGroupFormats(affix, affix, "", "").format(SIMON, "Hello");
            assertTextColor(rendered, "Simon", "#55FFFF");
            assertTextColor(rendered, "Hello", "#FFFFFF");
        }
    }

    @Test
    void hexSectionCodesAndOpenColorTagsOverrideDefaults() {
        for (String affix : List.of("&#123abc", "§#123abc", "<color:#123abc>", "<c:#123abc>")) {
            var rendered = withGroupFormats(affix, affix, "", "").format(SIMON, "Hello");
            assertTextColor(rendered, "Simon", "#123ABC");
            assertTextColor(rendered, "Hello", "#123ABC");
        }
        var rendered = withGroupFormats("§c", "<color:green>", "", "").format(SIMON, "Hello");
        assertTextColor(rendered, "Simon", "#FF5555");
        assertTextColor(rendered, "Hello", "#55FF55");
    }

    @Test
    void groupColorsOverridePlayerNameFormatButPreserveNicknamesAndPlayerMessageColors() {
        config.playerNameFormat.value = "<color:red><bold>{player_name}</bold></color>";
        Placeholders.setNicknameResolver(player -> "Creator_123");
        var rendered = withGroupFormats("&a", "&d", "", "").format(SIMON, "Hello &eYellow <bold>literal</bold>");
        assertTextColor(rendered, "Creator_123", "#55FF55");
        assertTextColor(rendered, "Hello", "#FF55FF");
        assertTextColor(rendered, "Yellow", "#FFFF55");
        assertTrue(rendered.getRawText().contains("<bold>literal</bold>"));
        assertEquals("/msg Simon ", interactiveStyle(rendered).orElseThrow().clickValue());
    }

    @Test
    void inactiveInvalidAndEscapedAffixColorsDoNotOverrideDefaults() {
        for (String affix : List.of("<color:invalid>", "<color:#12345>", "<color:red/>",
                "\\&a", "\\<color:red>", "&l", "<bold>")) {
            var rendered = withGroupFormats(affix, affix, "", "").format(SIMON, "Hello");
            assertTextColor(rendered, "Simon", "#55FFFF");
            assertTextColor(rendered, "Hello", "#FFFFFF");
        }
    }

    @Test
    void closingNestedColorsRestoresEarlierAffixColors() {
        var rendered = withGroupFormats("<color:green><bold><color:red>[VIP]</color></bold>",
                "&d<color:red>label</color>", "", "").format(SIMON, "Hello");
        assertTextColor(rendered, "Simon", "#55FF55");
        assertTextColor(rendered, "Hello", "#FF55FF");
    }

    @Test
    void trailingColorUsesTheSameStateAsRenderingMixedLegacyAndScopedTags() {
        for (String affix : List.of("&a", "&#123abc", "<color:red>&aLabel</color>",
                "&d<color:red>label</color>", "<color:green><bold>x</bold>", "&a&r", "<color:red>x</color>")) {
            String color = parser.trailingColor(affix, SIMON);
            var slots = eu.avalanche7.paradigm.utils.formatting.ComponentSlots.builder()
                    .add("{probe}", style -> new TestComponent("PROBE").setStyle(style)).build();
            var rendered = (TestComponent) parser.parseMessage(slots.mark(affix + "{probe}"), SIMON, slots);
            var actual = rendered.flatten().stream().filter(part -> part.ownText().equals("PROBE"))
                    .findFirst().orElseThrow().testStyle().color();
            String expected = color == null ? null : ((TestComponent) new TestComponent("").withColor(color)).testStyle().color();
            assertEquals(expected == null ? null : expected.toLowerCase(java.util.Locale.ROOT),
                    actual == null ? null : actual.toLowerCase(java.util.Locale.ROOT), affix);
        }
    }

    @Test
    void metadataChangesAreAppliedWithoutRecompilingTheChatTemplate() {
        var meta = new java.util.concurrent.atomic.AtomicReference<>(
                new eu.avalanche7.paradigm.modules.permissions.PermissionAPI.PermissionMeta(
                        "vip", "&a", "&d", List.of("vip")));
        var formatter = new ChatFormatter(() -> config, () -> parser, () -> platform,
                (player, node) -> false, player -> meta.get());
        assertTextColor(formatter.format(SIMON, "Hello"), "Simon", "#55FF55");
        meta.set(new eu.avalanche7.paradigm.modules.permissions.PermissionAPI.PermissionMeta(
                "vip", "&c", "&e", List.of("vip")));
        var rendered = formatter.format(SIMON, "Hello");
        assertTextColor(rendered, "Simon", "#FF5555");
        assertTextColor(rendered, "Hello", "#FFFF55");
        meta.set(null);
        assertTextColor(formatter.format(SIMON, "Hello"), "Simon", "#55FFFF");
    }

    @Test
    void nicknameOnlyChangesRenderedNameAndPreservesCanonicalClickAndStyles() {
        Placeholders.setNicknameResolver(player -> "Creator_123");
        config.customChatFormat.value = "{prefix}{player_name} » {message}";
        config.playerNameFormat.value = "<bold>{player_name}</bold>";
        config.playerNameClickAction.value = "suggest_command";
        config.playerNameClickValue.value = "/msg {player_name} ";
        var rendered = formatter().format(SIMON, "Hello");
        assertEquals("[Admin] Creator_123 » Hello", rendered.getRawText());
        assertEquals("/msg Simon ", interactiveStyle(rendered).orElseThrow().clickValue());
        assertEquals("Simon|Creator_123|Simon", placeholders.replacePlaceholders("{player_name}|{display_name}|{real_name}", SIMON));
        assertEquals("Simon", SIMON.getName());
    }

    @Test
    void customFormattingDisabledLeavesChatToVanilla() {
        config.enableCustomChatFormat.value = false;

        assertNull(formatter().format(SIMON, "Hello"));
    }

    @Test
    void existingChatFormatKeepsRenderingWhenPlayerNameFeaturesAreOff() {
        config.enablePlayerNameHover.value = false;
        config.playerNameClickAction.value = "none";
        config.customChatFormat.value = "{prefix}{player_name} » {message}";

        IComponent rendered = formatter().format(SIMON, "Hello");

        assertEquals("[Admin] Simon » Hello", rendered.getRawText());
        assertTrue(interactiveStyle(rendered).isEmpty());
    }

    @Test
    void hoverEnabledAttachesAMultilineHoverToTheNameOnly() {
        config.customChatFormat.value = "{prefix}{player_name} » {message}";

        IComponent rendered = formatter().format(SIMON, "Hello");

        assertEquals("[Admin] Simon » Hello", rendered.getRawText());
        TestStyle style = interactiveStyle(rendered).orElseThrow();
        assertEquals("Simon\n                    \nGroup » admin\nWorld » overworld\nPing » 42ms", style.hoverText());
    }

    @Test
    void hoverLinesAreParsedSeparatelySoUnclosedTagsDoNotLeak() {
        config.playerNameHover.value = new ArrayList<>(List.of("<bold>Bold", "Plain"));

        IComponent rendered = formatter().format(SIMON, "Hello");
        IComponent hover = interactiveStyle(rendered).orElseThrow().hover();

        List<TestComponent> parts = ((TestComponent) hover).flatten();
        assertTrue(parts.stream().anyMatch(part -> part.ownText().equals("Bold") && part.testStyle().tokens().contains("bold")));
        assertTrue(parts.stream().anyMatch(part -> part.ownText().equals("Plain") && part.testStyle().tokens().isEmpty()));
    }

    @Test
    void nameKeepsSurroundingColorWhileGainingHover() {
        config.customChatFormat.value = "<color:#55FFFF>{player_name}</color> » {message}";

        TestStyle style = interactiveStyle(formatter().format(SIMON, "Hello")).orElseThrow();

        assertEquals("#55FFFF", style.color());
        assertNotNull(style.hover());
    }

    @Test
    void clickActionIsAppliedWithPlaceholdersResolved() {
        config.enablePlayerNameHover.value = false;
        config.playerNameClickAction.value = "suggest_command";
        config.playerNameClickValue.value = "/msg {player_name} ";

        TestStyle style = interactiveStyle(formatter().format(SIMON, "Hello")).orElseThrow();

        assertEquals("SUGGEST_COMMAND", style.clickAction());
        assertEquals("/msg Simon ", style.clickValue());
    }

    @Test
    void malformedClickActionIsIgnoredRatherThanBreakingChat() {
        config.enablePlayerNameHover.value = false;
        config.playerNameClickAction.value = "open_url";
        config.playerNameClickValue.value = "https://example.invalid";
        config.customChatFormat.value = "{player_name} » {message}";

        IComponent rendered = formatter().format(SIMON, "Hello");

        assertEquals("Simon » Hello", rendered.getRawText());
        assertTrue(interactiveStyle(rendered).isEmpty());
    }

    @Test
    void hoverVariantsResolveInOrderAndFallBackToTheDefault() {
        config.playerNameHover.value = new ArrayList<>(List.of("default"));
        config.playerNameHoverVariants.value = new ArrayList<>(List.of(
                new ChatConfigHandler.PlayerNameHoverVariant("paradigm.chat.owner-hover", List.of("owner")),
                new ChatConfigHandler.PlayerNameHoverVariant("paradigm.chat.staff-hover", List.of("staff"))));

        assertEquals("default", interactiveStyle(formatter().format(SIMON, "Hi")).orElseThrow().hoverText());

        granted.add("paradigm.chat.staff-hover");
        assertEquals("staff", interactiveStyle(formatter().format(SIMON, "Hi")).orElseThrow().hoverText());

        granted.add("paradigm.chat.owner-hover");
        assertEquals("owner", interactiveStyle(formatter().format(SIMON, "Hi")).orElseThrow().hoverText());
    }

    @Test
    void aNameTokenTypedIntoTheMessageDoesNotBecomeASecondInteractiveName() {
        config.customChatFormat.value = "{player_name} » {message}";

        IComponent rendered = formatter().format(SIMON, "look at {player_name}");

        assertEquals("Simon » look at Simon", rendered.getRawText());
        String interactiveText = ((TestComponent) rendered).flatten().stream()
                .filter(part -> part.testStyle().hover() != null)
                .map(TestComponent::ownText)
                .reduce("", String::concat);
        assertEquals("Simon", interactiveText);
    }

    @Test
    void slotMarkersTypedIntoTheMessageCannotForgeASecondInteractiveName() {
        config.customChatFormat.value = "{player_name} » {message}";
        String injected = "hey look: " + (char) 0xE010 + "0" + (char) 0xE011 + " not the name";

        IComponent rendered = formatter().format(SIMON, injected);

        assertEquals("Simon » hey look: 0 not the name", rendered.getRawText());
        String interactiveText = ((TestComponent) rendered).flatten().stream()
                .filter(part -> part.testStyle().hover() != null)
                .map(TestComponent::ownText)
                .reduce("", String::concat);
        assertEquals("Simon", interactiveText);
    }

    @Test
    void perCharacterTagsKeepTheNameHoverInsteadOfFlatteningItAway() {
        for (String wrapper : List.of("<gradient:red:blue>{player_name}</gradient>", "<rainbow>{player_name}</rainbow>")) {
            config.customChatFormat.value = wrapper + " » {message}";

            IComponent rendered = formatter().format(SIMON, "Hello");

            assertEquals("Simon » Hello", rendered.getRawText(), wrapper);
            assertNotNull(interactiveStyle(rendered).orElseThrow().hover(), wrapper);
        }
    }

    @Test
    void tagsTypedByAPlayerAreShownLiterallyInsteadOfBecomingComponents() {
        config.enablePlayerNameHover.value = false;
        config.playerNameClickAction.value = "none";
        config.customChatFormat.value = "{player_name} » {message}";

        for (String typed : List.of(
                "<click:execute:kill @a>free diamonds</click>",
                "<click:run_command:/kill @a>free diamonds</click>",
                "<click:suggest:/op Mallory>click me</click>",
                "<hover:'gotcha'>x</hover>",
                "\\<click:execute:kill @a>bypass</click>")) {
            IComponent rendered = formatter().format(SIMON, typed);

            assertTrue(interactiveStyle(rendered).isEmpty(), typed);
            assertTrue(rendered.getRawText().contains("<"), typed);
        }
    }

    @Test
    void playerTypedColorTagsAreNotInterpretedButLegacyCodesStillWork() {
        config.enablePlayerNameHover.value = false;
        config.playerNameClickAction.value = "none";
        config.customChatFormat.value = "{player_name} » {message}";

        IComponent tagged = formatter().format(SIMON, "<color:red>nope</color>");
        assertEquals("Simon » <color:red>nope</color>", tagged.getRawText());

        IComponent legacy = formatter().format(SIMON, "&cstill red");
        assertEquals("Simon » still red", legacy.getRawText());
        assertTrue(((TestComponent) legacy).flatten().stream()
                .anyMatch(part -> "#FF5555".equals(part.testStyle().color())));
    }

    @Test
    void configuredHoverAndClickStillUseTagsBecauseTheyComeFromTheAdmin() {
        config.playerNameHover.value = new ArrayList<>(List.of("<color:red>Staff</color>"));

        TestStyle style = interactiveStyle(formatter().format(SIMON, "hi")).orElseThrow();

        assertEquals("Staff", style.hoverText());
    }

    @Test
    void compiledTemplateIsReusedUntilConfigurationChanges() {
        ChatFormatter formatter = formatter();

        formatter.format(SIMON, "Hello");
        assertEquals("Simon\n                    \nGroup » admin\nWorld » overworld\nPing » 42ms",
                interactiveStyle(formatter.format(SIMON, "Hello")).orElseThrow().hoverText());

        config.playerNameHover.value = new ArrayList<>(List.of("<color:white>{player_name}</color>"));
        assertEquals("Simon", interactiveStyle(formatter.format(SIMON, "Hello")).orElseThrow().hoverText());
    }
}
