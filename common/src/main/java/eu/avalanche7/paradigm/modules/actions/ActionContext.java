package eu.avalanche7.paradigm.modules.actions;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.jetbrains.annotations.Nullable;

import eu.avalanche7.paradigm.core.Services;
import eu.avalanche7.paradigm.platform.Interfaces.ICommandSource;
import eu.avalanche7.paradigm.platform.Interfaces.IComponent;
import eu.avalanche7.paradigm.platform.Interfaces.IPlayer;

public final class ActionContext {

    private static final String[] NO_ARGS = new String[0];
    private static final Pattern VALUE_TOKEN = Pattern.compile("\\{([a-z][a-z0-9_]*)\\}");

    private final Services services;
    private final ICommandSource source;
    private final IPlayer player;
    private final String[] argsTokens;
    private final String rawArgs;
    private final Map<String, String> values;
    private final Set<String> literalKeys;
    private final String origin;

    private ActionContext(Builder builder) {
        this.services = builder.services;
        this.source = builder.source;
        this.player = builder.player;
        this.argsTokens = builder.argsTokens != null ? builder.argsTokens.clone() : NO_ARGS;
        this.rawArgs = builder.rawArgs != null ? builder.rawArgs : String.join(" ", this.argsTokens);
        this.values = Collections.unmodifiableMap(new LinkedHashMap<>(builder.values));
        this.literalKeys = Collections.unmodifiableSet(new LinkedHashSet<>(builder.literalKeys));
        this.origin = builder.origin != null ? builder.origin : "unknown";
    }

    public static Builder builder(Services services) {
        return new Builder(services);
    }

    public Services services() {
        return services;
    }

    @Nullable
    public ICommandSource source() {
        return source;
    }

    @Nullable
    public IPlayer player() {
        return player;
    }

    public String[] argsTokens() {
        return argsTokens.clone();
    }

    public String rawArgs() {
        return rawArgs;
    }

    public String origin() {
        return origin;
    }

    public Map<String, String> values() {
        return values;
    }

    @Nullable
    public String value(String key) {
        return key != null ? values.get(key.trim().toLowerCase(Locale.ROOT)) : null;
    }

    public ActionContext withValue(String key, String value) {
        return toBuilder().value(key, value).build();
    }

    /**
     * Replies through the command source when there is one, so command feedback keeps
     * its normal routing, and falls back to a direct system message for sources that
     * only exist as a player (menu clicks, scheduled actions).
     */
    public void reply(String rawMessage) {
        reply(rawMessage, false);
    }

    public void replyFailure(String rawMessage) {
        reply(rawMessage, true);
    }

    private void reply(String rawMessage, boolean failure) {
        if (services == null || services.getPlatformAdapter() == null || services.getMessageParser() == null) {
            return;
        }
        IComponent message = services.getMessageParser().parseMessage(rawMessage, player);
        if (source != null) {
            if (failure) {
                services.getPlatformAdapter().sendFailure(source, message);
            } else {
                services.getPlatformAdapter().sendSuccess(source, message, false);
            }
        } else if (player != null) {
            services.getPlatformAdapter().sendSystemMessage(player, message);
        }
    }

    public Builder toBuilder() {
        Builder builder = new Builder(services)
                .source(source)
                .player(player)
                .args(argsTokens)
                .rawArgs(rawArgs)
                .origin(origin);
        builder.values.putAll(values);
        builder.literalKeys.addAll(literalKeys);
        return builder;
    }

    public String expand(@Nullable String text) {
        if (text == null || text.isEmpty()) {
            return "";
        }
        String out = text;
        for (Map.Entry<String, String> entry : values.entrySet()) {
            if (literalKeys.contains(entry.getKey())) continue;
            String token = "{" + entry.getKey() + "}";
            if (out.contains(token)) {
                out = out.replace(token, entry.getValue() != null ? entry.getValue() : "");
            }
        }
        if (services != null && services.getPlatformAdapter() != null) {
            out = services.getPlatformAdapter().replacePlaceholders(out, player);
        }
        for (int i = argsTokens.length - 1; i >= 0; i--) {
            String token = "$" + (i + 1);
            if (out.contains(token)) {
                String argValue = argsTokens[i];
                out = out.replace(token, argValue != null ? argValue : "");
            }
        }
        if (out.contains("$*")) {
            out = out.replace("$*", rawArgs != null ? rawArgs : "");
        }
        out = out.replaceAll("\\$[1-9][0-9]*", "");
        if (literalKeys.isEmpty()) return out.trim();
        Matcher matcher = VALUE_TOKEN.matcher(out);
        StringBuffer expanded = new StringBuffer();
        while (matcher.find()) {
            String key = matcher.group(1);
            matcher.appendReplacement(expanded, literalKeys.contains(key)
                    ? Matcher.quoteReplacement(values.get(key)) : Matcher.quoteReplacement(matcher.group()));
        }
        matcher.appendTail(expanded);
        return expanded.toString().trim();
    }

    public static final class Builder {
        private final Services services;
        private final Map<String, String> values = new LinkedHashMap<>();
        private final Set<String> literalKeys = new LinkedHashSet<>();
        private ICommandSource source;
        private IPlayer player;
        private String[] argsTokens;
        private String rawArgs;
        private String origin;

        private Builder(Services services) {
            this.services = services;
        }

        public Builder source(@Nullable ICommandSource source) {
            this.source = source;
            if (this.player == null && source != null) {
                this.player = source.getPlayer();
            }
            return this;
        }

        public Builder player(@Nullable IPlayer player) {
            this.player = player;
            return this;
        }

        public Builder args(@Nullable String[] argsTokens) {
            this.argsTokens = argsTokens;
            return this;
        }

        public Builder rawArgs(@Nullable String rawArgs) {
            this.rawArgs = rawArgs;
            return this;
        }

        public Builder origin(@Nullable String origin) {
            this.origin = origin;
            return this;
        }

        public Builder value(String key, @Nullable String value) {
            if (key != null && !key.isBlank()) {
                values.put(key.trim().toLowerCase(Locale.ROOT), value != null ? value : "");
                literalKeys.remove(key.trim().toLowerCase(Locale.ROOT));
            }
            return this;
        }

        public Builder literalValue(String key, @Nullable String value) {
            value(key, value);
            if (key != null && !key.isBlank()) literalKeys.add(key.trim().toLowerCase(Locale.ROOT));
            return this;
        }

        public ActionContext build() {
            return new ActionContext(this);
        }
    }
}
