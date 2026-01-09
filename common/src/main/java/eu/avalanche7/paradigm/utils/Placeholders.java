package eu.avalanche7.paradigm.utils;

import eu.avalanche7.paradigm.platform.Interfaces.IPlayer;

public class Placeholders {

    private Object luckPerms;
    private Boolean luckPermsAvailable = null;

    public Placeholders() {
    }

    private void initLuckPerms() {
        if (luckPermsAvailable != null) return;

        try {
            Class<?> providerClass = Class.forName("net.luckperms.api.LuckPermsProvider");
            java.lang.reflect.Method getMethod = providerClass.getMethod("get");
            this.luckPerms = getMethod.invoke(null);
            this.luckPermsAvailable = true;
        } catch (Exception e) {
            this.luckPermsAvailable = false;
        }
    }

    /**
     * Agnostic placeholder resolving using IPlayer interface.
     */
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

            initLuckPerms();
            if (luckPermsAvailable != null && luckPermsAvailable) {
                replacedText = replaceLuckPermsPlaceholders(replacedText, player.getOriginalPlayer());
            } else {
                replacedText = stripLuckPermsPlaceholders(replacedText);
            }
            return replacedText;
        }

        replacedText = replacedText.replace("{player}", "");
        replacedText = replacedText.replace("{player_name}", "");
        replacedText = replacedText.replace("{player_uuid}", "");
        replacedText = replacedText.replace("{player_level}", "");
        replacedText = replacedText.replace("{player_health}", "");
        replacedText = replacedText.replace("{max_player_health}", "");
        replacedText = stripLuckPermsPlaceholders(replacedText);
        return replacedText;
    }

    /**
     * Backward-compatible resolver for native player instances (reflection).
     * Prefer using {@link #replacePlaceholders(String, IPlayer)} from common code.
     */
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

            initLuckPerms();

            if (luckPermsAvailable != null && luckPermsAvailable) {
                replacedText = replaceLuckPermsPlaceholders(replacedText, player);
            } else {
                replacedText = stripLuckPermsPlaceholders(replacedText);
            }
        } else {
            replacedText = replacedText.replace("{player}", "");
            replacedText = replacedText.replace("{player_name}", "");
            replacedText = replacedText.replace("{player_uuid}", "");
            replacedText = replacedText.replace("{player_level}", "");
            replacedText = replacedText.replace("{player_health}", "");
            replacedText = replacedText.replace("{max_player_health}", "");
            replacedText = stripLuckPermsPlaceholders(replacedText);
        }

        return replacedText;
    }

    private String stripLuckPermsPlaceholders(String text) {
        String replacedText = text;
        replacedText = replacedText.replace("{player_prefix}", "");
        replacedText = replacedText.replace("{player_suffix}", "");
        replacedText = replacedText.replace("{player_group}", "");
        replacedText = replacedText.replace("{player_primary_group}", "");
        replacedText = replacedText.replace("{player_groups}", "");
        return replacedText;
    }

    private String replaceLuckPermsPlaceholders(String text, Object player) {
        try {
            Object uuidObj = invoke(player, "getUuid");
            if (uuidObj == null) uuidObj = invoke(player, "getUUID");
            if (!(uuidObj instanceof java.util.UUID uuid)) {
                return stripLuckPermsPlaceholders(text);
            }

            Class<?> luckPermsClass = luckPerms.getClass();
            java.lang.reflect.Method getUserManagerMethod = luckPermsClass.getMethod("getUserManager");
            Object userManager = getUserManagerMethod.invoke(luckPerms);

            java.lang.reflect.Method loadUserMethod = userManager.getClass().getMethod("loadUser", java.util.UUID.class);
            Object completableFuture = loadUserMethod.invoke(userManager, uuid);

            java.lang.reflect.Method joinMethod = completableFuture.getClass().getMethod("join");
            Object user = joinMethod.invoke(completableFuture);

            if (user == null) return stripLuckPermsPlaceholders(text);

            String replacedText = text;

            java.lang.reflect.Method getPrimaryGroupMethod = user.getClass().getMethod("getPrimaryGroup");
            String primaryGroup = (String) getPrimaryGroupMethod.invoke(user);
            if (primaryGroup != null) {
                replacedText = replacedText.replace("{player_group}", primaryGroup);
                replacedText = replacedText.replace("{player_primary_group}", primaryGroup);
            } else {
                replacedText = replacedText.replace("{player_group}", "");
                replacedText = replacedText.replace("{player_primary_group}", "");
            }

            java.lang.reflect.Method getCachedDataMethod = user.getClass().getMethod("getCachedData");
            Object cachedData = getCachedDataMethod.invoke(user);

            java.lang.reflect.Method getMetaDataMethod = cachedData.getClass().getMethod("getMetaData");
            Object metaData = getMetaDataMethod.invoke(cachedData);

            java.lang.reflect.Method getPrefixMethod = metaData.getClass().getMethod("getPrefix");
            String prefix = (String) getPrefixMethod.invoke(metaData);
            replacedText = replacedText.replace("{player_prefix}", prefix != null ? prefix : "");

            java.lang.reflect.Method getSuffixMethod = metaData.getClass().getMethod("getSuffix");
            String suffix = (String) getSuffixMethod.invoke(metaData);
            replacedText = replacedText.replace("{player_suffix}", suffix != null ? suffix : "");

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
        // Fabric returns Text for getName(); try getString()
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
}
