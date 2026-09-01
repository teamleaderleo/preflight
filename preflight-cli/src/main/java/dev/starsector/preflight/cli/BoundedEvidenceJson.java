package dev.starsector.preflight.cli;

import java.io.IOException;
import java.io.InputStream;
import java.nio.ByteBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.Map;
import java.util.Objects;

/** Bounded encoded-byte admission for already-reviewed local evidence JSON paths. */
final class BoundedEvidenceJson {
    private BoundedEvidenceJson() {
    }

    static Map<String, Object> readObject(Path source, long maximumBytes, String label) throws IOException {
        validateLimit(maximumBytes);
        Path absolute = source.toAbsolutePath().normalize();
        BasicFileAttributes before = Files.readAttributes(
                absolute, BasicFileAttributes.class, LinkOption.NOFOLLOW_LINKS);
        if (!before.isRegularFile() || Files.isSymbolicLink(absolute)) {
            throw new IOException(label + " is not a regular file: " + absolute);
        }
        if (before.size() > maximumBytes) {
            throw new IOException(label + " exceeds " + maximumBytes + " bytes: " + source);
        }
        Map<String, Object> result;
        try (InputStream input = Files.newInputStream(
                absolute, StandardOpenOption.READ, LinkOption.NOFOLLOW_LINKS)) {
            result = readObject(input, maximumBytes, absolute.toString(), label);
        }
        BasicFileAttributes after = Files.readAttributes(
                absolute, BasicFileAttributes.class, LinkOption.NOFOLLOW_LINKS);
        if (!after.isRegularFile()
                || !Objects.equals(before.fileKey(), after.fileKey())
                || before.size() != after.size()
                || !before.lastModifiedTime().equals(after.lastModifiedTime())) {
            throw new IOException(label + " changed while it was being read: " + absolute);
        }
        return result;
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
