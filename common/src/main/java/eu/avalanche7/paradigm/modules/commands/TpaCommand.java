package eu.avalanche7.paradigm.modules.commands;

import eu.avalanche7.paradigm.core.ParadigmModule;
import eu.avalanche7.paradigm.core.Services;
import eu.avalanche7.paradigm.data.PlayerDataStore;
import eu.avalanche7.paradigm.platform.Interfaces.ICommandBuilder;
import eu.avalanche7.paradigm.platform.Interfaces.ICommandContext;
import eu.avalanche7.paradigm.platform.Interfaces.IEventSystem;
import eu.avalanche7.paradigm.platform.Interfaces.IPlatformAdapter;
import eu.avalanche7.paradigm.platform.Interfaces.IPlayer;
import eu.avalanche7.paradigm.utils.PermissionsHandler;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

public class TpaCommand implements ParadigmModule {
    private Services services;
    private IPlatformAdapter platform;
    private TeleportRequestService teleportRequests;

    @Override
    public String getName() {
        return "Tpa";
    }

    @Override
    public boolean isEnabled(Services services) {
        return services == null
                || services.getMainConfig() == null
                || Boolean.TRUE.equals(services.getMainConfig().tpaCommandsEnable.value);
    }

    @Override
    public void onLoad(Object event, Services services, Object modEventBus) {
        this.services = services;
        this.platform = services.getPlatformAdapter();
        this.teleportRequests = new TeleportRequestService();
    }

    @Override
    public void onServerStarting(Object event, Services services) {
    }

    @Override
    public void onEnable(Services services) {
    }

    @Override
    public void onDisable(Services services) {
    }

    @Override
    public void onServerStopping(Object event, Services services) {
    }

    @Override
    public void registerCommands(Object dispatcher, Object registryAccess, Services services) {
        registerTpa();
        registerTpaHere();
        registerTpAccept();
        registerTpDeny();
        registerTpCancel();
    }

    @Override
    public void registerEventListeners(Object eventBus, Services services) {
        IEventSystem events = services.getPlatformAdapter().getEventSystem();
        if (events != null) {
            events.onPlayerLeave(event -> {
                IPlayer player = event.getPlayer();
                if (player != null) {
                    teleportRequests.removePlayer(player.getUUID());
                }
            });
        }
    }

    private void registerTpa() {
        ICommandBuilder command = platform.createCommandBuilder()
                .literal("tpa")
                .requires(source -> services.getCommandToggleStore().isEnabled("tpa")
                        && source.getPlayer() != null
                        && services.getPermissionsHandler().hasPermission(source.getPlayer(), PermissionsHandler.TPA_PERMISSION, PermissionsHandler.TPA_PERMISSION_LEVEL))
                .then(platform.createCommandBuilder()
                        .argument("player", ICommandBuilder.ArgumentType.PLAYER)
                        .suggests((c, input) -> onlinePlayerNameSuggestions(c.getSource().getPlayer(), input, true))
                        .executes(ctx -> executeRequest(ctx.getSource().getPlayer(), resolvePlayerArgument(ctx, "player"), TeleportRequestService.RequestType.TPA)));
        platform.registerCommand(command);
    }

    private void registerTpaHere() {
        ICommandBuilder command = platform.createCommandBuilder()
                .literal("tpahere")
                .requires(source -> services.getCommandToggleStore().isEnabled("tpahere")
                        && source.getPlayer() != null
                        && services.getPermissionsHandler().hasPermission(source.getPlayer(), PermissionsHandler.TPAHERE_PERMISSION, PermissionsHandler.TPAHERE_PERMISSION_LEVEL))
                .then(platform.createCommandBuilder()
                        .argument("player", ICommandBuilder.ArgumentType.PLAYER)
                        .suggests((c, input) -> onlinePlayerNameSuggestions(c.getSource().getPlayer(), input, true))
                        .executes(ctx -> executeRequest(ctx.getSource().getPlayer(), resolvePlayerArgument(ctx, "player"), TeleportRequestService.RequestType.TPAHERE)));
        platform.registerCommand(command);
    }

    private void registerTpAccept() {
        ICommandBuilder root = platform.createCommandBuilder()
                .literal("tpaccept")
                .requires(source -> services.getCommandToggleStore().isEnabled("tpaccept")
                        && source.getPlayer() != null
                        && services.getPermissionsHandler().hasPermission(source.getPlayer(), PermissionsHandler.TPACCEPT_PERMISSION, PermissionsHandler.TPACCEPT_PERMISSION_LEVEL))
                .executes(ctx -> executeAccept(ctx.getSource().getPlayer(), null));

        ICommandBuilder withPlayer = platform.createCommandBuilder()
                .argument("player", ICommandBuilder.ArgumentType.PLAYER)
                .suggests((c, input) -> pendingRequesterNameSuggestions(c.getSource().getPlayer(), input))
                .executes(ctx -> {
                    IPlayer requester = resolvePlayerArgument(ctx, "player");
                    return executeAccept(ctx.getSource().getPlayer(), requester != null ? requester.getUUID() : null);
                });

        platform.registerCommand(root.then(withPlayer));
    }

    private void registerTpDeny() {
        ICommandBuilder root = platform.createCommandBuilder()
                .literal("tpdeny")
                .requires(source -> services.getCommandToggleStore().isEnabled("tpdeny")
                        && source.getPlayer() != null
                        && services.getPermissionsHandler().hasPermission(source.getPlayer(), PermissionsHandler.TPDENY_PERMISSION, PermissionsHandler.TPDENY_PERMISSION_LEVEL))
                .executes(ctx -> executeDeny(ctx.getSource().getPlayer(), null));

        ICommandBuilder withPlayer = platform.createCommandBuilder()
                .argument("player", ICommandBuilder.ArgumentType.PLAYER)
                .suggests((c, input) -> pendingRequesterNameSuggestions(c.getSource().getPlayer(), input))
                .executes(ctx -> {
                    IPlayer requester = resolvePlayerArgument(ctx, "player");
                    return executeDeny(ctx.getSource().getPlayer(), requester != null ? requester.getUUID() : null);
                });

        platform.registerCommand(root.then(withPlayer));
    }

    private void registerTpCancel() {
        ICommandBuilder root = platform.createCommandBuilder()
                .literal("tpcancel")
                .requires(source -> services.getCommandToggleStore().isEnabled("tpcancel")
                        && source.getPlayer() != null
                        && services.getPermissionsHandler().hasPermission(source.getPlayer(), PermissionsHandler.TPCANCEL_PERMISSION, PermissionsHandler.TPCANCEL_PERMISSION_LEVEL))
                .executes(ctx -> executeCancel(ctx.getSource().getPlayer(), null));

        ICommandBuilder withPlayer = platform.createCommandBuilder()
                .argument("player", ICommandBuilder.ArgumentType.PLAYER)
                .suggests((c, input) -> outgoingTargetNameSuggestions(c.getSource().getPlayer(), input))
                .executes(ctx -> {
                    IPlayer target = resolvePlayerArgument(ctx, "player");
                    return executeCancel(ctx.getSource().getPlayer(), target != null ? target.getUUID() : null);
                });

        platform.registerCommand(root.then(withPlayer));
    }

    private int executeRequest(IPlayer requester, IPlayer target, TeleportRequestService.RequestType type) {
        if (requester == null) return 0;
        if (target == null) {
            send(requester, "tpa.player_not_found", "That player is not online.");
            return 0;
        }
        if (requester.getUUID() != null && requester.getUUID().equals(target.getUUID())) {
            send(requester, "tpa.cannot_target_self", "You cannot target yourself.");
            return 0;
        }
        if (target.getUUID() != null && requester.getUUID() != null
                && services.getPlayerDataStore().isIgnoring(target.getUUID(), requester.getUUID())) {
            send(requester, "tpa.blocked_by_ignore", "That player is ignoring you.");
            return 0;
        }

        TeleportRequestService.Request request = teleportRequests.create(type, requester.getUUID(), target.getUUID(), TeleportRequestService.DEFAULT_EXPIRES_SECONDS);
        if (request == null) {
            send(requester, "tpa.request_failed", "Could not create teleport request.");
            return 0;
        }

        String requesterToTargetKey = type == TeleportRequestService.RequestType.TPA ? "tpa.request_received_tpa" : "tpa.request_received_tpahere";
        String senderKey = type == TeleportRequestService.RequestType.TPA ? "tpa.request_sent_tpa" : "tpa.request_sent_tpahere";

        send(requester, senderKey,
                type == TeleportRequestService.RequestType.TPA
                        ? "Teleport request sent to {player}."
                        : "Teleport-here request sent to {player}.",
                "{player}", target.getName());

        String cancelLine = "<color:#A78BFA><bold>[TPA]</bold></color> "
                + "<hover:'<color:#FCD34D>Click to cancel this request</color>'>"
                + "<click:execute:'/tpcancel " + safe(target.getName()) + "'>"
                + "<color:#F59E0B><bold>[CANCEL REQUEST]</bold></color>"
                + "</click></hover>";
        platform.sendSystemMessage(requester, services.getMessageParser().parseMessage(cancelLine, requester));

        send(target, requesterToTargetKey,
                type == TeleportRequestService.RequestType.TPA
                        ? "{player} wants to teleport to you. Use /tpaccept or /tpdeny."
                        : "{player} wants you to teleport to them. Use /tpaccept or /tpdeny.",
                "{player}", requester.getName());

        String requesterName = safe(requester.getName());
        String actions = "<color:#A78BFA><bold>[TPA]</bold></color> "
                + "<hover:'<color:#BBF7D0>Accept teleport request</color>'><click:execute:'/tpaccept " + requesterName + "'><color:#22C55E><bold>[ACCEPT]</bold></color></click></hover>"
                + " <color:#6B7280>|</color> "
                + "<hover:'<color:#FCA5A5>Deny teleport request</color>'><click:execute:'/tpdeny " + requesterName + "'><color:#EF4444><bold>[DENY]</bold></color></click></hover>";
        platform.sendSystemMessage(target, services.getMessageParser().parseMessage(actions, target));
        return 1;
    }

    private int executeAccept(IPlayer accepter, String requesterUuidHint) {
        if (accepter == null) return 0;

        TeleportRequestService.Request request = teleportRequests.accept(accepter.getUUID(), requesterUuidHint);
        if (request == null) {
            send(accepter, "tpa.no_pending_request", "You have no pending teleport requests.");
            return 0;
        }

        IPlayer requester = platform.getPlayerByUuid(request.requesterUuid());
        if (requester == null) {
            send(accepter, "tpa.requester_offline", "That request is no longer valid because requester is offline.");
            return 0;
        }

        if (request.type() == TeleportRequestService.RequestType.TPA) {
            platform.getPlayerLocation(requester).ifPresent(loc -> services.getPlayerDataStore().setLastLocation(requester, loc));
            if (!teleportPlayer(requester, accepter)) return 0;
        } else {
            platform.getPlayerLocation(accepter).ifPresent(loc -> services.getPlayerDataStore().setLastLocation(accepter, loc));
            if (!teleportPlayer(accepter, requester)) return 0;
        }

        send(accepter, "tpa.accepted_you", "Accepted request from {player}.", "{player}", requester.getName());
        send(requester, "tpa.accepted_sender", "{player} accepted your teleport request.", "{player}", accepter.getName());
        return 1;
    }

    private int executeDeny(IPlayer denier, String requesterUuidHint) {
        if (denier == null) return 0;

        TeleportRequestService.Request request = teleportRequests.deny(denier.getUUID(), requesterUuidHint);
        if (request == null) {
            send(denier, "tpa.no_pending_request", "You have no pending teleport requests.");
            return 0;
        }

        IPlayer requester = platform.getPlayerByUuid(request.requesterUuid());
        send(denier, "tpa.denied_you", "Denied request from {player}.", "{player}", requester != null ? requester.getName() : "unknown");

        if (requester != null) {
            send(requester, "tpa.denied_sender", "{player} denied your teleport request.", "{player}", denier.getName());
        }
        return 1;
    }

    private int executeCancel(IPlayer requester, String targetUuidHint) {
        if (requester == null) return 0;

        TeleportRequestService.Request request = teleportRequests.cancelOutgoing(requester.getUUID(), targetUuidHint);
        if (request == null) {
            send(requester, "tpa.no_outgoing_request", "You have no outgoing teleport request.");
            return 0;
        }

        IPlayer target = platform.getPlayerByUuid(request.targetUuid());
        send(requester, "tpa.cancelled_you", "Cancelled request to {player}.", "{player}", target != null ? target.getName() : "unknown");

        if (target != null) {
            send(target, "tpa.cancelled_target", "{player} cancelled their teleport request.", "{player}", requester.getName());
        }
        return 1;
    }

    private boolean teleportPlayer(IPlayer player, IPlayer destination) {
        if (player == null || destination == null) {
            return false;
        }

        PlayerDataStore.StoredLocation location = platform.getPlayerLocation(destination).orElse(null);
        if (location != null) {
            if (platform.teleportPlayer(player, location)) {
                return true;
            }
        }
        String fromName = player.getName();
        String toName = destination.getName();
        if (fromName == null || fromName.isBlank() || toName == null || toName.isBlank()) {
            send(player, "home.location_unavailable", "Unable to read your location right now.");
            return false;
        }

        platform.executeCommandAsConsole("tp " + safe(fromName) + " " + safe(toName));
        return true;
    }

    private List<String> pendingRequesterNameSuggestions(IPlayer target, String input) {
        if (target == null || target.getUUID() == null) return List.of();

        List<String> names = new ArrayList<>();
        for (String requesterUuid : teleportRequests.getPendingRequesterUuids(target.getUUID())) {
            IPlayer requester = platform.getPlayerByUuid(requesterUuid);
            if (requester != null && requester.getName() != null && !requester.getName().isBlank()) {
                names.add(requester.getName());
            }
        }
        return filterSuggestions(names, input);
    }

    private List<String> outgoingTargetNameSuggestions(IPlayer requester, String input) {
        if (requester == null || requester.getUUID() == null) return List.of();

        List<String> names = new ArrayList<>();
        for (String targetUuid : teleportRequests.getOutgoingTargetUuids(requester.getUUID())) {
            IPlayer target = platform.getPlayerByUuid(targetUuid);
            if (target != null && target.getName() != null && !target.getName().isBlank()) {
                names.add(target.getName());
            }
        }
        return filterSuggestions(names, input);
    }

    private List<String> onlinePlayerNameSuggestions(IPlayer sourcePlayer, String input, boolean excludeSelf) {
        List<String> names = new ArrayList<>(platform.getOnlinePlayerNames());
        if (excludeSelf && sourcePlayer != null && sourcePlayer.getName() != null) {
            names.removeIf(name -> name.equalsIgnoreCase(sourcePlayer.getName()));
        }
        return filterSuggestions(names, input);
    }

    private List<String> filterSuggestions(List<String> raw, String input) {
        if (raw == null || raw.isEmpty()) return List.of();
        String q = input != null ? input.trim().toLowerCase(Locale.ROOT) : "";

        return raw.stream()
                .filter(name -> name != null && !name.isBlank())
                .distinct()
                .sorted(String.CASE_INSENSITIVE_ORDER)
                .filter(name -> q.isEmpty() || name.toLowerCase(Locale.ROOT).startsWith(q))
                .toList();
    }

    private static class TeleportRequestService {
        private static final long DEFAULT_EXPIRES_SECONDS = 60L;

        private final Map<String, List<Request>> requestsByTarget = new ConcurrentHashMap<>();

        public synchronized Request create(RequestType type, String requesterUuid, String targetUuid, long expiresSeconds) {
            if (type == null || isBlank(requesterUuid) || isBlank(targetUuid)) return null;
            if (requesterUuid.equalsIgnoreCase(targetUuid)) return null;

            long now = System.currentTimeMillis();
            long ttlMillis = Math.max(1L, expiresSeconds > 0 ? expiresSeconds : DEFAULT_EXPIRES_SECONDS) * 1000L;
            cleanupExpiredLocked(now);

            String targetKey = normalize(targetUuid);
            String requesterKey = normalize(requesterUuid);
            if (targetKey == null || requesterKey == null) return null;

            List<Request> list = requestsByTarget.computeIfAbsent(targetKey, ignored -> new ArrayList<>());
            list.removeIf(request -> requesterKey.equals(request.requesterUuid()));

            Request request = new Request(type, requesterKey, targetKey, now, now + ttlMillis);
            list.add(request);
            return request;
        }

        public synchronized Request accept(String targetUuid, String requesterUuidOrNull) {
            return takeIncoming(targetUuid, requesterUuidOrNull);
        }

        public synchronized Request deny(String targetUuid, String requesterUuidOrNull) {
            return takeIncoming(targetUuid, requesterUuidOrNull);
        }

        public synchronized Request cancelOutgoing(String requesterUuid, String targetUuidOrNull) {
            if (isBlank(requesterUuid)) return null;
            cleanupExpiredLocked(System.currentTimeMillis());

            String requesterKey = normalize(requesterUuid);
            String targetKey = normalize(targetUuidOrNull);
            if (requesterKey == null) return null;

            Request selected = null;
            String selectedTarget = null;
            for (Map.Entry<String, List<Request>> entry : requestsByTarget.entrySet()) {
                if (targetKey != null && !targetKey.equals(entry.getKey())) continue;
                List<Request> list = entry.getValue();
                if (list == null || list.isEmpty()) continue;

                for (Request request : list) {
                    if (!requesterKey.equals(request.requesterUuid())) continue;
                    if (selected == null || request.createdAtMillis() > selected.createdAtMillis()) {
                        selected = request;
                        selectedTarget = entry.getKey();
                    }
                }
            }

            if (selected == null || selectedTarget == null) return null;

            List<Request> list = requestsByTarget.get(selectedTarget);
            if (list != null) {
                list.remove(selected);
                if (list.isEmpty()) requestsByTarget.remove(selectedTarget);
            }
            return selected;
        }

        public synchronized void removePlayer(String playerUuid) {
            if (isBlank(playerUuid)) return;
            String key = normalize(playerUuid);
            if (key == null) return;

            requestsByTarget.remove(key);
            for (Map.Entry<String, List<Request>> entry : requestsByTarget.entrySet()) {
                List<Request> list = entry.getValue();
                if (list == null) continue;
                list.removeIf(request -> key.equals(request.requesterUuid()) || key.equals(request.targetUuid()));
                if (list.isEmpty()) requestsByTarget.remove(entry.getKey());
            }
        }

        public synchronized List<String> getPendingRequesterUuids(String targetUuid) {
            if (isBlank(targetUuid)) return List.of();
            cleanupExpiredLocked(System.currentTimeMillis());

            List<Request> list = requestsByTarget.get(normalize(targetUuid));
            if (list == null || list.isEmpty()) return List.of();

            return list.stream()
                    .sorted(Comparator.comparingLong(Request::createdAtMillis).reversed())
                    .map(Request::requesterUuid)
                    .distinct()
                    .toList();
        }

        public synchronized List<String> getOutgoingTargetUuids(String requesterUuid) {
            if (isBlank(requesterUuid)) return List.of();
            cleanupExpiredLocked(System.currentTimeMillis());

            String requesterKey = normalize(requesterUuid);
            if (requesterKey == null) return List.of();

            List<Request> selected = new ArrayList<>();
            for (List<Request> list : requestsByTarget.values()) {
                if (list == null || list.isEmpty()) continue;
                for (Request request : list) {
                    if (requesterKey.equals(request.requesterUuid())) {
                        selected.add(request);
                    }
                }
            }

            return selected.stream()
                    .sorted(Comparator.comparingLong(Request::createdAtMillis).reversed())
                    .map(Request::targetUuid)
                    .distinct()
                    .toList();
        }

        private Request takeIncoming(String targetUuid, String requesterUuidOrNull) {
            if (isBlank(targetUuid)) return null;
            cleanupExpiredLocked(System.currentTimeMillis());

            String targetKey = normalize(targetUuid);
            String requesterKey = normalize(requesterUuidOrNull);
            if (targetKey == null) return null;

            List<Request> list = requestsByTarget.get(targetKey);
            if (list == null || list.isEmpty()) return null;

            Request selected = list.stream()
                    .filter(request -> requesterKey == null || requesterKey.equals(request.requesterUuid()))
                    .max(Comparator.comparingLong(Request::createdAtMillis))
                    .orElse(null);

            if (selected == null) return null;
            list.remove(selected);
            if (list.isEmpty()) requestsByTarget.remove(targetKey);
            return selected;
        }

        private void cleanupExpiredLocked(long now) {
            for (Map.Entry<String, List<Request>> entry : requestsByTarget.entrySet()) {
                List<Request> list = entry.getValue();
                if (list == null) continue;
                list.removeIf(request -> request.expiresAtMillis() <= now);
                if (list.isEmpty()) requestsByTarget.remove(entry.getKey());
            }
        }

        private static boolean isBlank(String value) {
            return value == null || value.isBlank();
        }

        private static String normalize(String value) {
            if (isBlank(value)) return null;
            return value.trim().toLowerCase(Locale.ROOT);
        }

        enum RequestType {
            TPA,
            TPAHERE
        }

        record Request(RequestType type, String requesterUuid, String targetUuid, long createdAtMillis, long expiresAtMillis) {
        }
    }

    private IPlayer resolvePlayerArgument(ICommandContext ctx, String argName) {
        IPlayer target = ctx.getPlayerArgument(argName);
        if (target != null) {
            return target;
        }

        try {
            String rawName = ctx.getStringArgument(argName);
            if (rawName == null || rawName.isBlank()) {
                return null;
            }
            return platform.getPlayerByName(rawName);
        } catch (Throwable ignored) {
            return null;
        }
    }

    private void send(IPlayer player, String key, String fallback) {
        send(player, key, fallback, null, null);
    }

    private void send(IPlayer player, String key, String fallback, String placeholder, String value) {
        String raw = services.getLang().getTranslation(key);
        if (raw == null || raw.equals(key)) {
            raw = fallback;
        }
        if (placeholder != null && value != null) {
            raw = raw.replace(placeholder, value);
        }
        String decorated = "<color:#A78BFA><bold>[TPA]</bold></color> <color:#E5E7EB>" + raw + "</color>";
        platform.sendSystemMessage(player, services.getMessageParser().parseMessage(decorated, player));
    }

    private static String safe(String value) {
        if (value == null) {
            return "";
        }
        return value.replace("'", "").replace("\n", " ");
    }
}

