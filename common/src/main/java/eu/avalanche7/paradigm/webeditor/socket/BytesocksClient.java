package eu.avalanche7.paradigm.webeditor.socket;

import java.io.IOException;
import java.net.HttpURLConnection;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.WebSocket;
import java.util.Objects;

public class BytesocksClient {
    private final String httpUrl;
    private final String wsUrl;
    private final String userAgent;
    private boolean debugEnabled = false;

    public BytesocksClient(String host, String userAgent) {
        String h = Objects.requireNonNull(host, "host");
        if (h.endsWith("/")) h = h.substring(0, h.length() - 1);
        this.httpUrl = "https://" + h + "/";
        this.wsUrl = "wss://" + h + "/";
        this.userAgent = userAgent == null ? "paradigm/editor" : userAgent;
        try {
            this.debugEnabled = eu.avalanche7.paradigm.configs.MainConfigHandler.getConfig().debugEnable.value;
        } catch (Throwable ignored) {}
    }

    public Socket createSocket(WebSocket.Listener listener) throws IOException {
        HttpURLConnection conn = null;
        try {
            String createUrl = this.httpUrl + "create";
            if (debugEnabled) {
                System.out.println("[BytesocksClient] Creating channel at: " + createUrl);
            }//

            conn = (HttpURLConnection) URI.create(createUrl).toURL().openConnection();
            conn.setRequestMethod("GET");
            conn.setRequestProperty("User-Agent", this.userAgent);
            conn.setConnectTimeout(15000);
            conn.setReadTimeout(15000);

            int code = conn.getResponseCode();
            if (debugEnabled) {
                System.out.println("[BytesocksClient] HTTP response code: " + code);
            }

            if (code != 201) {
                String errorBody = readErrorStream(conn);
                String errorMsg = "Bytesocks create returned status " + code + (errorBody.isEmpty() ? "" : ", body: " + errorBody);
                if (debugEnabled) {
                    System.err.println("[BytesocksClient] " + errorMsg);
                }
                throw new IOException(errorMsg);
            }

            String id = conn.getHeaderField("Location");
            if (debugEnabled) {
                System.out.println("[BytesocksClient] Location header: " + id);
            }

            if (id == null || id.isEmpty()) {
                throw new IOException("Bytesocks create did not return channel id");
            }

            String wsFullUrl = this.wsUrl + id;
            if (debugEnabled) {
                System.out.println("[BytesocksClient] Connecting WebSocket to: " + wsFullUrl);
            }

            WebSocket ws = HttpClient.newHttpClient()
                    .newWebSocketBuilder()
                    .buildAsync(URI.create(wsFullUrl), listener)
                    .join();

            if (debugEnabled) {
                System.out.println("[BytesocksClient] WebSocket connected, channel: " + id);
            }

            return new Socket(id, ws);
        } catch (Exception e) {
            if (debugEnabled) {
                System.err.println("[BytesocksClient] Exception: " + e.getClass().getName() + ": " + e.getMessage());
            }
            if (e instanceof IOException) {
                throw (IOException) e;
            }
            throw new IOException("Failed to create socket: " + e.getMessage(), e);
        } finally {
            if (conn != null) conn.disconnect();
        }
    }

    private String readErrorStream(HttpURLConnection conn) {
        try {
            java.io.InputStream es = conn.getErrorStream();
            if (es == null) return "";
            java.io.ByteArrayOutputStream baos = new java.io.ByteArrayOutputStream();
            byte[] buf = new byte[1024];
            int read;
            while ((read = es.read(buf)) != -1 && baos.size() < 4096) {
                if (read > 0) baos.write(buf, 0, read);
            }
            return baos.toString(java.nio.charset.StandardCharsets.UTF_8);
        } catch (Exception ignored) {
            return "";
        }
    }

    public static final class Socket {
        private final String channelId;
        private final WebSocket webSocket;
        public Socket(String channelId, WebSocket webSocket) {
            this.channelId = channelId;
            this.webSocket = webSocket;
        }
        public String channelId() { return channelId; }
        public WebSocket webSocket() { return webSocket; }
    }
}
