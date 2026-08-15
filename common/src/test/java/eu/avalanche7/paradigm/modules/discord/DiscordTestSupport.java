package eu.avalanche7.paradigm.modules.discord;

import java.lang.reflect.Field;
import java.lang.reflect.InvocationHandler;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;

import eu.avalanche7.paradigm.configs.ConfigEntry;
import eu.avalanche7.paradigm.configs.DiscordConfigHandler;
import eu.avalanche7.paradigm.core.Services;
import eu.avalanche7.paradigm.platform.Interfaces.IComponent;
import eu.avalanche7.paradigm.platform.Interfaces.IConfig;
import eu.avalanche7.paradigm.platform.Interfaces.IPlatformAdapter;
import eu.avalanche7.paradigm.platform.Interfaces.IPlayer;
import eu.avalanche7.paradigm.support.TestComponent;
import eu.avalanche7.paradigm.support.TestStyle;
import eu.avalanche7.paradigm.utils.DebugLogger;
import eu.avalanche7.paradigm.utils.MessageParser;
import eu.avalanche7.paradigm.utils.Placeholders;

final class DiscordTestSupport {
    private DiscordTestSupport() {
    }

    static DiscordConfigHandler.Config freshConfig(Path tempDir) {
        if (!DiscordConfigHandler.isInitialized()) {
            DiscordConfigHandler.init(configDirectory(tempDir), new DebugLogger(null));
        }
        DiscordConfigHandler.Config config = DiscordConfigHandler.getConfig();
        copyDefaults(config);
        return config;
    }

    @SuppressWarnings("unchecked")
    private static void copyDefaults(DiscordConfigHandler.Config target) {
        DiscordConfigHandler.Config defaults = new DiscordConfigHandler.Config();
        for (Field field : DiscordConfigHandler.Config.class.getDeclaredFields()) {
            if (field.getType() != ConfigEntry.class) {
                continue;
            }
            try {
                field.setAccessible(true);
                ConfigEntry<Object> targetEntry = (ConfigEntry<Object>) field.get(target);
                ConfigEntry<?> defaultEntry = (ConfigEntry<?>) field.get(defaults);
                targetEntry.value = defaultEntry.value;
            } catch (ReflectiveOperationException failure) {
                throw new IllegalStateException("Could not reset Discord config field " + field.getName(), failure);
            }
        }
    }

    static DiscordConfigHandler.Config enabledConfig(Path tempDir) {
        DiscordConfigHandler.Config config = freshConfig(tempDir);
        config.enabled.value = true;
        config.botToken.value = "test-token-value-1234567890";
        config.chatChannelId.value = "100000000000000001";
        config.moderationChannelId.value = "100000000000000002";
        config.notificationChannelId.value = "100000000000000003";
        return config;
    }

    static IConfig configDirectory(Path tempDir) {
        return new IConfig() {
            @Override
            public Path getConfigDirectory() {
                return tempDir;
            }

            @Override
            public Path resolveConfigPath(String relative) {
                return tempDir.resolve(relative);
            }

            @Override
            public String getModId() {
                return "paradigm";
            }
        };
    }

    static final class RecordingEventSystem implements eu.avalanche7.paradigm.platform.Interfaces.IEventSystem {
        private final List<ChatEventListener> chat = new ArrayList<>();
        private final List<PlayerJoinEventListener> join = new ArrayList<>();
        private final List<PlayerLeaveEventListener> leave = new ArrayList<>();

        @Override
        public void onPlayerChat(ChatEventListener listener) {
            chat.add(listener);
        }

        @Override
        public void onPlayerJoin(PlayerJoinEventListener listener) {
            join.add(listener);
        }

        @Override
        public void onPlayerLeave(PlayerLeaveEventListener listener) {
            leave.add(listener);
        }

        void fireChat(IPlayer player, String message, boolean preCancelled) {
            MutableChatEvent event = new MutableChatEvent(player, message, preCancelled);
            for (ChatEventListener listener : chat) {
                listener.onPlayerChat(event);
                if (event.isCancelled()) {
                    return;
                }
            }
        }

        void fireJoin(IPlayer player) {
            for (PlayerJoinEventListener listener : join) {
                listener.onPlayerJoin(new PlayerJoinEvent() {
                    @Override
                    public IPlayer getPlayer() {
                        return player;
                    }

                    @Override
                    public IComponent getJoinMessage() {
                        return null;
                    }

                    @Override
                    public void setJoinMessage(IComponent message) {
                    }
                });
            }
        }

        void fireLeave(IPlayer player) {
            for (PlayerLeaveEventListener listener : leave) {
                listener.onPlayerLeave(new PlayerLeaveEvent() {
                    @Override
                    public IPlayer getPlayer() {
                        return player;
                    }

                    @Override
                    public IComponent getLeaveMessage() {
                        return null;
                    }

                    @Override
                    public void setLeaveMessage(IComponent message) {
                    }
                });
            }
        }

        private static final class MutableChatEvent implements ChatEvent {
            private final IPlayer player;
            private String message;
            private boolean cancelled;

            private MutableChatEvent(IPlayer player, String message, boolean cancelled) {
                this.player = player;
                this.message = message;
                this.cancelled = cancelled;
            }

            @Override
            public IPlayer getPlayer() {
                return player;
            }

            @Override
            public String getMessage() {
                return message;
            }

            @Override
            public void setMessage(String message) {
                this.message = message;
            }

            @Override
            public boolean isCancelled() {
                return cancelled;
            }

            @Override
            public void setCancelled(boolean cancelled) {
                this.cancelled = cancelled;
            }
        }
    }

    static final class RecordingPlatform {
        final List<String> broadcasts = new ArrayList<>();
        final List<IComponent> broadcastComponents = new ArrayList<>();
        final List<String> executedConsoleCommands = new ArrayList<>();
        final AtomicReference<Integer> onlineCount = new AtomicReference<>(3);
        final RecordingEventSystem events = new RecordingEventSystem();
        final IPlatformAdapter adapter;

        RecordingPlatform(Placeholders placeholders) {
            InvocationHandler handler = (proxy, method, args) -> switch (method.getName()) {
                case "createLiteralComponent", "createComponentFromLiteral" -> new TestComponent((String) args[0]);
                case "createEmptyComponent" -> new TestComponent("");
                case "replacePlaceholders" -> placeholders.replacePlaceholders((String) args[0], (IPlayer) args[1]);
                case "createStyleWithClickEvent" -> style(args[0]).withClick((String) args[1], (String) args[2]);
                case "createStyleWithHoverEvent" -> style(args[0]).withHover(args[1] instanceof IComponent component
                        ? component
                        : new TestComponent(String.valueOf(args[1])));
                case "executeOnServerThread" -> {
                    ((Runnable) args[0]).run();
                    yield null;
                }
                case "executeCommandAsConsole" -> {
                    executedConsoleCommands.add((String) args[0]);
                    yield null;
                }
                case "broadcastSystemMessage", "broadcastChatMessage" -> {
                    if (args[0] instanceof IComponent component) {
                        broadcasts.add(component.getRawText());
                        broadcastComponents.add(component);
                    } else {
                        broadcasts.add(String.valueOf(args[0]));
                    }
                    yield null;
                }
                case "getOnlinePlayers" -> java.util.Collections.nCopies(onlineCount.get(), null);
                case "getMaxPlayers" -> 20;
                case "getEventSystem" -> events;
                case "hashCode" -> System.identityHashCode(proxy);
                case "equals" -> proxy == args[0];
                case "toString" -> "RecordingPlatform";
                default -> method.isDefault() ? InvocationHandler.invokeDefault(proxy, method, args) : fallback(method);
            };
            this.adapter = (IPlatformAdapter) Proxy.newProxyInstance(DiscordTestSupport.class.getClassLoader(),
                    new Class<?>[] { IPlatformAdapter.class }, handler);
        }
    }

    static final class RecordingOutbox implements DiscordOutbox {
        final List<DiscordMessage> messages = new ArrayList<>();
        final java.util.Set<String> ownWebhookIds = new java.util.HashSet<>();
        final List<String[]> auditedConsoleCommands = new ArrayList<>();
        private final DiscordConfigHandler.Config config;
        String botUserId = "999000111222333444";

        RecordingOutbox(DiscordConfigHandler.Config config) {
            this.config = config;
        }

        @Override
        public DiscordConfigHandler.Config config() {
            return config;
        }

        @Override
        public boolean isEnabled() {
            return Boolean.TRUE.equals(config.enabled.get());
        }

        @Override
        public String botUserId() {
            return botUserId;
        }

        @Override
        public boolean isOwnWebhook(String webhookId) {
            return webhookId != null && ownWebhookIds.contains(webhookId);
        }

        @Override
        public void auditConsoleCommand(String actorId, String actorName, String command) {
            auditedConsoleCommands.add(new String[] { actorId, actorName, command });
        }

        @Override
        public boolean send(DiscordMessage message) {
            messages.add(message);
            return true;
        }

        @Override
        public boolean sendNotification(String content, int colorRgb, String title) {
            return send(new DiscordMessage(DiscordDestination.NOTIFICATIONS, content, null, false, null));
        }

        @Override
        public boolean sendServer(String content, int colorRgb, String title) {
            return send(new DiscordMessage(DiscordDestination.SERVER, content, null, false, null));
        }

        @Override
        public boolean sendModeration(String content, int colorRgb, String title, String dedupeKey) {
            return send(new DiscordMessage(DiscordDestination.MODERATION, content, null, false, dedupeKey));
        }

        List<DiscordMessage> to(DiscordDestination destination) {
            return messages.stream().filter(message -> message.destination() == destination).toList();
        }
    }

    static Services services(IPlatformAdapter platform, Placeholders placeholders) {
        MessageParser parser = new MessageParser(placeholders, platform);
        return new Services(null, null, null, null, null, null, null, null, null,
                new DebugLogger(null), null, parser, null, placeholders, null, null, null, null, null, null, platform);
    }

    static Map<String, String> noMentions() {
        return Map.of();
    }

    private static TestStyle style(Object base) {
        return base instanceof TestStyle typed ? typed : TestStyle.EMPTY;
    }

    private static Object fallback(Method method) {
        Class<?> type = method.getReturnType();
        if (type == boolean.class) return false;
        if (type == int.class) return 0;
        if (type == long.class) return 0L;
        if (type == double.class) return 0.0d;
        if (type == float.class) return 0.0f;
        return null;
    }
}
