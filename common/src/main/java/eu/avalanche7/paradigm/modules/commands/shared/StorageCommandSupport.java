package eu.avalanche7.paradigm.modules.commands.shared;

import eu.avalanche7.paradigm.core.Services;
import eu.avalanche7.paradigm.platform.Interfaces.ICommandSource;
import eu.avalanche7.paradigm.platform.Interfaces.IPlayer;

import java.util.function.BiConsumer;
import java.util.function.Consumer;
import java.util.function.Supplier;

public final class StorageCommandSupport {
    private StorageCommandSupport() {
    }

    public static <T> int runForSource(
            Services services,
            ICommandSource source,
            String operation,
            Supplier<T> work,
            Consumer<T> onSuccess
    ) {
        return runForSource(services, source, operation, work, onSuccess, "storage.error.operation_failed");
    }

    public static <T> int runForSource(
            Services services,
            ICommandSource source,
            String operation,
            Supplier<T> work,
            Consumer<T> onSuccess,
            String failureKey
    ) {
        if (services == null || services.getStorageService() == null) {
            CommandMessages.source(services, source, "Storage", "storage.error.provider_unavailable", "Storage is currently unavailable. Please try again later.");
            return 0;
        }
        services.getStorageService().runAsync(
                operation,
                work,
                services.getTaskScheduler(),
                onSuccess,
                failure -> CommandMessages.source(services, source, "Storage", failureKey, "Storage operation failed. Please try again later.")
        );
        return 1;
    }

    public static <T> int runForPlayer(
            Services services,
            IPlayer player,
            String operation,
            Supplier<T> work,
            BiConsumer<IPlayer, T> onSuccess
    ) {
        return runForPlayer(services, player, operation, work, onSuccess, "storage.error.operation_failed");
    }

    public static <T> int runForPlayer(
            Services services,
            IPlayer player,
            String operation,
            Supplier<T> work,
            BiConsumer<IPlayer, T> onSuccess,
            String failureKey
    ) {
        if (services == null || services.getStorageService() == null) {
            CommandMessages.send(services, player, "Storage", "storage.error.provider_unavailable", "Storage is currently unavailable. Please try again later.");
            return 0;
        }
        if (player == null || player.getUUID() == null || player.getUUID().isBlank()) {
            return 0;
        }
        String playerUuid = player.getUUID();
        services.getStorageService().runAsync(
                operation,
                work,
                services.getTaskScheduler(),
                result -> {
                    IPlayer current = services.getPlatformAdapter().getPlayerByUuid(playerUuid);
                    if (current != null) {
                        onSuccess.accept(current, result);
                    }
                },
                failure -> {
                    IPlayer current = services.getPlatformAdapter().getPlayerByUuid(playerUuid);
                    if (current != null) {
                        CommandMessages.send(services, current, "Storage", failureKey, "Storage operation failed. Please try again later.");
                    }
                }
        );
        return 1;
    }
}
