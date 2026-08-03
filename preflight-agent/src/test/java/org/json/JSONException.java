package org.json;

/**
 * Minimal test double for the exception the game's merged readers declare.
 *
 * <p>It exists because reflection resolves a method's declared exception types: without it,
 * {@code getMethod} on a generated merged reader fails before the test can call it.
 */
public class JSONException extends RuntimeException {
    private static final long serialVersionUID = 1L;

    public JSONException(String message) {
        super(message);
    }
}
