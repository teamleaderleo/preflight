package dev.starsector.preflight.core;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.ByteArrayInputStream;
import java.io.FilterInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class PreparedRuleTokenCacheIOTest {
    private static final String PROFILE = "a".repeat(64);
    private static final int PAYLOAD_LENGTH_OFFSET = 8;
    private static final int PAYLOAD_OFFSET = 12;
    private static final int PROFILE_BYTES = 32;
    private static final int CHECKSUM_BYTES = 32;

    @TempDir
    Path temporary;

    @Test
    void roundTripsNullableStringsAndTokenTypes() throws Exception {
        PreparedRuleTokenCache cache = new PreparedRuleTokenCache(PROFILE, Map.of(
                "$market.size > 3", List.of(
                        new PreparedRuleTokenCache.Token("$market.size", "VARIABLE"),
                        new PreparedRuleTokenCache.Token(">", "OPERATOR"),
                        new PreparedRuleTokenCache.Token("3", "LITERAL")),
                "empty", List.of(new PreparedRuleTokenCache.Token(null, "OTHER")),
                "$世界 == ☃", List.of(new PreparedRuleTokenCache.Token("雪", "UNICODE"))));
        Path artifact = temporary.resolve(PROFILE + ".sprt");

        PreparedRuleTokenCacheIO.write(artifact, cache);

        assertEquals(cache, PreparedRuleTokenCacheIO.read(artifact));
    }

    @Test
    void checksumCorruptionIsRejected() throws Exception {
        PreparedRuleTokenCache cache = new PreparedRuleTokenCache(
                PROFILE, Map.of("x", List.of(new PreparedRuleTokenCache.Token("x", "WORD"))));
        byte[] bytes = PreparedRuleTokenCacheIO.toBytes(cache);
        bytes[bytes.length / 2] ^= 0x20;

        assertThrows(IOException.class, () -> PreparedRuleTokenCacheIO.fromBytes(bytes));
    }

    @Test
    void checksumValidMalformedUtf8CannotBecomeAcceptedExpression() throws Exception {
        String expression = "\ufffd";
        PreparedRuleTokenCache cache = new PreparedRuleTokenCache(
                PROFILE,
                Map.of(expression, List.of(new PreparedRuleTokenCache.Token("x", "WORD"))));
        byte[] bytes = PreparedRuleTokenCacheIO.toBytes(cache);
        int expressionOffset = firstExpressionBytesOffset(bytes);
        byte[] encoded = expression.getBytes(StandardCharsets.UTF_8);
        assertEquals(3, encoded.length);
        assertEquals((byte) 0xef, bytes[expressionOffset]);
        assertEquals((byte) 0xbf, bytes[expressionOffset + 1]);
        assertEquals((byte) 0xbd, bytes[expressionOffset + 2]);

        // ED A0 80 encodes a surrogate code point and is malformed UTF-8. The previous decoder
        // replacement-decoded these authenticated bytes back to the same logical U+FFFD key.
        bytes[expressionOffset] = (byte) 0xed;
        bytes[expressionOffset + 1] = (byte) 0xa0;
        bytes[expressionOffset + 2] = (byte) 0x80;
        resignPayload(bytes);

        assertThrows(IOException.class, () -> PreparedRuleTokenCacheIO.fromBytes(bytes));
    }

    @Test
    void checksumValidNoncanonicalNullableStringFlagIsRejected() throws Exception {
        PreparedRuleTokenCache cache = new PreparedRuleTokenCache(
                PROFILE, Map.of("x", List.of(new PreparedRuleTokenCache.Token("x", "WORD"))));
        byte[] bytes = PreparedRuleTokenCacheIO.toBytes(cache);
        int flagOffset = firstTokenNullableFlagOffset(bytes);
        assertEquals(1, Byte.toUnsignedInt(bytes[flagOffset]));

        // DataInputStream.readBoolean accepts every non-zero byte as true, but writeBoolean emits
        // only 0 or 1. A checksum-valid 2 therefore used to decode into state the writer never emits.
        bytes[flagOffset] = 2;
        resignPayload(bytes);

        IOException error = assertThrows(
                IOException.class, () -> PreparedRuleTokenCacheIO.fromBytes(bytes));
        assertTrue(error.getMessage().contains("nullable-string flag"), error.getMessage());
    }

    @Test
    void writerRejectsMalformedUtf16Strings() {
        PreparedRuleTokenCache malformedExpression = new PreparedRuleTokenCache(
                PROFILE,
                Map.of("bad-\ud800", List.of(new PreparedRuleTokenCache.Token("x", "WORD"))));
        PreparedRuleTokenCache malformedTokenString = new PreparedRuleTokenCache(
                PROFILE,
                Map.of("x", List.of(new PreparedRuleTokenCache.Token("bad-\ud800", "WORD"))));
        PreparedRuleTokenCache malformedTokenType = new PreparedRuleTokenCache(
                PROFILE,
                Map.of("x", List.of(new PreparedRuleTokenCache.Token("x", "TYPE-\ud800"))));

        assertThrows(IOException.class, () -> PreparedRuleTokenCacheIO.toBytes(malformedExpression));
        assertThrows(IOException.class, () -> PreparedRuleTokenCacheIO.toBytes(malformedTokenString));
        assertThrows(IOException.class, () -> PreparedRuleTokenCacheIO.toBytes(malformedTokenType));
    }

    @Test
    void exactReadLimitIsInclusive() throws Exception {
        PreparedRuleTokenCache cache = new PreparedRuleTokenCache(
                PROFILE, Map.of("x", List.of(new PreparedRuleTokenCache.Token("x", "WORD"))));
        byte[] bytes = PreparedRuleTokenCacheIO.toBytes(cache);

        assertEquals(
                cache,
                PreparedRuleTokenCacheIO.read(
                        new ByteArrayInputStream(bytes), bytes.length, "exact-limit.sprt"));
    }

    @Test
    void actualStreamReadRejectsGrowthPastTheLimit() throws Exception {
        PreparedRuleTokenCache cache = new PreparedRuleTokenCache(
                PROFILE, Map.of("x", List.of(new PreparedRuleTokenCache.Token("x", "WORD"))));
        byte[] bytes = PreparedRuleTokenCacheIO.toBytes(cache);
        Path artifact = temporary.resolve("growing.sprt");
        Files.write(artifact, bytes);
        boolean[] appended = {false};

        try (InputStream raw = Files.newInputStream(artifact);
             InputStream growing = new FilterInputStream(raw) {
                 @Override
                 public int read(byte[] buffer, int offset, int length) throws IOException {
                     int requested = appended[0] ? length : Math.min(1, length);
                     int read = super.read(buffer, offset, requested);
                     if (!appended[0] && read > 0) {
                         Files.write(artifact, new byte[] {0x55}, StandardOpenOption.APPEND);
                         appended[0] = true;
                     }
                     return read;
                 }
             }) {
            IOException error = assertThrows(
                    IOException.class,
                    () -> PreparedRuleTokenCacheIO.read(growing, bytes.length, artifact.toString()));
            assertTrue(appended[0]);
            assertTrue(error.getMessage().contains("byte safety limit"), error.getMessage());
        }
        assertEquals(bytes.length + 1L, Files.size(artifact));
    }

    @Test
    void initialSizePrefilterStillRejectsBeforeTheBoundedRead() throws Exception {
        PreparedRuleTokenCache cache = new PreparedRuleTokenCache(
                PROFILE, Map.of("x", List.of(new PreparedRuleTokenCache.Token("x", "WORD"))));
        byte[] bytes = PreparedRuleTokenCacheIO.toBytes(cache);
        Path artifact = temporary.resolve("already-too-large.sprt");
        Files.write(artifact, bytes);

        IOException error = assertThrows(
                IOException.class,
                () -> PreparedRuleTokenCacheIO.read(artifact, bytes.length - 1));
        assertTrue(error.getMessage().contains("size is invalid"), error.getMessage());
    }

    @Test
    void truncatedFileIsRejected() throws Exception {
        Path artifact = temporary.resolve("truncated.sprt");
        Files.write(artifact, new byte[] {'S', 'P', 'R', 'T'});

        assertThrows(IOException.class, () -> PreparedRuleTokenCacheIO.read(artifact));
    }

    private static int firstExpressionBytesOffset(byte[] bytes) {
        int payloadLength = intAt(bytes, PAYLOAD_LENGTH_OFFSET);
        if (payloadLength <= PROFILE_BYTES + Integer.BYTES * 2) {
            throw new IllegalStateException("fixture payload is too small");
        }
        int countOffset = PAYLOAD_OFFSET + PROFILE_BYTES;
        assertEquals(1, intAt(bytes, countOffset));
        int expressionLengthOffset = countOffset + Integer.BYTES;
        assertEquals(3, intAt(bytes, expressionLengthOffset));
        return expressionLengthOffset + Integer.BYTES;
    }

    private static int firstTokenNullableFlagOffset(byte[] bytes) {
        int countOffset = PAYLOAD_OFFSET + PROFILE_BYTES;
        assertEquals(1, intAt(bytes, countOffset));
        int expressionLengthOffset = countOffset + Integer.BYTES;
        int expressionLength = intAt(bytes, expressionLengthOffset);
        int tokenCountOffset = expressionLengthOffset + Integer.BYTES + expressionLength;
        assertEquals(1, intAt(bytes, tokenCountOffset));
        return tokenCountOffset + Integer.BYTES;
    }

    private static void resignPayload(byte[] bytes) {
        int payloadLength = intAt(bytes, PAYLOAD_LENGTH_OFFSET);
        byte[] payload = Arrays.copyOfRange(bytes, PAYLOAD_OFFSET, PAYLOAD_OFFSET + payloadLength);
        byte[] checksum = Hashes.sha256Bytes(payload);
        System.arraycopy(
                checksum,
                0,
                bytes,
                PAYLOAD_OFFSET + payloadLength,
                CHECKSUM_BYTES);
    }

    private static int intAt(byte[] bytes, int offset) {
        return ByteBuffer.wrap(bytes).order(ByteOrder.BIG_ENDIAN).getInt(offset);
    }
}
