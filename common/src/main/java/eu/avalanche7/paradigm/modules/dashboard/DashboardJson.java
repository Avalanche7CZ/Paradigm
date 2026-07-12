package eu.avalanche7.paradigm.modules.dashboard;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonObject;

import java.io.Reader;

public final class DashboardJson {
    public static final Gson GSON = new GsonBuilder().disableHtmlEscaping().setPrettyPrinting().create();

    private DashboardJson() {
    }

    public static String toJson(Object value) {
        return GSON.toJson(value);
    }

    public static <T> T fromJson(Reader reader, Class<T> type) {
        return GSON.fromJson(reader, type);
    }

    public static JsonObject object() {
        return new JsonObject();
    }
}
