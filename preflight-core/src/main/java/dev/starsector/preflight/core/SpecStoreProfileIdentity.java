package dev.starsector.preflight.core;

import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.Locale;

/** Exact content identity for one reusable {@code SpecStore} preparation profile. */
public record SpecStoreProfileIdentity(
        String gameJarSha256,
        String dataProvidersSha256,
        String classpathArchivesSha256,
        long dataProviderCount,
        long dataProviderBytes,
        int classpathArchiveCount,
        long classpathArchiveBytes) {
    public static final int FORMAT_VERSION = 1;
    private static final String SCHEMA = "starsector-preflight-spec-store-profile-v1";

    public SpecStoreProfileIdentity {
        gameJarSha256 = hash(gameJarSha256, "gameJarSha256");
        dataProvidersSha256 = hash(dataProvidersSha256, "dataProvidersSha256");
        classpathArchivesSha256 = hash(classpathArchivesSha256, "classpathArchivesSha256");
        if (dataProviderCount < 0 || dataProviderBytes < 0
                || classpathArchiveCount < 0 || classpathArchiveBytes < 0) {
            throw new IllegalArgumentException("SpecStore profile counts and byte sizes may not be negative");
        }
    }

    public String identitySha256() {
        MessageDigest digest = sha256();
        update(digest, SCHEMA);
        update(digest, gameJarSha256);
        update(digest, dataProvidersSha256);
        update(digest, classpathArchivesSha256);
        update(digest, dataProviderCount);
        update(digest, dataProviderBytes);
        update(digest, classpathArchiveCount);
        update(digest, classpathArchiveBytes);
        return HexFormat.of().formatHex(digest.digest());
    }

    private static String hash(String value, String name) {
        Hashes.decodeSha256(value);
        return value.toLowerCase(Locale.ROOT);
    }

    private static void update(MessageDigest digest, String value) {
        byte[] bytes = value.getBytes(StandardCharsets.UTF_8);
        update(digest, bytes.length);
        digest.update(bytes);
    }

    private static void update(MessageDigest digest, long value) {
        digest.update(ByteBuffer.allocate(Long.BYTES).putLong(value).array());
    }

    private static MessageDigest sha256() {
        try {
            return MessageDigest.getInstance("SHA-256");
        } catch (NoSuchAlgorithmException impossible) {
            throw new IllegalStateException("SHA-256 is unavailable", impossible);
        }
    }
}
