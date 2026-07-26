package com.jcraft.jorbis;

/**
 * Repository-owned stand-in for the Vorbis comment header.
 *
 * <p>{@code sound/void} constructs one and initialises it so the header parse has somewhere to put
 * metadata, then never reads it back. The stand-in does the same amount of nothing.
 */
public final class Comment {
    public void init() {
    }
}
