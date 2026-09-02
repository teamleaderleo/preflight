package com.genir.renderer.overrides.loading;

import java.awt.Color;
import java.nio.ByteBuffer;

/** Test-only shape of the exact public Fast Rendering 0.8.4 carrier contract. */
public class TextureData {
    public ByteBuffer buffer;
    public int width;
    public int height;
    public boolean hasAlpha;
    public boolean isDDS;
    public Color mean = Color.WHITE;
    public Color weighted = Color.WHITE;
    public Color median = Color.WHITE;
}
