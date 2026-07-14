package eu.avalanche7.paradigm.api.internal;

import eu.avalanche7.paradigm.api.ApiCapability;
import eu.avalanche7.paradigm.api.MessageResult;
import eu.avalanche7.paradigm.api.MessageService;
import eu.avalanche7.paradigm.api.PermissionContext;
import eu.avalanche7.paradigm.api.PermissionDecision;
import eu.avalanche7.paradigm.api.PermissionNodeDefinition;
import eu.avalanche7.paradigm.api.PermissionService;
import eu.avalanche7.paradigm.api.PlaceholderService;
import eu.avalanche7.paradigm.api.PlayerPermissionMeta;
import eu.avalanche7.paradigm.api.Registration;
import eu.avalanche7.paradigm.api.RegistrationStatus;

import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;

/** Internal lifecycle holder behind the stable public facade. */
public final class ApiProviderRegistry {
    private static final ApiProvider UNAVAILABLE = new UnavailableProvider();
    private static final AtomicReference<ApiProvider> CURRENT = new AtomicReference<>(UNAVAILABLE);

    private ApiProviderRegistry() {
    }

    public static ApiProvider provider() {
        return CURRENT.get();
    }

    public static void install(ApiProvider provider) {
        if (provider == null) throw new IllegalArgumentException("provider cannot be null");
        ApiProvider previous = CURRENT.getAndSet(provider);
        if (previous != UNAVAILABLE && previous != provider) previous.close();
    }

    public static void uninstall() {
        ApiProvider previous = CURRENT.getAndSet(UNAVAILABLE);
        if (previous != UNAVAILABLE) previous.close();
    }

    public static String resolveExternalPlaceholders(String text, UUID playerUuid) {
        return CURRENT.get().resolveExternalPlaceholders(text, playerUuid);
    }

    private static final class UnavailableProvider implements ApiProvider {
        private final PermissionService permissions = new PermissionService() {
            @Override public PermissionDecision check(UUID playerUuid, String node, PermissionContext context) { return PermissionDecision.UNDEFINED; }
            @Override public boolean hasPermission(UUID playerUuid, String node, int level, PermissionContext context) { return false; }
            @Override public PlayerPermissionMeta metadata(UUID playerUuid) { return PlayerPermissionMeta.EMPTY; }
            @Override public Registration registerPermissionNode(String owner, PermissionNodeDefinition definition) {
                return SimpleRegistration.inactive(owner, definition != null ? definition.node() : "", RegistrationStatus.API_UNAVAILABLE);
            }
        };
        private final MessageService messages = new MessageService() {
            @Override public MessageResult sendPlayerMessage(UUID player, String template, Map<String, String> values) {
                return MessageResult.API_UNAVAILABLE;
            }
            @Override public MessageResult broadcastMessage(String template, Map<String, String> values) {
                return MessageResult.API_UNAVAILABLE;
            }
        };
        private final PlaceholderService placeholders = (owner, key, resolver) ->
                SimpleRegistration.inactive(owner, key, RegistrationStatus.API_UNAVAILABLE);

        @Override public boolean available() { return false; }
        @Override public String modVersion() { return "unavailable"; }
        @Override public Set<ApiCapability> capabilities() { return Set.of(); }
        @Override public PermissionService permissions() { return permissions; }
        @Override public MessageService messages() { return messages; }
        @Override public PlaceholderService placeholders() { return placeholders; }
        @Override public String resolveExternalPlaceholders(String text, UUID playerUuid) { return text != null ? text : ""; }
        @Override public void close() { }
    }
}
