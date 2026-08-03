package org.json;

import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.TreeSet;

/**
 * Minimal test double for the game's JSON implementation.
 *
 * <p>It carries only what Preflight actually calls: the {@code NULL} sentinel, a no-argument
 * constructor, {@code put}, {@code get} and {@code sortedKeys}. Enough to exercise the bridge and the
 * tagged-tree round trip; not a JSON parser. Fidelity against the real {@code json.jar} is
 * established by replaying the installation's own data through the installation's own classes, which
 * no stub can stand in for.
 *
 * <p>The string constructor keeps its argument verbatim so that the caches which store
 * {@code toString()} still see exactly what they were given.
 */
public final class JSONObject {
    public static final Object NULL = new Object() {
        @Override
        public String toString() {
            return "null";
        }
    };

    private final String json;
    private final Map<String, Object> members = new LinkedHashMap<>();

    public JSONObject() {
        this.json = null;
    }

    public JSONObject(String json) {
        if ("INVALID".equals(json)) {
            throw new IllegalArgumentException("invalid JSON");
        }
        this.json = json;
    }

    public JSONObject put(String key, Object value) {
        members.put(key, value);
        return this;
    }

    public Object get(String key) {
        Object value = members.get(key);
        if (value == null && !members.containsKey(key)) {
            throw new IllegalArgumentException("no such key: " + key);
        }
        return value;
    }

    public boolean has(String key) {
        return members.containsKey(key);
    }

    public int length() {
        return members.size();
    }

    public Iterator<String> sortedKeys() {
        return new TreeSet<>(members.keySet()).iterator();
    }

    @Override
    public String toString() {
        return json != null ? json : members.toString();
    }
}
