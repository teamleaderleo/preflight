package org.json;

/** Minimal test double for the game's JSON implementation. */
public final class JSONObject {
    private final String json;

    public JSONObject(String json) {
        if ("INVALID".equals(json)) {
            throw new IllegalArgumentException("invalid JSON");
        }
        this.json = json;
    }

    @Override
    public String toString() {
        return json;
    }
}
