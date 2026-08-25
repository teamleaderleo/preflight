package dev.starsector.preflight.cli;

import dev.starsector.preflight.core.Json;
import dev.starsector.preflight.core.ResourceIndex;
import dev.starsector.preflight.core.ResourceIndexIO;
import dev.starsector.preflight.core.ResourceIndexValidator;
import java.io.IOException;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.Map;

final class TextureBatchCommand {
    private static final long MIB = 1024L * 1024L;

    private TextureBatchCommand() {
    }

    static int execute(String[] args, int offset) throws Exception {
        Options options = parse(args, offset);
        Path cacheDirectory = CacheRootBoundary.canonical(
                options.cacheDirectory() == null
                        ? PreflightHome.current().cache()
                        : options.cacheDirectory());

        ResourceIndex sourceIndex;
        Path sourceIndexPath;
        if (options.index() != null) {
            sourceIndexPath = options.index().toAbsolutePath().normalize();
            sourceIndex = ResourceIndexIO.read(sourceIndexPath);
            validateIndex(sourceIndex, "Resource index is stale");
        } else {
            DiscoveryResult discovery = StarsectorDiscovery.discover(
                    Platform.current(),
                    Path.of(System.getProperty("user.home")),
                    Path.of(System.getProperty("user.dir")),
                    System.getenv(),
                    options.game(),
                    options.launcher());
            LaunchTarget target = discovery.selected();
            if (target == null) {
                System.err.println("Preflight could not locate Starsector. Run `doctor` or provide --game.");
                return 3;
            }
            ResourceIndexBuilder.BuildResult built = ResourceIndexBuilder.build(target.installRoot());
            sourceIndex = built.index();
            sourceIndexPath = ResourceIndexIO.directory(cacheDirectory)
                    .resolve(sourceIndex.profileFingerprint() + ".spfi");
            ResourceIndexIO.write(sourceIndexPath, sourceIndex);
        }

        ResourceIndex activeIndex = sourceIndex;
        Path activeIndexPath = sourceIndexPath;
        ResourceIndexSubset.Result selection = null;
        if (options.pathsFile() != null) {
            selection = ResourceIndexSubset.select(sourceIndex, options.pathsFile());
            activeIndex = selection.index();
            activeIndexPath = ResourceIndexIO.directory(cacheDirectory)
                    .resolve(activeIndex.profileFingerprint() + ".spfi")
                    .toAbsolutePath().normalize();
            ResourceIndexIO.write(activeIndexPath, activeIndex);
            validateIndex(activeIndex, "Selected resource index is stale");
        }

        TextureBatchBuilder.Result result = TextureBatchBuilder.build(
                activeIndex,
                cacheDirectory,
                new TextureBatchBuilder.Options(
                        options.workers(),
                        options.memoryBudgetBytes(),
                        options.storagePolicy().codec(),
                        options.storagePolicy().rawWhenCompressionIsIneffective()));

        Map<String, Object> report = new LinkedHashMap<>();
        report.put("cacheDirectory", cacheDirectory);
        report.put("sourceIndex", sourceIndexPath);
        report.put("index", activeIndexPath);
        report.put("sourceProfileFingerprint", sourceIndex.profileFingerprint());
        report.put("profileFingerprint", activeIndex.profileFingerprint());
        report.put("selection", selection == null ? null : selection.toMap());
        report.put("manifest", result.manifestPath());
        report.put("pack", result.packPath());
        report.put("packHit", result.packHit());
        report.put("packBytes", result.packBytes());
        report.put("packedBlobs", result.packedBlobs());
        report.put("rawBlobs", result.rawBlobs());
        report.put("lz4Blobs", result.lz4Blobs());
        report.put("packDurationMs", result.packDurationMillis());
        report.put("manifestEntries", result.manifest().entryCount());
        report.put("candidateEntries", result.candidateEntries());
        report.put("hashedEntries", result.hashedEntries());
        report.put("uniqueContent", result.uniqueContent());
        report.put("cacheHitBlobs", result.cacheHitBlobs());
        report.put("builtBlobs", result.builtBlobs());
        report.put("failedBlobs", result.failedBlobs());
        report.put("skippedUnsupportedBlobs", result.skippedUnsupportedBlobs());
        report.put("quarantinedBlobs", result.quarantinedBlobs());
        report.put("deduplicatedEntries", result.deduplicatedEntries());
        report.put("sourceBytes", result.sourceBytes());
        report.put("uniquePixelBytes", result.uniquePixelBytes());
        report.put("uniqueBlobBytes", result.uniqueBlobBytes());
        report.put("workers", options.workers());
        report.put("memoryBudgetBytes", options.memoryBudgetBytes());
        report.put("textureStorage", options.storagePolicy().optionValue());
        report.put("durationMs", result.durationMillis());
        report.put("diagnostics", result.diagnostics());
        System.out.println(Json.object(report));
        return result.failedBlobs() == 0 ? 0 : 6;
    }

    private static void validateIndex(ResourceIndex index, String prefix) throws IOException {
        ResourceIndexValidator.Result validation = ResourceIndexValidator.validate(index);
        if (!validation.valid()) {
            throw new IOException(prefix + ": " + validation.invalidProviders()
                    + " provider entries differ from disk");
        }
    }

    private static Options parse(String[] args, int offset) {
        Path game = null;
        Path launcher = null;
        Path index = null;
        Path cacheDirectory = null;
        Path pathsFile = null;
        int workers = defaultWorkers();
        long memoryBudgetBytes = defaultMemoryBudgetBytes();
        TextureStoragePolicy storagePolicy = TextureStoragePolicy.DEFAULT;
        for (int i = offset; i < args.length; i++) {
            switch (args[i]) {
                case "--game" -> game = Path.of(requireValue(args, ++i, "--game"));
                case "--launcher" -> launcher = Path.of(requireValue(args, ++i, "--launcher"));
                case "--index" -> index = Path.of(requireValue(args, ++i, "--index"));
                case "--cache-dir" -> cacheDirectory = Path.of(requireValue(args, ++i, "--cache-dir"));
                case "--paths-file" -> pathsFile = Path.of(requireValue(args, ++i, "--paths-file"));
                case "--workers" -> workers = integer(requireValue(args, ++i, "--workers"), "workers");
                case "--memory-mb" -> {
                    long memoryMb = positiveLong(requireValue(args, ++i, "--memory-mb"), "memory-mb");
                    memoryBudgetBytes = Math.multiplyExact(memoryMb, MIB);
                }
                case "--texture-storage" -> storagePolicy =
                        TextureStoragePolicy.parse(requireValue(args, ++i, "--texture-storage"));
                default -> throw new IllegalArgumentException("Unknown texture build option: " + args[i]);
            }
        }
        if (index != null && (game != null || launcher != null)) {
            throw new IllegalArgumentException("Use either --index or game discovery options, not both");
        }
        return new Options(
                game, launcher, index, cacheDirectory, pathsFile, workers, memoryBudgetBytes, storagePolicy);
    }

    private static int defaultWorkers() {
        return Math.max(1, Math.min(8, Runtime.getRuntime().availableProcessors() - 1));
    }

    private static long defaultMemoryBudgetBytes() {
        long preferred = Runtime.getRuntime().maxMemory() / 4;
        return Math.max(128L * MIB, Math.min(512L * MIB, preferred));
    }

    private static int integer(String raw, String name) {
        try {
            return Integer.parseInt(raw);
        } catch (NumberFormatException error) {
            throw new IllegalArgumentException("Invalid " + name + ": " + raw, error);
        }
    }

    private static long positiveLong(String raw, String name) {
        try {
            long value = Long.parseLong(raw);
            if (value <= 0) {
                throw new IllegalArgumentException(name + " must be positive");
            }
            return value;
        } catch (NumberFormatException error) {
            throw new IllegalArgumentException("Invalid " + name + ": " + raw, error);
        }
    }

    private static String requireValue(String[] args, int index, String option) {
        if (index >= args.length) {
            throw new IllegalArgumentException("Missing value for " + option);
        }
        return args[index];
    }

    private record Options(
            Path game,
            Path launcher,
            Path index,
            Path cacheDirectory,
            Path pathsFile,
            int workers,
            long memoryBudgetBytes,
            TextureStoragePolicy storagePolicy) {
    }
}
