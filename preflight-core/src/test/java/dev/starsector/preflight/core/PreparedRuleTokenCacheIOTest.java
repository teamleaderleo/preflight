package dev.starsector.preflight.core;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
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