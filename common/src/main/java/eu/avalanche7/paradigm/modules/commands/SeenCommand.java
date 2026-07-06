package eu.avalanche7.paradigm.modules.commands;

import eu.avalanche7.paradigm.core.ParadigmModule;
import eu.avalanche7.paradigm.core.Services;
import eu.avalanche7.paradigm.modules.commands.shared.StorageCommandSupport;
import eu.avalanche7.paradigm.platform.Interfaces.ICommandBuilder;
import eu.avalanche7.paradigm.platform.Interfaces.IEventSystem;
import eu.avalanche7.paradigm.platform.Interfaces.IPlayer;
import eu.avalanche7.paradigm.storage.model.StoredPlayerProfile;
import eu.avalanche7.paradigm.utils.PermissionsHandler;

import java.time.Duration;
import java.util.UUID;

public class SeenCommand implements ParadigmModule {
    private Services services;

    @Override
    public String getName() {
        return "Seen";
    }

    @Override
    public boolean isEnabled(Services services) {
        return services == null
                || services.getMainConfig() == null
                || Boolean.TRUE.equals(services.getMainConfig().seenCommandEnable.value);
    }

    @Override
    public void onLoad(Object event, Services services, Object modEventBus) {
        this.services = services;
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
        ICommandBuilder cmd = services.getPlatformAdapter().createCommandBuilder()
                .literal("seen")
                .requires(src -> src.getPlayer() != null
                        && services.getCommandToggleStore().isEnabled("seen")
                        && services.getPermissionsHandler().hasPermission(src.getPlayer(), PermissionsHandler.SEEN_PERMISSION, PermissionsHandler.SEEN_PERMISSION_LEVEL))
                .then(services.getPlatformAdapter().createCommandBuilder()
                        .argument("player", ICommandBuilder.ArgumentType.WORD)
                        .executes(ctx -> {
                            IPlayer sender = ctx.getSource().getPlayer();
                            String input = ctx.getStringArgument("player");
                            IPlayer online = services.getPlatformAdapter().getPlayerByName(input);
                            if (online != null) {
                                send(sender, "seen.online", "{player} is online right now.", "{player}", online.getName());
                                return 1;
                            }

                            UUID uuid = parseUuid(input);
                            if (uuid == null) {
                                send(sender, "seen.need_uuid", "Player is offline. Use UUID for exact lookup.");
                                return 0;
                            }

                            return StorageCommandSupport.runForPlayer(services, sender, "seen.lookup", () ->
                                    services.getStorageService().players().getProfile(uuid.toString()).orElse(null),
                                    (current, profile) -> sendSeenResult(current, profile),
                                    "seen.error_load");
                        }));
        services.getPlatformAdapter().registerCommand(cmd);
    }

    @Override
    public void registerEventListeners(Object eventBus, Services services) {
        IEventSystem events = services.getPlatformAdapter().getEventSystem();
        if (events != null) {
            events.onPlayerJoin(event -> {
                IPlayer player = event.getPlayer();
                if (player != null) {
                    updateSeenAsync(player);
                }
            });
            events.onPlayerLeave(event -> {
                IPlayer player = event.getPlayer();
                if (player != null) {
                    updateSeenAsync(player);
                }
            });
        }
    }

    private void updateSeenAsync(IPlayer player) {
        if (player == null || player.getUUID() == null || player.getUUID().isBlank()) {
            return;
        }
        String uuid = player.getUUID();
        String name = player.getName();
        services.getStorageService().runAsync("seen.update", () -> {
            long now = System.currentTimeMillis();
            long firstSeen = services.getStorageService().players()
                    .getProfile(uuid)
                    .map(StoredPlayerProfile::firstSeenMs)
                    .filter(value -> value > 0L)
                    .orElse(now);
            services.getStorageService().players().upsertProfile(new StoredPlayerProfile(uuid, name, firstSeen, now));
            return null;
        }, services.getTaskScheduler(), ignored -> {}, ignored -> {});
    }

    private void sendSeenResult(IPlayer sender, StoredPlayerProfile profile) {
        if (profile == null || profile.lastSeenMs() <= 0L) {
            send(sender, "seen.unknown", "No seen data for that player.");
            return;
        }

        Duration d = Duration.ofMillis(Math.max(0L, System.currentTimeMillis() - profile.lastSeenMs()));
        long days = d.toDays();
        long hours = d.minusDays(days).toHours();
        long minutes = d.minusDays(days).minusHours(hours).toMinutes();
        String ago = days + "d " + hours + "h " + minutes + "m";
        send(sender, "seen.last_seen", "Last seen: {ago} ago.", "{ago}", ago);
    }

    private UUID parseUuid(String input) {
        if (input == null || input.isBlank()) {
            return null;
        }
        try {
            return UUID.fromString(input.trim());
        } catch (Exception ignored) {
            return null;
        }
    }

    private void send(IPlayer player, String key, String fallback, String... placeholders) {
        if (player == null) {
            return;
        }
        String raw = services.getLang().getTranslation(key);
        if (raw == null || raw.equals(key)) {
            raw = fallback;
        }
        for (int i = 0; i + 1 < placeholders.length; i += 2) {
            raw = raw.replace(placeholders[i], placeholders[i + 1]);
        }
        String decorated = "<color:#22D3EE><bold>[Utility]</bold></color> <color:#E5E7EB>" + raw + "</color>";
        services.getPlatformAdapter().sendSystemMessage(player, services.getMessageParser().parseMessage(decorated, player));
    }
}
