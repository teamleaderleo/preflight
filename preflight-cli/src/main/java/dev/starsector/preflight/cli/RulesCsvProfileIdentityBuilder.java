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
import java.util.HexFormat;
import java.util.List;

/** Exact identity for only the inputs that can change vanilla's merged campaign-rules rows. */
final class RulesCsvProfileIdentityBuilder {
    private static final String SCHEMA = "starsector-preflight-rules-csv-profile-v1";
    private static final String RULES_PATH = "data/campaign/rules.csv";

    private RulesCsvProfileIdentityBuilder() {
    }

    static Result build(Path installRoot, ResourceIndex resources) throws IOException {
        Path gameJar = locateGameJar(installRoot);
        String gameJarSha256 = Hashes.sha256(gameJar);
        MessageDigest digest = sha256();
        update(digest, SCHEMA);
        update(digest, gameJarSha256);
        List<Path> realRoots = new java.util.ArrayList<>(resources.roots().size());
        for (ResourceIndex.Root root : resources.roots()) {
            realRoots.add(PathContainment.realDirectory(root.path()));
        }

        List<ResourceIndex.Provider> providers = resources.entries().getOrDefault(RULES_PATH, List.of());
        update(digest, RULES_PATH);
        update(digest, providers.size());
        long providerBytes = 0;
        for (ResourceIndex.Provider provider : providers) {
            ResourceIndex.Root root = resources.roots().get(provider.rootIndex());
            Path source = PathContainment.existingInsideRealRoot(
                    realRoots.get(provider.rootIndex()), resources.resolve(provider));
            update(digest, provider.rootIndex());
            update(digest, root.id());
            update(digest, root.core());
            update(digest, provider.relativePath());
            update(digest, provider.size());
            update(digest, Hashes.sha256(source));
            providerBytes = Math.addExact(providerBytes, provider.size());
        }
        update(digest, providerBytes);
        return new Result(
                HexFormat.of().formatHex(digest.digest()),
                gameJar,
                providers.size(),
                providerBytes);
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
            long providerCount,
            long providerBytes) {
    }
}
