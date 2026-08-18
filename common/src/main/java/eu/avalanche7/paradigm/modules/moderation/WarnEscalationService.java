package eu.avalanche7.paradigm.modules.moderation;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;

import eu.avalanche7.paradigm.configs.ModerationConfigHandler;
import eu.avalanche7.paradigm.core.Services;
import eu.avalanche7.paradigm.modules.commands.shared.DurationParser;
import eu.avalanche7.paradigm.storage.identity.ServerScope;
import eu.avalanche7.paradigm.utils.DurationFormatter;

public final class WarnEscalationService {
    public static final String METADATA_SOURCE = "source";
    public static final String SOURCE_WARN_ESCALATION = "warn_escalation";
    public static final String METADATA_RULES = "escalationRules";
    public static final String METADATA_WARNINGS = "escalationWarnings";
    public static final String METADATA_TRIGGER = "escalationTriggerId";

    private static final int HISTORY_LIMIT = 500;

    private final Services services;

    public WarnEscalationService(Services services) {
        this.services = services;
    }

    public record Rule(int warnings, long windowMs, long banMs, String window, String reason) {
        public String key() {
            return warnings + "@" + window;
        }
    }

    public record Result(PunishmentRecord punishment, Rule rule, int warningCount) {
    }

    public boolean isEnabled() {
        return services != null
                && Boolean.TRUE.equals(ModerationConfigHandler.getConfig().warnEscalationEnabled.get());
    }

    public static List<Rule> parseRules(List<ModerationConfigHandler.EscalationRule> configured) {
        List<Rule> rules = new ArrayList<>();
        if (configured == null) {
            return rules;
        }
        for (ModerationConfigHandler.EscalationRule raw : configured) {
            if (raw == null || raw.warnings <= 0) {
                continue;
            }
            String window = normalize(raw.window);
            long windowMs = DurationParser.parseToMillis(window);
            long banMs = DurationParser.parseToMillis(normalize(raw.banDuration));
            if (windowMs <= 0L || banMs <= 0L) {
                continue;
            }
            rules.add(new Rule(raw.warnings, windowMs, banMs, window, raw.reason != null ? raw.reason.trim() : ""));
        }
        return rules;
    }

    public Result evaluate(String subjectUuid, String subjectName, PunishmentRecord trigger,
                           String actorUuid, String actorName) {
        try {
            return evaluateInternal(subjectUuid, subjectName, trigger, actorUuid, actorName);
        } catch (RuntimeException | LinkageError failure) {
            if (services != null && services.getLogger() != null) {
                services.getLogger().warn("[Paradigm] Moderation: warn escalation failed; the warning itself was still recorded.",
                        failure);
            }
            return null;
        }
    }

    private Result evaluateInternal(String subjectUuid, String subjectName, PunishmentRecord trigger,
                                    String actorUuid, String actorName) {
        if (!isEnabled() || !isValidUuid(subjectUuid)) {
            return null;
        }
        List<Rule> rules = parseRules(ModerationConfigHandler.getConfig().warnEscalationRules.get());
        if (rules.isEmpty()) {
            return null;
        }

        List<PunishmentRecord> history = services.getStorageService().moderation()
                .listPunishmentRecords(subjectUuid, 0, HISTORY_LIMIT);
        if (history == null || history.isEmpty()) {
            return null;
        }

        List<PunishmentRecord> warnings = new ArrayList<>();
        List<PunishmentRecord> escalations = new ArrayList<>();
        for (PunishmentRecord record : history) {
            if (record == null) {
                continue;
            }
            if (record.type() == PunishmentType.WARN && record.revokedAtMs() == null) {
                warnings.add(record);
            }
            if (SOURCE_WARN_ESCALATION.equals(record.metadata().get(METADATA_SOURCE))) {
                escalations.add(record);
            }
        }
        if (warnings.isEmpty()) {
            return null;
        }

        long now = System.currentTimeMillis();
        Decision decision = decide(rules, warnings, escalations, now);
        if (decision == null) {
            return null;
        }

        Rule selected = decision.rule();
        int count = decision.warningCount();
        String reason = renderReason(selected, subjectName, count);
        Map<String, String> metadata = new LinkedHashMap<>();
        metadata.put(METADATA_SOURCE, SOURCE_WARN_ESCALATION);
        metadata.put(METADATA_RULES, String.join(",", decision.ruleKeys()));
        metadata.put(METADATA_WARNINGS, Integer.toString(count));
        if (trigger != null) {
            metadata.put(METADATA_TRIGGER, trigger.punishmentId());
        }

        PunishmentRecord punishment = services.getPunishmentService().create(
                PunishmentType.BAN,
                ServerScope.GLOBAL,
                subjectUuid,
                subjectName,
                null,
                reason,
                actorUuid,
                actorName,
                now + selected.banMs(),
                metadata);
        return new Result(punishment, selected, count);
    }

    public record Decision(Rule rule, int warningCount, List<String> ruleKeys) {
    }

    public static Decision decide(List<Rule> rules, List<PunishmentRecord> warnings,
                                  List<PunishmentRecord> escalations, long nowMs) {
        if (rules == null || rules.isEmpty() || warnings == null || warnings.isEmpty()) {
            return null;
        }
        Map<Rule, Integer> triggered = new LinkedHashMap<>();
        for (Rule rule : rules) {
            long since = Math.max(nowMs - rule.windowMs(), lastEscalationMs(escalations, rule.key()));
            int count = 0;
            for (PunishmentRecord warning : warnings) {
                if (warning != null && warning.createdAtMs() > since) {
                    count++;
                }
            }
            if (count >= rule.warnings()) {
                triggered.put(rule, count);
            }
        }
        if (triggered.isEmpty()) {
            return null;
        }

        Rule selected = null;
        for (Rule rule : triggered.keySet()) {
            if (selected == null
                    || rule.banMs() > selected.banMs()
                    || (rule.banMs() == selected.banMs() && rule.warnings() > selected.warnings())) {
                selected = rule;
            }
        }
        return new Decision(selected, triggered.get(selected), triggered.keySet().stream().map(Rule::key).toList());
    }

    private String renderReason(Rule rule, String subjectName, int count) {
        String template = !rule.reason().isBlank()
                ? rule.reason()
                : ModerationConfigHandler.getConfig().warnEscalationReason.get();
        if (template == null || template.isBlank()) {
            template = "Automatic escalation: {count} warnings within {window}.";
        }
        return template
                .replace("{count}", Integer.toString(count))
                .replace("{window}", rule.window())
                .replace("{duration}", DurationFormatter.compact(rule.banMs()))
                .replace("{player}", subjectName != null ? subjectName : "");
    }

    private static long lastEscalationMs(List<PunishmentRecord> escalations, String ruleKey) {
        long latest = 0L;
        if (escalations == null) {
            return latest;
        }
        for (PunishmentRecord record : escalations) {
            String rules = record.metadata().get(METADATA_RULES);
            if (rules == null || rules.isBlank()) {
                continue;
            }
            for (String entry : rules.split(",")) {
                if (entry.trim().equalsIgnoreCase(ruleKey)) {
                    latest = Math.max(latest, record.createdAtMs());
                    break;
                }
            }
        }
        return latest;
    }

    private static boolean isValidUuid(String value) {
        if (value == null || value.isBlank()) {
            return false;
        }
        try {
            UUID.fromString(value.trim());
            return true;
        } catch (IllegalArgumentException ignored) {
            return false;
        }
    }

    private static String normalize(String value) {
        return value != null ? value.trim().toLowerCase(Locale.ROOT) : "";
    }
}
