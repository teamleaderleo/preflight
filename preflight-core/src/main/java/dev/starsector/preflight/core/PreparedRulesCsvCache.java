package dev.starsector.preflight.core;

import java.util.Locale;

/** Immutable merged rules rows for vanilla's live rule constructors and registries. */
public record PreparedRulesCsvCache(
        String profileIdentitySha256,
        String mergedJson) {
    public static final int FORMAT_VERSION = 1;

    public PreparedRulesCsvCache {
        Hashes.decodeSha256(profileIdentitySha256);
        profileIdentitySha256 = profileIdentitySha256.toLowerCase(Locale.ROOT);
        if (mergedJson == null || mergedJson.isBlank()) {
            throw new IllegalArgumentException("Prepared rules JSON is empty");
        }
        String stripped = mergedJson.strip();
        if (!stripped.startsWith("[") || !stripped.endsWith("]")) {
            throw new IllegalArgumentException("Prepared rules JSON is not an array");
        }
    }
}
