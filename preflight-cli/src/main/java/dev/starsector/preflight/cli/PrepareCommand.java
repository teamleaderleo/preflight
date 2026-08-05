package dev.starsector.preflight.cli;

import dev.starsector.preflight.core.ClasspathProfileIndex;
import dev.starsector.preflight.core.Json;
import dev.starsector.preflight.core.ResourceIndex;
import dev.starsector.preflight.core.ResourceIndexIO;
import dev.starsector.preflight.core.ResourceIndexValidator;
import dev.starsector.preflight.core.SpecStoreProfileIdentity;
import dev.starsector.preflight.core.TextureManifestValidator;
import dev.starsector.preflight.core.TextureMemoryEstimator;
import java.io.IOException;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/** Runs every renderer-independent preparation stage and writes one atomic report. */
final class PrepareCommand {
    private static final int DEFAULT_WORKERS = Math.max(1, Math.min(4, Runtime.getRuntime().availableProcessors()));
    private static final long DEFAULT_MEMORY_MIB = 256;
    private static final int DEFAULT_LOOKUP_QUERIES = 5_000;

    private PrepareCommand() {
    }

    static int execute(String[] args, int offset) throws Exception {
        Options options = parse(args, offset);
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

        Path cache = (options.cacheDirectory() == null ? defaultCacheDirectory() : options.cacheDirectory())
                .toAbsolutePath().normalize();
        Files.createDirectories(cache);
        Path report = (options.report() == null
                ? cache.resolve("reports/preparation-latest.json")
                : options.report()).toAbsolutePath().normalize();

        long started = System.nanoTime();
        Map<String, Object> stages = new LinkedHashMap<>();
        List<String> diagnostics = new ArrayList<>();
        ResourceIndex resourceIndex = null;
        Path resourceIndexPath = null;
        ClasspathProfileIndex classpathIndex = null;
        Path classpathIndexPath = null;
        Path specStoreProfilePath = null;
        boolean allEnabledStagesSuccessful = true;

        stageStarted("census");
        Stage census = runStage("census", () -> {
            ProfileCensus.Result result = ProfileCensus.scan(target.installRoot());
            Map<String, Object> output = new LinkedHashMap<>();
            output.put("profile", result.values());
            return Stage.success(output);
        });
        stageCompleted("census", census);
        stages.put("census", census.toMap());
        allEnabledStagesSuccessful &= census.successful();
        diagnostics.addAll(census.diagnostics());

        stageStarted("resource-index");
        Stage resourceStage;
        if (options.resourceIndex()) {
            try {
                long stageStarted = System.nanoTime();
                ResourceIndexBuilder.BuildResult built = ResourceIndexBuilder.build(target.installRoot());
                ResourceIndex selected = built.index();
                Path output = cache.resolve("resource-indexes")
                        .resolve(selected.profileFingerprint() + ".spfi");
                boolean artifactHit = false;
                if (Files.isRegularFile(output)) {
                    try {
                        ResourceIndex existing = ResourceIndexIO.read(output);
                        ResourceIndexValidator.Result existingValidation = ResourceIndexValidator.validate(existing);
                        if (existing.profileFingerprint().equals(selected.profileFingerprint())
                                && existingValidation.valid()) {
                            selected = existing;
                            artifactHit = true;
                        }
                    } catch (IOException | RuntimeException error) {
                        diagnostics.add("Existing resource index was replaced: " + message(error));
                    }
                }
                if (!artifactHit) {
                    ResourceIndexIO.write(output, selected);
                }
                ResourceIndexValidator.Result validation = ResourceIndexValidator.validate(selected);
                Map<String, Object> details = new LinkedHashMap<>();
                details.put("file", output.toAbsolutePath().normalize());
                details.put("artifactHit", artifactHit);
                details.put("profileFingerprint", selected.profileFingerprint());
                details.put("rootCount", selected.roots().size());
                details.put("entryCount", selected.entryCount());
                details.put("providerCount", selected.providerCount());
                details.put("buildMs", built.durationMillis());
                details.put("buildDiagnostics", built.diagnostics());
                details.put("valid", validation.valid());
                details.put("checkedProviders", validation.checkedProviders());
                details.put("invalidProviders", validation.invalidProviders());
                details.put("validationProblems", validation.problems());
                resourceStage = validation.valid()
                        ? Stage.success(details, System.nanoTime() - stageStarted)
                        : Stage.failed(details, List.of("Resource index validation failed"), System.nanoTime() - stageStarted);
                resourceIndex = selected;
                resourceIndexPath = output.toAbsolutePath().normalize();
            } catch (Exception error) {
                resourceStage = Stage.failed(error);
            }
        } else {
            resourceStage = Stage.skipped("Disabled by --no-resource-index");
        }
        stageCompleted("resource-index", resourceStage);
        stages.put("resourceIndex", resourceStage.toMap());
        allEnabledStagesSuccessful &= resourceStage.successful();
        diagnostics.addAll(resourceStage.diagnostics());

        stageStarted("classpath-index");
        Stage classpathStage;
        if (options.classpath()) {
            try {
                long stageStarted = System.nanoTime();
                ClasspathIndexBuilder.Result built = ClasspathIndexBuilder.build(target.installRoot(), cache);
                ClasspathIndexBuilder.Validation validation = ClasspathIndexBuilder.validate(
                        built.profile(), cache, options.deep());
                Map<String, Object> details = new LinkedHashMap<>();
                details.put("file", built.profilePath());
                details.put("profileHit", built.profileHit());
                details.put("profileWritten", built.profileWritten());
                details.put("profileFingerprint", built.profile().profileFingerprint());
                details.put("archiveCount", built.profile().archives().size());
                details.put("entryCount", built.profile().entryCount());
                details.put("providerCount", built.profile().providerCount());
                details.put("archiveHits", built.archiveHits());
                details.put("archiveBuilds", built.archiveBuilds());
                details.put("quarantinedIndexes", built.quarantinedIndexes());
                details.put("failedArchives", built.failedArchives());
                details.put("buildDiagnostics", built.diagnostics());
                details.put("valid", validation.valid());
                details.put("deepValidation", validation.deep());
                details.put("checkedArchives", validation.checkedArchives());
                details.put("checkedEntries", validation.checkedEntries());
                details.put("validationProblems", validation.problems());
                classpathStage = validation.valid() && built.failedArchives() == 0
                        ? Stage.success(details, System.nanoTime() - stageStarted)
                        : Stage.failed(details, List.of("Classpath index preparation or validation failed"), System.nanoTime() - stageStarted);
                classpathIndex = built.profile();
                classpathIndexPath = built.profilePath().toAbsolutePath().normalize();
            } catch (Exception error) {
                classpathStage = Stage.failed(error);
            }
        } else {
            classpathStage = Stage.skipped("Disabled by --no-classpath");
        }
        stageCompleted("classpath-index", classpathStage);
        stages.put("classpathIndex", classpathStage.toMap());
        allEnabledStagesSuccessful &= classpathStage.successful();
        diagnostics.addAll(classpathStage.diagnostics());

        stageStarted("spec-store-identity");
        Stage specStoreStage;
        if (resourceIndex == null || classpathIndex == null) {
            specStoreStage = Stage.skipped(
                    "Prepared resource and classpath indexes are required for SpecStore identity");
        } else {
            try {
                long stageStarted = System.nanoTime();
                SpecStoreProfileIdentityBuilder.Result built = SpecStoreProfileIdentityBuilder.build(
                        target.installRoot(), resourceIndex, classpathIndex);
                SpecStoreProfileIdentity identity = built.identity();
                Path output = cache.resolve("spec-store/profiles")
                        .resolve(identity.identitySha256() + ".json")
                        .toAbsolutePath().normalize();
                Map<String, Object> artifact = new LinkedHashMap<>();
                artifact.put("format", "starsector-preflight-spec-store-profile-v1");
                artifact.put("formatVersion", SpecStoreProfileIdentity.FORMAT_VERSION);
                artifact.put("identitySha256", identity.identitySha256());
                artifact.put("gameJar", built.gameJar());
                artifact.put("gameJarSha256", identity.gameJarSha256());
                artifact.put("dataProvidersSha256", identity.dataProvidersSha256());
                artifact.put("dataProviderCount", identity.dataProviderCount());
                artifact.put("dataProviderBytes", identity.dataProviderBytes());
                artifact.put("classpathArchivesSha256", identity.classpathArchivesSha256());
                artifact.put("classpathArchiveCount", identity.classpathArchiveCount());
                artifact.put("classpathArchiveBytes", identity.classpathArchiveBytes());
                String content = Json.object(artifact) + System.lineSeparator();
                boolean artifactHit = Files.isRegularFile(output)
                        && Files.readString(output).equals(content);
                if (!artifactHit) {
                    writeAtomic(output, content);
                }
                artifact.put("file", output);
                artifact.put("artifactHit", artifactHit);
                specStoreStage = Stage.success(artifact, System.nanoTime() - stageStarted);
                specStoreProfilePath = output;
            } catch (Exception error) {
                specStoreStage = Stage.failed(error);
            }
        }
        stageCompleted("spec-store-identity", specStoreStage);
        stages.put("specStoreIdentity", specStoreStage.toMap());
        allEnabledStagesSuccessful &= specStoreStage.successful();
        diagnostics.addAll(specStoreStage.diagnostics());

        stageStarted("textures");
        Stage textureStage;
        if (options.textures()) {
            if (resourceIndex == null) {
                textureStage = Stage.skipped("A prepared resource index is required for texture preparation");
                allEnabledStagesSuccessful = false;
            } else {
                try {
                    long stageStarted = System.nanoTime();
                    TextureBatchBuilder.Result built = TextureBatchBuilder.build(
                            resourceIndex,
                            cache,
                            new TextureBatchBuilder.Options(
                                    options.workers(),
                                    options.memoryMib() * 1024L * 1024L,
                                    options.textureStorage().codec()));
                    TextureManifestValidator.Result validation = TextureManifestValidator.validate(cache, built.manifest());
                    Map<String, Object> details = new LinkedHashMap<>();
                    details.put("manifest", built.manifestPath());
                    details.put("pack", built.packPath());
                    details.put("packHit", built.packHit());
                    details.put("packBytes", built.packBytes());
                    details.put("packedBlobs", built.packedBlobs());
                    details.put("packDurationMs", built.packDurationMillis());
                    details.put("candidateEntries", built.candidateEntries());
                    details.put("hashedEntries", built.hashedEntries());
                    details.put("uniqueContent", built.uniqueContent());
                    details.put("cacheHitBlobs", built.cacheHitBlobs());
                    details.put("builtBlobs", built.builtBlobs());
                    details.put("failedBlobs", built.failedBlobs());
                    details.put("skippedUnsupportedBlobs", built.skippedUnsupportedBlobs());
                    details.put("quarantinedBlobs", built.quarantinedBlobs());
                    details.put("deduplicatedEntries", built.deduplicatedEntries());
                    details.put("sourceBytes", built.sourceBytes());
                    details.put("uniquePixelBytes", built.uniquePixelBytes());
                    details.put("uniqueBlobBytes", built.uniqueBlobBytes());
                    details.put("textureStorage", options.textureStorage().optionValue());
                    details.put("memoryEstimate", TextureMemoryEstimator.estimate(built.manifest()).toReportValues());
                    details.put("buildDiagnostics", built.diagnostics());
                    details.put("valid", validation.valid());
                    details.put("checkedEntries", validation.checkedEntries());
                    details.put("invalidEntries", validation.invalidEntries());
                    details.put("validationProblems", validation.problems());
                    textureStage = validation.valid() && built.failedBlobs() == 0
                            ? Stage.success(details, System.nanoTime() - stageStarted)
                            : Stage.failed(details, List.of("Texture cache preparation or validation failed"), System.nanoTime() - stageStarted);
                } catch (Exception error) {
                    textureStage = Stage.failed(error);
                }
            }
        } else {
            textureStage = Stage.skipped("Disabled by --no-textures");
        }
        stageCompleted("textures", textureStage);
        stages.put("textures", textureStage.toMap());
        allEnabledStagesSuccessful &= textureStage.successful();
        diagnostics.addAll(textureStage.diagnostics());

        stageStarted("lookup-verification");
        Stage verificationStage;
        if (options.verifyLookups()) {
            if (resourceIndex == null && classpathIndex == null) {
                verificationStage = Stage.skipped("No prepared indexes were available for lookup verification");
                allEnabledStagesSuccessful = false;
            } else {
                try {
                    long stageStarted = System.nanoTime();
                    List<LookupEquivalence.DomainResult> domains = new ArrayList<>();
                    if (resourceIndex != null) {
                        domains.add(LookupEquivalence.resources(
                                resourceIndex, options.lookupQueries(), options.seed()));
                    }
                    if (classpathIndex != null) {
                        domains.add(LookupEquivalence.classpath(
                                classpathIndex,
                                options.lookupQueries(),
                                options.seed() ^ 0x9e37_79b9_7f4a_7c15L));
                    }
                    boolean equivalent = domains.stream().allMatch(LookupEquivalence.DomainResult::equivalent);
                    Map<String, Object> details = new LinkedHashMap<>();
                    details.put("queriesPerDomain", options.lookupQueries());
                    details.put("seed", options.seed());
                    details.put("equivalent", equivalent);
                    details.put("totalMismatches", domains.stream()
                            .mapToLong(LookupEquivalence.DomainResult::mismatches)
                            .sum());
                    details.put("domains", domains.stream().map(LookupEquivalence.DomainResult::toMap).toList());
                    verificationStage = equivalent
                            ? Stage.success(details, System.nanoTime() - stageStarted)
                            : Stage.failed(details, List.of("Indexed lookup results differed from baseline probing"), System.nanoTime() - stageStarted);
                } catch (Exception error) {
                    verificationStage = Stage.failed(error);
                }
            }
        } else {
            verificationStage = Stage.skipped("Enable with --verify-lookups");
        }
        stageCompleted("lookup-verification", verificationStage);
        stages.put("lookupVerification", verificationStage.toMap());
        allEnabledStagesSuccessful &= verificationStage.successful();
        diagnostics.addAll(verificationStage.diagnostics());

        Map<String, Object> readiness = PreparationReadiness.toMap(allEnabledStagesSuccessful);

        Map<String, Object> output = new LinkedHashMap<>();
        output.put("generatedAt", Instant.now());
        output.put("successful", allEnabledStagesSuccessful);
        output.put("installRoot", target.installRoot());
        output.put("launcher", target.launcher());
        output.put("launcherKind", target.kind());
        output.put("cacheDirectory", cache);
        output.put("report", report);
        output.put("resourceIndex", resourceIndexPath);
        output.put("classpathIndex", classpathIndexPath);
        output.put("specStoreProfile", specStoreProfilePath);
        output.put("options", options.toMap());
        output.put("stages", stages);
        output.put("readiness", readiness);
        output.put("diagnostics", List.copyOf(diagnostics));
        output.put("durationMs", (System.nanoTime() - started) / 1_000_000.0);

        writeAtomic(report, Json.object(output) + System.lineSeparator());
        System.out.println(report);
        return allEnabledStagesSuccessful ? 0 : 5;
    }

    private static Stage runStage(String name, StageOperation operation) {
        long started = System.nanoTime();
        try {
            Stage result = operation.run();
            return result.withDuration(System.nanoTime() - started);
        } catch (Exception error) {
            return Stage.failed(name + " failed: " + message(error), System.nanoTime() - started);
        }
    }

    private static void stageStarted(String name) {
        System.err.println("prepare: " + name + " started");
    }

    private static void stageCompleted(String name, Stage stage) {
        System.err.printf(
                Locale.ROOT,
                "prepare: %s completed status=%s durationMs=%.3f%n",
                name,
                stage.status(),
                stage.durationNanos() / 1_000_000.0);
    }

    private static void writeAtomic(Path target, String content) throws IOException {
        Path parent = target.getParent();
        if (parent != null) {
            Files.createDirectories(parent);
        }
        Path temporary = target.resolveSibling(
                target.getFileName() + ".tmp-" + ProcessHandle.current().pid() + "-" + System.nanoTime());
        boolean moved = false;
        try {
            Files.writeString(
                    temporary,
                    content,
                    StandardOpenOption.CREATE_NEW,
                    StandardOpenOption.WRITE);
            try {
                Files.move(
                        temporary,
                        target,
                        StandardCopyOption.ATOMIC_MOVE,
                        StandardCopyOption.REPLACE_EXISTING);
            } catch (AtomicMoveNotSupportedException ignored) {
                Files.move(temporary, target, StandardCopyOption.REPLACE_EXISTING);
            }
            moved = true;
        } finally {
            if (!moved) {
                Files.deleteIfExists(temporary);
            }
        }
    }

    private static Options parse(String[] args, int offset) {
        Path game = null;
        Path launcher = null;
        Path cache = null;
        Path report = null;
        int workers = DEFAULT_WORKERS;
        long memoryMib = DEFAULT_MEMORY_MIB;
        boolean deep = false;
        boolean verifyLookups = false;
        int lookupQueries = DEFAULT_LOOKUP_QUERIES;
        long seed = 0x5eed_5eedL;
        boolean resourceIndex = true;
        boolean classpath = true;
        boolean textures = true;
        TextureStoragePolicy textureStorage = TextureStoragePolicy.DEFAULT;
        for (int i = offset; i < args.length; i++) {
            switch (args[i]) {
                case "--game" -> game = Path.of(requireValue(args, ++i, "--game"));
                case "--launcher" -> launcher = Path.of(requireValue(args, ++i, "--launcher"));
                case "--cache-dir" -> cache = Path.of(requireValue(args, ++i, "--cache-dir"));
                case "--report" -> report = Path.of(requireValue(args, ++i, "--report"));
                case "--workers" -> workers = parseInt(requireValue(args, ++i, "--workers"), "worker count");
                case "--memory-mb" -> memoryMib = parseLong(requireValue(args, ++i, "--memory-mb"), "memory budget");
                case "--deep" -> deep = true;
                case "--verify-lookups" -> verifyLookups = true;
                case "--lookup-queries" -> lookupQueries = parseInt(requireValue(args, ++i, "--lookup-queries"), "lookup query count");
                case "--seed" -> seed = parseLong(requireValue(args, ++i, "--seed"), "seed");
                case "--no-resource-index" -> resourceIndex = false;
                case "--no-classpath" -> classpath = false;
                case "--no-textures" -> textures = false;
                case "--texture-storage" -> textureStorage =
                        TextureStoragePolicy.parse(requireValue(args, ++i, "--texture-storage"));
                default -> throw new IllegalArgumentException("Unknown prepare option: " + args[i]);
            }
        }
        if (workers < 1 || workers > 64) {
            throw new IllegalArgumentException("Texture workers must be between 1 and 64");
        }
        if (memoryMib < 16 || memoryMib > 65_536) {
            throw new IllegalArgumentException("Texture memory budget must be between 16 and 65536 MiB");
        }
        if (lookupQueries < 1 || lookupQueries > 1_000_000) {
            throw new IllegalArgumentException("Lookup query count must be between 1 and 1,000,000");
        }
        return new Options(
                game, launcher, cache, report, workers, memoryMib, deep, verifyLookups,
                lookupQueries, seed, resourceIndex, classpath, textures, textureStorage);
    }

    private static String requireValue(String[] args, int index, String option) {
        if (index >= args.length) {
            throw new IllegalArgumentException("Missing value for " + option);
        }
        return args[index];
    }

    private static int parseInt(String raw, String kind) {
        try {
            return Integer.parseInt(raw);
        } catch (NumberFormatException error) {
            throw new IllegalArgumentException("Invalid " + kind + ": " + raw, error);
        }
    }

    private static long parseLong(String raw, String kind) {
        try {
            return Long.parseLong(raw);
        } catch (NumberFormatException error) {
            throw new IllegalArgumentException("Invalid " + kind + ": " + raw, error);
        }
    }

    static Path defaultCacheDirectory() {
        return Path.of(System.getProperty("user.home"), ".starsector-preflight", "cache");
    }

    private static String message(Throwable error) {
        String value = error.getMessage();
        return value == null || value.isBlank() ? error.getClass().getSimpleName() : value;
    }

    private record Options(
            Path game,
            Path launcher,
            Path cacheDirectory,
            Path report,
            int workers,
            long memoryMib,
            boolean deep,
            boolean verifyLookups,
            int lookupQueries,
            long seed,
            boolean resourceIndex,
            boolean classpath,
            boolean textures,
            TextureStoragePolicy textureStorage) {
        Map<String, Object> toMap() {
            Map<String, Object> values = new LinkedHashMap<>();
            values.put("workers", workers);
            values.put("memoryMib", memoryMib);
            values.put("deep", deep);
            values.put("verifyLookups", verifyLookups);
            values.put("lookupQueries", lookupQueries);
            values.put("seed", seed);
            values.put("resourceIndex", resourceIndex);
            values.put("classpath", classpath);
            values.put("textures", textures);
            values.put("textureStorage", textureStorage.optionValue());
            return values;
        }
    }

    private record Stage(
            String status,
            Map<String, Object> details,
            List<String> diagnostics,
            long durationNanos) {
        static Stage success(Map<String, Object> details) {
            return new Stage("SUCCESS", Map.copyOf(details), List.of(), 0);
        }

        static Stage success(Map<String, Object> details, long durationNanos) {
            return new Stage("SUCCESS", Map.copyOf(details), List.of(), durationNanos);
        }

        static Stage failed(Throwable error) {
            return failed(message(error), 0);
        }

        static Stage failed(String diagnostic, long durationNanos) {
            return new Stage("FAILED", Map.of(), List.of(diagnostic), durationNanos);
        }

        static Stage failed(Map<String, Object> details, List<String> diagnostics, long durationNanos) {
            return new Stage("FAILED", Map.copyOf(details), List.copyOf(diagnostics), durationNanos);
        }

        static Stage skipped(String reason) {
            return new Stage("SKIPPED", Map.of("reason", reason), List.of(), 0);
        }

        Stage withDuration(long nanos) {
            return new Stage(status, details, diagnostics, nanos);
        }

        boolean successful() {
            return !"FAILED".equals(status);
        }

        Map<String, Object> toMap() {
            Map<String, Object> values = new LinkedHashMap<>();
            values.put("status", status);
            values.put("durationMs", durationNanos / 1_000_000.0);
            values.put("details", details);
            values.put("diagnostics", diagnostics);
            return values;
        }
    }

    @FunctionalInterface
    private interface StageOperation {
        Stage run() throws Exception;
    }
}
