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

import java.util.Objects;

public class WebEditorResponse {

    private final String id;
    private final JsonObject payload;

    public WebEditorResponse(String id, JsonObject payload) {
        this.id = Objects.requireNonNull(id, "id");
        this.payload = Objects.requireNonNull(payload, "payload");
    }

    public String getId() {
        return id;
    }

    public JsonObject getPayload() {
        return payload;
    }

    public boolean hasAnyChanges() {
        if (payload.has("changes")) {
            JsonElement el = payload.get("changes");
            if (el.isJsonArray() && el.getAsJsonArray().size() > 0) return true;
        }
        if (payload.has("deletions")) {
            JsonElement el = payload.get("deletions");
            if (el.isJsonArray() && el.getAsJsonArray().size() > 0) return true;
        }
        return false;
    }

    public int countChanges() {
        int total = 0;
        if (payload.has("changes") && payload.get("changes").isJsonArray()) {
            total += payload.getAsJsonArray("changes").size();
        }
        if (payload.has("deletions") && payload.get("deletions").isJsonArray()) {
            total += payload.getAsJsonArray("deletions").size();
        }
        return total;
    }

    public JsonArray getChanges() {
        return payload.has("changes") && payload.get("changes").isJsonArray() ? payload.getAsJsonArray("changes") : new JsonArray();
    }

    public JsonArray getDeletions() {
        return payload.has("deletions") && payload.get("deletions").isJsonArray() ? payload.getAsJsonArray("deletions") : new JsonArray();
    }
}
