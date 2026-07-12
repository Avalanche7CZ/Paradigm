package eu.avalanche7.paradigm.modules.dashboard;

import java.util.List;
import java.util.Map;

public class DashboardResponse {
    private final int status;
    private final String contentType;
    private final byte[] body;
    private final Map<String, String> headers;

    public DashboardResponse(int status, String contentType, byte[] body, Map<String, String> headers) {
        this.status = status;
        this.contentType = contentType;
        this.body = body != null ? body : new byte[0];
        this.headers = headers != null ? headers : Map.of();
    }

    public int status() {
        return status;
    }

    public String contentType() {
        return contentType;
    }

    public byte[] body() {
        return body;
    }

    public Map<String, String> headers() {
        return headers;
    }

    public static DashboardResponse json(int status, Object data) {
        return new DashboardResponse(status, "application/json; charset=utf-8", DashboardJson.toJson(data).getBytes(java.nio.charset.StandardCharsets.UTF_8), Map.of("Cache-Control", "no-store"));
    }

    public static DashboardResponse apiOk(Object data) {
        return json(200, new ApiEnvelope(true, data, null, List.of()));
    }

    public static DashboardResponse apiOk(Object data, List<String> warnings) {
        return json(200, new ApiEnvelope(true, data, null, warnings != null ? warnings : List.of()));
    }

    public static DashboardResponse apiError(int status, String code, String message) {
        return json(status, new ApiEnvelope(false, null, new ApiError(code, message), List.of()));
    }

    public static DashboardResponse bytes(int status, String contentType, byte[] body, Map<String, String> headers) {
        return new DashboardResponse(status, contentType, body, headers);
    }

    public record ApiEnvelope(boolean ok, Object data, ApiError error, List<String> warnings) {
    }

    public record ApiError(String code, String message) {
    }
}
