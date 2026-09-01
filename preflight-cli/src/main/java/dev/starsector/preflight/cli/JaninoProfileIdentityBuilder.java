package dev.starsector.preflight.cli;

import dev.starsector.preflight.core.ClasspathProfileIndex;
import dev.starsector.preflight.core.GeneratedBytecodeContext;
import dev.starsector.preflight.core.Hashes;
import dev.starsector.preflight.core.ResourceIndex;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/** Conservative exact identity for every installed input that can affect Janino output. */
final class JaninoProfileIdentityBuilder {
    private static final String CLASSPATH_SCHEMA = "starsector-preflight-janino-classpath-v1";
    private static final String SOURCE_GRAPH_SCHEMA = "starsector-preflight-janino-full-source-profile-v1";
    private static final String COMPILER_SCHEMA = "starsector-preflight-janino-compiler-policy-v1";
    private static final String PARENT_SCHEMA = "starsector-preflight-janino-parent-loader-v1";
    private static final String PROTECTION_SCHEMA = "starsector-preflight-janino-protection-policy-v1";
    private static final String JANINO_JAR = "janino.jar";
    private static final String RUNTIME_MODULES = "modules";

    private JaninoProfileIdentityBuilder() {
    }

    static Result build(ProfileIdentityContext profile, ClasspathProfileIndex classpath) throws IOException {
        List<OrderedArchive> archives = classpath.archives().stream()
                .map(archive -> new OrderedArchive(
                        archive.modId(), archive.relativePath(), archive.physicalPath(),
                        archive.sourceBytes(), archive.declared()))
                .toList();
        return build(profile, archives);
    }

    /** Reconstructs Starsector's mod-JAR order from the launch resource roots already in memory. */
    static List<OrderedArchive> discoverOrderedArchives(ProfileIdentityContext profile)
            throws IOException {
        ResourceIndex resources = profile.resources();
        List<OrderedArchive> result = new ArrayList<>();
        List<Map<String, ResourceIndex.Provider>> archivesByRoot = new ArrayList<>();
        for (int rootIndex = 0; rootIndex < resources.roots().size(); rootIndex++) {
            archivesByRoot.add(new LinkedHashMap<>());
        }
        for (List<ResourceIndex.Provider> providers : resources.entries().values()) {
            for (ResourceIndex.Provider provider : providers) {
                if (provider.relativePath().toLowerCase(Locale.ROOT).endsWith(".jar")) {
                    archivesByRoot.get(provider.rootIndex())
                            .put(provider.relativePath(), provider);
                }
            }
        }
        for (int rootIndex = 0; rootIndex < resources.roots().size(); rootIndex++) {
            ResourceIndex.Root root = resources.roots().get(rootIndex);
            if (root.core()) {
                continue;
            }
            Map<String, ResourceIndex.Provider> byRelative = archivesByRoot.get(rootIndex);
            List<String> actual = byRelative.keySet().stream().sorted().toList();
            List<String> declared = declaredJars(profile, rootIndex);
            Set<String> actualSet = new LinkedHashSet<>(actual);
            Set<String> declaredSet = new LinkedHashSet<>(declared);
            List<String> ordered = new ArrayList<>();
            declared.stream().filter(actualSet::contains).forEach(ordered::add);
            actual.stream().filter(path -> !declaredSet.contains(path)).forEach(ordered::add);
            for (String relative : ordered) {
                ResourceIndex.Provider provider = byRelative.get(relative);
                result.add(new OrderedArchive(
                        root.id(), relative, profile.resolve(provider), provider.size(),
                        declaredSet.contains(relative)));
            }
        }
        return List.copyOf(result);
    }

    private static List<String> declaredJars(ProfileIdentityContext profile, int rootIndex)
            throws IOException {
        for (ResourceIndex.Provider provider :
                profile.resources().entries().getOrDefault("mod_info.json", List.of())) {
            if (provider.rootIndex() != rootIndex) {
                continue;
            }
            List<String> raw = JsonText.stringArray(
                    Files.readString(profile.resolve(provider), StandardCharsets.UTF_8), "jars");
            LinkedHashSet<String> normalized = new LinkedHashSet<>();
            for (String path : raw) {
                normalized.add(ResourceIndex.normalizeRelativePath(path));
            }
            return List.copyOf(normalized);
        }
        return List.of();
    }

    static Result build(ProfileIdentityContext profile, List<OrderedArchive> archives) throws IOException {
        return build(profile, archives, profile.gameJarSha256());
    }

    static Result build(
            ProfileIdentityContext profile,
            List<OrderedArchive> archives,
            String launchContractSha256) throws IOException {
        Hashes.decodeSha256(launchContractSha256);
        Path javaDirectory = profile.gameJar().getParent();
        if (javaDirectory == null || !Files.isDirectory(javaDirectory)) {
            throw new IOException("Could not locate Starsector's Java library directory");
        }

        List<Path> archivePaths = archives.stream().map(OrderedArchive::physicalPath).toList();
        List<String> archiveHashes = profile.sha256All(archivePaths);

        MessageDigest orderedClasspath = digest();
        update(orderedClasspath, CLASSPATH_SCHEMA);
        long archiveBytes = 0;
        for (int index = 0; index < archives.size(); index++) {
            OrderedArchive archive = archives.get(index);
            update(orderedClasspath, index);
            update(orderedClasspath, archive.modId());
            update(orderedClasspath, archive.relativePath());
            update(orderedClasspath, archive.declared());
            update(orderedClasspath, archiveHashes.get(index));
            update(orderedClasspath, archive.sourceBytes());
            archiveBytes = Math.addExact(archiveBytes, archive.sourceBytes());
        }
        String orderedClasspathSha256 = finish(orderedClasspath);

        List<LooseProvider> loose = looseProviders(profile.resources());
        List<ResourceIndex.Provider> looseProviders = loose.stream().map(LooseProvider::provider).toList();
        List<String> looseHashes = profile.sha256All(profile.resolveAll(looseProviders));
        MessageDigest sourceGraph = digest();
        update(sourceGraph, SOURCE_GRAPH_SCHEMA);
        update(sourceGraph, profile.resources().profileFingerprint());
        for (int index = 0; index < profile.resources().roots().size(); index++) {
            ResourceIndex.Root root = profile.resources().roots().get(index);
            update(sourceGraph, index);
            update(sourceGraph, root.id());
            update(sourceGraph, root.core());
        }
        for (int index = 0; index < archives.size(); index++) {
            update(sourceGraph, "archive");
            update(sourceGraph, index);
            update(sourceGraph, archiveHashes.get(index));
        }
        long looseBytes = 0;
        for (int index = 0; index < loose.size(); index++) {
            LooseProvider source = loose.get(index);
            ResourceIndex.Provider provider = source.provider();
            ResourceIndex.Root root = profile.resources().roots().get(provider.rootIndex());
            update(sourceGraph, "loose");
            update(sourceGraph, source.logicalPath());
            update(sourceGraph, provider.rootIndex());
            update(sourceGraph, root.id());
            update(sourceGraph, provider.relativePath());
            update(sourceGraph, provider.size());
            update(sourceGraph, looseHashes.get(index));
            looseBytes = Math.addExact(looseBytes, provider.size());
        }
        String sourceGraphSha256 = finish(sourceGraph);

        List<Path> coreJars;
        try (var files = Files.list(javaDirectory)) {
            coreJars = files.filter(Files::isRegularFile)
                    .filter(path -> path.getFileName().toString().toLowerCase(Locale.ROOT).endsWith(".jar"))
                    .sorted(Comparator.comparing(path -> path.getFileName().toString().toLowerCase(Locale.ROOT)))
                    .toList();
        }
        if (coreJars.isEmpty()) {
            throw new IOException("No Starsector core JARs were found in " + javaDirectory);
        }
        List<String> coreHashes = profile.sha256All(coreJars);
        int janinoIndex = -1;
        for (int index = 0; index < coreJars.size(); index++) {
            if (JANINO_JAR.equalsIgnoreCase(coreJars.get(index).getFileName().toString())) {
                janinoIndex = index;
                break;
            }
        }
        if (janinoIndex < 0) {
            throw new IOException("Could not locate " + JANINO_JAR + " in " + javaDirectory);
        }

        Path runtimeModules = locateRuntimeModules(profile.installRoot());
        String runtimeModulesSha256 = Hashes.sha256(runtimeModules);
        MessageDigest parent = digest();
        update(parent, PARENT_SCHEMA);
        update(parent, "jdk/internal/loader/ClassLoaders$AppClassLoader");
        update(parent, "app");
        update(parent, launchContractSha256);
        update(parent, runtimeModulesSha256);
        for (int index = 0; index < coreJars.size(); index++) {
            update(parent, coreJars.get(index).getFileName().toString());
            update(parent, coreHashes.get(index));
        }
        update(parent, orderedClasspathSha256);

        MessageDigest compiler = digest();
        update(compiler, COMPILER_SCHEMA);
        update(compiler, "character-encoding=UTF-8");
        update(compiler, "debug-source=true");
        update(compiler, "debug-lines=true");
        update(compiler, "debug-vars=true");
        update(compiler, "loader-class=org.codehaus.janino.JavaSourceClassLoader");
        update(compiler, "game-enableScriptCaching=false");
        update(compiler, "runtime-modules=" + runtimeModulesSha256);

        MessageDigest protection = digest();
        update(protection, PROTECTION_SCHEMA);
        update(protection, "optionalProtectionDomainFactory=null");

        GeneratedBytecodeContext context = new GeneratedBytecodeContext(
                profile.gameJarSha256(),
                coreHashes.get(janinoIndex),
                orderedClasspathSha256,
                sourceGraphSha256,
                finish(compiler),
                finish(parent),
                finish(protection));
        return new Result(
                context,
                archives.size(),
                archiveBytes,
                loose.size(),
                looseBytes,
                coreJars.size(),
                runtimeModules,
                runtimeModulesSha256);
    }

    private static List<LooseProvider> looseProviders(ResourceIndex resources) {
        List<LooseProvider> result = new ArrayList<>();
        for (Map.Entry<String, List<ResourceIndex.Provider>> entry : resources.entries().entrySet()) {
            String path = entry.getKey().toLowerCase(Locale.ROOT);
            if (!path.endsWith(".java") && !path.endsWith(".class")) {
                continue;
            }
            for (ResourceIndex.Provider provider : entry.getValue()) {
                result.add(new LooseProvider(entry.getKey(), provider));
            }
        }
        return List.copyOf(result);
    }

    private static Path locateRuntimeModules(Path installRoot) throws IOException {
        Path root = installRoot.toAbsolutePath().normalize();
        List<Path> candidates = List.of(
                root.resolve("Contents/Home/lib/modules"),
                root.resolve("jre_linux/lib/modules"),
                root.resolve("jre/lib/modules"),
                root.resolve("starsector-core/jre/lib/modules"),
                root.resolve("Contents/Resources/Java/jre/lib/modules"),
                root.resolve("Contents/Home/jre/lib/modules"));
        for (Path candidate : candidates) {
            if (Files.isRegularFile(candidate)
                    && RUNTIME_MODULES.equals(candidate.getFileName().toString())) {
                return candidate.toAbsolutePath().normalize();
            }
        }
        throw new IOException("Could not locate the bundled JVM modules image under " + root);
    }

    private static MessageDigest digest() {
        try {
            return MessageDigest.getInstance("SHA-256");
        } catch (NoSuchAlgorithmException impossible) {
            throw new IllegalStateException("SHA-256 is unavailable", impossible);
        }
    }

    private static String finish(MessageDigest digest) {
        return HexFormat.of().formatHex(digest.digest());
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

    private record LooseProvider(String logicalPath, ResourceIndex.Provider provider) {
    }

    record OrderedArchive(
            String modId,
            String relativePath,
            Path physicalPath,
            long sourceBytes,
            boolean declared) {
        OrderedArchive {
            if (modId == null || modId.isBlank()) {
                throw new IllegalArgumentException("Classpath archive mod ID is required");
            }
            relativePath = ResourceIndex.normalizeRelativePath(relativePath);
            physicalPath = physicalPath.toAbsolutePath().normalize();
            if (sourceBytes < 0) {
                throw new IllegalArgumentException("Classpath archive size may not be negative");
            }
        }
    }

    record Result(
            GeneratedBytecodeContext context,
            long archiveCount,
            long archiveBytes,
            long looseProviderCount,
            long looseProviderBytes,
            long coreJarCount,
            Path runtimeModules,
            String runtimeModulesSha256) {
    }
}
