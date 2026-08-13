package dev.starsector.preflight.core;

import java.util.Locale;
import java.util.Objects;

/** Immutable merged rules rows for vanilla's live rule constructors and registries. */
public record PreparedRulesCsvCache(
        String profileIdentitySha256,
        byte[] mergedTree) {
    public static final int FORMAT_VERSION = 2;

    public PreparedRulesCsvCache {
        Hashes.decodeSha256(profileIdentitySha256);
        profileIdentitySha256 = profileIdentitySha256.toLowerCase(Locale.ROOT);
        Objects.requireNonNull(mergedTree, "mergedTree");
        if (mergedTree.length == 0) {
            throw new IllegalArgumentException("Prepared rules tree is empty");
        }
        mergedTree = mergedTree.clone();
    }

    @Override
    public byte[] mergedTree() {
        return mergedTree.clone();
    }

    @Override
    public boolean equals(Object value) {
        return value instanceof PreparedRulesCsvCache other
                && profileIdentitySha256.equals(other.profileIdentitySha256)
                && java.util.Arrays.equals(mergedTree, other.mergedTree);
    }

    @Override
    public int hashCode() {
        return 31 * profileIdentitySha256.hashCode() + java.util.Arrays.hashCode(mergedTree);
    }
}
