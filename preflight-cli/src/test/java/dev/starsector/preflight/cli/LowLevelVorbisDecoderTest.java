package dev.starsector.preflight.cli;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.Base64;
import org.junit.jupiter.api.Test;

/**
 * Exercises the decoder against the repository-owned jogg/jorbis stand-ins.
 *
 * <p>These prove the harness drives the container correctly — pages in, packets out, headers before
 * audio, blocks drained to completion. They cannot prove agreement with the installed decoder; only a
 * run against the reviewed installation does that.
 */
class LowLevelVorbisDecoderTest {

    @Test
    void decodesEveryFrameTheContainerHandsOver() throws Exception {
        byte[] pcm = new LowLevelVorbisDecoder().decode(source("mono-22050.ogg")).pcm();
        assertArrayEquals(reference("mono-22050-reference.s16le"), pcm);
    }

    @Test
    void interleavesChannelsInTheOrderTheUploadExpects() throws Exception {
        byte[] pcm = new LowLevelVorbisDecoder().decode(source("stereo-44100.ogg")).pcm();
        assertArrayEquals(reference("stereo-44100-reference.s16le"), pcm);
    }

    @Test
    void reportsTheFormatItLearnedFromTheHeaders() throws Exception {
        LowLevelVorbisDecoder.Decoded decoded = new LowLevelVorbisDecoder().decode(source("stereo-44100.ogg"));
        assertEquals(2, decoded.channels());
        assertEquals(44_100, decoded.sampleRate());
        assertEquals(decoded.pcm().length / 4, reference("stereo-44100-reference.s16le").length / 4);
    }

    @Test
    void drainsBlocksLargerThanTheConversionBuffer() throws Exception {
        // The stereo conversion buffer holds 2,048 frames and the stand-in hands over 2,500 per packet,
        // so the first block cannot be converted in one pass. A decoder that drained once per packet
        // would return short output rather than fail, which is the kind of quiet loss this gate exists
        // to catch.
        LowLevelVorbisDecoder.Decoded decoded = new LowLevelVorbisDecoder().decode(source("stereo-44100.ogg"));
        assertTrue(decoded.packets() > 1, "expected several audio packets, got " + decoded.packets());
        assertEquals(reference("stereo-44100-reference.s16le").length, decoded.pcm().length);
    }

    @Test
    void readsThroughToEndOfStream() throws Exception {
        assertTrue(new LowLevelVorbisDecoder().decode(source("mono-22050.ogg")).sawEndOfStream());
    }

    @Test
    void decodesTheSameBytesToTheSamePcmTwice() throws Exception {
        LowLevelVorbisDecoder decoder = new LowLevelVorbisDecoder();
        assertArrayEquals(
                decoder.decode(source("stereo-44100.ogg")).pcm(),
                decoder.decode(source("stereo-44100.ogg")).pcm());
    }

    @Test
    void keepsSilenceDistinctFromTone() throws Exception {
        LowLevelVorbisDecoder decoder = new LowLevelVorbisDecoder();
        byte[] silence = decoder.decode(source("silence-mono-8000.ogg")).pcm();
        byte[] tone = decoder.decode(source("mono-22050.ogg")).pcm();
        // The superseded gate decoded a clipping-stress fixture to digital silence and reported only a
        // hash mismatch. Any decoder this project trusts has to fail loudly instead of quietly.
        assertTrue(allZero(silence), "silence fixture should decode to zeros");
        assertNotEquals(0, countNonZero(tone), "tone fixture must not decode to silence");
    }

    @Test
    void refusesInputThatNeverYieldsHeaders() {
        LowLevelVorbisDecoder decoder = assertDoesNotThrowDecoder();
        IOException failure = assertThrows(IOException.class,
                () -> decoder.decode(new ByteArrayInputStream("not an ogg stream".getBytes(StandardCharsets.US_ASCII))));
        assertTrue(failure.getMessage().contains("three Vorbis headers"), failure.getMessage());
    }

    @Test
    void refusesAStreamThatStopsBeforeItIsComplete() throws Exception {
        byte[] truncated = Arrays.copyOf(bytes("mono-22050.ogg"), 19);
        LowLevelVorbisDecoder decoder = new LowLevelVorbisDecoder();
        assertThrows(IOException.class, () -> decoder.decode(new ByteArrayInputStream(truncated)));
    }

    private static LowLevelVorbisDecoder assertDoesNotThrowDecoder() {
        try {
            return new LowLevelVorbisDecoder();
        } catch (Exception failure) {
            throw new AssertionError("decoder should construct against the stand-ins", failure);
        }
    }

    private static boolean allZero(byte[] pcm) {
        for (byte value : pcm) {
            if (value != 0) {
                return false;
            }
        }
        return true;
    }

    private static int countNonZero(byte[] pcm) {
        int count = 0;
        for (byte value : pcm) {
            if (value != 0) {
                count++;
            }
        }
        return count;
    }

    private static InputStream source(String name) throws IOException {
        return new ByteArrayInputStream(bytes(name));
    }

    private static byte[] reference(String name) throws IOException {
        return bytes(name);
    }

    private static byte[] bytes(String name) throws IOException {
        String base = "/audio/ogg-v1/" + name + ".b64";
        InputStream single = LowLevelVorbisDecoderTest.class.getResourceAsStream(base);
        if (single != null) {
            try (single) {
                return Base64.getMimeDecoder().decode(single.readAllBytes());
            }
        }
        ByteArrayOutputStream encoded = new ByteArrayOutputStream();
        int parts = 0;
        for (int i = 0; i < 100; i++) {
            InputStream part = LowLevelVorbisDecoderTest.class.getResourceAsStream(
                    base + ".part" + String.format("%02d", i));
            if (part == null) {
                break;
            }
            try (part) {
                part.transferTo(encoded);
            }
            parts++;
        }
        if (parts == 0) {
            throw new IOException("missing fixture " + base);
        }
        return Base64.getMimeDecoder().decode(encoded.toByteArray());
    }
}
