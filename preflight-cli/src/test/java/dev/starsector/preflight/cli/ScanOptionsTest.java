package dev.starsector.preflight.cli;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.util.OptionalInt;
import java.util.OptionalLong;
import org.junit.jupiter.api.Test;

class ScanOptionsTest {
    @Test
    void parsesBinarySuffixesCaseInsensitively() {
        assertEquals(2048L, ScanOptions.parseByteSize("2048"));
        assertEquals(512L * 1024L, ScanOptions.parseByteSize("512K"));
        assertEquals(4L * 1024L * 1024L * 1024L, ScanOptions.parseByteSize("4g"));
        assertEquals(1024L * 1024L, ScanOptions.parseByteSize(" 1m "));
    }

    @Test
    void rejectsGarbageSizes() {
        assertThrows(IllegalArgumentException.class, () -> ScanOptions.parseByteSize(""));
        assertThrows(IllegalArgumentException.class, () -> ScanOptions.parseByteSize("4T"));
        assertThrows(IllegalArgumentException.class, () -> ScanOptions.parseByteSize("-1"));
        assertThrows(IllegalArgumentException.class, () -> ScanOptions.parseByteSize("12.5G"));
    }

    @Test
    void rejectsGarbageTextureSizes() {
        assertEquals(2048, ScanOptions.parseTextureSize(" 2048 "));
        assertThrows(IllegalArgumentException.class, () -> ScanOptions.parseTextureSize("0"));
        assertThrows(IllegalArgumentException.class, () -> ScanOptions.parseTextureSize("-4"));
        assertThrows(IllegalArgumentException.class, () -> ScanOptions.parseTextureSize("2K"));
    }

    @Test
    void carriesTheBudgetAndSizeCapThroughParsing() {
        ScanOptions options = ScanOptions.parse(
                new String[] {"scan", "--vram-budget", "4G", "--max-texture-size", "2048"}, 1);
        assertEquals(OptionalLong.of(4L * 1024L * 1024L * 1024L), options.vramBudgetBytes());
        assertEquals(OptionalInt.of(2048), options.maxTextureSizePixels());
        assertEquals(options.vramBudgetBytes(), options.censusOptions().vramBudgetBytes());
        assertEquals(options.maxTextureSizePixels(), options.censusOptions().maxTextureSizePixels());

        ScanOptions bare = ScanOptions.parse(new String[] {"scan"}, 1);
        assertEquals(OptionalLong.empty(), bare.vramBudgetBytes());
        assertEquals(OptionalInt.empty(), bare.maxTextureSizePixels());
    }
}
