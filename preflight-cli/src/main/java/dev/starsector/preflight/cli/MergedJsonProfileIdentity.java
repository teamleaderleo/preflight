package dev.starsector.preflight.cli;

import dev.starsector.preflight.core.ResourceIndex;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.function.Predicate;

/**
 * The shape shared by every identity over a merged JSON corpus.
 *
 * <p>Variants, weapons, projectiles and hulls all answer the same question -- which files, in which
 * override order, feed one merged corpus -- and differed only in their schema string and the logical
 * paths they select. They are one implementation here so that the digest layout has one definition
 * and the hashing has one place to be made fast.
 *
 * <p>The digest layout is unchanged from the four separate copies, and deliberately so: an identity
 * that shifted would orphan every cache artifact already on disk. Providers are hashed in parallel
 * but fed to the digest in index order, which is the only reason that is safe.
 */
final class MergedJsonProfileIdentity {
    private MergedJsonProfileIdentity() {
    }

    static Result build(ProfileIdentityContext context, String schema, Predicate<String> selects)
            throws IOException {
        ResourceIndex resources = context.resources();
        MessageDigest digest = sha256();
        update(digest, schema);
        update(digest, context.gameJarSha256());

        List<String> logicalPaths = new ArrayList<>();
        List<List<ResourceIndex.Provider>> perPath = new ArrayList<>();
        List<ResourceIndex.Provider> flattened = new ArrayList<>();
        for (var item : resources.entries().entrySet()) {
            if (!selects.test(item.getKey())) {
                continue;
            }
            logicalPaths.add(item.getKey());
            perPath.add(item.getValue());
            flattened.addAll(item.getValue());
        }

        List<String> hashes = context.sha256All(context.resolveAll(flattened));

        long providerBytes = 0;
        int next = 0;
        for (int path = 0; path < logicalPaths.size(); path++) {
            List<ResourceIndex.Provider> providers = perPath.get(path);
            update(digest, logicalPaths.get(path));
            update(digest, providers.size());
            for (ResourceIndex.Provider provider : providers) {
                ResourceIndex.Root root = resources.roots().get(provider.rootIndex());
                update(digest, provider.rootIndex());
                update(digest, root.id());
                update(digest, root.core());
                update(digest, provider.relativePath());
                update(digest, provider.size());
                update(digest, hashes.get(next++));
                providerBytes = Math.addExact(providerBytes, provider.size());
            }
        }
        update(digest, logicalPaths.size());
        update(digest, flattened.size());
        update(digest, providerBytes);
        return new Result(
                HexFormat.of().formatHex(digest.digest()),
                context.gameJar(),
                logicalPaths.size(),
                flattened.size(),
                providerBytes);
    }

    static void update(MessageDigest digest, String value) {
        byte[] bytes = value.getBytes(StandardCharsets.UTF_8);
        update(digest, bytes.length);
        digest.update(bytes);
    }

    static void update(MessageDigest digest, long value) {
        digest.update(ByteBuffer.allocate(Long.BYTES).putLong(value).array());
    }

    static void update(MessageDigest digest, boolean value) {
        digest.update((byte) (value ? 1 : 0));
    }

    static MessageDigest sha256() {
        try {
            return MessageDigest.getInstance("SHA-256");
        } catch (NoSuchAlgorithmException impossible) {
            throw new IllegalStateException("SHA-256 is unavailable", impossible);
        }
    }

    record Result(
            String identitySha256,
            Path gameJar,
            int logicalPaths,
            long providerCount,
            long providerBytes) {
    }
}
