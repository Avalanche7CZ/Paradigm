package eu.avalanche7.paradigm.webeditor;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import eu.avalanche7.paradigm.ParadigmAPI;
import eu.avalanche7.paradigm.ParadigmConstants;
import eu.avalanche7.paradigm.configs.*;
import eu.avalanche7.paradigm.core.Services;

import java.io.BufferedReader;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.net.HttpURLConnection;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

public final class EditorApplier {
    private static final Gson GSON = new GsonBuilder().disableHtmlEscaping().setPrettyPrinting().create();
    private static final String USER_AGENT = "paradigm/editor";

    public static final class ApplyResult {
        public final int applied;
        public final int unchanged;
        public final String message;
        public ApplyResult(int applied, int unchanged, String message) {
            this.applied = applied;
            this.unchanged = unchanged;
            this.message = message;
        }
    }

    private EditorApplier() {}

    private static JsonObject downloadFromBytebinOrThrow(String code) throws Exception {
        String url = WebEditorSession.BYTEBIN_BASE_URL + code;
        HttpURLConnection conn = (HttpURLConnection) URI.create(url).toURL().openConnection();
        conn.setRequestMethod("GET");
        conn.setRequestProperty("User-Agent", USER_AGENT);
        conn.setRequestProperty("Accept", "application/json, text/plain;q=0.5, */*;q=0.1");
        conn.setConnectTimeout(8000);
        conn.setReadTimeout(10000);
        int status = conn.getResponseCode();
        if (status < 200 || status >= 300) {
            String msg;
            try {
                msg = conn.getResponseMessage();
            } catch (Exception e) {
                msg = null;
            }
            InputStream es = null;
            String errBody = null;
            try {
                es = conn.getErrorStream();
                if (es != null) {
                    errBody = new String(es.readAllBytes(), StandardCharsets.UTF_8);
                }
            } catch (Exception ignored) {
            } finally {
                if (es != null) try { es.close(); } catch (Exception ignored) {}
            }
            String respCt = null;
            String respCe = null;
            try { respCt = conn.getHeaderField("Content-Type"); } catch (Throwable ignored) {}
            try { respCe = conn.getHeaderField("Content-Encoding"); } catch (Throwable ignored) {}
            conn.disconnect();
            throw new Exception("Bytebin GET failed: url=" + url + ", HTTP " + status + (msg != null ? " (" + msg + ")" : "") + (respCt != null ? ", contentType=" + respCt : "") + (respCe != null ? ", contentEncoding=" + respCe : "") + (errBody != null && !errBody.isEmpty() ? ", body=" + errBody : ""));
        }
        try (BufferedReader br = new BufferedReader(new InputStreamReader(conn.getInputStream(), StandardCharsets.UTF_8))) {
            StringBuilder sb = new StringBuilder();
            String line;
            while ((line = br.readLine()) != null) sb.append(line);
            String body = sb.toString();
            JsonObject obj = GSON.fromJson(body, JsonObject.class);
            if (obj == null) throw new Exception("Bytebin response was empty or invalid JSON. url=" + url);
            return obj;
        } finally {
            conn.disconnect();
        }
    }

    public static ApplyResult applyFromBytebinWithReport(Services services, String code) throws Exception {
        JsonObject payload = downloadFromBytebinOrThrow(code);
        return applyPayloadWithReport(services, payload);
    }

    private static JsonElement unwrapValues(JsonElement element) {
        if (element == null) return null;
        if (element.isJsonArray()) {
            for (int i = 0; i < element.getAsJsonArray().size(); i++) {
                JsonElement child = element.getAsJsonArray().get(i);
                element.getAsJsonArray().set(i, unwrapValues(child));
            }
            return element;
        }
        if (element.isJsonObject()) {
            JsonObject obj = element.getAsJsonObject();
            if (!obj.entrySet().isEmpty() && obj.has("value")) {
                return unwrapValues(obj.get("value"));
            }
            JsonObject out = new JsonObject();
            for (Map.Entry<String, JsonElement> e : obj.entrySet()) {
                out.add(e.getKey(), unwrapValues(e.getValue()));
            }
            return out;
        }
        return element;
    }

    public static ApplyResult applyPayloadWithReport(Services services, JsonObject payload) throws Exception {
        if (payload == null) throw new Exception("Payload was null");
        if (!payload.has("files") || !payload.get("files").isJsonObject()) {
            throw new Exception("Payload missing 'files' object");
        }
        JsonObject files = payload.getAsJsonObject("files");
        int applied = 0;
        int unchanged = 0;
        List<String> appliedFiles = new ArrayList<>();
        List<String> unchangedFiles = new ArrayList<>();
        List<String> skipped = new ArrayList<>();

        ApplyOneResult result;

        // Main config
        result = applyOne(files, () -> {
            JsonElement el = find(files, "main", "main.json");
            if (el == null) return ApplyStatus.MISSING;
            MainConfigHandler.Config newConfig = GSON.fromJson(el, MainConfigHandler.Config.class);
            if (configsEqual(MainConfigHandler.getConfig(), newConfig)) return ApplyStatus.UNCHANGED;
            writeConfigToFile(newConfig, "main.json");
            MainConfigHandler.reload();
            return ApplyStatus.APPLIED;
        }, "main");
        updateCounters(result, "main", appliedFiles, unchangedFiles, skipped);
        if (result.status == ApplyStatus.APPLIED) applied++;
        if (result.status == ApplyStatus.UNCHANGED) unchanged++;

        // Announcements config
        result = applyOne(files, () -> {
            JsonElement el = find(files, "announcements", "announcements.json");
            if (el == null) return ApplyStatus.MISSING;
            AnnouncementsConfigHandler.Config newConfig = GSON.fromJson(el, AnnouncementsConfigHandler.Config.class);
            if (configsEqual(AnnouncementsConfigHandler.getConfig(), newConfig)) return ApplyStatus.UNCHANGED;
            writeConfigToFile(newConfig, "announcements.json");
            AnnouncementsConfigHandler.reload();
            return ApplyStatus.APPLIED;
        }, "announcements");
        updateCounters(result, "announcements", appliedFiles, unchangedFiles, skipped);
        if (result.status == ApplyStatus.APPLIED) applied++;
        if (result.status == ApplyStatus.UNCHANGED) unchanged++;

        // Chat config
        result = applyOne(files, () -> {
            JsonElement el = find(files, "chat", "chat.json");
            if (el == null) return ApplyStatus.MISSING;
            ChatConfigHandler.Config newConfig = GSON.fromJson(el, ChatConfigHandler.Config.class);
            if (configsEqual(ChatConfigHandler.getConfig(), newConfig)) return ApplyStatus.UNCHANGED;
            writeConfigToFile(newConfig, "chat.json");
            ChatConfigHandler.reload();
            return ApplyStatus.APPLIED;
        }, "chat");
        updateCounters(result, "chat", appliedFiles, unchangedFiles, skipped);
        if (result.status == ApplyStatus.APPLIED) applied++;
        if (result.status == ApplyStatus.UNCHANGED) unchanged++;

        // MOTD config
        result = applyOne(files, () -> {
            JsonElement el = find(files, "motd", "motd.json");
            if (el == null) return ApplyStatus.MISSING;
            MOTDConfigHandler.Config newConfig = GSON.fromJson(el, MOTDConfigHandler.Config.class);
            if (configsEqual(MOTDConfigHandler.getConfig(), newConfig)) return ApplyStatus.UNCHANGED;
            writeConfigToFile(newConfig, "motd.json");
            MOTDConfigHandler.reload();
            return ApplyStatus.APPLIED;
        }, "motd");
        updateCounters(result, "motd", appliedFiles, unchangedFiles, skipped);
        if (result.status == ApplyStatus.APPLIED) applied++;
        if (result.status == ApplyStatus.UNCHANGED) unchanged++;

        // Mention config
        result = applyOne(files, () -> {
            JsonElement el = find(files, "mention", "mentions", "mention.json", "mentions.json");
            if (el == null) return ApplyStatus.MISSING;
            MentionConfigHandler.Config newConfig = GSON.fromJson(el, MentionConfigHandler.Config.class);
            if (configsEqual(MentionConfigHandler.getConfig(), newConfig)) return ApplyStatus.UNCHANGED;
            writeConfigToFile(newConfig, "mentions.json");
            MentionConfigHandler.reload();
            return ApplyStatus.APPLIED;
        }, "mention");
        updateCounters(result, "mention", appliedFiles, unchangedFiles, skipped);
        if (result.status == ApplyStatus.APPLIED) applied++;
        if (result.status == ApplyStatus.UNCHANGED) unchanged++;

        // Restart config
        result = applyOne(files, () -> {
            JsonElement el = find(files, "restart", "restarts", "restart.json", "restarts.json");
            if (el == null) return ApplyStatus.MISSING;
            RestartConfigHandler.Config newConfig = GSON.fromJson(el, RestartConfigHandler.Config.class);
            if (configsEqual(RestartConfigHandler.getConfig(), newConfig)) return ApplyStatus.UNCHANGED;
            writeConfigToFile(newConfig, "restarts.json");
            RestartConfigHandler.reload();
            return ApplyStatus.APPLIED;
        }, "restart");
        updateCounters(result, "restart", appliedFiles, unchangedFiles, skipped);
        if (result.status == ApplyStatus.APPLIED) applied++;
        if (result.status == ApplyStatus.UNCHANGED) unchanged++;


        if (applied > 0) {

            ParadigmAPI.getModules().stream()
                    .filter(m -> m instanceof eu.avalanche7.paradigm.modules.Restart)
                    .findFirst()
                    .ifPresent(m -> ((eu.avalanche7.paradigm.modules.Restart)m).rescheduleNextRestart(services));
            ParadigmAPI.getModules().stream()
                    .filter(m -> m instanceof eu.avalanche7.paradigm.modules.Announcements)
                    .findFirst()
                    .ifPresent(m -> ((eu.avalanche7.paradigm.modules.Announcements)m).rescheduleAnnouncements());

            reloadAffectedModules(services, appliedFiles);
        }

        StringBuilder msg = new StringBuilder();
        if (applied > 0) {
            msg.append("Applied changes to ").append(appliedFiles.size()).append(" file");
            if (appliedFiles.size() != 1) msg.append("s");
            msg.append(": ").append(String.join(", ", appliedFiles)).append(". Modules reloaded.");
        } else {
            msg.append("No changes were applied.");
        }
        if (unchanged > 0) {
            msg.append(" ").append(unchangedFiles.size()).append(" file");
            if (unchangedFiles.size() != 1) msg.append("s");
            msg.append(" had no changes.");
        }
        if (!skipped.isEmpty()) {
            msg.append(" Skipped: ").append(String.join(", ", skipped)).append(".");
        }

        String finalMsg = msg.toString().trim();
        try {
            services.getLogger().info("Paradigm WebEditor: {}", finalMsg);
        } catch (Throwable ignored) {}
        return new ApplyResult(applied, unchanged, finalMsg);
    }

    // private static ApplyStatus applyCustomCommands(Services services, JsonObject files) throws Exception {
    //     if (!files.has("customCommands") || !files.get("customCommands").isJsonObject()) {
    //         return ApplyStatus.MISSING;
    //     }
    //     JsonObject cc = files.getAsJsonObject("customCommands");
    //     JsonObject ccFiles = cc.has("files") && cc.get("files").isJsonObject() ? cc.getAsJsonObject("files") : null;
    //     if (ccFiles == null || ccFiles.entrySet().isEmpty()) {
    //         return ApplyStatus.MISSING;
    //     }
    //     java.nio.file.Path commandsDir = net.minecraftforge.fml.loading.FMLPaths.GAMEDIR.get()
    //             .resolve("config").resolve("paradigm").resolve("commands");
    //     try {
    //         java.nio.file.Files.createDirectories(commandsDir);
    //     } catch (Exception e) {
    //         throw new Exception("Failed to create commands directory: " + commandsDir + ", " + e.getMessage());
    //     }
    //     boolean anyApplied = false;
    //     for (Map.Entry<String, JsonElement> e : ccFiles.entrySet()) {
    //         String baseName = e.getKey();
    //         JsonElement value = e.getValue();
    //         if (value == null || !value.isJsonArray()) continue;
    //         java.nio.file.Path target = commandsDir.resolve(baseName + ".json");
    //         String newContent = GSON.toJson(value.getAsJsonArray());
    //         String oldContent = null;
    //         try {
    //             if (java.nio.file.Files.exists(target)) {
    //                 oldContent = java.nio.file.Files.readString(target, java.nio.charset.StandardCharsets.UTF_8);
    //             }
    //         } catch (Exception ignored) {}
    //         boolean changed = (oldContent == null) || !jsonEquals(oldContent, newContent);
    //         if (changed) {
    //             try {
    //                 java.nio.file.Files.writeString(target, newContent, java.nio.charset.StandardCharsets.UTF_8, java.nio.file.StandardOpenOption.CREATE, java.nio.file.StandardOpenOption.TRUNCATE_EXISTING);
    //                 anyApplied = true;
    //             } catch (Exception ex) {
    //                 throw new Exception("Failed to write custom command file '" + target.getFileName() + "': " + ex.getMessage());
    //             }
    //         }
    //     }
    //     if (anyApplied) {
    //         try {
    //             services.getCmConfig().reloadCommands();
    //         } catch (Throwable t) {
    //             services.getLogger().warn("Failed to reload custom commands after apply", t);
    //         }
    //         return ApplyStatus.APPLIED;
    //     }
    //     return ApplyStatus.UNCHANGED;
    // }

    private static boolean jsonEquals(String a, String b) {
        try {
            JsonElement ja = com.google.gson.JsonParser.parseString(a);
            JsonElement jb = com.google.gson.JsonParser.parseString(b);
            return ja.equals(jb);
        } catch (Throwable ignored) {
            return a != null && a.trim().equals(b != null ? b.trim() : null);
        }
    }

    private static void updateCounters(ApplyOneResult result, String name, List<String> appliedFiles, List<String> unchangedFiles, List<String> skipped) {
        switch (result.status) {
            case APPLIED:
                appliedFiles.add(name);
                break;
            case UNCHANGED:
                unchangedFiles.add(name);
                break;
            case MISSING:
                break;
            case ERROR:
                skipped.add(name + " (" + result.errorMsg + ")");
                break;
        }
    }

    private static void reloadAffectedModules(Services services, List<String> appliedFiles) {
        try {
            final var server = services != null ? services.getMinecraftServer() : null;
            Runnable task = () -> {
                for (String fileName : appliedFiles) {
                    switch (fileName) {
                        case "main":
                            ParadigmAPI.getModules().forEach(m -> {
                                try {
                                    m.onDisable(services);
                                    if (m.isEnabled(services)) {
                                        m.onEnable(services);
                                    }
                                } catch (Exception e) {
                                    try { services.getLogger().warn("Failed to reload module {} after main config change", m.getName(), e); } catch (Throwable ignored) {}
                                }
                            });
                            ParadigmAPI.getModules().stream()
                                    .filter(m -> m instanceof eu.avalanche7.paradigm.modules.Announcements)
                                    .forEach(m -> {
                                        try { ((eu.avalanche7.paradigm.modules.Announcements)m).rescheduleAnnouncements(); } catch (Throwable ignored) {}
                                    });
                            ParadigmAPI.getModules().stream()
                                    .filter(m -> m instanceof eu.avalanche7.paradigm.modules.Restart)
                                    .forEach(m -> {
                                        try { ((eu.avalanche7.paradigm.modules.Restart)m).rescheduleNextRestart(services); } catch (Throwable ignored) {}
                                    });
                            break;
                        case "chat":
                            ParadigmAPI.getModules().stream()
                                .filter(m -> m instanceof eu.avalanche7.paradigm.modules.chat.GroupChat ||
                                             m instanceof eu.avalanche7.paradigm.modules.chat.StaffChat)
                                .forEach(m -> {
                                    try {
                                        m.onDisable(services);
                                        if (m.isEnabled(services)) {
                                            m.onEnable(services);
                                        }
                                    } catch (Exception e) {
                                        services.getLogger().warn("Failed to reload module {}", m.getName(), e);
                                    }
                                });
                            break;
                        case "motd":
                            ParadigmAPI.getModules().stream()
                                .filter(m -> m instanceof eu.avalanche7.paradigm.modules.chat.MOTD)
                                .forEach(m -> {
                                    try {
                                        m.onDisable(services);
                                        if (m.isEnabled(services)) {
                                            m.onEnable(services);
                                        }
                                    } catch (Exception e) {
                                        services.getLogger().warn("Failed to reload module {}", m.getName(), e);
                                    }
                                });
                            break;
                        case "mention":
                            ParadigmAPI.getModules().stream()
                                .filter(m -> m instanceof eu.avalanche7.paradigm.modules.Mentions)
                                .forEach(m -> {
                                    try {
                                        m.onDisable(services);
                                        if (m.isEnabled(services)) {
                                            m.onEnable(services);
                                        }
                                    } catch (Exception e) {
                                        services.getLogger().warn("Failed to reload module {}", m.getName(), e);
                                    }
                                });
                            break;
                        case "announcements":
                            ParadigmAPI.getModules().stream()
                                .filter(m -> m instanceof eu.avalanche7.paradigm.modules.Announcements)
                                .forEach(m -> {
                                    try {
                                        m.onDisable(services);
                                        if (m.isEnabled(services)) {
                                            m.onEnable(services);
                                        }
                                        try {
                                            ((eu.avalanche7.paradigm.modules.Announcements) m).rescheduleAnnouncements();
                                        } catch (Throwable ignored) {}
                                    } catch (Exception e) {
                                        services.getLogger().warn("Failed to reload module {}", m.getName(), e);
                                    }
                                });
                            break;
                        case "restart":
                            ParadigmAPI.getModules().stream()
                                .filter(m -> m instanceof eu.avalanche7.paradigm.modules.Restart)
                                .forEach(m -> {
                                    try {
                                        m.onDisable(services);
                                        if (m.isEnabled(services)) {
                                            m.onEnable(services);
                                        }
                                        try {
                                            ((eu.avalanche7.paradigm.modules.Restart) m).rescheduleNextRestart(services);
                                        } catch (Throwable ignored) {}
                                    } catch (Exception e) {
                                        services.getLogger().warn("Failed to reload module {}", m.getName(), e);
                                    }
                                });
                            break;
                    }
                }
            };

            if (server != null) {
                try {
                    try {
                        var m = server.getClass().getMethod("execute", Runnable.class);
                        m.invoke(server, task);
                    } catch (Throwable t) {
                        try { services.getLogger().warn("Paradigm WebEditor: server.execute failed, running reload synchronously", t); } catch (Throwable ignored) {}
                        task.run();
                    }
                } catch (Throwable t) {
                    task.run();
                }
            } else {
                task.run();
            }
        } catch (Exception ex) {
            try { services.getLogger().warn("Paradigm WebEditor: Failed to reload affected modules", ex); } catch (Throwable ignored) {}
        }
    }

    private static boolean configsEqual(Object current, Object newConfig) {
        if (current == null && newConfig == null) return true;
        if (current == null || newConfig == null) return false;
        String currentJson = GSON.toJson(current);
        String newJson = GSON.toJson(newConfig);
        return currentJson.equals(newJson);
    }

    private static void writeConfigToFile(Object config, String fileName) throws Exception {
        try {
            java.nio.file.Path configDir;
            try {
                configDir = java.nio.file.Path.of(System.getProperty("user.dir")).resolve("config");
            } catch (Throwable t) {
                configDir = java.nio.file.Path.of("config");
            }

            java.nio.file.Path paradigmDir = configDir.resolve("paradigm");
            java.nio.file.Path targetFile = paradigmDir.resolve(fileName);

            java.nio.file.Files.createDirectories(paradigmDir);

            try (java.io.Writer writer = java.nio.file.Files.newBufferedWriter(targetFile, java.nio.charset.StandardCharsets.UTF_8)) {
                GSON.toJson(config, writer);
            }
        } catch (Exception e) {
            throw new Exception("Failed to write config file '" + fileName + "': " + e.getMessage(), e);
        }
    }

    private static JsonElement find(JsonObject files, String... keys) {
        for (String k : keys) {
            if (files.has(k)) return files.get(k);
        }
        return null;
    }

    private enum ApplyStatus {
        APPLIED,
        UNCHANGED,
        MISSING,
        ERROR
    }

    private static class ApplyOneResult {
        final ApplyStatus status;
        final String errorMsg;

        ApplyOneResult(ApplyStatus status, String errorMsg) {
            this.status = status;
            this.errorMsg = errorMsg;
        }
    }

    private static ApplyOneResult applyOne(JsonObject files, Applier applier, String nameForMsg) {
        try {
            ApplyStatus status = applier.apply();
            return new ApplyOneResult(status, null);
        } catch (Exception e) {
            String errMsg = e.getClass().getSimpleName();
            if (e.getMessage() != null && !e.getMessage().isEmpty()) {
                errMsg += ": " + e.getMessage();
            }
            return new ApplyOneResult(ApplyStatus.ERROR, errMsg);
        }
    }

    @FunctionalInterface
    private interface Applier {
        ApplyStatus apply() throws Exception;
    }

    public static int applyFromBytebin(Services services, String code) throws Exception {
        ApplyResult r = applyFromBytebinWithReport(services, code);
        return r.applied;
    }
}

