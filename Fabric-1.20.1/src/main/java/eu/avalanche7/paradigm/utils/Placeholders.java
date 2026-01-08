package eu.avalanche7.paradigm.utils;

import net.minecraft.server.network.ServerPlayerEntity;

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

    public String replacePlaceholders(String text, ServerPlayerEntity player) {
        if (text == null) return "";
        if (player == null) {
            return text;
        }

        String replacedText = text;
        replacedText = replacedText.replace("{player}", player.getName().getString());
        replacedText = replacedText.replace("{player_name}", player.getName().getString());
        replacedText = replacedText.replace("{player_uuid}", player.getUuidAsString());
        replacedText = replacedText.replace("{player_level}", String.valueOf(player.experienceLevel));
        replacedText = replacedText.replace("{player_health}", String.format("%.1f", player.getHealth()));
        replacedText = replacedText.replace("{max_player_health}", String.format("%.1f", player.getMaxHealth()));

        initLuckPerms();

        if (luckPermsAvailable != null && luckPermsAvailable) {
            replacedText = replaceLuckPermsPlaceholders(replacedText, player);
        } else {
            replacedText = replacedText.replace("{player_prefix}", "");
            replacedText = replacedText.replace("{player_suffix}", "");
            replacedText = replacedText.replace("{player_group}", "");
            replacedText = replacedText.replace("{player_primary_group}", "");
            replacedText = replacedText.replace("{player_groups}", "");
        }

        return replacedText;
    }

    private String replaceLuckPermsPlaceholders(String text, ServerPlayerEntity player) {
        try {
            Class<?> luckPermsClass = luckPerms.getClass();
            java.lang.reflect.Method getUserManagerMethod = luckPermsClass.getMethod("getUserManager");
            Object userManager = getUserManagerMethod.invoke(luckPerms);

            java.lang.reflect.Method loadUserMethod = userManager.getClass().getMethod("loadUser", java.util.UUID.class);
            Object completableFuture = loadUserMethod.invoke(userManager, player.getUuid());

            java.lang.reflect.Method joinMethod = completableFuture.getClass().getMethod("join");
            Object user = joinMethod.invoke(completableFuture);

            if (user == null) return text;

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
            if (prefix != null) {
                replacedText = replacedText.replace("{player_prefix}", prefix);
            } else {
                replacedText = replacedText.replace("{player_prefix}", "");
            }

            java.lang.reflect.Method getSuffixMethod = metaData.getClass().getMethod("getSuffix");
            String suffix = (String) getSuffixMethod.invoke(metaData);
            if (suffix != null) {
                replacedText = replacedText.replace("{player_suffix}", suffix);
            } else {
                replacedText = replacedText.replace("{player_suffix}", "");
            }

            java.lang.reflect.Method getInheritedGroupsMethod = user.getClass().getMethod("getInheritedGroups", Class.forName("net.luckperms.api.query.QueryOptions"));

            Class<?> queryOptionsClass = Class.forName("net.luckperms.api.query.QueryOptions");
            java.lang.reflect.Method builderMethod = queryOptionsClass.getMethod("builder");
            Object builder = builderMethod.invoke(null);
            java.lang.reflect.Method buildMethod = builder.getClass().getMethod("build");
            Object queryOptions = buildMethod.invoke(builder);

            @SuppressWarnings("unchecked")
            java.util.Collection<Object> groups = (java.util.Collection<Object>) getInheritedGroupsMethod.invoke(user, queryOptions);
            StringBuilder groupsBuilder = new StringBuilder();
            if (groups != null) {
                for (Object group : groups) {
                    if (groupsBuilder.length() > 0) groupsBuilder.append(", ");
                    java.lang.reflect.Method getNameMethod = group.getClass().getMethod("getName");
                    groupsBuilder.append((String) getNameMethod.invoke(group));
                }
            }
            replacedText = replacedText.replace("{player_groups}", groupsBuilder.toString());

            return replacedText;
        } catch (Exception e) {
            return text;
        }
    }
}

