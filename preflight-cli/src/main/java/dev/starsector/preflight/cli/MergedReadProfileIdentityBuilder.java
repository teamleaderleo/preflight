package dev.starsector.preflight.cli;

import dev.starsector.preflight.core.MergedReadKey;
import java.io.IOException;
import java.nio.file.Path;

/**
 * Exact identity for every input that can change any merged read.
 *
 * <p>The four per-corpus identities each cover the files one loader reads, because each of those
 * caches answers for one loader. This cache answers for whoever asks, so its identity has to cover
 * whatever they might ask for -- and the boundary it draws is {@code data/}: every file under it, in
 * every enabled root, in override order, by content.
 *
 * <p>That boundary is the cache's rule as well as its identity. {@link MergedReadKey} refuses a
 * request outside {@code data/}, so there is no path by which the cache can answer for a file this
 * digest did not hash. Anything else the game reads -- graphics, sounds, jars -- is not covered here
 * and is not served from here.
 *
 * <p>The digest layout is {@link MergedJsonProfileIdentity}'s, unchanged, because there is no reason
 * for a fifth spelling of the same idea.
 */
final class MergedReadProfileIdentityBuilder {
    private static final String SCHEMA = "starsector-preflight-merged-read-profile-v1";

    private MergedReadProfileIdentityBuilder() {
    }

    static Result build(ProfileIdentityContext context) throws IOException {
        MergedJsonProfileIdentity.Result merged = MergedJsonProfileIdentity.build(
                context, SCHEMA, MergedReadProfileIdentityBuilder::selects);
        return new Result(
                merged.identitySha256(),
                merged.gameJar(),
                merged.logicalPaths(),
                merged.providerCount(),
                merged.providerBytes());
    }

    private static boolean selects(String logicalPath) {
        return logicalPath.startsWith(MergedReadKey.SERVED_PREFIX);
    }

    record Result(
            String identitySha256,
            Path gameJar,
            int logicalPaths,
            long providerCount,
            long providerBytes) {
    }
}
