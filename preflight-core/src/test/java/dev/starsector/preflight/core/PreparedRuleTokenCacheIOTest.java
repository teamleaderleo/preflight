package dev.starsector.preflight.core;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class PreparedRuleTokenCacheIOTest {
    private static final String PROFILE = "a".repeat(64);

    @TempDir
    Path temporary;

    @Test
    void roundTripsNullableStringsAndTokenTypes() throws Exception {
        PreparedRuleTokenCache cache = new PreparedRuleTokenCache(PROFILE, Map.of(
                "$market.size > 3", List.of(
                        new PreparedRuleTokenCache.Token("$market.size", "VARIABLE"),
                        new PreparedRuleTokenCache.Token(">", "OPERATOR"),
                        new PreparedRuleTokenCache.Token("3", "LITERAL")),
                "empty", List.of(new PreparedRuleTokenCache.Token(null, "OTHER"))));
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
    void truncatedFileIsRejected() throws Exception {
        Path artifact = temporary.resolve("truncated.sprt");
        Files.write(artifact, new byte[] {'S', 'P', 'R', 'T'});

        assertThrows(IOException.class, () -> PreparedRuleTokenCacheIO.read(artifact));
    }
}
