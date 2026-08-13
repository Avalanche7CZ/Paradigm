package eu.avalanche7.paradigm.modules.chat;

import java.util.ArrayList;
import java.util.List;
import java.util.function.BiPredicate;
import java.util.function.Supplier;

import eu.avalanche7.paradigm.configs.ChatConfigHandler;
import eu.avalanche7.paradigm.core.Services;
import eu.avalanche7.paradigm.platform.Interfaces.IComponent;
import eu.avalanche7.paradigm.platform.Interfaces.IPlatformAdapter;
import eu.avalanche7.paradigm.platform.Interfaces.IPlayer;
import eu.avalanche7.paradigm.utils.MessageParser;
import eu.avalanche7.paradigm.utils.formatting.ComponentSlot;
import eu.avalanche7.paradigm.utils.formatting.ComponentSlots;

public final class ChatFormatter {

    static final String NAME_TOKEN = "{player_name}";
    private static final String SHORT_NAME_TOKEN = "{player}";

    private final Supplier<ChatConfigHandler.Config> configSupplier;
    private final Supplier<MessageParser> parserSupplier;
    private final Supplier<IPlatformAdapter> platformSupplier;
    private final BiPredicate<IPlayer, String> permissions;
    private volatile Template template;

    public ChatFormatter(Services services) {
        this(services::getChatConfig, services::getMessageParser, services::getPlatformAdapter,
                (player, permission) -> services.getPermissionsHandler().hasPermission(player, permission));
    }

    ChatFormatter(Supplier<ChatConfigHandler.Config> configSupplier, Supplier<MessageParser> parserSupplier,
                  Supplier<IPlatformAdapter> platformSupplier, BiPredicate<IPlayer, String> permissions) {
        this.configSupplier = configSupplier;
        this.parserSupplier = parserSupplier;
        this.platformSupplier = platformSupplier;
        this.permissions = permissions;
    }

    public IComponent format(IPlayer sender, String message) {
        ChatConfigHandler.Config config = configSupplier.get();
        if (config == null || !Boolean.TRUE.equals(config.enableCustomChatFormat.get())) {
            return null;
        }
        String chatFormat = config.customChatFormat.get();
        if (chatFormat == null || chatFormat.isEmpty()) {
            return null;
        }

        Template compiled = template(config);
        MessageParser parser = parserSupplier.get();

        ComponentSlots slots = ComponentSlots.none();
        if (sender != null && compiled.interactive()) {
            ComponentSlot name = style -> renderName(sender, style, compiled);
            slots = ComponentSlots.builder()
                    .add(NAME_TOKEN, name)
                    .add(SHORT_NAME_TOKEN, name)
                    .build();
        }

        String marked = slots.mark(chatFormat);
        String withMessage = marked.replace("{message}", escapeTags(message));
        return parser.parseMessage(withMessage, sender, slots);
    }

    static String escapeTags(String message) {
        return ComponentSlots.strip(message).replace("\\", "\\\\").replace("<", "\\<");
    }

    private IComponent renderName(IPlayer sender, Object surroundingStyle, Template compiled) {
        IPlatformAdapter platform = platformSupplier.get();
        MessageParser parser = parserSupplier.get();
        Object style = surroundingStyle;

        List<String> hoverLines = compiled.hoverLinesFor(sender, permissions);
        if (!hoverLines.isEmpty()) {
            IComponent hover = renderHover(hoverLines, sender, parser, platform);
            style = platform.createStyleWithHoverEvent(style, hover.getOriginalText());
        }

        PlayerNameClickAction.Spec click = compiled.click();
        if (click.enabled()) {
            String command = platform.replacePlaceholders(click.value(), sender);
            style = platform.createStyleWithClickEvent(style, click.type().platformAction(), command);
        }

        return parser.parseNested(compiled.nameFormat(), sender, style);
    }

    private IComponent renderHover(List<String> lines, IPlayer sender, MessageParser parser, IPlatformAdapter platform) {
        IComponent hover = platform.createEmptyComponent();
        for (int index = 0; index < lines.size(); index++) {
            if (index > 0) {
                hover.append(platform.createLiteralComponent("\n"));
            }
            hover.append(parser.parseMessage(lines.get(index), sender));
        }
        return hover;
    }

    private Template template(ChatConfigHandler.Config config) {
        Template current = this.template;
        if (current != null && current.matches(config)) {
            return current;
        }
        Template rebuilt = Template.compile(config);
        this.template = rebuilt;
        return rebuilt;
    }

    record Template(String nameFormat, List<String> hoverLines,
                    List<Variant> variants, PlayerNameClickAction.Spec click, List<Object> signature) {

        record Variant(String permission, List<String> hoverLines) {
        }

        static Template compile(ChatConfigHandler.Config config) {
            boolean hoverEnabled = Boolean.TRUE.equals(config.enablePlayerNameHover.get());
            String nameFormat = config.playerNameFormat.get();
            if (nameFormat == null || nameFormat.isBlank()) {
                nameFormat = NAME_TOKEN;
            }
            List<String> hoverLines = hoverEnabled ? lines(config.playerNameHover.get()) : List.of();

            List<Variant> variants = new ArrayList<>();
            if (hoverEnabled && config.playerNameHoverVariants.get() != null) {
                for (ChatConfigHandler.PlayerNameHoverVariant variant : config.playerNameHoverVariants.get()) {
                    if (variant == null || variant.permission == null || variant.permission.isBlank()) {
                        continue;
                    }
                    List<String> variantLines = lines(variant.hover);
                    if (!variantLines.isEmpty()) {
                        variants.add(new Variant(variant.permission.trim(), variantLines));
                    }
                }
            }

            PlayerNameClickAction.Spec click = PlayerNameClickAction.validateOrDisabled(
                    config.playerNameClickAction.get(), config.playerNameClickValue.get());

            return new Template(nameFormat, hoverLines, List.copyOf(variants), click, signature(config));
        }

        boolean matches(ChatConfigHandler.Config config) {
            return signature.equals(signature(config));
        }

        boolean interactive() {
            return click.enabled() || !hoverLines.isEmpty() || !variants.isEmpty();
        }

        List<String> hoverLinesFor(IPlayer sender, BiPredicate<IPlayer, String> permissions) {
            for (Variant variant : variants) {
                if (holdsPermission(sender, variant.permission(), permissions)) {
                    return variant.hoverLines();
                }
            }
            return hoverLines;
        }

        private static boolean holdsPermission(IPlayer sender, String permission, BiPredicate<IPlayer, String> permissions) {
            try {
                return permissions.test(sender, permission);
            } catch (RuntimeException | LinkageError unavailable) {
                return false;
            }
        }

        private static List<Object> signature(ChatConfigHandler.Config config) {
            List<Object> values = new ArrayList<>();
            values.add(config.enablePlayerNameHover.get());
            values.add(config.playerNameFormat.get());
            values.add(lines(config.playerNameHover.get()));
            values.add(config.playerNameClickAction.get());
            values.add(config.playerNameClickValue.get());
            List<ChatConfigHandler.PlayerNameHoverVariant> variants = config.playerNameHoverVariants.get();
            if (variants != null) {
                for (ChatConfigHandler.PlayerNameHoverVariant variant : variants) {
                    values.add(variant == null ? null : variant.permission);
                    values.add(variant == null ? List.of() : lines(variant.hover));
                }
            }
            return values;
        }

        private static List<String> lines(List<String> raw) {
            if (raw == null || raw.isEmpty()) {
                return List.of();
            }
            List<String> result = new ArrayList<>(raw.size());
            for (String line : raw) {
                result.add(line != null ? line : "");
            }
            return List.copyOf(result);
        }
    }
}
