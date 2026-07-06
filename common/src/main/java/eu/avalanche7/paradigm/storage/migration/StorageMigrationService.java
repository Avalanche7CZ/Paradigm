package eu.avalanche7.paradigm.storage.migration;

import eu.avalanche7.paradigm.storage.StorageProvider;
import eu.avalanche7.paradigm.storage.identity.ServerIdentity;
import eu.avalanche7.paradigm.storage.model.StoredAdminState;
import eu.avalanche7.paradigm.storage.model.StoredJailState;
import eu.avalanche7.paradigm.storage.model.StoredPermissionGroup;
import eu.avalanche7.paradigm.storage.model.StoredPlayerProfile;
import eu.avalanche7.paradigm.storage.model.StoredPunishment;
import eu.avalanche7.paradigm.storage.model.StoredUserPermissionData;
import eu.avalanche7.paradigm.storage.model.StoredWarning;
import eu.avalanche7.paradigm.storage.model.StoredWarp;
import org.slf4j.Logger;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.function.BooleanSupplier;

public class StorageMigrationService {
    private final Logger logger;

    public StorageMigrationService(Logger logger) {
        this.logger = logger;
    }

    public MigrationSummary migrate(StorageProvider source, StorageProvider target, ServerIdentity identity) {
        return migrate(source, target, identity, StorageMigrationOptions.defaults());
    }

    public MigrationSummary migrate(StorageProvider source, StorageProvider target, ServerIdentity identity, StorageMigrationOptions options) {
        StorageMigrationOptions effectiveOptions = options != null ? options : StorageMigrationOptions.defaults();
        Counter counter = new Counter(source.type().configValue(), target.type().configValue(), identity, effectiveOptions);

        copyPlayers(source, target, counter);
        copyWarps(source, target, counter);
        copyModeration(source, target, counter);
        copyPermissions(source, target, counter);
        copyAdminState(source, target, counter);

        boolean success = counter.failures == 0;
        return new MigrationSummary(
                counter.sourceProvider,
                counter.targetProvider,
                identity != null ? identity.networkId() : "default",
                identity != null ? identity.serverId() : "default",
                effectiveOptions.dryRun(),
                effectiveOptions.conflictPolicy().configValue(),
                effectiveOptions.jsonBackupPath(),
                counter.players,
                counter.homes,
                counter.warps,
                counter.moderationRecords,
                counter.permissionGroups,
                counter.permissionUsers,
                counter.adminStates,
                counter.conflicts,
                counter.failures,
                counter.skipped,
                success,
                List.copyOf(counter.messages)
        );
    }

    private void copyPlayers(StorageProvider source, StorageProvider target, Counter counter) {
        for (StoredPlayerProfile profile : source.players().listProfiles()) {
            if (profile == null || profile.uuid() == null || profile.uuid().isBlank()) {
                counter.skipped++;
                continue;
            }
            if (transfer(counter, "player " + profile.uuid(),
                    () -> target.players().getProfile(profile.uuid()).isPresent(),
                    () -> target.players().upsertProfile(profile))) {
                counter.players++;
            }

            for (var home : source.players().listHomes(profile.uuid())) {
                if (home == null || home.name() == null || home.name().isBlank()) {
                    counter.skipped++;
                    continue;
                }
                if (transfer(counter, "home " + profile.uuid() + "/" + home.name(),
                        () -> target.players().getHome(home.uuid(), home.name()).isPresent(),
                        () -> target.players().saveHome(home))) {
                    counter.homes++;
                }
            }

            source.players().getBackLocation(profile.uuid()).ifPresent(location ->
                    transfer(counter, "back location " + profile.uuid(),
                            () -> target.players().getBackLocation(profile.uuid()).isPresent(),
                            () -> target.players().setBackLocation(profile.uuid(), location)));

            for (String ignored : source.players().listIgnoredPlayers(profile.uuid())) {
                if (ignored == null || ignored.isBlank()) {
                    counter.skipped++;
                    continue;
                }
                transfer(counter, "ignored player " + profile.uuid() + "/" + ignored,
                        () -> target.players().listIgnoredPlayers(profile.uuid()).contains(ignored),
                        () -> target.players().addIgnoredPlayer(profile.uuid(), ignored));
            }
        }
    }

    private void copyWarps(StorageProvider source, StorageProvider target, Counter counter) {
        for (StoredWarp warp : source.warps().listWarps()) {
            if (warp == null || warp.name() == null || warp.name().isBlank()) {
                counter.skipped++;
                continue;
            }
            if (transfer(counter, "warp " + warp.name(),
                    () -> target.warps().getWarp(warp.name()).isPresent(),
                    () -> target.warps().saveWarp(warp))) {
                counter.warps++;
            }
        }
        source.warps().getGlobalSpawn().ifPresent(location ->
                transfer(counter, "global spawn",
                        () -> target.warps().getGlobalSpawn().isPresent(),
                        () -> target.warps().setGlobalSpawn(location)));
    }

    private void copyModeration(StorageProvider source, StorageProvider target, Counter counter) {
        for (StoredPunishment punishment : source.moderation().listPunishments()) {
            if (punishment == null) {
                counter.skipped++;
                continue;
            }
            if (transfer(counter, "punishment " + punishmentLabel(punishment),
                    () -> punishmentExists(target, punishment),
                    () -> target.moderation().addPunishment(punishment))) {
                counter.moderationRecords++;
            }
        }
        for (StoredWarning warning : source.moderation().listWarnings()) {
            if (warning == null) {
                counter.skipped++;
                continue;
            }
            if (transfer(counter, "warning " + warningLabel(warning),
                    () -> warningExists(target, warning),
                    () -> target.moderation().addWarning(warning))) {
                counter.moderationRecords++;
            }
        }
        source.moderation().getJailLocation().ifPresent(location ->
                transfer(counter, "jail location",
                        () -> target.moderation().getJailLocation().isPresent(),
                        () -> target.moderation().setJailLocation(location)));

        for (StoredJailState jail : source.moderation().listJailStates()) {
            if (jail == null || jail.uuid() == null || jail.uuid().isBlank()) {
                counter.skipped++;
                continue;
            }
            if (transfer(counter, "jail " + jail.uuid(),
                    () -> target.moderation().getJailState(jail.uuid()).isPresent(),
                    () -> target.moderation().setJailState(jail))) {
                counter.moderationRecords++;
            }
        }
    }

    private void copyPermissions(StorageProvider source, StorageProvider target, Counter counter) {
        for (StoredPermissionGroup group : source.permissions().listGroups()) {
            if (group == null || group.name() == null || group.name().isBlank()) {
                counter.skipped++;
                continue;
            }
            if (transfer(counter, "permission group " + group.name(),
                    () -> target.permissions().getGroup(group.name()).isPresent(),
                    () -> target.permissions().saveGroup(group))) {
                counter.permissionGroups++;
            }
        }
        for (StoredUserPermissionData user : source.permissions().listUsers()) {
            if (user == null || user.uuid() == null || user.uuid().isBlank()) {
                counter.skipped++;
                continue;
            }
            if (transfer(counter, "permission user " + user.uuid(),
                    () -> target.permissions().getUser(user.uuid()).isPresent(),
                    () -> target.permissions().saveUser(user))) {
                counter.permissionUsers++;
            }
        }
    }

    private void copyAdminState(StorageProvider source, StorageProvider target, Counter counter) {
        for (String key : source.adminState().keys()) {
            try {
                StoredAdminState state = source.adminState().getState(key).orElse(null);
                if (state == null) {
                    counter.skipped++;
                    continue;
                }
                if (transfer(counter, "admin state " + key,
                        () -> target.adminState().getState(state.stateKey()).isPresent(),
                        () -> {
                            if (!copyStructuredAdminState(target, state, counter.options.dryRun())) {
                                target.adminState().setState(state.stateKey(), state.stateValue());
                            }
                        })) {
                    counter.adminStates++;
                }
            } catch (UnsupportedOperationException ex) {
                counter.skipped++;
                counter.messages.add("Skipped admin state " + key + ": target does not support generic state writes.");
            } catch (Throwable t) {
                fail(counter, "admin state " + key, t);
            }
        }
    }

    private boolean transfer(Counter counter, String item, BooleanSupplier targetExists, Runnable writer) {
        try {
            boolean conflict = targetExists != null && targetExists.getAsBoolean();
            if (conflict && !handleConflict(counter, item)) {
                return false;
            }
            if (!counter.options.dryRun() && writer != null) {
                writer.run();
            }
            return true;
        } catch (Throwable t) {
            fail(counter, item, t);
            return false;
        }
    }

    private boolean handleConflict(Counter counter, String item) {
        counter.conflicts++;
        return switch (counter.options.conflictPolicy()) {
            case OVERWRITE -> true;
            case SKIP -> {
                counter.skipped++;
                counter.messages.add("Skipped existing " + item + " because conflict policy is skip.");
                yield false;
            }
            case FAIL -> {
                counter.failures++;
                counter.messages.add("Conflict on existing " + item + " because conflict policy is fail.");
                yield false;
            }
        };
    }

    private boolean copyStructuredAdminState(StorageProvider target, StoredAdminState state, boolean dryRun) {
        String key = state.stateKey() != null ? state.stateKey().trim().toLowerCase(Locale.ROOT) : "";
        if ("global_spawn".equals(key) || "jail_location".equals(key)) {
            return true;
        }
        if (dryRun) {
            return key.startsWith("god.") || key.startsWith("vanish.") || "god".equals(key) || "vanish".equals(key) || "vanished".equals(key);
        }
        if (key.startsWith("god.")) {
            target.adminState().setGod(key.substring("god.".length()), true);
            return true;
        }
        if (key.startsWith("vanish.")) {
            target.adminState().setVanished(key.substring("vanish.".length()), true);
            return true;
        }
        if ("god".equals(key)) {
            for (String uuid : splitCsv(state.stateValue())) {
                target.adminState().setGod(uuid, true);
            }
            return true;
        }
        if ("vanish".equals(key) || "vanished".equals(key)) {
            for (String uuid : splitCsv(state.stateValue())) {
                target.adminState().setVanished(uuid, true);
            }
            return true;
        }
        return false;
    }

    private boolean punishmentExists(StorageProvider target, StoredPunishment punishment) {
        for (StoredPunishment existing : target.moderation().listPunishments()) {
            if (existing == null) {
                continue;
            }
            if (punishment.id() > 0L && existing.id() == punishment.id()) {
                return true;
            }
            if (same(existing.type(), punishment.type())
                    && Objects.equals(existing.scope(), punishment.scope())
                    && same(existing.serverId(), punishment.serverId())
                    && same(existing.uuid(), punishment.uuid())
                    && same(existing.name(), punishment.name())
                    && Objects.equals(existing.createdAtMs(), punishment.createdAtMs())
                    && Objects.equals(existing.expiresAtMs(), punishment.expiresAtMs())
                    && existing.active() == punishment.active()) {
                return true;
            }
        }
        return false;
    }

    private boolean warningExists(StorageProvider target, StoredWarning warning) {
        for (StoredWarning existing : target.moderation().listWarnings()) {
            if (existing == null) {
                continue;
            }
            if (warning.id() > 0L && existing.id() == warning.id()) {
                return true;
            }
            if (same(existing.uuid(), warning.uuid())
                    && same(existing.name(), warning.name())
                    && same(existing.reason(), warning.reason())
                    && same(existing.actor(), warning.actor())
                    && existing.createdAtMs() == warning.createdAtMs()) {
                return true;
            }
        }
        return false;
    }

    private List<String> splitCsv(String value) {
        List<String> result = new ArrayList<>();
        if (value == null || value.isBlank()) {
            return result;
        }
        for (String part : value.split(",")) {
            String trimmed = part.trim();
            if (!trimmed.isBlank()) {
                result.add(trimmed);
            }
        }
        return result;
    }

    private void fail(Counter counter, String item, Throwable t) {
        counter.failures++;
        String message = "Failed to migrate " + item + ": " + (t.getMessage() != null ? t.getMessage() : t.toString());
        counter.messages.add(message);
        if (logger != null) {
            logger.warn("Paradigm storage migration: {}", message);
        }
    }

    private static boolean same(String left, String right) {
        String a = left != null ? left.trim().toLowerCase(Locale.ROOT) : "";
        String b = right != null ? right.trim().toLowerCase(Locale.ROOT) : "";
        return a.equals(b);
    }

    private static String punishmentLabel(StoredPunishment punishment) {
        return (punishment.type() != null ? punishment.type() : "<unknown>") + "/" + (punishment.uuid() != null ? punishment.uuid() : punishment.name());
    }

    private static String warningLabel(StoredWarning warning) {
        return warning.uuid() != null ? warning.uuid() : warning.name();
    }

    private static class Counter {
        private final String sourceProvider;
        private final String targetProvider;
        private final StorageMigrationOptions options;
        private int players;
        private int homes;
        private int warps;
        private int moderationRecords;
        private int permissionGroups;
        private int permissionUsers;
        private int adminStates;
        private int conflicts;
        private int failures;
        private int skipped;
        private final List<String> messages = new ArrayList<>();

        private Counter(String sourceProvider, String targetProvider, ServerIdentity identity, StorageMigrationOptions options) {
            this.sourceProvider = sourceProvider;
            this.targetProvider = targetProvider;
            this.options = options;
            messages.add((options.dryRun() ? "Dry-run migration" : "Migration") + " using conflict policy " + options.conflictPolicy().configValue() + ".");
            if (options.jsonBackupPath() != null && !options.jsonBackupPath().isBlank()) {
                messages.add("JSON backup created at " + options.jsonBackupPath());
            }
            if (identity != null) {
                messages.add("Migration scope networkId=" + identity.networkId() + ", serverId=" + identity.serverId());
            }
        }
    }

    public record MigrationSummary(
            String sourceProvider,
            String targetProvider,
            String networkId,
            String serverId,
            boolean dryRun,
            String conflictPolicy,
            String jsonBackupPath,
            int players,
            int homes,
            int warps,
            int moderationRecords,
            int permissionGroups,
            int permissionUsers,
            int adminStates,
            int conflicts,
            int failures,
            int skipped,
            boolean success,
            List<String> messages
    ) {
    }
}
