package eu.avalanche7.paradigm.utils;

import java.util.List;
import java.util.function.Function;
import java.util.function.ToIntFunction;

import eu.avalanche7.paradigm.api.internal.ApiProviderRegistry;
import eu.avalanche7.paradigm.platform.Interfaces.IPlayer;

public class Placeholders {

    private Boolean luckPermsClassPresent = null;
    private static volatile Function<IPlayer, PermissionMeta> permissionMetaResolver;
    private static volatile ToIntFunction<IPlayer> pingResolver;
    private static volatile Function<IPlayer, PlayerActivity> activityResolver;

    public Placeholders() {
    }

    public static void setPermissionMetaResolver(Function<IPlayer, PermissionMeta> resolver) {
        permissionMetaResolver = resolver;
    }

    public static void setPingResolver(ToIntFunction<IPlayer> resolver) {
        pingResolver = resolver;
    }

    public static void setActivityResolver(Function<IPlayer, PlayerActivity> resolver) {
        activityResolver = resolver;
    }

    private Object resolveLuckPerms() {
        if (luckPermsClassPresent == null) {
            try {
                Class.forName("net.luckperms.api.LuckPermsProvider", false, Placeholders.class.getClassLoader());
                luckPermsClassPresent = true;
            } catch (ClassNotFoundException | LinkageError absent) {
                luckPermsClassPresent = false;
            }
        }
        if (!luckPermsClassPresent) {
            return null;
        }
        try {
            Class<?> providerClass = Class.forName("net.luckperms.api.LuckPermsProvider");
            return providerClass.getMethod("get").invoke(null);
        } catch (Exception notReadyYet) {
            return null;
        }
    }

    public String replacePlaceholders(String text, IPlayer player) {
        if (text == null) return "";

        String replacedText = text;

        if (player != null) {
            String name = player.getName();
            String uuidStr = player.getUUID();

            replacedText = replacedText.replace("{player}", safe(name));
            replacedText = replacedText.replace("{player_name}", safe(name));
            replacedText = replacedText.replace("{player_uuid}", safe(uuidStr));

            if (replacedText.contains("{player_level}")) {
                Integer lvl = player.getLevel();
                replacedText = replacedText.replace("{player_level}", lvl != null ? String.valueOf(lvl) : "");
            }
            if (replacedText.contains("{player_health}")) {
                Double hp = player.getHealth();
                replacedText = replacedText.replace("{player_health}", hp != null ? format1(hp) : "");
            }
            if (replacedText.contains("{max_player_health}")) {
                Double mhp = player.getMaxHealth();
                replacedText = replacedText.replace("{max_player_health}", mhp != null ? format1(mhp) : "");
            }
            if (replacedText.contains("{player_world}") || replacedText.contains("{player_dimension}")) {
                replacedText = replaceWorldPlaceholders(replacedText, player.getWorldId());
            }
            if (replacedText.contains("{player_ping}")) {
                replacedText = replacedText.replace("{player_ping}", resolvePing(player));
            }
            replacedText = replaceActivityPlaceholders(replacedText, player);

            Object luckPerms = resolveLuckPerms();
            if (luckPerms != null) {
                replacedText = replaceLuckPermsPlaceholders(replacedText, player.getOriginalPlayer(), luckPerms);
            } else {
                replacedText = replaceInternalPermissionPlaceholders(replacedText, player);
            }
            return resolveExternal(replacedText, uuidStr);
        }

        replacedText = replacedText.replace("{player}", "");
        replacedText = replacedText.replace("{player_name}", "");
        replacedText = replacedText.replace("{player_uuid}", "");
        replacedText = replacedText.replace("{player_level}", "");
        replacedText = replacedText.replace("{player_health}", "");
        replacedText = replacedText.replace("{max_player_health}", "");
        replacedText = stripRuntimePlaceholders(replacedText);
        replacedText = stripActivityPlaceholders(replacedText);
        replacedText = stripLuckPermsPlaceholders(replacedText);
        replacedText = stripInternalGroupPlaceholders(replacedText);
        return resolveExternal(replacedText, null);
    }

    public String replacePlaceholders(String text, Object player) {
        if (text == null) return "";

        if (player instanceof IPlayer ip) {
            return replacePlaceholders(text, ip);
        }

        String replacedText = text;

        if (player != null) {
            String name = null;
            Object profile = invoke(player, "getGameProfile");
            if (profile != null) {
                Object pn = invoke(profile, "getName");
                if (pn != null) {
                    name = String.valueOf(pn);
                }
            }

            if (name == null || name.isEmpty()) {
                Object nObj = invoke(player, "getName");
                name = invokeToString(nObj);
            }

            if (name == null || name.isEmpty()) {
                Object nObj = invoke(player, "getName");
                Object str = invoke(nObj, "getString");
                if (str == null) str = invoke(nObj, "asString");
                if (str != null) name = String.valueOf(str);
            }

            String uuidStr = invokeToString(invoke(player, "getUuidAsString"));
            if (uuidStr == null) {
                Object uuid = invoke(player, "getUUID");
                if (uuid == null) uuid = invoke(player, "getUuid");
                if (uuid != null) uuidStr = String.valueOf(uuid);
            }

            String level = invokeIntField(player, "experienceLevel");
            if (level == null) {
                Object xp = invoke(player, "experienceLevel");
                if (xp != null) level = String.valueOf(xp);
            }

            String health = invokeFloatLike(invoke(player, "getHealth"));
            String maxHealth = invokeFloatLike(invoke(player, "getMaxHealth"));

            replacedText = replacedText.replace("{player}", safe(name));
            replacedText = replacedText.replace("{player_name}", safe(name));
            replacedText = replacedText.replace("{player_uuid}", safe(uuidStr));

            if (replacedText.contains("{player_level}")) {
                replacedText = replacedText.replace("{player_level}", safe(level));
            }
            if (replacedText.contains("{player_health}")) {
                replacedText = replacedText.replace("{player_health}", safe(health));
            }
            if (replacedText.contains("{max_player_health}")) {
                replacedText = replacedText.replace("{max_player_health}", safe(maxHealth));
            }
            replacedText = stripRuntimePlaceholders(replacedText);
            replacedText = stripActivityPlaceholders(replacedText);

            Object luckPerms = resolveLuckPerms();
            if (luckPerms != null) {
                replacedText = replaceLuckPermsPlaceholders(replacedText, player, luckPerms);
            } else {
                replacedText = replaceInternalPermissionPlaceholders(replacedText, null);
            }
        } else {
            replacedText = replacedText.replace("{player}", "");
            replacedText = replacedText.replace("{player_name}", "");
            replacedText = replacedText.replace("{player_uuid}", "");
            replacedText = replacedText.replace("{player_level}", "");
            replacedText = replacedText.replace("{player_health}", "");
            replacedText = replacedText.replace("{max_player_health}", "");
            replacedText = stripActivityPlaceholders(replacedText);
            replacedText = stripLuckPermsPlaceholders(replacedText);
            replacedText = stripInternalGroupPlaceholders(replacedText);
        }

        String uuid = null;
        if (player != null) {
            Object rawUuid = invoke(player, "getUUID");
            if (rawUuid == null) rawUuid = invoke(player, "getUuid");
            if (rawUuid != null) uuid = String.valueOf(rawUuid);
        }
        return resolveExternal(replacedText, uuid);
    }

    private static String resolveExternal(String text, String playerUuid) {
        java.util.UUID uuid = null;
        try {
            if (playerUuid != null && !playerUuid.isBlank()) uuid = java.util.UUID.fromString(playerUuid);
        } catch (IllegalArgumentException ignored) {
        }
        return ApiProviderRegistry.resolveExternalPlaceholders(text, uuid);
    }

    private static String replaceWorldPlaceholders(String text, String worldId) {
        if (!text.contains("{player_world}") && !text.contains("{player_dimension}")) {
            return text;
        }
        String world = safe(worldId);
        int separator = world.indexOf(':');
        String dimension = separator >= 0 && separator + 1 < world.length() ? world.substring(separator + 1) : world;
        return text.replace("{player_world}", world).replace("{player_dimension}", dimension);
    }

    private static String resolvePing(IPlayer player) {
        ToIntFunction<IPlayer> resolver = pingResolver;
        if (resolver == null) {
            return "";
        }
        try {
            return Integer.toString(Math.max(0, resolver.applyAsInt(player)));
        } catch (RuntimeException | LinkageError unavailable) {
            return "";
        }
    }

    private static String stripRuntimePlaceholders(String text) {
        return text
                .replace("{player_world}", "")
                .replace("{player_dimension}", "")
                .replace("{player_ping}", "");
    }

    private static String replaceActivityPlaceholders(String text, IPlayer player) {
        if (!containsActivityToken(text)) {
            return text;
        }

        Function<IPlayer, PlayerActivity> resolver = activityResolver;
        PlayerActivity activity = null;
        if (resolver != null && player != null) {
            try {
                activity = resolver.apply(player);
            } catch (RuntimeException | LinkageError ignored) {
                activity = null;
            }
        }
        if (activity == null) {
            return stripActivityPlaceholders(text);
        }

        long playtime = activity.playtimeMs();
        boolean playtimeKnown = playtime >= 0L;
        return text
                .replace("{afk}", activity.afk() ? safe(activity.afkTag()) : "")
                .replace("{is_afk}", Boolean.toString(activity.afk()))
                .replace("{playtime}", playtimeKnown ? DurationFormatter.humanize(playtime) : "")
                .replace("{playtime_short}", playtimeKnown ? DurationFormatter.compact(playtime) : "")
                .replace("{playtime_hours}", playtimeKnown ? Long.toString(DurationFormatter.wholeHours(playtime)) : "");
    }

    private static boolean containsActivityToken(String text) {
        return text.contains("{afk}")
                || text.contains("{is_afk}")
                || text.contains("{playtime}")
                || text.contains("{playtime_short}")
                || text.contains("{playtime_hours}");
    }

    private static String stripActivityPlaceholders(String text) {
        return text
                .replace("{afk}", "")
                .replace("{is_afk}", "false")
                .replace("{playtime}", "")
                .replace("{playtime_short}", "")
                .replace("{playtime_hours}", "");
    }

    private String stripLuckPermsPlaceholders(String text) {
        String replacedText = text;
        replacedText = replacedText.replace("{player_prefix}", "");
        replacedText = replacedText.replace("{player_suffix}", "");
        replacedText = replacedText.replace("{player_group}", "");
        replacedText = replacedText.replace("{player_primary_group}", "");
        replacedText = replacedText.replace("{player_groups}", "");
        replacedText = replacedText.replace("{prefix}", "");
        replacedText = replacedText.replace("{suffix}", "");
        replacedText = replacedText.replace("{group}", "");
        return replacedText;
    }

    private String stripInternalGroupPlaceholders(String text) {
        String replacedText = text;
        replacedText = replacedText.replace("{player_prefix}", "");
        replacedText = replacedText.replace("{player_suffix}", "");
        replacedText = replacedText.replace("{player_group}", "");
        replacedText = replacedText.replace("{player_primary_group}", "");
        replacedText = replacedText.replace("{player_groups}", "");
        replacedText = replacedText.replace("{prefix}", "");
        replacedText = replacedText.replace("{suffix}", "");
        replacedText = replacedText.replace("{group}", "");
        return replacedText;
    }

    private String replaceInternalPermissionPlaceholders(String text, IPlayer player) {
        if (text == null) {
            return "";
        }

        Function<IPlayer, PermissionMeta> resolver = permissionMetaResolver;
        if (resolver == null || player == null) {
            return stripInternalGroupPlaceholders(text);
        }

        PermissionMeta meta;
        try {
            meta = resolver.apply(player);
        } catch (Throwable ignored) {
            meta = null;
        }
        if (meta == null) {
            return stripInternalGroupPlaceholders(text);
        }

        String group = safe(meta.primaryGroup());
        String prefix = safe(meta.prefix());
        String suffix = safe(meta.suffix());
        String groups = meta.groups() != null ? String.join(",", meta.groups()) : "";

        String replaced = text;
        replaced = replaced.replace("{player_group}", group);
        replaced = replaced.replace("{player_primary_group}", group);
        replaced = replaced.replace("{player_prefix}", prefix);
        replaced = replaced.replace("{player_suffix}", suffix);
        replaced = replaced.replace("{player_groups}", groups);

        replaced = replaced.replace("{group}", group);
        replaced = replaced.replace("{prefix}", prefix);
        replaced = replaced.replace("{suffix}", suffix);
        return replaced;
    }

    private String replaceLuckPermsPlaceholders(String text, Object player, Object luckPerms) {
        try {
            Object uuidObj = invoke(player, "getUuid");
            if (uuidObj == null) uuidObj = invoke(player, "getUUID");
            if (!(uuidObj instanceof java.util.UUID uuid)) {
                return stripLuckPermsPlaceholders(text);
            }

            Class<?> luckPermsClass = luckPerms.getClass();
            java.lang.reflect.Method getUserManagerMethod = luckPermsClass.getMethod("getUserManager");
            Object userManager = getUserManagerMethod.invoke(luckPerms);

            java.lang.reflect.Method getUserMethod = userManager.getClass().getMethod("getUser", java.util.UUID.class);
            Object user = getUserMethod.invoke(userManager, uuid);

            if (user == null) return stripLuckPermsPlaceholders(text);

            String replacedText = text;

            java.lang.reflect.Method getPrimaryGroupMethod = user.getClass().getMethod("getPrimaryGroup");
            String primaryGroup = (String) getPrimaryGroupMethod.invoke(user);
            if (primaryGroup != null) {
                replacedText = replacedText.replace("{player_group}", primaryGroup);
                replacedText = replacedText.replace("{player_primary_group}", primaryGroup);
                replacedText = replacedText.replace("{group}", primaryGroup);
            } else {
                replacedText = replacedText.replace("{player_group}", "");
                replacedText = replacedText.replace("{player_primary_group}", "");
                replacedText = replacedText.replace("{group}", "");
            }

            java.lang.reflect.Method getCachedDataMethod = user.getClass().getMethod("getCachedData");
            Object cachedData = getCachedDataMethod.invoke(user);

            java.lang.reflect.Method getMetaDataMethod = cachedData.getClass().getMethod("getMetaData");
            Object metaData = getMetaDataMethod.invoke(cachedData);

            java.lang.reflect.Method getPrefixMethod = metaData.getClass().getMethod("getPrefix");
            String prefix = (String) getPrefixMethod.invoke(metaData);
            replacedText = replacedText.replace("{player_prefix}", prefix != null ? prefix : "");
            replacedText = replacedText.replace("{prefix}", prefix != null ? prefix : "");

            java.lang.reflect.Method getSuffixMethod = metaData.getClass().getMethod("getSuffix");
            String suffix = (String) getSuffixMethod.invoke(metaData);
            replacedText = replacedText.replace("{player_suffix}", suffix != null ? suffix : "");
            replacedText = replacedText.replace("{suffix}", suffix != null ? suffix : "");

            replacedText = replacedText.replace("{player_groups}", "");

            return replacedText;
        } catch (Exception e) {
            return stripLuckPermsPlaceholders(text);
        }
    }

    private static Object invoke(Object target, String method) {
        if (target == null) return null;
        try {
            java.lang.reflect.Method m = target.getClass().getMethod(method);
            m.setAccessible(true);
            return m.invoke(target);
        } catch (Exception ignored) {
            return null;
        }
    }

    private static String invokeToString(Object obj) {
        if (obj == null) return null;

        Object s = invoke(obj, "getString");
        if (s != null) return String.valueOf(s);
        return String.valueOf(obj);
    }

    private static String safe(String s) {
        return s == null ? "" : s;
    }

    private static String invokeIntField(Object target, String fieldName) {
        try {
            java.lang.reflect.Field f = target.getClass().getField(fieldName);
            f.setAccessible(true);
            Object v = f.get(target);
            return v != null ? String.valueOf(v) : null;
        } catch (Exception ignored) {
            return null;
        }
    }

    private static String invokeFloatLike(Object v) {
        if (v == null) return "";
        try {
            double d;
            if (v instanceof Number n) {
                d = n.doubleValue();
            } else {
                d = Double.parseDouble(String.valueOf(v));
            }
            return String.format(java.util.Locale.ROOT, "%.1f", d);
        } catch (Exception e) {
            return String.valueOf(v);
        }
    }

    private static String format1(double d) {
        return String.format(java.util.Locale.ROOT, "%.1f", d);
    }

    public record PermissionMeta(String primaryGroup, String prefix, String suffix, List<String> groups) {
    }

    public record PlayerActivity(boolean afk, String afkTag, long playtimeMs) {
        public static PlayerActivity withoutPlaytime(boolean afk, String afkTag) {
            return new PlayerActivity(afk, afkTag, -1L);
        }
    }
}
