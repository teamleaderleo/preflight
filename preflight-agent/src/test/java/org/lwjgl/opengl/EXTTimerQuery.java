package org.lwjgl.opengl;

/** Minimal EXT timer-query result API used by GPU timer runtime tests. */
public final class EXTTimerQuery {
    private static long resultNanos = 4_000_000L;

    private EXTTimerQuery() {
    }

    public static long glGetQueryObjectuEXT(int id, int parameter) {
        return resultNanos;
    }

    public static void setResultNanos(long value) {
        resultNanos = value;
    }

    public static void reset() {
        resultNanos = 4_000_000L;
    }
}
