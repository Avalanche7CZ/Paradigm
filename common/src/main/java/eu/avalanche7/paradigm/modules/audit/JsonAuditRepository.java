package eu.avalanche7.paradigm.modules.audit;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import eu.avalanche7.paradigm.platform.Interfaces.IConfig;
import org.slf4j.Logger;

import java.io.BufferedReader;
import java.io.BufferedWriter;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Locale;

public class JsonAuditRepository implements AuditRepository {
    private static final Gson GSON = new GsonBuilder().disableHtmlEscaping().create();

    private final Path path;
    private final Logger logger;
    private final Object lock = new Object();

    public JsonAuditRepository(IConfig config, Logger logger) {
        this.path = config.resolveConfigPath("paradigm/audit/audit.jsonl");
        this.logger = logger;
    }

    @Override
    public void append(AuditEntry entry) {
        if (entry == null) {
            return;
        }
        synchronized (lock) {
            try {
                Files.createDirectories(path.getParent());
                try (BufferedWriter writer = Files.newBufferedWriter(path, StandardCharsets.UTF_8,
                        java.nio.file.StandardOpenOption.CREATE, java.nio.file.StandardOpenOption.APPEND)) {
                    writer.write(GSON.toJson(entry));
                    writer.newLine();
                }
            } catch (Throwable t) {
                if (logger != null) {
                    logger.warn("Paradigm audit: failed to append audit entry: {}", t.getMessage());
                }
            }
        }
    }

    @Override
    public List<AuditEntry> recent(int limit) {
        return filter(null, null, limit);
    }

    @Override
    public List<AuditEntry> byActor(String actor, int limit) {
        return filter(actor, null, limit);
    }

    @Override
    public List<AuditEntry> byType(String type, int limit) {
        return filter(null, type, limit);
    }

    private List<AuditEntry> filter(String actor, String type, int limit) {
        int max = Math.max(1, Math.min(limit <= 0 ? 100 : limit, 500));
        String actorQuery = actor != null ? actor.trim().toLowerCase(Locale.ROOT) : "";
        String typeQuery = type != null ? type.trim().toUpperCase(Locale.ROOT) : "";
        List<AuditEntry> entries = new ArrayList<>();
        synchronized (lock) {
            if (!Files.exists(path)) {
                return List.of();
            }
            try (BufferedReader reader = Files.newBufferedReader(path, StandardCharsets.UTF_8)) {
                String line;
                while ((line = reader.readLine()) != null) {
                    if (line.isBlank()) {
                        continue;
                    }
                    AuditEntry entry = GSON.fromJson(line, AuditEntry.class);
                    if (entry == null || !matches(entry, actorQuery, typeQuery)) {
                        continue;
                    }
                    entries.add(entry);
                    if (entries.size() > max * 4) {
                        entries = entries.subList(entries.size() - max * 2, entries.size());
                    }
                }
            } catch (Throwable t) {
                if (logger != null) {
                    logger.warn("Paradigm audit: failed to read audit log: {}", t.getMessage());
                }
                return List.of();
            }
        }
        Collections.reverse(entries);
        return entries.size() > max ? List.copyOf(entries.subList(0, max)) : List.copyOf(entries);
    }

    private static boolean matches(AuditEntry entry, String actorQuery, String typeQuery) {
        if (entry == null) {
            return false;
        }
        if (!actorQuery.isBlank()) {
            String actorUuid = entry.actorUuid() != null ? entry.actorUuid().toLowerCase(Locale.ROOT) : "";
            String actorName = entry.actorName() != null ? entry.actorName().toLowerCase(Locale.ROOT) : "";
            if (!actorUuid.contains(actorQuery) && !actorName.contains(actorQuery)) {
                return false;
            }
        }
        if (!typeQuery.isBlank()) {
            String action = entry.actionType() != null ? entry.actionType().name() : "";
            return action.contains(typeQuery);
        }
        return true;
    }
}
