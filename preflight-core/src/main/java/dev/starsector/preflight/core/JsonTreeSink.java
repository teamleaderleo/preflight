package dev.starsector.preflight.core;

/**
 * Where {@link JsonTree#decode} puts the values it reads.
 *
 * <p>This exists so the decoder never builds an intermediate {@code Map} and {@code List} only to
 * copy them into the game's own JSON types. The caller supplies the containers, the decoder fills
 * them in one pass, and nothing is allocated that does not end up in the returned value.
 *
 * <p>Implementations run on the launch's critical path and may reach the game's classes reflectively,
 * so no method here declares a checked exception: an implementation that cannot complete a
 * {@code put} throws a runtime exception and the caller falls back to vanilla.
 */
public interface JsonTreeSink {
    /** The value that means "present, and null" -- not a missing key. */
    Object nullValue();

    /** A fresh, empty object. */
    Object newObject();

    /** Adds one member to an object made by {@link #newObject()}. */
    void put(Object object, String key, Object value);

    /** A fresh, empty array. */
    Object newArray();

    /** Appends one element to an array made by {@link #newArray()}. */
    void add(Object array, Object value);
}
