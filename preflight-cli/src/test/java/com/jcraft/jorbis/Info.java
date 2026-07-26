package com.jcraft.jorbis;

import com.jcraft.jogg.Packet;
import com.jcraft.jogg.SyncState;

/** Repository-owned stand-in used only by packaged child-JVM integration fixtures. */
public final class Info {
    public int channels;
    public int rate;

    public void init() {
        channels = 0;
        rate = 0;
    }

    /**
     * Consumes one of the three Vorbis header packets, learning the format from the last of them.
     *
     * <p>Rejecting anything that is not a header is what lets the harness's own error handling be
     * exercised: a stream whose packets arrive out of order fails here rather than quietly producing
     * silence, and silence that nobody questioned is exactly how the superseded gate went unnoticed.
     */
    public int synthesis_headerin(Comment comment, Packet packet) {
        if (!(packet.payload instanceof SyncState.Header header)) {
            return -1;
        }
        if (header.index() == 2) {
            channels = header.channels();
            rate = header.sampleRate();
        }
        return 0;
    }

    /** Retained for the unrelated {@code sound/J} stand-in, which predates the decoder fixtures. */
    public int synthesis_headerin(int value) {
        return value + 44_100;
    }

    public void clear() {
        channels = 0;
        rate = 0;
    }
}
