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

class PreparedProjectileJsonCacheBoundedReadTest {
    private static final String PROFILE = "a".repeat(64);

    @TempDir
    Path temporaryDirectory;

    @Test
    void exactReadLimitIsInclusive() throws Exception {
        PreparedProjectileJsonCache expected = fixture();
        byte[] bytes = PreparedProjectileJsonCacheIO.toBytes(expected);

        PreparedProjectileJsonCache actual = PreparedProjectileJsonCacheIO.read(
                new ByteArrayInputStream(bytes), bytes.length, "exact-limit.sppj");

        assertEquals(expected.profileIdentitySha256(), actual.profileIdentitySha256());
        assertArrayEquals(
                expected.entries().get("data/weapons/proj/a.proj"),
                actual.entries().get("data/weapons/proj/a.proj"));
    }

    @Test
    void actualStreamReadRejectsGrowthPastTheLimit() throws Exception {
        byte[] bytes = PreparedProjectileJsonCacheIO.toBytes(fixture());
        Path file = temporaryDirectory.resolve("growing.sppj");
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
                    () -> PreparedProjectileJsonCacheIO.read(growing, bytes.length, file.toString()));
            assertTrue(appended[0]);
            assertTrue(error.getMessage().contains("byte safety limit"), error.getMessage());
        }
        assertEquals(bytes.length + 1L, Files.size(file));
    }

    @Test
    void initialSizePrefilterStillRejectsBeforeTheBoundedRead() throws Exception {
        byte[] bytes = PreparedProjectileJsonCacheIO.toBytes(fixture());
        Path file = temporaryDirectory.resolve("already-too-large.sppj");
        Files.write(file, bytes);

        IOException error = assertThrows(
                IOException.class,
                () -> PreparedProjectileJsonCacheIO.read(file, bytes.length - 1));

        assertTrue(error.getMessage().contains("size is invalid"), error.getMessage());
    }

    private static PreparedProjectileJsonCache fixture() {
        return new PreparedProjectileJsonCache(
                PROFILE,
                Map.of("data/weapons/proj/a.proj", JsonTree.encode(Map.of("id", "a"))));
    }
}
