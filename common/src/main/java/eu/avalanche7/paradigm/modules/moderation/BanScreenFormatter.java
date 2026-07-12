package eu.avalanche7.paradigm.modules.moderation;

import eu.avalanche7.paradigm.configs.ModerationConfigHandler;
import eu.avalanche7.paradigm.core.Services;
import eu.avalanche7.paradigm.modules.commands.shared.DurationParser;
import eu.avalanche7.paradigm.platform.Interfaces.IComponent;

import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.LinkedHashMap;
import java.util.Map;

public final class BanScreenFormatter {
    private static final DateTimeFormatter DATE = DateTimeFormatter.ofPattern("dd MMM yyyy HH:mm").withZone(ZoneId.systemDefault());
    private final Services services;

    public BanScreenFormatter(Services services) { this.services = services; }

    public IComponent format(PunishmentRecord record) {
        var config = ModerationConfigHandler.getConfig();
        Map<String, String> values = placeholders(record, config.appealUrl.value);
        IComponent result = services.getPlatformAdapter().createEmptyComponent();
        if (!Boolean.TRUE.equals(config.banScreenEnabled.value)) {
            String titleKey = record.type() == PunishmentType.IP_BAN ? "moderation.ban_screen.ip_title" : "moderation.ban_screen.title";
            result.append(services.getPlatformAdapter().createComponentFromLiteral(services.getLang().getTranslation(titleKey)));
            result.append(services.getPlatformAdapter().createComponentFromLiteral("\n" + safe(record.reason())));
            return result;
        }
        boolean first = true;
        for (String configuredLine : config.banScreenLines.value) {
            if (!first) result.append(services.getPlatformAdapter().createComponentFromLiteral("\n"));
            first = false;
            String line = configuredLine == null ? "" : configuredLine;
            for (Map.Entry<String, String> entry : values.entrySet()) line = line.replace("{" + entry.getKey() + "}", entry.getValue());
            try { result.append(services.getMessageParser().parseMessage(line, null)); }
            catch (Throwable ignored) { result.append(services.getPlatformAdapter().createComponentFromLiteral(stripTags(line))); }
        }
        return result;
    }

    public Map<String, String> placeholders(PunishmentRecord record, String appealTemplate) {
        long now = System.currentTimeMillis();
        boolean permanent = record.expiresAtMs() == null;
        Map<String, String> values = new LinkedHashMap<>();
        values.put("punishment_id", record.punishmentId());
        values.put("punishment_type", record.type().name());
        values.put("player_name", safe(record.subjectName()));
        values.put("player_uuid", safe(record.subjectUuid()));
        values.put("reason", safe(record.reason()));
        values.put("actor", safe(record.actorName()));
        values.put("actor_uuid", safe(record.actorUuid()));
        values.put("created_at", DATE.format(Instant.ofEpochMilli(record.createdAtMs())));
        values.put("expires_at", permanent ? services.getLang().getTranslation("moderation.punishment.expiry.permanent") : DATE.format(Instant.ofEpochMilli(record.expiresAtMs())));
        values.put("expiry", permanent ? services.getLang().getTranslation("moderation.punishment.expiry.permanent") : DATE.format(Instant.ofEpochMilli(record.expiresAtMs())));
        values.put("remaining", permanent ? services.getLang().getTranslation("moderation.punishment.expiry.permanent") : DurationParser.describeRemaining(Math.max(now, record.expiresAtMs())));
        values.put("scope", record.scope().name().toLowerCase(java.util.Locale.ROOT));
        values.put("server_name", services.getStorageService().context().serverIdentity().serverName());
        values.put("server_id", safe(record.serverId()));
        values.put("network_id", safe(record.networkId()));
        values.put("appeal_url", replaceAppeal(appealTemplate, record.punishmentId()));
        return values;
    }

    private static String replaceAppeal(String template, String id) { return safe(template).replace("{punishment_id}", id); }
    private static String safe(String value) { return value != null ? value : ""; }
    private static String stripTags(String value) { return value.replaceAll("<[^>]*>", ""); }
}
