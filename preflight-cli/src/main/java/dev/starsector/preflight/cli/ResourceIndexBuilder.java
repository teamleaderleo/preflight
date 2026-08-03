package dev.starsector.preflight.cli;

import dev.starsector.preflight.core.PathContainment;
import dev.starsector.preflight.core.ResourceIndex;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.BasicFileAttributes;
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
import java.util.TreeMap;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.stream.Stream;

final class ResourceIndexBuilder {
    private ResourceIndexBuilder() {
    }

    /**
     * The default width of the root scan.
     *
     * <p>The walk is two syscalls per file -- a {@code toRealPath} containment check and a
     * {@code readAttributes} -- across 61,693 files on the reviewed profile, and it was the single
     * largest thing Preflight did before the game's JVM started. It is latency-bound rather than
     * CPU-bound, so the useful width is the number of roots that can have a syscall outstanding at
     * once rather than the number of cores.
     */
    private static final int DEFAULT_SCAN_WORKERS =
            Math.max(2, Math.min(8, Runtime.getRuntime().availableProcessors()));

    static BuildResult build(Path installRoot) throws IOException {
        return build(installRoot, DEFAULT_SCAN_WORKERS);
    }

    /**
     * @param scanWorkers how many roots to walk at once; 1 is the serial reference the test compares
     *     against, because a recorded constant would only prove the two agreed on the day it was
     *     written
     */
    static BuildResult build(Path installRoot, int scanWorkers) throws IOException {
        long started = System.nanoTime();
        GameLayout layout = GameLayout.locate(installRoot);
        List<String> diagnostics = new ArrayList<>(layout.diagnostics());
        List<String> enabledIds = JsonText.stringArray(
                Files.readString(layout.enabledModsFile(), StandardCharsets.UTF_8),
                "enabledMods");
        Map<String, Path> modDirectories = discoverModDirectories(layout.modsDirectory(), diagnostics);

        List<SourceRoot> sourceRoots = new ArrayList<>();
        Path core = locateCoreDirectory(layout.installRoot());
        if (core == null) {
            diagnostics.add("Starsector core resource directory was not found; building a mod-only index");
        } else {
            sourceRoots.add(new SourceRoot("core", core, true));
        }
        for (String id : enabledIds) {
            Path directory = modDirectories.get(id);
            if (directory == null) {
                diagnostics.add("Enabled mod directory not found for ID: " + id);
            } else {
                sourceRoots.add(new SourceRoot(id, directory, false));
            }
        }
        if (sourceRoots.isEmpty()) {
            throw new IOException("No resource roots were available for indexing");
        }

        List<ResourceIndex.Root> roots = sourceRoots.stream()
                .map(root -> new ResourceIndex.Root(root.id(), root.directory(), root.core()))
                .toList();
        TreeMap<String, List<ResourceIndex.Provider>> entries = new TreeMap<>();
        MessageDigest fingerprint = sha256();
        update(fingerprint, "preflight-resource-index-v1");
        for (String enabledId : enabledIds) {
            update(fingerprint, "enabled");
            update(fingerprint, enabledId);
        }

        // Each root is walked into its own scan, then folded in root order. The fold is what keeps
        // the fingerprint identical to the serial one: a worker records the exact bytes the digest
        // would have been fed rather than digesting them, so the digest still sees one root's worth
        // of bytes after another in the original order. Provider lists are appended in the same
        // order for the same reason -- resolution order across roots is the whole point of the
        // index, and a permuted merge would silently change which mod wins every path.
        List<RootScan> scans = scanRoots(sourceRoots, scanWorkers);
        for (int rootIndex = 0; rootIndex < sourceRoots.size(); rootIndex++) {
            SourceRoot root = sourceRoots.get(rootIndex);
            RootScan scan = scans.get(rootIndex);
            update(fingerprint, "root");
            update(fingerprint, root.id());
            update(fingerprint, Boolean.toString(root.core()));
            fingerprint.update(scan.digestInput());
            for (Map.Entry<String, List<ResourceIndex.Provider>> entry : scan.entries().entrySet()) {
                entries.computeIfAbsent(entry.getKey(), ignored -> new ArrayList<>()).addAll(entry.getValue());
            }
            diagnostics.addAll(scan.diagnostics());
        }

        ResourceIndex index = new ResourceIndex(
                HexFormat.of().formatHex(fingerprint.digest()),
                roots,
                entries);
        return new BuildResult(index, List.copyOf(new LinkedHashSet<>(diagnostics)), System.nanoTime() - started);
    }

    /**
     * Indexes one directory as a lone resource root, with no profile around it.
     *
     * <p>A mod author has their work in a directory, not installed into somebody's seventy-mod
     * profile. Analysis that can only run against a resolved profile is analysis they cannot run at
     * all, so this exists to let a single mod be read on its own terms.</p>
     *
     * <p>The result deliberately has one root, which means no path has a second provider and any
     * check that depends on comparing providers has nothing to say. Callers must suppress those
     * rather than report their empty answers as findings.</p>
     */
    static BuildResult buildStandalone(Path directory, String id) throws IOException {
        long started = System.nanoTime();
        List<String> diagnostics = new ArrayList<>();
        Path root = PathContainment.realDirectory(directory);
        SourceRoot source = new SourceRoot(id, root, false);

        TreeMap<String, List<ResourceIndex.Provider>> entries = new TreeMap<>();
        MessageDigest fingerprint = sha256();
        update(fingerprint, "preflight-standalone-index-v1");
        update(fingerprint, id);
        RootScan scan = scanRoot(source, 0);
        fingerprint.update(scan.digestInput());
        entries.putAll(scan.entries());
        diagnostics.addAll(scan.diagnostics());

        ResourceIndex index = new ResourceIndex(
                HexFormat.of().formatHex(fingerprint.digest()),
                List.of(new ResourceIndex.Root(id, root, false)),
                entries);
        return new BuildResult(index, List.copyOf(new LinkedHashSet<>(diagnostics)), System.nanoTime() - started);
    }

    private static Map<String, Path> discoverModDirectories(Path modsDirectory, List<String> diagnostics) throws IOException {
        Map<String, Path> byId = new LinkedHashMap<>();
        try (Stream<Path> entries = Files.list(modsDirectory)) {
            for (Path directory : entries.filter(Files::isDirectory).sorted().toList()) {
                String id = null;
                Path info = directory.resolve("mod_info.json");
                if (Files.isRegularFile(info)) {
                    try {
                        id = JsonText.string(Files.readString(info, StandardCharsets.UTF_8), "id");
                    } catch (RuntimeException | IOException error) {
                        diagnostics.add("Could not read mod ID from " + info + ": " + error.getMessage());
                    }
                }
                if (id == null || id.isBlank()) {
                    id = directory.getFileName().toString();
                }
                Path normalized;
                try {
                    normalized = PathContainment.realDirectory(directory);
                } catch (IOException | IllegalArgumentException error) {
                    diagnostics.add("Could not resolve mod directory " + directory + ": " + error.getMessage());
                    continue;
                }
                Path prior = byId.putIfAbsent(id, normalized);
                if (prior != null) {
                    diagnostics.add("Duplicate mod ID " + id + " in " + prior + " and " + normalized);
                }
            }
        }
        return byId;
    }

    private static Path locateCoreDirectory(Path installRoot) throws IOException {
        Path root = installRoot.toAbsolutePath().normalize();
        List<Path> candidates = List.of(
                root.resolve("starsector-core"),
                root.resolve("Contents/Resources/Java/starsector-core"),
                root.resolve("Contents/Resources/Java"),
                root.resolve("Contents/Resources/starsector-core"),
                root.resolve("Contents/Java/starsector-core"));
        for (Path candidate : candidates) {
            if (isCoreResourceDirectory(candidate)) {
                return PathContainment.realDirectory(candidate);
            }
        }
        if (!Files.isDirectory(root)) {
            return null;
        }
        try (Stream<Path> found = Files.find(
                root,
                6,
                (path, attributes) -> attributes.isDirectory()
                        && path.getFileName() != null
                        && path.getFileName().toString().equalsIgnoreCase("starsector-core"))) {
            Path candidate = found.sorted().findFirst().orElse(null);
            return candidate == null ? null : PathContainment.realDirectory(candidate);
        }
    }

    private static boolean isCoreResourceDirectory(Path candidate) {
        if (!Files.isDirectory(candidate)) {
            return false;
        }
        Path name = candidate.getFileName();
        return (name != null && name.toString().equalsIgnoreCase("starsector-core"))
                || (Files.isDirectory(candidate.resolve("graphics"))
                        && Files.isDirectory(candidate.resolve("data")));
    }

    /** Walks every root, up to {@code workers} at a time, and returns the scans in root order. */
    private static List<RootScan> scanRoots(List<SourceRoot> sourceRoots, int workers) throws IOException {
        if (workers <= 1 || sourceRoots.size() == 1) {
            List<RootScan> scans = new ArrayList<>(sourceRoots.size());
            for (int rootIndex = 0; rootIndex < sourceRoots.size(); rootIndex++) {
                scans.add(scanRoot(sourceRoots.get(rootIndex), rootIndex));
            }
            return scans;
        }
        ExecutorService pool = Executors.newFixedThreadPool(
                Math.min(workers, sourceRoots.size()),
                runnable -> {
                    Thread thread = new Thread(runnable, "preflight-index-scan");
                    thread.setDaemon(true);
                    return thread;
                });
        try {
            List<Future<RootScan>> pending = new ArrayList<>(sourceRoots.size());
            for (int rootIndex = 0; rootIndex < sourceRoots.size(); rootIndex++) {
                SourceRoot root = sourceRoots.get(rootIndex);
                int index = rootIndex;
                pending.add(pool.submit(() -> scanRoot(root, index)));
            }
            List<RootScan> scans = new ArrayList<>(pending.size());
            for (Future<RootScan> future : pending) {
                try {
                    scans.add(future.get());
                } catch (ExecutionException failed) {
                    Throwable cause = failed.getCause();
                    if (cause instanceof IOException io) {
                        throw io;
                    }
                    if (cause instanceof RuntimeException runtime) {
                        throw runtime;
                    }
                    throw new IOException("Resource root scan failed", cause);
                } catch (InterruptedException interrupted) {
                    Thread.currentThread().interrupt();
                    throw new IOException("Resource root scan was interrupted", interrupted);
                }
            }
            return scans;
        } finally {
            pool.shutdownNow();
        }
    }

    private static RootScan scanRoot(SourceRoot root, int rootIndex) throws IOException {
        TreeMap<String, List<ResourceIndex.Provider>> entries = new TreeMap<>();
        ByteArrayOutputStream digestInput = new ByteArrayOutputStream(1 << 16);
        List<String> diagnostics = new ArrayList<>();
        scanDirectory(root, rootIndex, root.directory(), entries, digestInput, diagnostics, new LinkedHashSet<>());
        return new RootScan(entries, digestInput.toByteArray(), diagnostics);
    }

    /** One root's contribution: its providers, the digest bytes it owes, and what it noticed. */
    private record RootScan(
            TreeMap<String, List<ResourceIndex.Provider>> entries,
            byte[] digestInput,
            List<String> diagnostics) {
    }

    private static void scanDirectory(
            SourceRoot root,
            int rootIndex,
            Path directory,
            Map<String, List<ResourceIndex.Provider>> entries,
            ByteArrayOutputStream fingerprint,
            List<String> diagnostics,
            Set<Path> visited) throws IOException {
        Path realDirectory;
        try {
            realDirectory = PathContainment.existingInsideRealRoot(root.directory(), directory);
        } catch (IOException | IllegalArgumentException error) {
            diagnostics.add("Could not resolve " + directory + ": " + error.getMessage());
            return;
        }
        if (!Files.isDirectory(realDirectory)) {
            diagnostics.add("Skipped non-directory resource path " + directory);
            return;
        }
        if (!visited.add(realDirectory)) {
            diagnostics.add("Skipped directory cycle or duplicate link at " + directory);
            return;
        }

        List<Path> children;
        try (Stream<Path> stream = Files.list(directory)) {
            children = stream.sorted(Comparator.comparing(path -> path.getFileName().toString())).toList();
        } catch (IOException error) {
            diagnostics.add("Could not inspect " + directory + ": " + error.getMessage());
            return;
        }
        for (Path child : children) {
            try {
                Path realChild = PathContainment.existingInsideRealRoot(root.directory(), child);
                BasicFileAttributes attributes = Files.readAttributes(realChild, BasicFileAttributes.class);
                if (attributes.isDirectory()) {
                    scanDirectory(root, rootIndex, child, entries, fingerprint, diagnostics, visited);
                } else if (attributes.isRegularFile()) {
                    String childName = child.getFileName().toString();
                    if (isRuntimeGeneratedResource(childName)) {
                        diagnostics.add("Excluded runtime-generated file from the resource index: "
                                + root.directory().relativize(child.toAbsolutePath().normalize())
                                        .toString()
                                        .replace('\\', '/'));
                        continue;
                    }
                    String relative = root.directory().relativize(child.toAbsolutePath().normalize())
                            .toString()
                            .replace('\\', '/');
                    String logical = ResourceIndex.normalizeLogicalPath(relative);
                    ResourceIndex.Provider provider = new ResourceIndex.Provider(
                            rootIndex,
                            relative,
                            attributes.size(),
                            Math.max(0, attributes.lastModifiedTime().toMillis()));
                    List<ResourceIndex.Provider> providers = entries.computeIfAbsent(logical, ignored -> new ArrayList<>());
                    if (!providers.isEmpty() && providers.get(providers.size() - 1).rootIndex() == rootIndex) {
                        diagnostics.add("Case-colliding paths in " + root.id() + ": "
                                + providers.get(providers.size() - 1).relativePath() + " and " + relative);
                    }
                    providers.add(provider);
                    update(fingerprint, logical);
                    update(fingerprint, relative);
                    update(fingerprint, Long.toString(attributes.size()));
                    update(fingerprint, Long.toString(Math.max(0, attributes.lastModifiedTime().toMillis())));
                }
            } catch (IllegalArgumentException | IOException error) {
                diagnostics.add("Could not index " + child + ": " + error.getMessage());
            }
        }
    }

    /**
     * Identifies files Starsector, its launcher, or mods write into an indexed resource root at
     * runtime. These are never resources loaded by logical path, but their bytes and mtimes change
     * every launch. Indexing them would let ordinary runtime logging invalidate an otherwise valid
     * texture resource index (for example, a mod writing {@code stelnet.log} into the core
     * directory during startup), so they are excluded from both the provider set and the
     * fingerprint. The exclusion is intentionally narrow: only log files and their rotation/lock
     * companions, matched case-insensitively.
     */
    static boolean isRuntimeGeneratedResource(String fileName) {
        String lower = fileName.toLowerCase(Locale.ROOT);
        if (lower.endsWith(".log") || lower.endsWith(".log.lck")) {
            return true;
        }
        int marker = lower.indexOf(".log.");
        if (marker >= 0) {
            String suffix = lower.substring(marker + ".log.".length());
            return !suffix.isEmpty() && suffix.chars().allMatch(character -> character >= '0' && character <= '9');
        }
        return false;
    }

    private static void update(ByteArrayOutputStream recorded, String value) {
        byte[] utf8 = value.getBytes(StandardCharsets.UTF_8);
        recorded.write(utf8, 0, utf8.length);
        recorded.write(0);
    }

    private static void update(MessageDigest digest, String value) {
        digest.update(value.getBytes(StandardCharsets.UTF_8));
        digest.update((byte) 0);
    }

    private static MessageDigest sha256() {
        try {
            return MessageDigest.getInstance("SHA-256");
        } catch (NoSuchAlgorithmException impossible) {
            throw new IllegalStateException("SHA-256 is unavailable", impossible);
        }
    }

    record BuildResult(ResourceIndex index, List<String> diagnostics, long durationNanos) {
        double durationMillis() {
            return durationNanos / 1_000_000.0;
        }
    }

    private record SourceRoot(String id, Path directory, boolean core) {
        SourceRoot {
            directory = directory.toAbsolutePath().normalize();
        }
    }
}
