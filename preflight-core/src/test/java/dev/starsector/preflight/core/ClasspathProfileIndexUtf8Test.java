package dev.starsector.preflight.core;

import static org.junit.jupiter.api.Assertions.assertThrows;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class ClasspathProfileIndexUtf8Test {
    private static final int PAYLOAD_LENGTH_OFFSET = 8;
    private static final int PAYLOAD_OFFSET = 12;
    private static final int PROFILE_HASH_BYTES = 32;
    private static final int CHECKSUM_BYTES = 32;

    @Test
    void authenticatedMalformedUtf8NeverBecomesAnIndex() throws Exception {
        byte[] valid = ClasspathProfileIndexIO.toBytes(fixtureIndex());
        byte[] malformed = valid.clone();

        int firstModIdLengthOffset = PAYLOAD_OFFSET + PROFILE_HASH_BYTES + Integer.BYTES;
        int firstModIdLength = intAt(malformed, firstModIdLengthOffset);
        if (firstModIdLength < 2) {
            throw new IllegalStateException("fixture mod ID must contain at least two bytes");
        }
        int firstModIdOffset = firstModIdLengthOffset + Integer.BYTES;
        malformed[firstModIdOffset] = (byte) 0xc3;
        malformed[firstModIdOffset + 1] = 0x28;
        resignPayload(malformed);

        assertThrows(IOException.class, () -> ClasspathProfileIndexIO.fromBytes(malformed));
    }

    private static ClasspathProfileIndex fixtureIndex() {
        List<ClasspathProfileIndex.Archive> archives = List.of(new ClasspathProfileIndex.Archive(
                "alpha",
                "a.jar",
                Path.of("fixture-classpath", "alpha", "a.jar"),
                "aa".repeat(32),
                100,
                10,
                "classpath/a.spfj",
                true));
        Map<String, List<Integer>> providers = new LinkedHashMap<>();
        providers.put("a/A.class", List.of(0));
        return new ClasspathProfileIndex("cc".repeat(32), archives, providers);
    }

    private static void resignPayload(byte[] bytes) {
        int payloadLength = intAt(bytes, PAYLOAD_LENGTH_OFFSET);
        int checksumOffset = PAYLOAD_OFFSET + payloadLength;
        byte[] payload = Arrays.copyOfRange(bytes, PAYLOAD_OFFSET, checksumOffset);
        byte[] checksum = Hashes.sha256Bytes(payload);
        System.arraycopy(checksum, 0, bytes, checksumOffset, CHECKSUM_BYTES);
    }

    private static int intAt(byte[] bytes, int offset) {
        return ByteBuffer.wrap(bytes).order(ByteOrder.BIG_ENDIAN).getInt(offset);
    }
}
