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
import eu.avalanche7.paradigm.core.Services;
import eu.avalanche7.paradigm.modules.permissions.PermissionAPI;
import eu.avalanche7.paradigm.modules.permissions.PermissionNodeRegistry;
import eu.avalanche7.paradigm.modules.permissions.context.PermissionContextSet;
import eu.avalanche7.paradigm.platform.Interfaces.IPlatformAdapter;
import eu.avalanche7.paradigm.platform.Interfaces.IPlayer;
import eu.avalanche7.paradigm.utils.LiteralPlaceholders;

import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;

/** Adapts stable API contracts to the current internal services. */
public final class ParadigmApiProvider implements ApiProvider {
    private static final Set<ApiCapability> CAPABILITIES = Set.of(
            ApiCapability.PERMISSION_CHECKS,
            ApiCapability.PERMISSION_NODE_REGISTRATION,
            ApiCapability.MESSAGE_DELIVERY_AND_FORMATTING,
            ApiCapability.PERMISSION_METADATA,
            ApiCapability.EXTERNAL_PLACEHOLDERS
    );

    private final Services services;
    private final String modVersion;
    private final ExternalPlaceholderRegistry externalPlaceholders;
    private final AtomicBoolean active = new AtomicBoolean(true);
    private final PermissionService permissions = new Permissions();
    private final MessageService messages = new Messages();
    private final PlaceholderService placeholders = this::registerPlaceholder;

    public ParadigmApiProvider(Services services, String modVersion) {
        if (services == null) throw new IllegalArgumentException("services cannot be null");
        this.services = services;
        this.modVersion = modVersion != null ? modVersion : "unknown";
        this.externalPlaceholders = new ExternalPlaceholderRegistry(services.getLogger());
    }

    @Override public boolean available() { return active.get(); }
    @Override public String modVersion() { return modVersion; }
    @Override public Set<ApiCapability> capabilities() { return active.get() ? CAPABILITIES : Set.of(); }
    @Override public PermissionService permissions() { return permissions; }
    @Override public MessageService messages() { return messages; }
    @Override public PlaceholderService placeholders() { return placeholders; }

    @Override
    public String resolveExternalPlaceholders(String text, UUID playerUuid) {
        return active.get() ? externalPlaceholders.resolve(text, playerUuid) : (text != null ? text : "");
    }

    @Override
    public void close() {
        if (!active.compareAndSet(true, false)) return;
        externalPlaceholders.clear();
        if (services.getPermissionsHandler() != null) services.getPermissionsHandler().clearExternalPermissionNodes();
    }

    private Registration registerPlaceholder(String owner, String key, eu.avalanche7.paradigm.api.ExternalPlaceholderResolver resolver) {
        if (!active.get()) return SimpleRegistration.inactive(owner, key, RegistrationStatus.API_UNAVAILABLE);
        return externalPlaceholders.register(owner, key, resolver);
    }

    private final class Permissions implements PermissionService {
        @Override
        public PermissionDecision check(UUID playerUuid, String permissionNode, PermissionContext context) {
            if (!active.get() || playerUuid == null || permissionNode == null || permissionNode.isBlank()) {
                return PermissionDecision.UNDEFINED;
            }
            return PermissionDecision.fromNullable(services.getPermissionsHandler().queryDefinedPermission(
                    playerUuid, permissionNode, internalContext(context)));
        }

        @Override
        public boolean hasPermission(UUID playerUuid, String permissionNode, int fallbackOpLevel, PermissionContext context) {
            if (!active.get() || playerUuid == null || permissionNode == null || permissionNode.isBlank()) return false;
            return services.getPermissionsHandler().hasPermission(playerUuid, permissionNode, fallbackOpLevel,
                    internalContext(context));
        }

        @Override
        public PlayerPermissionMeta metadata(UUID playerUuid) {
            if (!active.get() || playerUuid == null) return PlayerPermissionMeta.EMPTY;
            PermissionAPI.PermissionMeta meta = services.getPermissionsHandler().resolvePlayerMetadata(playerUuid);
            if (meta == null) return PlayerPermissionMeta.EMPTY;
            return new PlayerPermissionMeta(meta.primaryGroup(), meta.prefix(), meta.suffix(), meta.groups());
        }

        @Override
        public Registration registerPermissionNode(String ownerModId, PermissionNodeDefinition definition) {
            if (!active.get()) {
                return SimpleRegistration.inactive(ownerModId, definition != null ? definition.node() : "",
                        RegistrationStatus.API_UNAVAILABLE);
            }
            if (definition == null) return SimpleRegistration.inactive(ownerModId, "", RegistrationStatus.INVALID);
            PermissionNodeRegistry.ExternalRegistration internal = services.getPermissionsHandler().registerExternalPermissionNode(
                    ownerModId, definition.node(), definition.description(), definition.fallbackOperatorLevel(),
                    definition.category().orElse(""), definition.featureIdentifier().orElse(""));
            RegistrationStatus status = switch (internal.status()) {
                case REGISTERED -> RegistrationStatus.REGISTERED;
                case ALREADY_REGISTERED -> RegistrationStatus.ALREADY_REGISTERED;
                case CONFLICT -> RegistrationStatus.CONFLICT;
                case INVALID -> RegistrationStatus.INVALID;
            };
            if (!internal.isActive()) return SimpleRegistration.inactive(internal.owner(), internal.node(), status);
            return new SimpleRegistration(internal.owner(), internal.node(), status, true, internal::close);
        }
    }

    private final class Messages implements MessageService {
        @Override
        public MessageResult sendPlayerMessage(UUID playerUuid, String template, Map<String, String> placeholders) {
            if (!active.get()) return MessageResult.API_UNAVAILABLE;
            if (playerUuid == null || template == null) return MessageResult.INVALID_TEMPLATE;
            IPlatformAdapter platform = services.getPlatformAdapter();
            IPlayer player = platform != null ? platform.getPlayerByUuid(playerUuid.toString()) : null;
            if (player == null) return MessageResult.PLAYER_OFFLINE;
            String prepared;
            try {
                prepared = LiteralPlaceholders.apply(template, placeholders);
            } catch (RuntimeException invalid) {
                return MessageResult.INVALID_TEMPLATE;
            }
            try {
                platform.executeOnServerThread(() -> {
                    if (!active.get()) return;
                    try {
                        platform.sendSystemMessage(player, services.getMessageParser().parseMessage(prepared, player));
                    } catch (Throwable failure) {
                        if (services.getLogger() != null) {
                            services.getLogger().warn("Paradigm API: failed to deliver companion message to {}: {}",
                                    playerUuid, failure.getMessage());
                        }
                    }
                });
                return MessageResult.SENT;
            } catch (Throwable failure) {
                return MessageResult.DELIVERY_FAILED;
            }
        }

        @Override
        public MessageResult broadcastMessage(String template, Map<String, String> placeholders) {
            if (!active.get()) return MessageResult.API_UNAVAILABLE;
            if (template == null) return MessageResult.INVALID_TEMPLATE;
            IPlatformAdapter platform = services.getPlatformAdapter();
            if (platform == null) return MessageResult.DELIVERY_FAILED;
            String prepared;
            try {
                prepared = LiteralPlaceholders.apply(template, placeholders);
            } catch (RuntimeException invalid) {
                return MessageResult.INVALID_TEMPLATE;
            }
            try {
                platform.executeOnServerThread(() -> {
                    if (!active.get()) return;
                    try {
                        platform.broadcastSystemMessage(services.getMessageParser().parseMessage(prepared, null));
                    } catch (Throwable failure) {
                        if (services.getLogger() != null) {
                            services.getLogger().warn("Paradigm API: failed to deliver companion broadcast: {}", failure.getMessage());
                        }
                    }
                });
                return MessageResult.SENT;
            } catch (Throwable failure) {
                return MessageResult.DELIVERY_FAILED;
            }
        }
    }

    private static PermissionContextSet internalContext(PermissionContext context) {
        return context == null ? PermissionContextSet.empty() : PermissionContextSet.of(context.values());
    }
}
