package com.jcraft.jorbis;

import java.util.ArrayDeque;
import java.util.Deque;

/**
 * Repository-owned stand-in for the Vorbis synthesis state.
 *
 * <p>Reproduces the borrow-and-return contract the installed decoder relies on: {@code pcmout}
 * exposes the samples in place and reports how many frames are ready, and the caller consumes some
 * prefix of them with {@code synthesis_read}. A block stays available until it has been fully read, so
 * a caller that reads in smaller pieces than the block still sees every frame exactly once.
 */
public final class DspState {

    private final Deque<float[][]> blocks = new ArrayDeque<>();
    private int channels;
    private int consumed;

    public int synthesis_init(Info info) {
        channels = info.channels;
        blocks.clear();
        consumed = 0;
        return 0;
    }

    public int synthesis_blockin(Block block) {
        if (block.samples != null) {
            blocks.addLast(block.samples);
            block.samples = null;
        }
        return 0;
    }

    public int synthesis_pcmout(float[][][] pcm, int[] offsets) {
        if (blocks.isEmpty()) {
            return 0;
        }
        float[][] block = blocks.peekFirst();
        pcm[0] = block;
        for (int channel = 0; channel < channels; channel++) {
            offsets[channel] = consumed;
        }
        return block[0].length - consumed;
    }

    public int synthesis_read(int frames) {
        if (blocks.isEmpty()) {
            return 0;
        }
        consumed += frames;
        if (consumed >= blocks.peekFirst()[0].length) {
            blocks.removeFirst();
            consumed = 0;
        }
        return 0;
    }

    public void clear() {
        blocks.clear();
        consumed = 0;
    }
}
