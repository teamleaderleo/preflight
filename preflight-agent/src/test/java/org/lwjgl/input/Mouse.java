package org.lwjgl.input;

/** Minimal LWJGL 2 cursor state used by the combat viewport control test. */
public final class Mouse {
    private static int x;
    private static int y;

    private Mouse() {
    }

    public static void setCursorPosition(int newX, int newY) {
        x = newX;
        y = newY;
    }

    public static int getX() {
        return x;
    }

    public static int getY() {
        return y;
    }
}
