package org.lwjgl.opengl;

/** Installation-owned LWJGL 2 seam represented without adding LWJGL to the agent artifact. */
public final class GLContext {
    private static ContextCapabilities capabilities = supported();
    private static boolean current = true;

    private GLContext() {
    }

    public static ContextCapabilities getCapabilities() {
        if (!current) {
            throw new IllegalStateException("No OpenGL context is current");
        }
        return capabilities;
    }

    public static void setCapabilities(boolean openGl20, boolean arbNpot) {
        ContextCapabilities value = new ContextCapabilities();
        value.OpenGL20 = openGl20;
        value.GL_ARB_texture_non_power_of_two = arbNpot;
        capabilities = value;
        current = true;
    }

    public static void setCurrent(boolean value) {
        current = value;
    }

    public static void reset() {
        capabilities = supported();
        current = true;
    }

    private static ContextCapabilities supported() {
        ContextCapabilities value = new ContextCapabilities();
        value.OpenGL20 = true;
        value.GL_ARB_texture_non_power_of_two = true;
        return value;
    }
}
