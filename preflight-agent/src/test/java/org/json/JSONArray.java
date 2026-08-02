package org.json;

/** Minimal test double for the game's JSON array implementation. */
public final class JSONArray {
    private final String json;

    public JSONArray(String json) {
        if (json.contains("INVALID")) {
            throw new IllegalArgumentException("invalid JSON");
        }
        this.json = json;
    }

    @Override
    public String toString() {
        return json;
    }
}
