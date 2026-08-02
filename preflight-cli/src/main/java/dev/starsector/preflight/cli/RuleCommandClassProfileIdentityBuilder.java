package dev.starsector.preflight.cli;

import dev.starsector.preflight.core.Hashes;
import dev.starsector.preflight.core.PathContainment;
import dev.starsector.preflight.core.ResourceIndex;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Exact identity for the two things that decide which package a rule command name resolves in.
 *
 * <p>Vanilla walks the merged {@code ruleCommandPackages} list in order and takes the first package
 * whose class loads and instantiates. So the answer moves when either of these moves:
 *
 * <ul>
 *   <li><b>the declared package list</b>, merged from every {@code data/config/settings.json}
 *       provider -- reorder it, or insert an entry ahead of a winner, and a different class wins;
 *   <li><b>the code on the classpath</b>, because a mod update that adds a same-named class to an
 *       already-declared earlier package changes the winner without changing the list at all. That
 *       is the only way this cache can go silently stale, which is why every jar is hashed rather
 *       than stamped by size or timestamp.
 * </ul>
 *
 * <p>Both come out of the resource index the launch already built, so this needs no extra scan.
 */
final class RuleCommandClassProfileIdentityBuilder {
    private static final String SCHEMA = "starsector-preflight-rule-command-class-profile-v1";
    private static final String SETTINGS_PATH = "data/config/settings.json";
    private static final String JAR_SUFFIX = ".jar";

    private RuleCommandClassProfileIdentityBuilder() {
    }

    static Result build(Path installRoot, ResourceIndex resources) throws IOException {
        Path gameJar = locateGameJar(installRoot);
        MessageDigest digest = sha256();
        update(digest, SCHEMA);
        update(digest, Hashes.sha256(gameJar));

        List<Path> realRoots = new ArrayList<>(resources.roots().size());
        for (ResourceIndex.Root root : resources.roots()) {
            realRoots.add(PathContainment.realDirectory(root.path()));
        }

        List<ResourceIndex.Provider> settings =
                resources.entries().getOrDefault(SETTINGS_PATH, List.of());
        update(digest, SETTINGS_PATH);
        update(digest, settings.size());
        for (ResourceIndex.Provider provider : settings) {
            hashProvider(digest, resources, realRoots, SETTINGS_PATH, provider);
        }

        long jarCount = 0;
        long jarBytes = 0;
        // Sorted by the index's own key order, so two runs over the same install agree.
        for (Map.Entry<String, List<ResourceIndex.Provider>> entry : resources.entries().entrySet()) {
            String path = entry.getKey();
            if (!path.toLowerCase(Locale.ROOT).endsWith(JAR_SUFFIX)) {
                continue;
            }
            for (ResourceIndex.Provider provider : entry.getValue()) {
                hashProvider(digest, resources, realRoots, path, provider);
                jarCount++;
                jarBytes = Math.addExact(jarBytes, provider.size());
            }
        }
        update(digest, jarCount);
        update(digest, jarBytes);

        return new Result(
                HexFormat.of().formatHex(digest.digest()),
                gameJar,
                settings.size(),
                jarCount,
                jarBytes);
    }

    private static void hashProvider(
            MessageDigest digest,
            ResourceIndex resources,
            List<Path> realRoots,
            String path,
            ResourceIndex.Provider provider) throws IOException {
        ResourceIndex.Root root = resources.roots().get(provider.rootIndex());
        Path source = PathContainment.existingInsideRealRoot(
                realRoots.get(provider.rootIndex()), resources.resolve(provider));
        update(digest, path);
        update(digest, provider.rootIndex());
        update(digest, root.id());
        update(digest, root.core());
        update(digest, provider.relativePath());
        update(digest, provider.size());
        update(digest, Hashes.sha256(source));
    }

    private static Path locateGameJar(Path installRoot) throws IOException {
        Path root = installRoot.toAbsolutePath().normalize();
        List<Path> candidates = List.of(
                root.resolve("Contents/Resources/Java/starfarer_obf.jar"),
                root.resolve("starsector-core/starfarer_obf.jar"),
                root.resolve("starfarer_obf.jar"));
        for (Path candidate : candidates) {
            if (Files.isRegularFile(candidate)) {
                return candidate.toAbsolutePath().normalize();
            }
        }
        throw new IOException("Could not locate starfarer_obf.jar under " + root);
    }

    private static void update(MessageDigest digest, String value) {
        byte[] bytes = value.getBytes(StandardCharsets.UTF_8);
        update(digest, bytes.length);
        digest.update(bytes);
    }

    private static void update(MessageDigest digest, long value) {
        digest.update(ByteBuffer.allocate(Long.BYTES).putLong(value).array());
    }

    private static void update(MessageDigest digest, boolean value) {
        digest.update((byte) (value ? 1 : 0));
    }

    private static MessageDigest sha256() {
        try {
            return MessageDigest.getInstance("SHA-256");
        } catch (NoSuchAlgorithmException impossible) {
            throw new IllegalStateException("SHA-256 is unavailable", impossible);
        }
    }

    record Result(
            String identitySha256,
            Path gameJar,
            long settingsProviderCount,
            long jarCount,
            long jarBytes) {
    }
}
