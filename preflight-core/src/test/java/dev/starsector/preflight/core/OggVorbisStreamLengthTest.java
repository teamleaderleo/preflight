package dev.starsector.preflight.core;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Base64;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class OggVorbisStreamLengthTest {
    @TempDir
    Path temporaryDirectory;

    /**
     * The claim of this class is that a two-seek header read predicts what the installed decoder
     * will produce. These are the frame counts {@code sound/void}'s call sequence was measured to
     * emit for these exact fixtures — 4,096 and 16,384 PCM bytes — so the prediction is checked
     * against the real decode path rather than against itself.
     */
    @Test
    void monoGranulePositionPredictsTheInstalledDecodeLength() throws Exception {
        OggVorbisStreamLength.Measurement measurement = OggVorbisStreamLength.measure(write("mono-22050.ogg"));

        assertEquals(OggVorbisStreamLength.Status.MEASURED, measurement.status(), measurement.detail());
        assertEquals(2_048, measurement.totalFrames());
        assertEquals(4_096, measurement.decodedBytes(1));
    }

    @Test
    void stereoGranulePositionPredictsTheInstalledDecodeLength() throws Exception {
        OggVorbisStreamLength.Measurement measurement = OggVorbisStreamLength.measure(write("stereo-44100.ogg"));

        assertEquals(OggVorbisStreamLength.Status.MEASURED, measurement.status(), measurement.detail());
        assertEquals(4_096, measurement.totalFrames());
        assertEquals(16_384, measurement.decodedBytes(2));
    }

    /**
     * JOrbis never trims its final block, so when a stream does not end on a block boundary it emits
     * more frames than the container declares. {@code packet-boundary-mono-44100} exists to have an
     * uneven final packet: it declares 8,193 frames and the installed decoder produces 8,320.
     *
     * <p>This is why the census treats the granule position as a floor. It also bounds the error —
     * one maximum block, and only for streams that do not end evenly.
     */
    @Test
    void anUnevenFinalPacketMakesTheDeclaredLengthAFloorRatherThanAnEquality() throws Exception {
        OggVorbisStreamLength.Measurement measurement =
                OggVorbisStreamLength.measure(write("packet-boundary-mono-44100.ogg"));
        OggVorbisIdentification.Result identification =
                OggVorbisIdentification.inspect(fixture("packet-boundary-mono-44100.ogg"));

        assertEquals(8_193, measurement.totalFrames(), measurement.detail());
        long installedFrames = 16_640 / 2;
        assertTrue(installedFrames > measurement.totalFrames(), "the declared length must be a floor");
        assertTrue(installedFrames - measurement.totalFrames() < identification.largeBlockSize(),
                "the overshoot must stay inside one maximum block");
    }

    /** Silence has a length like anything else; a zero here would be a decoder-shaped blind spot. */
    @Test
    void silenceIsMeasuredRatherThanTreatedAsEmpty() throws Exception {
        OggVorbisStreamLength.Measurement measurement = OggVorbisStreamLength.measure(write("silence-mono-8000.ogg"));

        assertEquals(OggVorbisStreamLength.Status.MEASURED, measurement.status(), measurement.detail());
        assertTrue(measurement.totalFrames() > 0, measurement.detail());
        assertEquals(measurement.totalFrames() * 2, measurement.decodedBytes(1));
    }

    @Test
    void everyGoldenFixtureMeasuresConsistentlyWithItsIdentification() throws Exception {
        for (String name : new String[] {
                "mono-22050.ogg",
                "stereo-44100.ogg",
                "silence-mono-8000.ogg",
                "clipping-stereo-48000.ogg",
                "packet-boundary-mono-44100.ogg"}) {
            byte[] bytes = fixture(name);
            OggVorbisIdentification.Result identification = OggVorbisIdentification.inspect(bytes);
            OggVorbisStreamLength.Measurement measurement = OggVorbisStreamLength.measure(write(name));

            assertTrue(identification.supported(), name + ": " + identification.detail());
            assertEquals(OggVorbisStreamLength.Status.MEASURED, measurement.status(), name + ": " + measurement.detail());
            long decoded = measurement.decodedBytes(identification.channels());
            assertEquals(0, decoded % (2L * identification.channels()), name);
            assertTrue(decoded > 0, name);
        }
    }

    /**
     * The backwards scan finds a capture pattern by content, and four bytes recur inside page
     * bodies by chance. Only the CRC separates a real page header from a coincidence, so corrupting
     * the final page's checksum must make the scan reject it rather than trust the granule beside it.
     */
    @Test
    void aFinalPageWithABrokenChecksumIsNotTrusted() throws Exception {
        byte[] bytes = fixture("mono-22050.ogg");
        int lastPage = lastCaptureOffset(bytes);
        bytes[lastPage + 22] ^= (byte) 0xff;
        Path source = temporaryDirectory.resolve("corrupt-crc.ogg");
        Files.write(source, bytes);

        OggVorbisStreamLength.Measurement measurement = OggVorbisStreamLength.measure(source);

        assertNotEquals(OggVorbisStreamLength.Status.MEASURED, measurement.status(), measurement.detail());
    }

    @Test
    void aStreamWithNoEndOfStreamPageIsMalformed() throws Exception {
        byte[] bytes = fixture("mono-22050.ogg");
        int lastPage = lastCaptureOffset(bytes);
        bytes[lastPage + 5] &= (byte) ~0x04;
        Path source = temporaryDirectory.resolve("no-eos.ogg");
        Files.write(source, bytes);

        OggVorbisStreamLength.Measurement measurement = OggVorbisStreamLength.measure(source);

        assertEquals(OggVorbisStreamLength.Status.MALFORMED, measurement.status(), measurement.detail());
    }

    @Test
    void nonOggInputIsMalformedRatherThanGuessed() throws Exception {
        Path source = temporaryDirectory.resolve("not-ogg.bin");
        Files.write(source, "this is not an Ogg container at all, not even close".getBytes(StandardCharsets.UTF_8));

        OggVorbisStreamLength.Measurement measurement = OggVorbisStreamLength.measure(source);

        assertEquals(OggVorbisStreamLength.Status.MALFORMED, measurement.status(), measurement.detail());
        assertEquals(0, measurement.totalFrames());
    }

    /** A mod in the reviewed profile ships a zero-byte {@code .ogg}; it must classify, not throw. */
    @Test
    void anEmptyFileIsMalformedRatherThanAnError() throws Exception {
        Path source = temporaryDirectory.resolve("empty.ogg");
        Files.write(source, new byte[0]);

        OggVorbisStreamLength.Measurement measurement = OggVorbisStreamLength.measure(source);

        assertEquals(OggVorbisStreamLength.Status.MALFORMED, measurement.status(), measurement.detail());
    }

    @Test
    void aMissingFileIsAnErrorAndCarriesNoFrameCount() {
        OggVorbisStreamLength.Measurement measurement =
                OggVorbisStreamLength.measure(temporaryDirectory.resolve("absent.ogg"));

        assertEquals(OggVorbisStreamLength.Status.ERROR, measurement.status());
        assertEquals(0, measurement.totalFrames());
        assertFalse(measurement.measured());
    }

    @Test
    void unmeasuredResultsCannotCarryAFrameCount() {
        assertThrows(IllegalArgumentException.class,
                () -> new OggVorbisStreamLength.Measurement(OggVorbisStreamLength.Status.MALFORMED, 512, 0, ""));
        assertThrows(IllegalArgumentException.class,
                () -> new OggVorbisStreamLength.Measurement(OggVorbisStreamLength.Status.MEASURED, 0, 0, ""));
    }

    @Test
    void decodedBytesRejectsANonPositiveChannelCount() throws Exception {
        OggVorbisStreamLength.Measurement measurement = OggVorbisStreamLength.measure(write("mono-22050.ogg"));

        assertThrows(IllegalArgumentException.class, () -> measurement.decodedBytes(0));
    }

    private static int lastCaptureOffset(byte[] bytes) {
        for (int at = bytes.length - 27; at >= 0; at--) {
            if (bytes[at] == 'O' && bytes[at + 1] == 'g' && bytes[at + 2] == 'g' && bytes[at + 3] == 'S') {
                return at;
            }
        }
        throw new IllegalStateException("Fixture contains no Ogg page");
    }

    private Path write(String name) throws IOException {
        Path source = temporaryDirectory.resolve(name);
        Files.write(source, fixture(name));
        return source;
    }

    private static byte[] fixture(String name) throws IOException {
        String base = "/audio/ogg-v1/" + name + ".b64";
        InputStream single = OggVorbisStreamLengthTest.class.getResourceAsStream(base);
        if (single != null) {
            try (single) {
                return Base64.getMimeDecoder().decode(single.readAllBytes());
            }
        }

        ByteArrayOutputStream encoded = new ByteArrayOutputStream();
        for (int i = 0; i < 100; i++) {
            String partName = base + ".part" + String.format("%02d", i);
            InputStream part = OggVorbisStreamLengthTest.class.getResourceAsStream(partName);
            if (part == null) {
                break;
            }
            try (part) {
                encoded.writeBytes(part.readAllBytes());
            }
        }
        if (encoded.size() == 0) {
            throw new IOException("Missing fixture resource: " + base);
        }
        return Base64.getMimeDecoder().decode(encoded.toByteArray());
    }
}
