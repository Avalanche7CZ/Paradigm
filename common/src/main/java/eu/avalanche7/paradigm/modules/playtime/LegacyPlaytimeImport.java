package eu.avalanche7.paradigm.modules.playtime;

import java.io.IOException;
import java.io.Reader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;

import com.google.gson.stream.JsonReader;
import com.google.gson.stream.JsonToken;

import eu.avalanche7.paradigm.storage.model.LegacyPlaytimeResult;
import eu.avalanche7.paradigm.storage.repository.PlayerRepository;

public final class LegacyPlaytimeImport {
    private LegacyPlaytimeImport() {}

    public record Entry(String uuid, long milliseconds) {}
    public record Scan(Path directory, int found, List<Entry> entries, List<String> errors, java.util.Map<String, Long> localAtStart) {
        public Scan(Path directory, int found, List<Entry> entries, List<String> errors) { this(directory, found, entries, errors, java.util.Map.of()); }
    }
    public record Report(Scan scan, List<LegacyPlaytimeResult> results, int skipped, List<String> errors) {
        public List<String> lines(boolean apply) {
            List<String> lines = new ArrayList<>();
            lines.add("Historical Playtime Import - " + (apply ? "confirmation" : "preview (read-only)"));
            lines.add("Source: Minecraft Statistics (20 ticks/s); " + scan.directory());
            lines.add("Policy: max(current server total, world statistics), once per UUID and server.");
            lines.add("Unknown legacy-network overlap is skipped; other servers remain unchanged.");
            lines.add("Found: " + scan.found() + "; valid: " + scan.entries().size() + "; invalid: " + scan.errors().size());
            long already = results.stream().filter(LegacyPlaytimeResult::alreadyImported).count();
            long additions = results.stream().filter(r -> r.addedMs() > 0).count();
            long ambiguous = results.stream().filter(r -> r.skippedReason() != null).count();
            lines.add((apply ? "Added: " : "Would add: ") + additions + "; unchanged: " + (results.size() - already - additions - ambiguous)
                    + "; already imported: " + already + "; skipped (online/pending): " + skipped + "; ambiguous: " + ambiguous + "; storage errors: " + errors.size());
            int unattempted = scan.entries().size() - results.size() - skipped - errors.size();
            if (unattempted > 0) lines.add("Not attempted after storage failure: " + unattempted);
            if (scan.entries().isEmpty()) lines.add("No supported historical playtime records found. Database unchanged.");
            for (int category = 0; category < 4; category++) {
                final int group = category;
                results.stream().filter(r -> (r.skippedReason() != null ? 3 : r.alreadyImported() ? 2 : r.addedMs() > 0 ? 0 : 1) == group)
                        .sorted(Comparator.comparingLong(LegacyPlaytimeResult::historicalMs).reversed()).limit(2).forEach(r -> {
                            lines.add((r.skippedReason() != null ? "Skipped: " : r.alreadyImported() ? "Already imported: " : r.addedMs() > 0 ? "Addition: " : "Unchanged: ")
                                    + (r.name() == null ? r.uuid() : r.name() + " (" + r.uuid() + ")"));
                            lines.add("History " + time(r.historicalMs()) + "; local existing " + time(r.existingMs()) + "; total " + time(r.totalMs()));
                            if (r.skippedReason() != null) lines.add(r.skippedReason() + " Legacy network: " + time(r.unattributedMs()));
                        });
            }
            scan.errors().stream().limit(3).forEach(e -> lines.add("Invalid: " + e));
            errors.stream().limit(3).forEach(e -> lines.add("Storage error: " + e));
            if (skipped > 0) lines.add("Skipped players: retry when logged out and normal playtime flush has completed.");
            if (!apply) lines.add("Run /paradigm importplaytime confirm to apply a fresh scan.");
            else lines.add("Committed decisions remain marked, including unchanged totals. Retry errors safely with confirm.");
            return lines;
        }
    }

    private static String time(long ms) { return ms / 3_600_000L + "h " + ms / 60_000L % 60L + "m"; }

    public static Scan scan(Path directory) throws IOException { return scan(directory, java.util.Map.of()); }

    public static Scan scan(Path directory, java.util.Map<String, Long> localAtStart) throws IOException {
        if (!Files.isDirectory(directory)) throw new IOException("No supported statistics directory: " + directory);
        var entries = new HashMap<String, Entry>();
        var seen = new HashSet<String>();
        List<String> errors = new ArrayList<>();
        int found = 0;
        try (var files = Files.list(directory)) {
            for (Path path : files.sorted().toList()) {
                String name = path.getFileName().toString();
                if (!name.endsWith(".json")) continue;
                found++;
                try {
                    String id = name.substring(0, name.length() - 5);
                    String uuid = UUID.fromString(id).toString();
                    if (!uuid.equalsIgnoreCase(id)) throw new IllegalArgumentException("non-canonical UUID filename (names are not matched)");
                    if (!seen.add(uuid)) {
                        if (entries.remove(uuid) != null) errors.add(uuid + ": duplicate UUID filename");
                        throw new IllegalArgumentException("duplicate UUID filename");
                    }
                    if (!Files.isRegularFile(path, LinkOption.NOFOLLOW_LINKS)) throw new IOException("not a regular statistics file");
                    try (Reader reader = Files.newBufferedReader(path, StandardCharsets.UTF_8)) {
                        entries.put(uuid, new Entry(uuid, parseMilliseconds(reader)));
                    }
                } catch (IOException | IllegalArgumentException | IllegalStateException failure) {
                    errors.add(name + ": " + failure.getMessage());
                }
            }
        }
        return new Scan(directory, found, entries.values().stream().sorted(Comparator.comparing(Entry::uuid)).toList(), List.copyOf(errors), java.util.Map.copyOf(localAtStart));
    }

    public static long parseMilliseconds(Reader input) throws IOException {
        JsonReader json = new JsonReader(input);
        json.setLenient(false);
        Long ticks = null;
        json.beginObject();
        while (json.hasNext()) {
            String key = json.nextName();
            Long value;
            if ("stat.playOneMinute".equals(key)) value = legacyTicks(json);
            else if ("stats".equals(key)) value = modernTicks(json, false);
            else { json.skipValue(); continue; }
            if (value != null) {
                if (ticks != null) throw new IllegalArgumentException("ambiguous or duplicate playtime statistics");
                ticks = value;
            }
        }
        json.endObject();
        if (json.peek() != JsonToken.END_DOCUMENT) throw new IllegalArgumentException("trailing statistics data");
        if (ticks == null || ticks == 0) throw new IllegalArgumentException("missing or zero playtime statistic");
        return ticks * 50L;
    }

    private static long legacyTicks(JsonReader json) throws IOException {
        if (json.peek() != JsonToken.BEGIN_OBJECT) return readTicks(json);
        Long ticks = null;
        json.beginObject();
        while (json.hasNext()) {
            if ("value".equals(json.nextName())) {
                if (ticks != null) throw new IllegalArgumentException("duplicate statistic value");
                ticks = readTicks(json);
            } else json.skipValue();
        }
        json.endObject();
        if (ticks == null) throw new IllegalArgumentException("missing statistic value");
        return ticks;
    }

    private static Long modernTicks(JsonReader json, boolean custom) throws IOException {
        Long ticks = null;
        json.beginObject();
        while (json.hasNext()) {
            String key = json.nextName();
            Long value;
            if (!custom && "minecraft:custom".equals(key)) value = modernTicks(json, true);
            else if (custom && ("minecraft:play_time".equals(key) || "minecraft:play_one_minute".equals(key))) value = readTicks(json);
            else { json.skipValue(); continue; }
            if (value != null) {
                if (ticks != null) throw new IllegalArgumentException("ambiguous or duplicate playtime statistics");
                ticks = value;
            }
        }
        json.endObject();
        return ticks;
    }

    private static long readTicks(JsonReader json) throws IOException {
        if (json.peek() != JsonToken.NUMBER) throw new IllegalArgumentException("playtime must be a numeric integer");
        String raw = json.nextString();
        if (!raw.matches("[0-9]+")) throw new IllegalArgumentException("negative or non-integer tick count");
        long ticks = Long.parseLong(raw);
        if (ticks > Integer.MAX_VALUE) throw new IllegalArgumentException("tick count exceeds the signed integer range");
        return ticks;
    }

    public static Report process(Scan scan, PlayerRepository repository, Set<String> blocked, boolean apply) {
        List<LegacyPlaytimeResult> results = new ArrayList<>();
        List<String> errors = new ArrayList<>();
        int skipped = 0;
        for (Entry entry : scan.entries()) {
            if (blocked.contains(entry.uuid())) { skipped++; continue; }
            try {
                results.add(repository.importLegacyPlaytime(entry.uuid(), entry.milliseconds(), scan.localAtStart().getOrDefault(entry.uuid(), 0L), apply));
            } catch (RuntimeException failure) {
                errors.add(entry.uuid() + ": " + failure.getMessage());
                break;
            }
        }
        return new Report(scan, List.copyOf(results), skipped, List.copyOf(errors));
    }
}
