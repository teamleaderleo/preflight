package dev.starsector.preflight.core;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import org.junit.jupiter.api.Test;

class EncodedOggPartsManifestTest {
    private static final String ROOT = "/audio/ogg-v1/";

    @Test
    void encodedFixturePartsMatchReviewedManifest() throws Exception {
        String manifest = new String(resource("encoded-ogg-parts.sha256"), StandardCharsets.UTF_8);
        int verified = 0;
        for (String raw : manifest.lines().toList()) {
            String line = raw.strip();
            if (line.isEmpty()) continue;

            String[] fields = line.split("\\s+");
            assertEquals(3, fields.length, "Malformed encoded fixture manifest line: " + line);
            String expectedSha256 = fields[0];
            long expectedBytes = Long.parseLong(fields[1]);
            String name = fields[2];
            byte[] content = resource(name);

            assertEquals(expectedBytes, content.length, name + " byte length");
            assertEquals(expectedSha256, Hashes.sha256(content), name + " SHA-256");
            verified++;
        }
        assertTrue(verified > 0, "Encoded fixture manifest contained no parts");
    }

    private static byte[] resource(String name) throws IOException {
        try (InputStream input = EncodedOggPartsManifestTest.class.getResourceAsStream(ROOT + name)) {
            assertNotNull(input, "Missing audio fixture resource " + ROOT + name);
            return input.readAllBytes();
        }
    }
}
