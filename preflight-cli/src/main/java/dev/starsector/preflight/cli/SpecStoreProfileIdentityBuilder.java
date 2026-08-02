package dev.starsector.preflight.cli;

import dev.starsector.preflight.core.ClasspathProfileIndex;
import dev.starsector.preflight.core.Hashes;
import dev.starsector.preflight.core.ResourceIndex;
import dev.starsector.preflight.core.SpecStoreProfileIdentity;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.List;

/** Hashes every ordered data and code provider that may influence vanilla {@code SpecStore}. */
final class SpecStoreProfileIdentityBuilder {
    private static final String DATA_SCHEMA = "starsector-preflight-spec-store-data-providers-v1";
    private static final String CLASSPATH_SCHEMA = "starsector-preflight-spec-store-classpath-v1";

    private SpecStoreProfileIdentityBuilder() {
    }

    static Result build(
            Path installRoot,
            ResourceIndex resources,
            ClasspathProfileIndex classpath) throws IOException {
        Path gameJar = locateGameJar(installRoot);
        String gameJarSha256 = Hashes.sha256(gameJar);

        MessageDigest data = sha256();
        update(data, DATA_SCHEMA);
        for (ResourceIndex.Root root : resources.roots()) {
            update(data, root.id());
            update(data, root.core());
        }
        long dataProviderCount = 0;
        long dataProviderBytes = 0;
        for (var item : resources.entries().entrySet()) {
            if (!item.getKey().startsWith("data/")) {
                continue;
            }
            for (ResourceIndex.Provider provider : item.getValue()) {
                ResourceIndex.Root root = resources.roots().get(provider.rootIndex());
                Path source = resources.resolveExisting(provider);
                String sourceSha256 = Hashes.sha256(source);
                update(data, item.getKey());
                update(data, provider.rootIndex());
                update(data, root.id());
                update(data, provider.relativePath());
                update(data, provider.size());
                update(data, sourceSha256);
                dataProviderCount = Math.addExact(dataProviderCount, 1);
                dataProviderBytes = Math.addExact(dataProviderBytes, provider.size());
            }
        }

        MessageDigest archives = sha256();
        update(archives, CLASSPATH_SCHEMA);
        long classpathArchiveBytes = 0;
        List<ClasspathProfileIndex.Archive> classpathArchives = classpath.archives();
        for (ClasspathProfileIndex.Archive archive : classpathArchives) {
            update(archives, archive.modId());
            update(archives, archive.relativePath());
            update(archives, archive.sourceSha256());
            update(archives, archive.sourceBytes());
            update(archives, archive.declared());
            classpathArchiveBytes = Math.addExact(classpathArchiveBytes, archive.sourceBytes());
        }

        SpecStoreProfileIdentity identity = new SpecStoreProfileIdentity(
                gameJarSha256,
                HexFormat.of().formatHex(data.digest()),
                HexFormat.of().formatHex(archives.digest()),
                dataProviderCount,
                dataProviderBytes,
                classpathArchives.size(),
                classpathArchiveBytes);
        return new Result(identity, gameJar);
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

    record Result(SpecStoreProfileIdentity identity, Path gameJar) {
    }
}
