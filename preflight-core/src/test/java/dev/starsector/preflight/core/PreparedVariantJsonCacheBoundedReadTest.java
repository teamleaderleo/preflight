package dev.starsector.preflight.core;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.ByteArrayInputStream;
import java.io.FilterInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class PreparedVariantJsonCacheBoundedReadTest {
    private static final String PROFILE = "a".repeat(64);

    @TempDir
    Path temporaryDirectory;

    @Test
    void exactReadLimitIsInclusive() throws Exception {
        PreparedVariantJsonCache expected = fixture();
        byte[] bytes = PreparedVariantJsonCacheIO.toBytes(expected);

        PreparedVariantJsonCache actual = PreparedVariantJsonCacheIO.read(
                new ByteArrayInputStream(bytes), bytes.length, "exact-limit.spvj");

        assertEquals(expected.profileIdentitySha256(), actual.profileIdentitySha256());
        assertArrayEquals(
                expected.entries().get("data/variants/a.variant"),
                actual.entries().get("data/variants/a.variant"));
    }

    @Test
    void actualStreamReadRejectsGrowthPastTheLimit() throws Exception {
        byte[] bytes = PreparedVariantJsonCacheIO.toBytes(fixture());
        Path file = temporaryDirectory.resolve("growing.spvj");
        Files.write(file, bytes);
        boolean[] appended = {false};

        try (InputStream raw = Files.newInputStream(file);
             InputStream growing = new FilterInputStream(raw) {
                 @Override
                 public int read(byte[] buffer, int offset, int length) throws IOException {
                     int requested = appended[0] ? length : Math.min(1, length);
                     int read = super.read(buffer, offset, requested);
                     if (!appended[0] && read > 0) {
                         Files.write(file, new byte[] {0x55}, StandardOpenOption.APPEND);
                         appended[0] = true;
                     }
                     return read;
                 }
             }) {
            IOException error = assertThrows(
                    IOException.class,
                    () -> PreparedVariantJsonCacheIO.read(growing, bytes.length, file.toString()));
            assertTrue(appended[0]);
            assertTrue(error.getMessage().contains("byte safety limit"), error.getMessage());
        }
        assertEquals(bytes.length + 1L, Files.size(file));
    }

    @Test
    void initialSizePrefilterStillRejectsBeforeTheBoundedRead() throws Exception {
        byte[] bytes = PreparedVariantJsonCacheIO.toBytes(fixture());
        Path file = temporaryDirectory.resolve("already-too-large.spvj");
        Files.write(file, bytes);

        IOException error = assertThrows(
                IOException.class,
                () -> PreparedVariantJsonCacheIO.read(file, bytes.length - 1));

        assertTrue(error.getMessage().contains("size is invalid"), error.getMessage());
    }

    private static PreparedVariantJsonCache fixture() {
        return new PreparedVariantJsonCache(
                PROFILE,
                Map.of("data/variants/a.variant", JsonTree.encode(Map.of("variantId", "a"))));
    }
}
