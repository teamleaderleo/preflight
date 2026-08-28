package org.lwjgl.opengl;

/** Minimal LWJGL 2 display dimensions used by the combat viewport control test. */
public final class Display {
    private Display() {
    }

    public static int getWidth() {
        return 1_920;
    }

    public static int getHeight() {
        return 1_080;
    }
}
