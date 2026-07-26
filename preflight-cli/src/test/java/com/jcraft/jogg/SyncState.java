package com.jcraft.jogg;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.Base64;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;

/**
 * Repository-owned stand-in for jogg's sync layer, used only by the installed-decoder child tests.
 *
 * <p>It models the shape of the container the installed decoder drives — feed bytes with
 * {@code buffer}/{@code wrote}, take whole pages with {@code pageout} — rather than parsing Ogg. A
 * fixture is recognised only once its bytes have arrived in full, so anything truncated or unknown
 * never yields a page and the caller runs out of input exactly as it would against the real decoder.
 *
 * <p>Page 0 carries the three Vorbis headers. Each later page carries one audio packet holding one
 * block of samples taken from the committed reference PCM, and the last page sets end-of-stream.
 */
public final class SyncState {

    /**
     * Frames of reference PCM handed over per audio packet.
     *
     * <p>Chosen to exceed the caller's stereo conversion buffer, which holds {@code 4096 / channels}
     * frames. A block bigger than that buffer cannot be converted in one pass, so this value is what
     * makes a decoder that drains each block only once produce short output instead of passing
     * quietly. The stereo fixture also spans more than one block, so paging is exercised too.
     */
    private static final int BLOCK_FRAMES = 2_500;

    private static final Map<String, Fixture> FIXTURES = Map.of(
            "2743d710c5df780d381664097a747bd4baf949f9721fbfa8a6e6c14477658b07",
            new Fixture("mono-22050-reference.s16le", 1, 22_050, 1001),
            "83c01b0343243bbff24d9b6de9619a476ccdf4b8993db13805f9a86f191031c0",
            new Fixture("stereo-44100-reference.s16le", 2, 44_100, 1002),
            "fe0202cd86957a1c6af4eb37d7dc540e266f1a9d81aff9a56274dd36cd8bbab3",
            new Fixture("silence-mono-8000-reference.s16le", 1, 8_000, 1003));

    public byte[] data = new byte[0];

    private int filled;
    private Fixture fixture;
    private List<List<Object>> pages;
    private int nextPage;

    public void init() {
        data = new byte[0];
        filled = 0;
        fixture = null;
        pages = null;
        nextPage = 0;
    }

    public int buffer(int bytes) {
        if (data.length < filled + bytes) {
            byte[] grown = new byte[filled + bytes];
            System.arraycopy(data, 0, grown, 0, filled);
            data = grown;
        }
        return filled;
    }

    public void wrote(int bytes) {
        filled += bytes;
    }

    public int pageout(Page page) {
        if (pages == null) {
            identify();
            if (pages == null) {
                return 0;
            }
        }
        if (nextPage >= pages.size()) {
            return 0;
        }
        page.packets = pages.get(nextPage);
        page.serialno = fixture.serial();
        page.eos = nextPage == pages.size() - 1 ? 1 : 0;
        nextPage++;
        return 1;
    }

    public void clear() {
        init();
    }

    /** Recognises the fixture only when every one of its bytes has been fed. */
    private void identify() {
        byte[] seen = new byte[filled];
        System.arraycopy(data, 0, seen, 0, filled);
        Fixture candidate = FIXTURES.get(sha256(seen));
        if (candidate == null) {
            return;
        }
        byte[] pcm;
        try {
            pcm = resource(candidate.pcmResource());
        } catch (IOException failure) {
            throw new IllegalStateException("missing stand-in reference PCM", failure);
        }
        fixture = candidate;
        pages = new ArrayList<>();
        pages.add(List.of(
                new Header(0, candidate.channels(), candidate.sampleRate()),
                new Header(1, candidate.channels(), candidate.sampleRate()),
                new Header(2, candidate.channels(), candidate.sampleRate())));

        int channels = candidate.channels();
        int frames = pcm.length / (channels * 2);
        for (int start = 0; start < frames; start += BLOCK_FRAMES) {
            int count = Math.min(BLOCK_FRAMES, frames - start);
            float[][] block = new float[channels][count];
            for (int channel = 0; channel < channels; channel++) {
                for (int frame = 0; frame < count; frame++) {
                    int index = ((start + frame) * channels + channel) * 2;
                    int sample = (short) ((pcm[index] & 0xff) | (pcm[index + 1] << 8));
                    block[channel][frame] = biased(sample);
                }
            }
            pages.add(List.of((Object) block));
        }
    }

    /**
     * Turns a reference sample into the float the caller's conversion will land back on exactly.
     *
     * <p>The installed conversion is {@code (int) (sample * 32767.0)}, which truncates toward zero, so
     * a float a hair below the intended magnitude would come back one step short. Biasing a quarter
     * step away from zero clears that edge by far more than {@code float} rounding can move it, which
     * keeps this stand-in from turning a decoder test into a floating-point test.
     */
    private static float biased(int sample) {
        return (sample + (sample >= 0 ? 0.25f : -0.25f)) / 32767.0f;
    }

    /**
     * One of the three Vorbis header packets that precede any audio.
     *
     * <p>The real headers encode the format, and the decoder learns channel count and sample rate by
     * parsing them. The stand-in carries those two values directly so {@code Info} can pick them up at
     * the same point in the sequence.
     */
    public record Header(int index, int channels, int sampleRate) {
    }

    private record Fixture(String pcmResource, int channels, int sampleRate, int serial) {
    }

    private static byte[] resource(String name) throws IOException {
        String base = "/audio/ogg-v1/" + name + ".b64";
        InputStream single = SyncState.class.getResourceAsStream(base);
        if (single != null) {
            try (single) {
                return Base64.getMimeDecoder().decode(single.readAllBytes());
            }
        }
        ByteArrayOutputStream encoded = new ByteArrayOutputStream();
        int parts = 0;
        for (int i = 0; i < 100; i++) {
            InputStream part = SyncState.class.getResourceAsStream(base + ".part" + String.format("%02d", i));
            if (part == null) {
                break;
            }
            try (part) {
                part.transferTo(encoded);
            }
            parts++;
        }
        if (parts == 0) {
            throw new IOException("missing stand-in PCM resource " + base);
        }
        return Base64.getMimeDecoder().decode(encoded.toByteArray());
    }

    private static String sha256(byte[] bytes) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
        } catch (Exception impossible) {
            throw new IllegalStateException(impossible);
        }
    }
}
