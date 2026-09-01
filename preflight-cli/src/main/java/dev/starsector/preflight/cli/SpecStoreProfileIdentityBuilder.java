package dev.starsector.preflight.cli;

import dev.starsector.preflight.core.ClasspathProfileIndex;
import dev.starsector.preflight.core.ResourceIndex;
import dev.starsector.preflight.core.SpecStoreProfileIdentity;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
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
        try (ProfileIdentityContext profile = ProfileIdentityContext.of(installRoot, resources)) {
            return build(profile, classpath);
        }
    }

    static Result build(
            ProfileIdentityContext profile,
            ClasspathProfileIndex classpath) throws IOException {
        ResourceIndex resources = profile.resources();
        Path gameJar = profile.gameJar();
        String gameJarSha256 = profile.gameJarSha256();

        MessageDigest data = sha256();
        update(data, DATA_SCHEMA);
        for (ResourceIndex.Root root : resources.roots()) {
            update(data, root.id());
            update(data, root.core());
        }
        List<OrderedDataProvider> orderedProviders = new ArrayList<>();
        List<ResourceIndex.Provider> providers = new ArrayList<>();
        long dataProviderBytes = 0;
        for (var item : resources.entries().entrySet()) {
            if (!item.getKey().startsWith("data/")) {
                continue;
            }
            for (ResourceIndex.Provider provider : item.getValue()) {
                ResourceIndex.Root root = resources.roots().get(provider.rootIndex());
                orderedProviders.add(new OrderedDataProvider(item.getKey(), provider, root));
                providers.add(provider);
                dataProviderBytes = Math.addExact(dataProviderBytes, provider.size());
            }
        }
        List<Path> sources = profile.resolveAll(providers);
        List<String> sourceHashes = profile.sha256All(sources);
        for (int index = 0; index < orderedProviders.size(); index++) {
            OrderedDataProvider ordered = orderedProviders.get(index);
            ResourceIndex.Provider provider = ordered.provider();
            update(data, ordered.logicalPath());
            update(data, provider.rootIndex());
            update(data, ordered.root().id());
            update(data, provider.relativePath());
            update(data, provider.size());
            update(data, sourceHashes.get(index));
        }
        long dataProviderCount = orderedProviders.size();

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

    private record OrderedDataProvider(
            String logicalPath,
            ResourceIndex.Provider provider,
            ResourceIndex.Root root) {
    }
}
