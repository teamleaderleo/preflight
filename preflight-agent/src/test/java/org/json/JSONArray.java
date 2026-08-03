package org.json;

import java.util.ArrayList;
import java.util.List;

/** Minimal test double for the game's JSON array implementation. See {@link JSONObject}. */
public final class JSONArray {
    private final String json;
    private final List<Object> elements = new ArrayList<>();

    public JSONArray() {
        this.json = null;
    }

    public JSONArray(String json) {
        if (json.contains("INVALID")) {
            throw new IllegalArgumentException("invalid JSON");
        }
        this.json = json;
    }

    public JSONArray put(Object value) {
        elements.add(value);
        return this;
    }

    public Object get(int index) {
        return elements.get(index);
    }

    public int length() {
        return elements.size();
    }

    @Override
    public String toString() {
        return json != null ? json : elements.toString();
    }
}
