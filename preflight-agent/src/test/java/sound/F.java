package sound;

import java.nio.ByteBuffer;

/**
 * Repository-owned decoded-audio object fixture.
 *
 * <p>{@code pcm} and {@code sampleRate} belong to the contract-report fixture. The three
 * obfuscated names below are the shape the installed class actually has, and are what
 * {@link dev.starsector.preflight.agent.PreparedAudioRuntime} fills in by name: channel count, the
 * direct PCM buffer handed to {@code alBufferData}, and the sample rate. Nothing here is copied
 * from the game -- only the names the runtime looks up, so that looking them up can be tested.
 */
public final class F {
    public final byte[] pcm;
    public final int sampleRate;

    /** Channel count: the loader turns 1 into mono16 and anything else into stereo16. */
    public int o00000;
    /** The direct PCM buffer. */
    public ByteBuffer Object;
    /** Sample rate in hertz. */
    public int Ò00000;

    public F() {
        this(new byte[0], 0);
    }

    public F(byte[] pcm, int sampleRate) {
        this.pcm = pcm.clone();
        this.sampleRate = sampleRate;
    }
}
