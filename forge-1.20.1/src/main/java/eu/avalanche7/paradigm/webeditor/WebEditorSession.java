/*
 * This file is part of LuckPerms, licensed under the MIT License.
 *
 *  Copyright (c) lucko (Luck) <luck@lucko.me>
 *  Copyright (c) contributors
 *
 *  Permission is hereby granted, free of charge, to any person obtaining a copy
 *  of this software and associated documentation files (the "Software"), to deal
 *  in the Software without restriction, including without limitation the rights
 *  to use, copy, modify, merge, publish, distribute, sublicense, and/or sell
 *  copies of the Software, and to permit persons to whom the Software is
 *  furnished to do so, subject to the following conditions:
 *
 *  The above copyright notice and this permission notice shall be included in all
 *  copies or substantial portions of the Software.
 *
 *  THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND, EXPRESS OR
 *  IMPLIED, INCLUDING BUT NOT LIMITED TO THE WARRANTIES OF MERCHANTABILITY,
 *  FITNESS FOR A PARTICULAR PURPOSE AND NONINFRINGEMENT. IN NO EVENT SHALL THE
 *  AUTHORS OR COPYRIGHT HOLDERS BE LIABLE FOR ANY CLAIM, DAMAGES OR OTHER
 *  LIABILITY, WHETHER IN AN ACTION OF CONTRACT, TORT OR OTHERWISE, ARISING FROM,
 *  OUT OF OR IN CONNECTION WITH THE SOFTWARE OR THE USE OR OTHER DEALINGS IN THE
 *  SOFTWARE.
 */

package eu.avalanche7.paradigm.webeditor;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import eu.avalanche7.paradigm.Paradigm;
import eu.avalanche7.paradigm.configs.*;
import eu.avalanche7.paradigm.core.Services;
import eu.avalanche7.paradigm.platform.Interfaces.ICommandSource;
import eu.avalanche7.paradigm.webeditor.socket.BytesocksClient;
import eu.avalanche7.paradigm.webeditor.socket.WebEditorSocket;

import java.io.BufferedReader;
import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.HashMap;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.zip.GZIPInputStream;
import java.util.zip.GZIPOutputStream;

public class WebEditorSession {

    private static final String WEB_EDITOR_URL_PATTERN = "https://paradigm.avalanche7.eu/editor/";
    private static final String BYTEBIN_POST_URL = "https://bytebin.avalanche7.eu/post";
    public static final String BYTEBIN_BASE_URL = "https://bytebin.avalanche7.eu/";
    private static final String BYTESOCKS_HOST = "bytesocks.avalanche7.eu";
    private static final String USER_AGENT = "paradigm/editor";

    private final Services services;
    private final ICommandSource owner;
    private WebEditorRequest initialRequest;

    public WebEditorSession(Services services, WebEditorRequest initialRequest) {
        this(services, initialRequest, null);
    }

    public WebEditorSession(Services services, WebEditorRequest initialRequest, ICommandSource owner) {
        this.services = Objects.requireNonNull(services, "services");
        this.initialRequest = Objects.requireNonNull(initialRequest, "initialRequest");
        this.owner = owner;
    }

    public static WebEditorSession of(Services services, JsonObject payload) {
        return new WebEditorSession(services, new WebEditorRequest(payload));
    }

    public static WebEditorSession of(Services services, JsonObject payload, ICommandSource owner) {
        return new WebEditorSession(services, new WebEditorRequest(payload), owner);
    }

    public String open() {
        return createInitialSession();
    }

    private boolean debugEnabled() {
        try {
            return MainConfigHandler.CONFIG.debugEnable.get();
        } catch (Throwable ignored) {
            return false;
        }
    }

    private String createInitialSession() {
        if (this.initialRequest == null) {
            return null;
        }

        WebEditorRequest request = this.initialRequest;
        this.initialRequest = null;

        try {
            WebEditorSocket socket = new WebEditorSocket(this.services, this.owner);
            BytesocksClient client = new BytesocksClient(BYTESOCKS_HOST, USER_AGENT);
            socket.initialize(client);
            socket.appendDetailToRequest(request);
            this.services.getWebEditorStore().sockets().putSocket(socket.getChannelId(), socket);
        } catch (Exception e) {
            try { services.getLogger().warn("Paradigm WebEditor: Unable to establish live editor socket: {}", e.toString()); } catch (Throwable ignored) {}
        }

        String id = uploadRequestData(request);
        if (id == null) {
            return null;
        }

        this.services.getWebEditorStore().sessions().addNewSession(id, request);

        String baseUrl = getEditorBaseUrl();
        String url = baseUrl + id;
        try {
            services.getLogger().info("Paradigm WebEditor session created: {}", url);
        } catch (Throwable ignored) {
        }
        return id;
    }

    private String getEditorBaseUrl() {
        try {
            boolean useTestUrl = MainConfigHandler.CONFIG.webEditorTestUrl.get();
            if (useTestUrl) {
                return "http://localhost:8083/editor/";
            }
        } catch (Throwable ignored) {}
        return WEB_EDITOR_URL_PATTERN;
    }

    private String uploadRequestData(WebEditorRequest request) {
        byte[] json = request.encodeJson();
        byte[] gz = gzip(json);

        if (debugEnabled()) {
            try {
                services.getLogger().info("Paradigm WebEditor: Uploading initial payload: jsonBytes={}, gzBytes={}, sha1={}",
                    json.length, gz.length, sha1Hex(json));
            } catch (Throwable ignored) {}
        }

        HttpURLConnection conn = null;
        try {
            conn = (HttpURLConnection) URI.create(BYTEBIN_POST_URL).toURL().openConnection();
            conn.setRequestMethod("POST");
            conn.setDoOutput(true);
            conn.setConnectTimeout(8000);
            conn.setReadTimeout(10000);
            conn.setRequestProperty("User-Agent", USER_AGENT);
            conn.setRequestProperty("Content-Type", "application/json");
            conn.setRequestProperty("Content-Encoding", "gzip");
            conn.setRequestProperty("X-Bytebin-Bucket", "editor");
            conn.setFixedLengthStreamingMode(gz.length);

            try (OutputStream os = conn.getOutputStream()) {
                os.write(gz);
            }

            int code = conn.getResponseCode();
            if (code < 200 || code >= 300) {
                try { services.getLogger().warn("Paradigm WebEditor: Bytebin POST failed with status {}", code); } catch (Throwable ignored) {}
                logErrorStreamSafely(this.services, conn);
                return null;
            }

            String location = conn.getHeaderField("Location");
            String key;
            if (location != null && !location.isEmpty()) {
                key = normalizeKey(location);
            } else {
                try (BufferedReader br = new BufferedReader(new InputStreamReader(conn.getInputStream(), StandardCharsets.UTF_8))) {
                    StringBuilder sb = new StringBuilder();
                    String line;
                    while ((line = br.readLine()) != null) sb.append(line);
                    String body = sb.toString().trim();
                    key = parseKeyFromResponse(body);
                    if (key == null || key.isEmpty()) {
                        try { services.getLogger().warn("Paradigm WebEditor: Unable to parse Bytebin response: {}", body); } catch (Throwable ignored) {}
                        return null;
                    }
                    key = normalizeKey(key);
                }
            }

            if (debugEnabled()) {
                try {
                    services.getLogger().info("Paradigm WebEditor: Uploaded initial payload to Bytebin: key={}, url={}{}",
                        key, BYTEBIN_BASE_URL, key);
                } catch (Throwable ignored) {}
                boolean accessible = verifyBytebinObjectAccessible(key);
                try { services.getLogger().info("Paradigm WebEditor: Verified uploaded key accessibility: {} (key={})", accessible, key); } catch (Throwable ignored) {}
                boolean contentMatch = verifyBytebinContentMatches(key, json);
                try { services.getLogger().info("Paradigm WebEditor: Verified uploaded content integrity: {} (key={}, sha1={})", contentMatch, key, sha1Hex(json)); } catch (Throwable ignored) {}
            }
            return key;
        } catch (Exception e) {
            try { services.getLogger().warn("Paradigm WebEditor: Failed to upload editor data: {}", e.toString()); } catch (Throwable ignored) {}
            return null;
        } finally {
            if (conn != null) conn.disconnect();
        }
    }

    public static String uploadPayload(Services services, JsonObject payload) {
        byte[] json = new com.google.gson.Gson().toJson(payload).getBytes(StandardCharsets.UTF_8);
        byte[] gz = gzip(json);
        HttpURLConnection conn = null;
        try {
            conn = (HttpURLConnection) URI.create(BYTEBIN_POST_URL).toURL().openConnection();
            conn.setRequestMethod("POST");
            conn.setDoOutput(true);
            conn.setConnectTimeout(8000);
            conn.setRequestProperty("User-Agent", USER_AGENT);
            conn.setRequestProperty("Content-Type", "application/json");
            conn.setRequestProperty("Content-Encoding", "gzip");
            conn.setRequestProperty("X-Bytebin-Bucket", "editor");
            conn.setFixedLengthStreamingMode(gz.length);
            try (OutputStream os = conn.getOutputStream()) {
                os.write(gz);
            }
            int code = conn.getResponseCode();
            if (code < 200 || code >= 300) {
                try { services.getLogger().warn("Paradigm WebEditor: Bytebin POST failed with status {}", code); } catch (Throwable ignored) {}
                logErrorStreamSafely(services, conn);
                return null;
            }

            String location = conn.getHeaderField("Location");
            String key;
            if (location != null && !location.isEmpty()) {
                key = normalizeKey(location);
            } else {
                try (BufferedReader br = new BufferedReader(new InputStreamReader(conn.getInputStream(), StandardCharsets.UTF_8))) {
                    StringBuilder sb = new StringBuilder();
                    String line;
                    while ((line = br.readLine()) != null) sb.append(line);
                    String body = sb.toString().trim();
                    String id = parseKeyFromResponse(body);
                    if (id == null || id.isEmpty()) return null;
                    key = normalizeKey(id);
                }
            }

            try { services.getLogger().info("Paradigm WebEditor: Uploaded follow-up payload to Bytebin: key={}, url={}{}", key, BYTEBIN_BASE_URL, key); } catch (Throwable ignored) {}
            if (MainConfigHandler.CONFIG != null) {
                boolean dbg = false;
                try { dbg = MainConfigHandler.CONFIG.debugEnable.get(); } catch (Throwable ignored) {}
                if (dbg) {
                    boolean accessible = verifyBytebinObjectAccessible(key);
                    try { services.getLogger().info("Paradigm WebEditor: Verified follow-up key accessibility: {} (key={})", accessible, key); } catch (Throwable ignored) {}
                    boolean contentMatch = verifyBytebinContentMatches(key, json);
                    try { services.getLogger().info("Paradigm WebEditor: Verified follow-up content integrity: {} (key={}, sha1={})", contentMatch, key, sha1Hex(json)); } catch (Throwable ignored) {}
                }
            }
            return key;
        } catch (Exception e) {
            try { services.getLogger().warn("Paradigm WebEditor: Failed to upload payload: {}", e.toString()); } catch (Throwable ignored) {}
            return null;
        } finally {
            if (conn != null) conn.disconnect();
        }
    }

    public static JsonObject buildPayload(Services services) {
        JsonObject root = new JsonObject();
        JsonObject meta = new JsonObject();
        meta.addProperty("plugin", "Paradigm");
        String version = "unknown";
        try {
            version = net.minecraftforge.fml.ModList.get().getModContainerById(Paradigm.MOD_ID)
                    .map(mc -> mc.getModInfo().getVersion().toString()).orElse("unknown");
        } catch (Throwable ignored) {}
        meta.addProperty("version", version);
        root.add("metadata", meta);

        JsonObject files = new JsonObject();
        files.add("main", new com.google.gson.Gson().toJsonTree(MainConfigHandler.CONFIG));
        files.add("announcements", new com.google.gson.Gson().toJsonTree(AnnouncementsConfigHandler.CONFIG));
        files.add("chat", new com.google.gson.Gson().toJsonTree(ChatConfigHandler.CONFIG));
        files.add("motd", new com.google.gson.Gson().toJsonTree(MOTDConfigHandler.CONFIG));
        files.add("mentions", new com.google.gson.Gson().toJsonTree(MentionConfigHandler.CONFIG));
        files.add("restart", new com.google.gson.Gson().toJsonTree(RestartConfigHandler.CONFIG));

        try {
            java.nio.file.Path cfgDir = net.minecraftforge.fml.loading.FMLPaths.GAMEDIR.get().resolve("config").resolve("paradigm");
            if (java.nio.file.Files.isDirectory(cfgDir)) {
                try (java.util.stream.Stream<java.nio.file.Path> stream = java.nio.file.Files.list(cfgDir)) {
                    stream.filter(java.nio.file.Files::isRegularFile)
                          .filter(p -> {
                              String n = p.getFileName().toString().toLowerCase(java.util.Locale.ROOT);
                              return n.endsWith(".json");
                          })
                          .forEach(p -> {
                              String name = p.getFileName().toString();
                              String base = name.substring(0, name.length() - 5);
                              String key = base.toLowerCase(java.util.Locale.ROOT);
                              if (files.has(key)) return;
                              try {
                                  String content = java.nio.file.Files.readString(p, java.nio.charset.StandardCharsets.UTF_8);
                                  com.google.gson.JsonElement parsed = com.google.gson.JsonParser.parseString(content);
                                  if (parsed != null) {
                                      files.add(key, parsed);
                                  }
                              } catch (Throwable ignored) {}
                          });
                }
            }
        } catch (Throwable ignored) {}
        try {
            java.nio.file.Path commandsDir = net.minecraftforge.fml.loading.FMLPaths.GAMEDIR.get()
                    .resolve("config").resolve("paradigm").resolve("commands");
            JsonObject ccRoot = new JsonObject();
            JsonObject ccFiles = new JsonObject();
            JsonArray ccAll = new JsonArray();
            if (java.nio.file.Files.isDirectory(commandsDir)) {
                try (java.util.stream.Stream<java.nio.file.Path> stream = java.nio.file.Files.list(commandsDir)) {
                    stream.filter(java.nio.file.Files::isRegularFile)
                            .filter(p -> p.getFileName().toString().toLowerCase(java.util.Locale.ROOT).endsWith(".json"))
                            .sorted()
                            .forEach(p -> {
                                String name = p.getFileName().toString();
                                String base = name.substring(0, name.length() - 5);
                                try {
                                    String content = java.nio.file.Files.readString(p, java.nio.charset.StandardCharsets.UTF_8);
                                    com.google.gson.JsonElement parsed = com.google.gson.JsonParser.parseString(content);
                                    if (parsed != null && parsed.isJsonArray()) {
                                        ccFiles.add(base, parsed);
                                        for (JsonElement el : parsed.getAsJsonArray()) {
                                            ccAll.add(el);
                                        }
                                    } else if (parsed != null && parsed.isJsonObject()) {
                                        // tolerate single-object file by wrapping into array
                                        JsonArray arr = new JsonArray();
                                        arr.add(parsed);
                                        ccFiles.add(base, arr);
                                        ccAll.add(parsed);
                                    }
                                } catch (Throwable ignored) {}
                            });
                }
            }
            ccRoot.add("files", ccFiles);
            ccRoot.add("all", ccAll);
            files.add("customCommands", ccRoot);
        } catch (Throwable ignored) {}

        root.add("files", files);

        JsonArray sections = buildSectionsFromFiles(files);
        JsonObject ccSection = buildCustomCommandsSection();
         if (ccSection != null) sections.add(ccSection);
        root.add("sections", sections);
        root.addProperty("schemaVersion", 1);

        return root;
    }

     private static JsonObject buildCustomCommandsSection() {
         JsonObject section = new JsonObject();
         section.addProperty("key", "customCommands");
         section.addProperty("title", "Custom Commands");
         section.addProperty("filename", "commands/*.json");
         section.addProperty("type", "array");

         JsonObject commandSchema = new JsonObject();
         commandSchema.addProperty("type", "object");
         JsonObject cmdFields = new JsonObject();

         JsonObject nameField = new JsonObject();
         nameField.addProperty("type", "string");
         nameField.addProperty("displayName", "Command Name");
         nameField.addProperty("description", "The name of the command (without /)");
         cmdFields.add("name", nameField);

         JsonObject descField = new JsonObject();
         descField.addProperty("type", "string");
         descField.addProperty("displayName", "Description");
         descField.addProperty("description", "Command description shown in help");
         cmdFields.add("description", descField);

         JsonObject permField = new JsonObject();
         permField.addProperty("type", "string");
         permField.addProperty("displayName", "Permission");
         permField.addProperty("description", "Permission node required to use this command");
         cmdFields.add("permission", permField);

         JsonObject requirePermField = new JsonObject();
         requirePermField.addProperty("type", "boolean");
         requirePermField.addProperty("displayName", "Require Permission");
         requirePermField.addProperty("description", "Whether permission is required");
         cmdFields.add("requirePermission", requirePermField);

         JsonObject permErrorField = new JsonObject();
         permErrorField.addProperty("type", "string");
         permErrorField.addProperty("displayName", "Permission Error Message");
         permErrorField.addProperty("description", "Message shown when player lacks permission");
         cmdFields.add("permissionErrorMessage", permErrorField);

         JsonObject cooldownField = new JsonObject();
         cooldownField.addProperty("type", "number");
         cooldownField.addProperty("displayName", "Cooldown (seconds)");
         cooldownField.addProperty("description", "Cooldown in seconds between uses");
         cmdFields.add("cooldown_seconds", cooldownField);

         JsonObject cooldownMsgField = new JsonObject();
         cooldownMsgField.addProperty("type", "string");
         cooldownMsgField.addProperty("displayName", "Cooldown Message");
         cooldownMsgField.addProperty("description", "Message shown when command is on cooldown. Use {remaining_time} for seconds left");
         cmdFields.add("cooldown_message", cooldownMsgField);

         JsonObject areaRestrictionField = new JsonObject();
         areaRestrictionField.addProperty("type", "object");
         areaRestrictionField.addProperty("displayName", "Area Restriction");
         areaRestrictionField.addProperty("description", "Restrict command to specific area");
         JsonObject areaFields = new JsonObject();
         JsonObject worldField = new JsonObject();
         worldField.addProperty("type", "string");
         worldField.addProperty("displayName", "World");
         worldField.addProperty("description", "World dimension (e.g., minecraft:overworld)");
         areaFields.add("world", worldField);
         JsonObject corner1Field = new JsonObject();
         corner1Field.addProperty("type", "number[]");
         corner1Field.addProperty("displayName", "Corner 1 [x, y, z]");
         areaFields.add("corner1", corner1Field);
         JsonObject corner2Field = new JsonObject();
         corner2Field.addProperty("type", "number[]");
         corner2Field.addProperty("displayName", "Corner 2 [x, y, z]");
         areaFields.add("corner2", corner2Field);
         JsonObject restrictMsgField = new JsonObject();
         restrictMsgField.addProperty("type", "string");
         restrictMsgField.addProperty("displayName", "Restriction Message");
         areaFields.add("restriction_message", restrictMsgField);
         areaRestrictionField.add("fields", areaFields);
         cmdFields.add("area_restriction", areaRestrictionField);

         JsonObject argSchema = new JsonObject();
         argSchema.addProperty("type", "object");
         JsonObject argFields = new JsonObject();
         JsonObject argNameField = new JsonObject();
         argNameField.addProperty("type", "string");
         argNameField.addProperty("displayName", "Argument Name");
         argFields.add("name", argNameField);
         JsonObject argTypeField = new JsonObject();
         argTypeField.addProperty("type", "enum");
         argTypeField.add("options", asArray("string", "integer", "boolean", "player", "world", "gamemode", "custom"));
         argTypeField.addProperty("displayName", "Argument Type");
         argFields.add("type", argTypeField);
         JsonObject argReqField = new JsonObject();
         argReqField.addProperty("type", "boolean");
         argReqField.addProperty("displayName", "Required");
         argFields.add("required", argReqField);
         JsonObject argErrField = new JsonObject();
         argErrField.addProperty("type", "string");
         argErrField.addProperty("displayName", "Error Message");
         argFields.add("errorMessage", argErrField);
         JsonObject argCompField = new JsonObject();
         argCompField.addProperty("type", "string[]");
         argCompField.addProperty("displayName", "Custom Completions");
         argFields.add("customCompletions", argCompField);
         JsonObject argMinField = new JsonObject();
         argMinField.addProperty("type", "number");
         argMinField.addProperty("displayName", "Min Value");
         argFields.add("minValue", argMinField);
         JsonObject argMaxField = new JsonObject();
         argMaxField.addProperty("type", "number");
         argMaxField.addProperty("displayName", "Max Value");
         argFields.add("maxValue", argMaxField);
         argSchema.add("fields", argFields);
         JsonObject argumentsArrayField = new JsonObject();
         argumentsArrayField.addProperty("type", "array");
         argumentsArrayField.add("item", argSchema);
         argumentsArrayField.addProperty("displayName", "Arguments");
         argumentsArrayField.addProperty("description", "Command arguments with type validation");
         cmdFields.add("arguments", argumentsArrayField);

         JsonObject condSchema = new JsonObject();
         condSchema.addProperty("type", "object");
         JsonObject condFields = new JsonObject();
         JsonObject condTypeField = new JsonObject();
         condTypeField.addProperty("type", "enum");
         condTypeField.add("options", asArray("has_permission", "has_item", "is_op"));
         condTypeField.addProperty("displayName", "Condition Type");
         condFields.add("type", condTypeField);
         JsonObject condValueField = new JsonObject();
         condValueField.addProperty("type", "string");
         condValueField.addProperty("displayName", "Value");
         condValueField.addProperty("description", "Permission node or item ID");
         condFields.add("value", condValueField);
         JsonObject condAmountField = new JsonObject();
         condAmountField.addProperty("type", "number");
         condAmountField.addProperty("displayName", "Item Amount");
         condFields.add("item_amount", condAmountField);
         JsonObject condNegateField = new JsonObject();
         condNegateField.addProperty("type", "boolean");
         condNegateField.addProperty("displayName", "Negate");
         condNegateField.addProperty("description", "Invert the condition (NOT)");
         condFields.add("negate", condNegateField);
         condSchema.add("fields", condFields);

         JsonObject actionSchema = new JsonObject();
         actionSchema.addProperty("type", "object");
         JsonObject actionFields = new JsonObject();
         JsonObject actionTypeField = new JsonObject();
         actionTypeField.addProperty("type", "enum");
         actionTypeField.add("options", asArray("message", "teleport", "run_command", "runcmd", "run_console", "conditional"));
         actionTypeField.addProperty("displayName", "Action Type");
         actionTypeField.addProperty("description", "Type of action to perform");
         actionFields.add("type", actionTypeField);
         JsonObject actionTextField = new JsonObject();
         actionTextField.addProperty("type", "string[]");
         actionTextField.addProperty("displayName", "Messages");
         actionTextField.addProperty("description", "Messages to send (for message action). Use {player} for player name, $1 $2 etc for arguments");
         actionFields.add("text", actionTextField);
         JsonObject actionXField = new JsonObject();
         actionXField.addProperty("type", "number");
         actionXField.addProperty("displayName", "X Coordinate");
         actionFields.add("x", actionXField);
         JsonObject actionYField = new JsonObject();
         actionYField.addProperty("type", "number");
         actionYField.addProperty("displayName", "Y Coordinate");
         actionFields.add("y", actionYField);
         JsonObject actionZField = new JsonObject();
         actionZField.addProperty("type", "number");
         actionZField.addProperty("displayName", "Z Coordinate");
         actionFields.add("z", actionZField);
         JsonObject actionCmdsField = new JsonObject();
         actionCmdsField.addProperty("type", "string[]");
         actionCmdsField.addProperty("displayName", "Commands");
         actionCmdsField.addProperty("description", "Commands to run. Use {player} for player name, $1 $2 etc for arguments");
         actionFields.add("commands", actionCmdsField);
         JsonObject actionCondsField = new JsonObject();
         actionCondsField.addProperty("type", "array");
         actionCondsField.add("item", condSchema);
         actionCondsField.addProperty("displayName", "Conditions");
         actionCondsField.addProperty("description", "Conditions for conditional action");
         actionFields.add("conditions", actionCondsField);
         JsonObject actionSuccessRef = new JsonObject();
         actionSuccessRef.addProperty("type", "actionArray");
         actionSuccessRef.addProperty("displayName", "On Success");
         actionSuccessRef.addProperty("description", "Actions to run when conditions pass");
         actionFields.add("on_success", actionSuccessRef);
         JsonObject actionFailureRef = new JsonObject();
         actionFailureRef.addProperty("type", "actionArray");
         actionFailureRef.addProperty("displayName", "On Failure");
         actionFailureRef.addProperty("description", "Actions to run when conditions fail");
         actionFields.add("on_failure", actionFailureRef);
         actionSchema.add("fields", actionFields);

         JsonObject actionsArrayField = new JsonObject();
         actionsArrayField.addProperty("type", "array");
         actionsArrayField.add("item", actionSchema);
         actionsArrayField.addProperty("displayName", "Actions");
         actionsArrayField.addProperty("description", "Actions to execute when command is run");
         cmdFields.add("actions", actionsArrayField);

         commandSchema.add("fields", cmdFields);
         section.add("item", commandSchema);
         return section;
     }

    private static JsonArray asArray(String... values) {
        JsonArray arr = new JsonArray();
        for (String v : values) arr.add(v);
        return arr;
    }

    private static String sha1Hex(byte[] data) {
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-1");
            byte[] digest = md.digest(data);
            StringBuilder sb = new StringBuilder(digest.length * 2);
            for (byte b : digest) sb.append(String.format("%02x", b));
            return sb.toString();
        } catch (Exception e) {
            return "";
        }
    }

    private static void logErrorStreamSafely(Services services, HttpURLConnection conn) {
        try {
            java.io.InputStream es = conn.getErrorStream();
            if (es == null) return;
            byte[] buf = new byte[2048];
            int read;
            ByteArrayOutputStream baos = new ByteArrayOutputStream(2048);
            while ((read = es.read(buf)) != -1) {
                if (read > 0) baos.write(buf, 0, read);
                if (baos.size() >= 2048) break;
            }
            String body = baos.toString(StandardCharsets.UTF_8);
            if (!body.isEmpty()) {
                try { services.getLogger().warn("Paradigm WebEditor: Bytebin error body: {}", body); } catch (Throwable ignored) {}
            }
        } catch (Exception ignored) {
        }
    }

    private static boolean verifyBytebinObjectAccessible(String key) {
        HttpURLConnection conn = null;
        try {
            java.net.URL url = URI.create(BYTEBIN_BASE_URL + normalizeKey(key)).toURL();
            conn = (HttpURLConnection) url.openConnection();
            conn.setRequestMethod("HEAD");
            conn.setRequestProperty("User-Agent", USER_AGENT);
            conn.setConnectTimeout(5000);
            conn.setReadTimeout(5000);
            int code = conn.getResponseCode();
            if (code >= 200 && code < 400) {
                return true;
            }
            if (code == HttpURLConnection.HTTP_BAD_METHOD || code == HttpURLConnection.HTTP_NOT_IMPLEMENTED) {
                conn.disconnect();
                conn = (HttpURLConnection) url.openConnection();
                conn.setRequestMethod("GET");
                conn.setRequestProperty("User-Agent", USER_AGENT);
                conn.setRequestProperty("Range", "bytes=0-0");
                conn.setConnectTimeout(5000);
                conn.setReadTimeout(5000);
                int code2 = conn.getResponseCode();
                return code2 >= 200 && code2 < 400;
            }
            return false;
        } catch (Exception e) {
            return false;
        } finally {
            if (conn != null) conn.disconnect();
        }
    }

    private static boolean verifyBytebinContentMatches(String key, byte[] expectedJson) {
        HttpURLConnection conn = null;
        try {
            java.net.URL url = URI.create(BYTEBIN_BASE_URL + normalizeKey(key)).toURL();
            conn = (HttpURLConnection) url.openConnection();
            conn.setRequestMethod("GET");
            conn.setRequestProperty("User-Agent", USER_AGENT);
            conn.setConnectTimeout(8000);
            conn.setReadTimeout(10000);
            int code = conn.getResponseCode();
            if (code < 200 || code >= 300) {
                return false;
            }
            String ce = conn.getHeaderField("Content-Encoding");
            InputStream is = conn.getInputStream();
            if (ce != null && ce.toLowerCase(java.util.Locale.ROOT).contains("gzip")) {
                is = new GZIPInputStream(is);
            }
            ByteArrayOutputStream baos = new ByteArrayOutputStream(Math.max(8192, expectedJson.length));
            byte[] buf = new byte[8192];
            int r;
            while ((r = is.read(buf)) != -1) {
                if (r > 0) baos.write(buf, 0, r);
                if (baos.size() > expectedJson.length * 4L + 1048576L) {
                    break;
                }
            }
            byte[] got = baos.toByteArray();
            String expectedSha1 = sha1Hex(expectedJson);
            String gotSha1 = sha1Hex(got);
            if (expectedSha1.equals(gotSha1)) {
                return true;
            }
            try {
                String expectedStr = new String(expectedJson, StandardCharsets.UTF_8).replaceAll("\\s+", "");
                String gotStr = new String(got, StandardCharsets.UTF_8).replaceAll("\\s+", "");
                return expectedStr.equals(gotStr);
            } catch (Throwable ignored) {
                return false;
            }
        } catch (Exception e) {
            return false;
        } finally {
            if (conn != null) conn.disconnect();
        }
    }

    private static String normalizeKey(String locationOrKey) {
        String s = locationOrKey.trim();
        int q = s.indexOf('?');
        if (q != -1) s = s.substring(0, q);
        if (s.endsWith("/")) s = s.substring(0, s.length() - 1);
        int slash = s.lastIndexOf('/');
        if (slash != -1) {
            return s.substring(slash + 1);
        }
        return s;
    }

    private static byte[] gzip(byte[] in) {
        try {
            ByteArrayOutputStream baos = new ByteArrayOutputStream();
            try (GZIPOutputStream gz = new GZIPOutputStream(baos)) {
                gz.write(in);
            }
            return baos.toByteArray();
        } catch (Exception e) {
            return in;
        }
    }

    private static String parseKeyFromResponse(String body) {
        if (body == null || body.isEmpty()) return null;
        if (body.startsWith("{")) {
            try {
                com.google.gson.JsonObject obj = com.google.gson.JsonParser.parseString(body).getAsJsonObject();
                if (obj.has("key")) return obj.get("key").getAsString();
            } catch (Throwable ignored) {
            }
        }
        return body;
    }

    private static JsonArray buildSectionsFromFiles(JsonObject files) {
        JsonArray sections = new JsonArray();
        for (Map.Entry<String, JsonElement> e : files.entrySet()) {
            String key = e.getKey();
            JsonElement value = e.getValue();
            String title = key.substring(0, 1).toUpperCase(Locale.ROOT) + (key.length() > 1 ? key.substring(1) : "");
            JsonObject section = buildSectionFromJson(key, title, value);
            if (section != null) sections.add(section);
        }
        return sections;
    }

    private static JsonObject buildSectionFromJson(String key, String title, JsonElement jsonData) {
        JsonObject section = new JsonObject();
        section.addProperty("key", key);
        section.addProperty("title", title);
        section.addProperty("filename", key + ".json");

        JsonObject fields = new JsonObject();
        boolean isEntryMap = false;

        if (jsonData != null && jsonData.isJsonObject()) {
            JsonObject obj = jsonData.getAsJsonObject();
            for (Map.Entry<String, JsonElement> fe : obj.entrySet()) {
                String fieldKey = fe.getKey();
                JsonElement fieldData = fe.getValue();
                if (fieldData != null && fieldData.isJsonObject()) {
                    JsonObject fo = fieldData.getAsJsonObject();
                    if (fo.has("value") && fo.has("description")) {
                        isEntryMap = true;
                        JsonObject f = new JsonObject();
                        String t = inferFieldType(fo.get("value"));
                        f.addProperty("type", t);
                        f.add("default", cloneJson(fo.get("value")));
                        f.addProperty("description", safeGetAsString(fo.get("description")));
                        f.addProperty("displayName", prettifyName(fieldKey));
                        if (shouldPreview(fo.get("value"))) f.addProperty("preview", true);
                        JsonArray opts = enumOptionsFor(key, fieldKey, fo.get("value"), f.get("description").getAsString());
                        if (opts != null) f.add("options", opts);
                        fields.add(fieldKey, f);
                        continue;
                    }
                }
                JsonObject f = new JsonObject();
                String t = inferFieldType(fieldData);
                f.addProperty("type", t);
                f.add("default", cloneJson(fieldData));
                f.addProperty("description", "");
                f.addProperty("displayName", prettifyName(fieldKey));
                if (shouldPreview(fieldData)) f.addProperty("preview", true);
                fields.add(fieldKey, f);
            }
        } else if (jsonData != null && jsonData.isJsonArray()) {
            JsonObject f = new JsonObject();
            f.addProperty("type", inferFieldType(jsonData));
            f.add("default", cloneJson(jsonData));
            f.addProperty("description", "");
            f.addProperty("displayName", prettifyName(key));
            fields.add(key, f);
        } else if (jsonData != null && jsonData.isJsonPrimitive()) {
            JsonObject f = new JsonObject();
            f.addProperty("type", inferFieldType(jsonData));
            f.add("default", cloneJson(jsonData));
            f.addProperty("description", "");
            f.addProperty("displayName", prettifyName(key));
            fields.add(key, f);
        }

        String type = "entryMap";
        if (!isEntryMap) {
            if ("motd".equals(key)) {
                type = "plainObject";
            } else {
                boolean allSimple = true;
                for (Map.Entry<String, JsonElement> fe : fields.entrySet()) {
                    JsonObject f = fe.getValue().getAsJsonObject();
                    String ft = f.get("type").getAsString();
                    if (!("string".equals(ft) || "number".equals(ft) || "boolean".equals(ft) || "string[]".equals(ft) || "number[]".equals(ft))) {
                        allSimple = false;
                        break;
                    }
                }
                type = allSimple ? "plainObject" : "entryMap";
            }
        }
        section.addProperty("type", type);
        section.add("fields", fields);
        return section;
    }

    private static String inferFieldType(JsonElement value) {
        if (value == null || value.isJsonNull()) return "string";
        if (value.isJsonPrimitive()) {
            if (value.getAsJsonPrimitive().isBoolean()) return "boolean";
            if (value.getAsJsonPrimitive().isNumber()) return "number";
            return "string";
        }
        if (value.isJsonArray()) {
            JsonArray arr = value.getAsJsonArray();
            if (arr.isEmpty()) return "string[]";
            JsonElement first = arr.get(0);
            if (first.isJsonPrimitive() && first.getAsJsonPrimitive().isNumber()) return "number[]";
            return "string[]";
        }
        return "string";
    }

    private static boolean shouldPreview(JsonElement value) {
        try {
            if (value == null) return false;
            if (value.isJsonPrimitive() && value.getAsJsonPrimitive().isString()) {
                String s = value.getAsString();
                return s.contains("&") || s.contains("[");
            }
            if (value.isJsonArray()) {
                for (JsonElement el : value.getAsJsonArray()) {
                    if (el.isJsonPrimitive() && el.getAsJsonPrimitive().isString()) {
                        String s = el.getAsString();
                        if (s.contains("&") || s.contains("[")) return true;
                    }
                }
            }
        } catch (Throwable ignored) {}
        return false;
    }

    private static String prettifyName(String in) {
        if (in == null || in.isEmpty()) return "";
        String s = in.replace('_', ' ');
        StringBuilder out = new StringBuilder();
        char prev = 0;
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            if (i == 0) {
                out.append(Character.toUpperCase(c));
            } else if (Character.isUpperCase(c) && Character.isLowerCase(prev)) {
                out.append(' ').append(c);
            } else {
                out.append(c);
            }
            prev = c;
        }
        return out.toString().trim();
    }

    private static String safeGetAsString(JsonElement e) {
        try {
            if (e != null && e.isJsonPrimitive()) return e.getAsString();
        } catch (Throwable ignored) {}
        return "";
    }

    private static JsonElement cloneJson(JsonElement e) {
        if (e == null) return com.google.gson.JsonNull.INSTANCE;
        return com.google.gson.JsonParser.parseString(e.toString());
    }

    private static JsonArray enumOptionsFor(String sectionKey, String fieldKey, JsonElement value, String description) {
        Map<String, String[]> known = new HashMap<>();
        known.put("announcements.orderMode", new String[]{"SEQUENTIAL", "RANDOM"});
        known.put("announcements.bossbarColor", new String[]{"BLUE", "GREEN", "PINK", "PURPLE", "RED", "WHITE", "YELLOW"});
        known.put("restart.restartType", new String[]{"Realtime", "Fixed", "None"});
        String path = sectionKey + "." + fieldKey;
        String[] opts = known.get(path);
        if (opts == null) {
            String parsed = null;
            if (description != null) {
                int idx = description.indexOf("Options:");
                if (idx >= 0) parsed = description.substring(idx + 8).trim();
            }
            if (parsed != null && !parsed.isEmpty()) {
                String[] parts = parsed.split(",");
                for (int i = 0; i < parts.length; i++) parts[i] = parts[i].trim();
                opts = parts;
            }
        }
        if (opts == null) return null;
        JsonArray arr = new JsonArray();
        for (String o : opts) arr.add(o);
        return arr;
    }
}
