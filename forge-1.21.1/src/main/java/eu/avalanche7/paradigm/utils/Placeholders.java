package eu.avalanche7.paradigm.utils;

import eu.avalanche7.paradigm.platform.Interfaces.IPlayer;
import eu.avalanche7.paradigm.platform.MinecraftPlayer;
import net.minecraft.server.level.ServerPlayer;

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

    public String replacePlaceholders(String text, IPlayer player) {
        if (text == null) return "";
        if (player == null) {
            return text;
        }
        ServerPlayer mcPlayer = player instanceof MinecraftPlayer ? ((MinecraftPlayer) player).getHandle() : null;
        if (mcPlayer == null) return text;
        String replacedText = text;
        replacedText = replacedText.replace("{player}", mcPlayer.getName().getString());
        replacedText = replacedText.replace("{player_name}", mcPlayer.getName().getString());
        replacedText = replacedText.replace("{player_uuid}", mcPlayer.getUUID().toString());
        replacedText = replacedText.replace("{player_level}", String.valueOf(mcPlayer.experienceLevel));
        replacedText = replacedText.replace("{player_health}", String.format("%.1f", mcPlayer.getHealth()));
        replacedText = replacedText.replace("{max_player_health}", String.format("%.1f", mcPlayer.getMaxHealth()));

        initLuckPerms();

        if (luckPermsAvailable != null && luckPermsAvailable) {
            replacedText = replaceLuckPermsPlaceholders(replacedText, mcPlayer);
        } else {
            replacedText = replacedText.replace("{player_prefix}", "");
            replacedText = replacedText.replace("{player_suffix}", "");
            replacedText = replacedText.replace("{player_group}", "");
            replacedText = replacedText.replace("{player_primary_group}", "");
            replacedText = replacedText.replace("{player_groups}", "");
        }

        return replacedText;
    }

    private String replaceLuckPermsPlaceholders(String text, ServerPlayer player) {
        try {
            Class<?> luckPermsClass = luckPerms.getClass();
            java.lang.reflect.Method getUserManagerMethod = luckPermsClass.getMethod("getUserManager");
            Object userManager = getUserManagerMethod.invoke(luckPerms);

            java.lang.reflect.Method loadUserMethod = userManager.getClass().getMethod("loadUser", java.util.UUID.class);
            Object completableFuture = loadUserMethod.invoke(userManager, player.getUUID());

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

            java.lang.reflect.Method getNodesMethod = user.getClass().getMethod("getNodes");
            java.util.Collection<?> nodes = (java.util.Collection<?>) getNodesMethod.invoke(user);

            java.util.List<String> groupNames = new java.util.ArrayList<>();
            for (Object node : nodes) {
                java.lang.reflect.Method getKeyMethod = node.getClass().getMethod("getKey");
                String key = (String) getKeyMethod.invoke(node);
                if (key != null && key.startsWith("group.")) {
                    groupNames.add(key.substring(6));
                }
            }

            String groups = String.join(", ", groupNames);
            if (!groups.isEmpty()) {
                replacedText = replacedText.replace("{player_groups}", groups);
            } else {
                replacedText = replacedText.replace("{player_groups}", "");
            }

            if (debugLogger != null) {
                debugLogger.debugLog("Final text after LP placeholders: '" + replacedText + "'");
            }
            return replacedText;
        } catch (Exception e) {
            if (debugLogger != null) {
                debugLogger.debugLog("Error replacing LP placeholders: " + e.getMessage());
            }
            return text;
        }
    }
}
