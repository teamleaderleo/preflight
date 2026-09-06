package com.genir.renderer.overrides.loading.textures;

import java.awt.Color;
import java.nio.ByteBuffer;
import java.nio.file.Path;

/** Synthetic public-field carrier for bridge tests; no renderer implementation. */
public class TextureData {
    public ByteBuffer buffer;
    public int width;
    public int height;
    public int imageWidth;
    public int imageHeight;
    public boolean hasAlpha;
    public Path ddsImagePath;
    public Color mean;
    public Color weighted;
    public Color median;
}
