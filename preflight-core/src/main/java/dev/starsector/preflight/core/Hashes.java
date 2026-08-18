package dev.starsector.preflight.core;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;

/** SHA-256 helpers used by cache formats and source validation. */
public final class Hashes {
    private Hashes() {
    }

    public static String sha256(Path file) throws IOException {
        if (!Files.isRegularFile(file)) {
            throw new IOException("Expected a regular file: " + file.toAbsolutePath().normalize());
        }
        try (InputStream input = Files.newInputStream(file)) {
            return sha256(input);
        }
    }

    /**
     * Digests whatever the stream yields, which is not always what the pathname holds.
     *
     * A caller that has to prove the digest belongs to a file it observed cannot re-open by name:
     * between the observation and the read, the name can be pointed at something else and back.
     * Opening once and hashing the handle keeps the read on the entry that was opened.
     */
    public static String sha256(InputStream bytes) throws IOException {
        MessageDigest digest = newSha256();
        byte[] buffer = new byte[64 * 1024];
        int read;
        while ((read = bytes.read(buffer)) >= 0) {
            if (read > 0) {
                digest.update(buffer, 0, read);
            }
        }
        return HexFormat.of().formatHex(digest.digest());
    }

    public static String sha256(byte[] bytes) {
        return HexFormat.of().formatHex(newSha256().digest(bytes));
    }

    public static byte[] sha256Bytes(byte[] bytes) {
        return newSha256().digest(bytes);
    }

    public static byte[] decodeSha256(String hex) {
        if (hex == null || !hex.matches("[0-9a-fA-F]{64}")) {
            throw new IllegalArgumentException("Expected a 64-character SHA-256 value");
        }
        return HexFormat.of().parseHex(hex);
    }

    private static MessageDigest newSha256() {
        try {
            return MessageDigest.getInstance("SHA-256");
        } catch (NoSuchAlgorithmException impossible) {
            throw new IllegalStateException("SHA-256 is unavailable", impossible);
        }
    }
}
