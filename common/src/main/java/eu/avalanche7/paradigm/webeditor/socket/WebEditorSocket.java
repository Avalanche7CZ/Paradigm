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

package eu.avalanche7.paradigm.webeditor.socket;

import com.google.gson.Gson;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import eu.avalanche7.paradigm.core.Services;
import eu.avalanche7.paradigm.platform.Interfaces.ICommandSource;
import eu.avalanche7.paradigm.platform.Interfaces.IComponent;
import eu.avalanche7.paradigm.webeditor.EditorApplier;
import eu.avalanche7.paradigm.webeditor.WebEditorRequest;
import eu.avalanche7.paradigm.webeditor.WebEditorSession;

import java.net.http.WebSocket;
import java.nio.ByteBuffer;
import java.security.KeyPair;
import java.security.PublicKey;
import java.util.Base64;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;

public class WebEditorSocket {

    private static final Gson GSON = new Gson();

    private final Services services;
    private final ICommandSource owner;
    private final KeyPair pluginKeyPair;

    private BytesocksClient.Socket socket;
    private volatile PublicKey remotePublicKey;
    private volatile boolean closed = false;
    private volatile long lastPing = System.currentTimeMillis();
    private final CompletableFuture<Void> connectFuture = new CompletableFuture<>();
    private ScheduledFuture<?> keepaliveTask;

    // Track untrusted connection attempts: nonce -> public key
    private final Map<String, PublicKey> attemptedConnections = new ConcurrentHashMap<>();

    public WebEditorSocket(Services services, ICommandSource owner) {
        this.services = Objects.requireNonNull(services, "services");
        this.owner = owner;
        this.pluginKeyPair = services.getWebEditorStore().keyPair();
    }

    private boolean debugEnabled() {
        try { return eu.avalanche7.paradigm.configs.MainConfigHandler.CONFIG.debugEnable.get(); } catch (Throwable t) { return false; }
    }

    public void initialize(BytesocksClient client) throws Exception {
        BytesocksListener listener = new BytesocksListener(this);
        try {
            this.socket = new BytesocksClient.Socket("pending", null);
        } catch (Throwable ignored) {}
        this.socket = client.createSocket(listener);
    }

    public void appendDetailToRequest(WebEditorRequest request) {
        String channelId = getChannelId();
        String publicKey = Base64.getEncoder().encodeToString(this.pluginKeyPair.getPublic().getEncoded());
        JsonObject socket = new JsonObject();
        socket.addProperty("protocolVersion", SignatureAlgorithm.INSTANCE.protocolVersion());
        socket.addProperty("channelId", channelId);
        socket.addProperty("publicKey", publicKey);
        request.getPayload().add("socket", socket);
    }

    public void send(JsonObject msg) {
        String encoded = GSON.toJson(msg);
        String signature = SignatureAlgorithm.INSTANCE.sign(this.pluginKeyPair.getPrivate(), encoded);
        if (debugEnabled()) {
            try {
                String type = msg.has("type") ? safeGetAsString(msg, "type") : null;
                services.getLogger().debug("Paradigm WebEditor: Socket SEND type={}, channel={}, bytes={}", type, getChannelId(), encoded.length());
            } catch (Throwable ignored) {}
        }
        JsonObject frame = new JsonObject();
        frame.addProperty("msg", encoded);
        frame.addProperty("signature", signature);
        String frameJson = GSON.toJson(frame);
        try {
            this.socket.webSocket().sendText(frameJson, true).whenComplete((ws, ex) -> {
                if (ex != null) {
                    try {
                        services.getLogger().warn("Paradigm WebEditor: WebSocket sendText failed for channel {}: {}", getChannelId(), ex.getMessage());
                    } catch (Throwable ignored) {}
                }
            });
        } catch (Exception e) {
            try {
                services.getLogger().warn("Paradigm WebEditor: Failed to send socket message to channel {}: {}", getChannelId(), e.getMessage(), e);
            } catch (Throwable ignored) {}
        }
    }

    public void scheduleCleanupIfUnused() {
        cancelKeepalive();
        try { services.getLogger().info("Paradigm WebEditor: Scheduling cleanup check for channel={} in 60s", getChannelId()); } catch (Throwable ignored) {}
        this.keepaliveTask = services.getTaskScheduler().schedule(() -> {
            if (this.closed) return;
            if (this.remotePublicKey == null && System.currentTimeMillis() - this.lastPing > TimeUnit.MINUTES.toMillis(1)) {
                try { services.getLogger().info("Paradigm WebEditor: Cleanup triggered for channel={} (no HELLO/heartbeat)", getChannelId()); } catch (Throwable ignored) {}
                close();
            } else {
                startKeepalive();
            }
        }, 60, TimeUnit.SECONDS);
    }

    private void startKeepalive() {
        cancelKeepalive();
        try { services.getLogger().info("Paradigm WebEditor: Starting keepalive checks for channel={}", getChannelId()); } catch (Throwable ignored) {}
        this.keepaliveTask = services.getTaskScheduler().scheduleAtFixedRate(() -> {
            if (System.currentTimeMillis() - this.lastPing > TimeUnit.MINUTES.toMillis(2)) {
                try { services.getLogger().info("Paradigm WebEditor: Keepalive timeout for channel={} (closing)", getChannelId()); } catch (Throwable ignored) {}
                close();
            }
        }, 30, 30, TimeUnit.SECONDS);
    }

    private void cancelKeepalive() {
        ScheduledFuture<?> t = this.keepaliveTask;
        if (t != null) {
            t.cancel(false);
            this.keepaliveTask = null;
            try { services.getLogger().info("Paradigm WebEditor: Cancelled keepalive for channel={}", getChannelId()); } catch (Throwable ignored) {}
        }
    }

    public void close() {
        if (this.closed) return;
        try { services.getLogger().info("Paradigm WebEditor: Closing socket for channel={}", getChannelId()); } catch (Throwable ignored) {}
        try {
            JsonObject pong = new JsonObject();
            pong.addProperty("type", SocketMessageType.PONG.id);
            pong.addProperty("ok", false);
            send(pong);
        } catch (Exception ignored) {}
        try { this.socket.webSocket().sendClose(WebSocket.NORMAL_CLOSURE, "Normal"); } catch (Exception ignored) {}
        this.closed = true;
        try { services.getWebEditorStore().sockets().removeSocket(this); } catch (Throwable ignored) {}
        cancelKeepalive();
    }

    public boolean isClosed() { return this.closed; }

    public String getChannelId() { return this.socket != null ? this.socket.channelId() : "unsupported"; }

    public PublicKey getRemotePublicKey() { return remotePublicKey; }

    public void setRemotePublicKey(PublicKey remotePublicKey) { this.remotePublicKey = remotePublicKey; }

    public CompletableFuture<Void> connectFuture() { return this.connectFuture; }

    public ICommandSource getOwner() { return this.owner; }

    public boolean isOwnedBy(ICommandSource src) {
        if (src == null || this.owner == null) return false;
        if (src.isConsole() && this.owner.isConsole()) return true;
        if (!src.isConsole() && !this.owner.isConsole()) {
            if (src.getPlayer() != null && this.owner.getPlayer() != null) {
                return Objects.equals(src.getPlayer().getUUID(), this.owner.getPlayer().getUUID());
            }
        }
        return false;
    }

    public PublicKey getAttemptedPublicKey(String nonce) {
        if (nonce == null) return null;
        return this.attemptedConnections.get(nonce);
    }

    public boolean clearAttempt(String nonce) {
        if (nonce == null) return false;
        return this.attemptedConnections.remove(nonce) != null;
    }

    public java.util.Set<String> getPendingNonces() {
        return attemptedConnections.keySet();
    }

    private static String safeGetAsString(JsonObject obj, String key) {
        if (obj == null || key == null) return null;
        if (!obj.has(key)) return null;
        JsonElement el = obj.get(key);
        if (el == null || el.isJsonNull()) return null;
        try { return el.getAsString(); } catch (Throwable ignored) { return null; }
    }

    private void handleMessageFrame(String stringMsg) {
        try {
            if (stringMsg == null || stringMsg.trim().isEmpty()) {
                if (debugEnabled()) {
                    try { services.getLogger().warn("Paradigm WebEditor: Received empty message frame on channel {}", getChannelId()); } catch (Throwable ignored) {}
                }
                return;
            }

            if (debugEnabled()) {
                try {
                    services.getLogger().debug("Paradigm WebEditor: Raw frame received, channel={}, bytes={}, preview={}",
                        getChannelId(), stringMsg.length(), stringMsg.length() > 300 ? stringMsg.substring(0, 300) + "..." : stringMsg);
                } catch (Throwable ignored) {}
            }

            JsonObject frame;
            try {
                frame = GSON.fromJson(stringMsg, JsonObject.class);
            } catch (Exception e) {
                if (debugEnabled()) {
                    try { services.getLogger().warn("Paradigm WebEditor: Failed to parse frame JSON on channel {}: {}", getChannelId(), e.getMessage()); } catch (Throwable ignored) {}
                }
                throw new IllegalArgumentException("Invalid JSON frame: " + e.getMessage());
            }

            if (frame == null) {
                if (debugEnabled()) {
                    try { services.getLogger().warn("Paradigm WebEditor: Frame parsed to null on channel {}", getChannelId()); } catch (Throwable ignored) {}
                }
                throw new IllegalArgumentException("Frame is null");
            }

            String innerMsg = safeGetAsString(frame, "msg");
            String signature = safeGetAsString(frame, "signature");

            if (innerMsg == null || innerMsg.isEmpty()) {
                if (debugEnabled()) {
                    try {
                        services.getLogger().warn("Paradigm WebEditor: Incomplete message frame on channel {}, hasMsg={}, hasSig={}, frameKeys={}",
                            getChannelId(), frame.has("msg"), frame.has("signature"), frame.keySet());
                    } catch (Throwable ignored) {}
                }
                throw new IllegalArgumentException("Incomplete message: missing 'msg' field");
            }

            JsonObject msg = GSON.fromJson(innerMsg, JsonObject.class);
            String typeStr = msg != null ? safeGetAsString(msg, "type") : null;
            if (typeStr == null) throw new IllegalStateException("Missing type");
            SocketMessageType type = SocketMessageType.getById(typeStr);

            if (debugEnabled()) {
                try { services.getLogger().debug("Paradigm WebEditor: Socket RECV type={}, channel={}, signed={}", typeStr, getChannelId(), signature != null); } catch (Throwable ignored) {}
            }

            if (type == SocketMessageType.HELLO) {
                services.getLogger().info("Paradigm WebEditor: Full HELLO message: {}", msg);
                this.lastPing = System.currentTimeMillis();
                try {
                    String nonce = safeGetAsString(msg, "nonce");
                    String publicKeyB64 = safeGetAsString(msg, "publicKey");
                    String sessionId = safeGetAsString(msg, "sessionId");
                    String browser = safeGetAsString(msg, "browser");

                    if (publicKeyB64 == null || publicKeyB64.isEmpty()) {
                        JsonObject reply = new JsonObject();
                        reply.addProperty("type", SocketMessageType.HELLO_REPLY.id);
                        reply.addProperty("nonce", nonce == null ? "" : nonce);
                        reply.addProperty("state", "invalid");
                        send(reply);
                        try { services.getLogger().info("Paradigm WebEditor: HELLO -> reply=invalid, channel={}, nonce={}", getChannelId(), nonce); } catch (Throwable ignored) {}
                        return;
                    }

                    PublicKey remote;
                    try {
                        remote = SignatureAlgorithm.INSTANCE.parsePublicKey(publicKeyB64);
                    } catch (Exception ex) {
                        JsonObject reply = new JsonObject();
                        reply.addProperty("type", SocketMessageType.HELLO_REPLY.id);
                        reply.addProperty("nonce", nonce == null ? "" : nonce);
                        reply.addProperty("state", "invalid");
                        send(reply);
                        try { services.getLogger().info("Paradigm WebEditor: HELLO -> reply=invalid(publicKey parse failed), channel={}, nonce={}", getChannelId(), nonce); } catch (Throwable ignored) {}
                        return;
                    }

                    if (this.remotePublicKey != null && !this.remotePublicKey.equals(remote)) {
                        String existingKeyHash = eu.avalanche7.paradigm.webeditor.store.WebEditorKeystore.hash(this.remotePublicKey.getEncoded());
                        String newKeyHash = eu.avalanche7.paradigm.webeditor.store.WebEditorKeystore.hash(remote.getEncoded());
                        try {
                            services.getLogger().info("Paradigm WebEditor: Rejecting connection with different key. channel={}, existingKey={}, attemptedKey={}",
                                getChannelId(), existingKeyHash, newKeyHash);
                        } catch (Throwable ignored) {}
                        JsonObject reply = new JsonObject();
                        reply.addProperty("type", SocketMessageType.HELLO_REPLY.id);
                        reply.addProperty("nonce", nonce == null ? "" : nonce);
                        reply.addProperty("state", "rejected");
                        send(reply);
                        return;
                    }

                    if (sessionId != null) {
                        eu.avalanche7.paradigm.webeditor.store.RemoteSession rs = services.getWebEditorStore().sessions().getSession(sessionId);
                        if (rs == null || rs.isCompleted()) {
                            JsonObject reply = new JsonObject();
                            reply.addProperty("type", SocketMessageType.HELLO_REPLY.id);
                            reply.addProperty("nonce", nonce == null ? "" : nonce);
                            reply.addProperty("state", "invalid");
                            send(reply);
                            try { services.getLogger().info("Paradigm WebEditor: HELLO -> reply=invalid(session), channel={}, sessionId={}, nonce={}", getChannelId(), sessionId, nonce); } catch (Throwable ignored) {}
                            if (debugEnabled()) {
                                try { services.getLogger().debug("Paradigm WebEditor: HELLO reply state=invalid, sessionId={}", sessionId); } catch (Throwable ignored) {}
                            }
                            return;
                        }
                    }

                    boolean trusted = this.owner != null && services.getWebEditorStore().keystore().isTrusted(this.owner, remote.getEncoded());
                    if (!trusted) {
                        this.attemptedConnections.put(nonce == null ? "" : nonce, remote);

                        JsonObject reply = new JsonObject();
                        reply.addProperty("type", SocketMessageType.HELLO_REPLY.id);
                        reply.addProperty("nonce", nonce == null ? "" : nonce);
                        reply.addProperty("state", "untrusted");
                        send(reply);
                        if (debugEnabled()) {
                            try { services.getLogger().debug("Paradigm WebEditor: HELLO reply state=untrusted, nonce={} channel={}", nonce, getChannelId()); } catch (Throwable ignored) {}
                        }

                        try {
                            if (this.owner != null) {
                                String cmd = "/paradigm editor trust " + (nonce == null ? "nonce" : nonce);
                                IComponent clickable = services.getPlatformAdapter().createComponentFromLiteral("Click to trust this editor (" + (browser == null ? "unknown" : browser) + ")").onClickRunCommand(cmd).onHoverText("Run " + cmd);
                                try {
                                    Object orig = this.owner.getOriginalSource();
                                    if (orig instanceof net.minecraft.server.command.ServerCommandSource scs) {
                                        services.getPlatformAdapter().sendSuccess(scs, clickable, false);
                                    } else {
                                        services.getLogger().info("Paradigm WebEditor: owner original source is not a ServerCommandSource for owner={}", this.owner.getSourceName());
                                    }
                                } catch (Throwable ignored) {}
                            }
                        } catch (Throwable ignored) {}
                        try {
                            String ownerName = this.owner == null ? "unknown" : this.owner.getSourceName();
                            String pkHash = eu.avalanche7.paradigm.webeditor.store.WebEditorKeystore.hash(remote.getEncoded());
                            services.getLogger().info("Paradigm WebEditor: Untrusted editor attempted to connect. channel={}, owner={}, browser={}, nonce={}, keyHash={}", getChannelId(), ownerName, browser, nonce, pkHash);
                        } catch (Throwable ignored) {}
                        return;
                    }

                    boolean reconnected = this.remotePublicKey != null;
                    this.remotePublicKey = remote;

                    JsonObject reply = new JsonObject();
                    reply.addProperty("type", SocketMessageType.HELLO_REPLY.id);
                    reply.addProperty("nonce", nonce == null ? "" : nonce);
                    reply.addProperty("state", "accepted");
                    send(reply);
                    try { services.getLogger().info("Paradigm WebEditor: HELLO -> reply=accepted, channel={}, nonce={}, reconnected={}", getChannelId(), nonce, reconnected); } catch (Throwable ignored) {}
                    if (debugEnabled()) {
                        try { services.getLogger().debug("Paradigm WebEditor: HELLO reply state=accepted, channel={} reconnected={}", getChannelId(), reconnected); } catch (Throwable ignored) {}
                    }

                    try {
                        if (this.owner != null && !reconnected) {
                            String msgTxt = "Web Editor socket connected.";
                            IComponent comp = services.getPlatformAdapter().createComponentFromLiteral(msgTxt);
                            try {
                                Object orig = this.owner.getOriginalSource();
                                if (orig instanceof net.minecraft.server.command.ServerCommandSource scs) {
                                    services.getPlatformAdapter().sendSuccess(scs, comp, false);
                                }
                            } catch (Throwable ignored) {}
                        }
                    } catch (Throwable ignored) {}
                } catch (Exception e) {
                    throw new IllegalStateException("HELLO handling failed", e);
                }
                return;
            }

            boolean verified = this.remotePublicKey != null && signature != null && !signature.isEmpty() &&
                    SignatureAlgorithm.INSTANCE.verify(this.remotePublicKey, innerMsg, signature);
            if (!verified) {
                throw new IllegalStateException("Signature not accepted");
            }

            switch (type) {
                case PING: {
                    this.lastPing = System.currentTimeMillis();
                    JsonObject pong = new JsonObject();
                    pong.addProperty("type", SocketMessageType.PONG.id);
                    pong.addProperty("ok", true);
                    send(pong);
                    break;
                }
                case CONNECTED: {
                    break;
                }
                case CHANGE_REQUEST: {
                    String code = safeGetAsString(msg, "code");
                    if (code == null || code.isEmpty()) throw new IllegalArgumentException("Invalid code");
                    services.getTaskScheduler().schedule(() -> {
                        try {
                            JsonObject changeAccepted = new JsonObject();
                            changeAccepted.addProperty("type", SocketMessageType.CHANGE_RESPONSE.id);
                            changeAccepted.addProperty("state", "accepted");
                            send(changeAccepted);

                            int applied;
                            try {
                                applied = EditorApplier.applyFromBytebin(services, code);
                            } catch (Exception ex) {
                                throw new RuntimeException(ex);
                            }

                            JsonObject followup = WebEditorSession.buildPayload(services);
                            String newSessionCode = WebEditorSession.uploadPayload(services, followup);

                            JsonObject appliedMsg = new JsonObject();
                            appliedMsg.addProperty("type", SocketMessageType.CHANGE_RESPONSE.id);
                            appliedMsg.addProperty("state", "applied");
                            if (newSessionCode != null) appliedMsg.addProperty("newSessionCode", newSessionCode);
                            appliedMsg.addProperty("appliedCount", applied);
                            send(appliedMsg);
                            try {
                                services.getLogger().info("Paradigm WebEditor: Applied {} change(s) from socket channel {}.", applied, getChannelId());
                            } catch (Throwable ignored) {}
                        } catch (Exception e) {
                            JsonObject err = new JsonObject();
                            err.addProperty("type", SocketMessageType.CHANGE_RESPONSE.id);
                            err.addProperty("state", "error");
                            err.addProperty("message", e.getMessage() == null ? e.toString() : e.getMessage());
                            send(err);
                            try {
                                services.getLogger().warn("Paradigm WebEditor: Apply from socket failed for channel {}", getChannelId(), e);
                            } catch (Throwable ignored) {}
                        }
                    }, 0, TimeUnit.SECONDS);
                    break;
                }
                default:
                    throw new IllegalStateException("Unknown message type: " + type);
            }
        } catch (Throwable t) {
            try { services.getLogger().warn("Paradigm WebEditor: Error in message handling", t); } catch (Throwable ignored) {}
        }
    }

    private static final class BytesocksListener implements WebSocket.Listener {
        private final WebEditorSocket parent;
        public BytesocksListener(WebEditorSocket parent) { this.parent = parent; }
        @Override
        public void onOpen(WebSocket webSocket) {
            webSocket.request(1);
            this.parent.connectFuture.complete(null);
            this.parent.scheduleCleanupIfUnused();
            try { parent.services.getLogger().info("Paradigm WebEditor: WebSocket opened, channel={}", parent.getChannelId()); } catch (Throwable ignored) {}
        }
        @Override
        public CompletionStage<?> onText(WebSocket webSocket, CharSequence data, boolean last) {
            try { parent.services.getLogger().info("Paradigm WebEditor: onText received, channel={}, bytes={}", parent.getChannelId(), data == null ? 0 : data.length()); } catch (Throwable ignored) {}
            try {
                String s = data == null ? "" : data.toString();
                this.parent.handleMessageFrame(s);
            } catch (Exception e) {
                try { parent.services.getLogger().warn("Paradigm WebEditor: Error handling socket frame", e); } catch (Throwable ignored) {}
            } finally {
                webSocket.request(1);
            }
            return CompletableFuture.completedFuture(null);
        }
        @Override
        public CompletionStage<?> onBinary(WebSocket webSocket, ByteBuffer data, boolean last) {
            webSocket.request(1);
            return CompletableFuture.completedFuture(null);
        }
        @Override
        public void onError(WebSocket webSocket, Throwable error) {
            try { parent.services.getLogger().warn("Paradigm WebEditor: Socket error", error); } catch (Throwable ignored) {}
        }
        @Override
        public CompletionStage<?> onClose(WebSocket webSocket, int statusCode, String reason) {
            parent.close();
            return CompletableFuture.completedFuture(null);
        }
    }
}
