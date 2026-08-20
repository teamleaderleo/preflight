package dev.starsector.preflight.cli;

import java.io.IOException;
import java.io.InputStream;
import java.nio.ByteBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.Map;

/** Bounded encoded-byte admission for already-reviewed local evidence JSON paths. */
final class BoundedEvidenceJson {
    private BoundedEvidenceJson() {
    }

    static Map<String, Object> readObject(Path source, long maximumBytes, String label) throws IOException {
        validateLimit(maximumBytes);
        long size = Files.size(source);
        if (size > maximumBytes) {
            throw new IOException(label + " exceeds " + maximumBytes + " bytes: " + source);
        }
        try (InputStream input = Files.newInputStream(source, StandardOpenOption.READ)) {
            return readObject(input, maximumBytes, source.toString(), label);
        }
    }

    static Map<String, Object> readObject(
            InputStream input,
            long maximumBytes,
            String sourceLabel,
            String label) throws IOException {
        int actualReadLimit = validateLimit(maximumBytes);
        byte[] bytes = input.readNBytes(actualReadLimit);
        if (bytes.length > maximumBytes) {
            throw new IOException(label + " exceeds " + maximumBytes + " bytes: " + sourceLabel);
        }

        String text;
        try {
            text = StandardCharsets.UTF_8.newDecoder()
                    .onMalformedInput(CodingErrorAction.REPORT)
                    .onUnmappableCharacter(CodingErrorAction.REPORT)
                    .decode(ByteBuffer.wrap(bytes))
                    .toString();
        } catch (CharacterCodingException error) {
            throw new IOException(label + " is not valid UTF-8: " + sourceLabel, error);
        }
        return StrictJson.object(text);
    }

    private static int validateLimit(long maximumBytes) {
        if (maximumBytes < 0 || maximumBytes >= Integer.MAX_VALUE) {
            throw new IllegalArgumentException("Evidence JSON byte limit is invalid: " + maximumBytes);
        }
        return Math.toIntExact(maximumBytes + 1);
    }
}
