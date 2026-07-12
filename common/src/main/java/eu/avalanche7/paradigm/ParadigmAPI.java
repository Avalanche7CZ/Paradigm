package eu.avalanche7.paradigm;

import eu.avalanche7.paradigm.core.ParadigmModule;
import eu.avalanche7.paradigm.core.Services;

import java.util.Collections;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import eu.avalanche7.paradigm.modules.moderation.PunishmentRecord;

/**
 * API accessor for Paradigm mod. Implementation is provided by version-specific Paradigm class.
 */
public class ParadigmAPI {
    private static ParadigmAccessor instance;

    public static void setInstance(ParadigmAccessor accessor) {
        instance = accessor;
    }

    public static List<ParadigmModule> getModules() {
        if (instance == null) {
            return Collections.emptyList();
        }
        return instance.getModules();
    }

    public static Services getServices() {
        if (instance == null) {
            return null;
        }
        return instance.getServices();
    }

    public static String getModVersion() {
        if (instance == null) {
            return "unknown";
        }
        return instance.getModVersion();
    }

    public static ModerationView moderation() {
        return new ModerationView();
    }

    public static final class ModerationView {
        public Optional<PunishmentRecord> findPunishment(String punishmentId) {
            Services services = getServices();
            return services == null ? Optional.empty() : services.getPunishmentService().find(punishmentId).map(PunishmentRecord::withoutSensitiveIp);
        }

        public List<PunishmentRecord> history(UUID playerUuid, int limit) {
            Services services = getServices();
            if (services == null || playerUuid == null) return List.of();
            return services.getPunishmentService().history(playerUuid.toString(), 1, limit).stream().map(PunishmentRecord::withoutSensitiveIp).toList();
        }

        public List<PunishmentRecord> activePunishments(UUID playerUuid) {
            Services services = getServices();
            if (services == null || playerUuid == null) return List.of();
            return services.getPunishmentService().activeFor(playerUuid.toString(), null).stream().map(PunishmentRecord::withoutSensitiveIp).toList();
        }
    }

    /**
     * Interface that version-specific Paradigm class must implement
     */
    public interface ParadigmAccessor {
        List<ParadigmModule> getModules();
        Services getServices();
        String getModVersion();
    }
}
