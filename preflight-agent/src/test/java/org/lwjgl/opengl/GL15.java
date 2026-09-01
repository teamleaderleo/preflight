package org.lwjgl.opengl;

/** Minimal stateful query API used by GPU timer runtime tests. */
public final class GL15 {
    private static int nextId = 1;
    private static int current;
    private static boolean available = true;

    private GL15() {
    }

    public static int glGenQueries() {
        return nextId++;
    }

    public static void glDeleteQueries(int id) {
    }

    public static void glBeginQuery(int target, int id) {
        if (current != 0) throw new IllegalStateException("query already active");
        current = id;
    }

    public static void glEndQuery(int target) {
        if (current == 0) throw new IllegalStateException("no query active");
        current = 0;
    }

    public static int glGetQueryi(int target, int parameter) {
        return current;
    }

    public static int glGetQueryObjecti(int id, int parameter) {
        return available ? 1 : 0;
    }

    public static void setAvailable(boolean value) {
        available = value;
    }

    public static void reset() {
        nextId = 1;
        current = 0;
        available = true;
    }
}
