package com.jcraft.jorbis;

import com.jcraft.jogg.Packet;

/**
 * Repository-owned stand-in for a Vorbis block.
 *
 * <p>Holds the samples belonging to one audio packet until {@link DspState#synthesis_blockin} takes
 * them, mirroring the real ownership: the block is filled by {@code synthesis} and drained by the DSP
 * state, and the caller never touches the samples directly.
 */
public final class Block {

    float[][] samples;

    public Block(DspState dsp) {
    }

    public void init(DspState dsp) {
    }

    public int synthesis(Packet packet) {
        if (!(packet.payload instanceof float[][] block)) {
            return -1;
        }
        samples = block;
        return 0;
    }

    public void clear() {
        samples = null;
    }
}
