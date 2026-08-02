package com.fs.starfarer.loading;

/** Minimal test double for the vanilla merged-JSON loader. */
public final class LoadingUtils {
    private static int calls;

    private LoadingUtils() {
    }

    public static org.json.JSONObject Ó00000(String path) {
        calls++;
        return new org.json.JSONObject("{\"path\":\"" + path + "\"}");
    }

    public static int calls() {
        return calls;
    }

    public static void reset() {
        calls = 0;
    }
}
