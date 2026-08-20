package dev.starsector.preflight.cli;

import dev.starsector.preflight.core.PreparedTexture;
import dev.starsector.preflight.core.PreparedTextureIO;
import dev.starsector.preflight.core.PreparedTexturePack;
import dev.starsector.preflight.core.PreparedTexturePackIO;
import dev.starsector.preflight.core.PreparedTexturePackOrderIO;
import dev.starsector.preflight.core.ResourceIndex;
import dev.starsector.preflight.core.ResourceIndexIO;
import dev.starsector.preflight.core.TextureManifestIO;
import java.io.IOException;
import java.nio.file.FileStore;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorCompletionService;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.function.LongSupplier;

/** Read-only prediction and conservative free-space gate for profile preparation. */
final class PreparationStoragePlanner {
    private static final long MIB = 1024L * 1024L;
    private static final long GIB = 1024L * MIB;
    private static final long PREDICTED_METADATA_BYTES = 32L * MIB;
    private static final long UPPER_METADATA_BYTES = 128L * MIB;
    private static final double BALANCED_ENCODED_FACTOR = 1.68;

    private PreparationStoragePlanner() {
    }

    static Plan plan(
            ResourceIndex index,
            Path cacheDirectory,
            TextureStoragePolicy storage,
            int workers) throws IOException, InterruptedException {
        Path cacheRoot = cacheDirectory.toAbsolutePath().normalize();
        Path volumePath = nearestExisting(cacheRoot);
        FileStore store = Files.getFileStore(volumePath);
        long usableBytes = store.getUsableSpace();
        return plan(index, cacheRoot, storage, workers, () -> usableBytes);
    }

    static Plan plan(
            ResourceIndex index,
            Path cacheRoot,
            TextureStoragePolicy storage,
            int workers,
            LongSupplier usableSpace) throws IOException, InterruptedException {
        long started = System.nanoTime();
        List<String> diagnostics = new ArrayList<>();
        ExecutorService executor = Executors.newFixedThreadPool(workers, runnable -> {
            Thread thread = new Thread(runnable, "preflight-storage-plan");
            thread.setDaemon(true);
            return thread;
        });
        try {
            List<TextureBatchBuilder.Candidate> candidates = TextureBatchBuilder.collectCandidates(index);
            List<TextureBatchBuilder.HashedCandidate> hashed = TextureBatchBuilder.hashCandidates(
                    candidates, diagnostics, executor, dev.starsector.preflight.core.Hashes::sha256);
            Map<TextureBatchBuilder.BlobKey, TextureBatchBuilder.HashedCandidate> unique =
                    new LinkedHashMap<>();
            for (TextureBatchBuilder.HashedCandidate candidate : hashed) {
                unique.putIfAbsent(
                        new TextureBatchBuilder.BlobKey(
                                candidate.sourceSha256(), PreparedTexture.Transformation.IDENTITY),
                        candidate);
            }

            ExecutorCompletionService<ProbeResult> completion = new ExecutorCompletionService<>(executor);
            for (Map.Entry<TextureBatchBuilder.BlobKey, TextureBatchBuilder.HashedCandidate> entry
                    : unique.entrySet()) {
                completion.submit(() -> probe(entry.getKey(), entry.getValue()));
            }

            Map<TextureBatchBuilder.BlobKey, ProbeResult> probes = new LinkedHashMap<>();
            for (int i = 0; i < unique.size(); i++) {
                try {
                    ProbeResult result = completion.take().get();
                    probes.put(result.key(), result);
                } catch (ExecutionException error) {
                    Throwable cause = error.getCause() == null ? error : error.getCause();
                    throw new IOException("Unexpected texture planning worker failure: " + cause.getMessage(), cause);
                }
            }

            long predictedLoose = 0;
            long upperLoose = 0;
            long reusableLoose = 0;
            long selectedReusableLoose = 0;
            long uniqueSourceBytes = 0;
            long uniquePixelBytes = 0;
            int supported = 0;
            int unsupported = 0;
            int failed = 0;
            Map<String, Long> predictedPackEntries = new LinkedHashMap<>();
            Map<String, Long> upperPackEntries = new LinkedHashMap<>();
            Map<TextureBatchBuilder.BlobKey, String> selectedPackPaths = new LinkedHashMap<>();

            for (Map.Entry<TextureBatchBuilder.BlobKey, TextureBatchBuilder.HashedCandidate> entry
                    : unique.entrySet()) {
                ProbeResult probe = probes.get(entry.getKey());
                uniqueSourceBytes = saturatedAdd(uniqueSourceBytes, entry.getValue().sourceBytes());
                if (probe == null || probe.failure() != null) {
                    failed++;
                    diagnostics.add("Could not size " + entry.getValue().logicalPath() + ": "
                            + (probe == null ? "no probe result" : probe.failure()));
                    continue;
                }
                if (probe.unsupported()) {
                    unsupported++;
                    continue;
                }
                supported++;
                uniquePixelBytes = saturatedAdd(uniquePixelBytes, probe.pixelBytes());
                BlobEstimate estimate = estimateBlob(
                        cacheRoot, entry.getKey(), entry.getValue().sourceBytes(), probe.pixelBytes(), storage);
                predictedLoose = saturatedAdd(predictedLoose, estimate.predictedAdditionalBytes());
                upperLoose = saturatedAdd(upperLoose, estimate.upperAdditionalBytes());
                reusableLoose = saturatedAdd(reusableLoose, estimate.reusableBytes());
                selectedReusableLoose = saturatedAdd(
                        selectedReusableLoose, estimate.selectedReusableBytes());
                predictedPackEntries.put(estimate.selectedPath(), estimate.predictedSelectedFileBytes());
                upperPackEntries.put(estimate.selectedPath(), estimate.upperSelectedFileBytes());
                selectedPackPaths.put(entry.getKey(), estimate.selectedPath());
            }

            List<String> logicalPackOrder = hashed.stream()
                    .sorted(Comparator.comparing(TextureBatchBuilder.HashedCandidate::logicalPath))
                    .map(candidate -> selectedPackPaths.get(new TextureBatchBuilder.BlobKey(
                            candidate.sourceSha256(), PreparedTexture.Transformation.IDENTITY)))
                    .filter(path -> path != null)
                    .distinct()
                    .toList();
            List<String> expectedPackOrder = preferredPackOrder(
                    cacheRoot, index.profileFingerprint(), logicalPackOrder);

            boolean packHit = false;
            long predictedPack = 0;
            long upperPack = 0;
            Path packPath = PreparedTexturePackIO.path(cacheRoot, index.profileFingerprint());
            if (!predictedPackEntries.isEmpty()) {
                if (Files.isRegularFile(packPath)) {
                    try (PreparedTexturePack existing = PreparedTexturePackIO.open(
                            packPath, index.profileFingerprint(), expectedPackOrder)) {
                        packHit = existing.hasEntryOrder(expectedPackOrder);
                    } catch (IOException ignored) {
                        // Loose content remains authoritative; preparation will replace this pack.
                    }
                }
                if (!packHit) {
                    predictedPack = PreparedTexturePackIO.estimatedFileBytes(
                            index.profileFingerprint(), predictedPackEntries);
                    upperPack = PreparedTexturePackIO.estimatedFileBytes(
                            index.profileFingerprint(), upperPackEntries);
                }
            }

            boolean profileMetadataPresent = Files.isRegularFile(ResourceIndexIO.directory(cacheRoot)
                            .resolve(index.profileFingerprint() + ".spfi"))
                    && Files.isRegularFile(TextureManifestIO.directory(cacheRoot)
                            .resolve(index.profileFingerprint() + ".spfm"));
            long predictedMetadata = profileMetadataPresent ? 0 : PREDICTED_METADATA_BYTES;
            long upperMetadata = profileMetadataPresent ? 0 : UPPER_METADATA_BYTES;
            long predictedAdditional = saturatedAdd(
                    saturatedAdd(predictedLoose, predictedPack), predictedMetadata);
            long upperAdditional = saturatedAdd(
                    saturatedAdd(upperLoose, upperPack), upperMetadata);
            long margin = Math.max(GIB, Math.min(4L * GIB, upperAdditional / 10));
            long required = saturatedAdd(upperAdditional, margin);
            long usable = Math.max(0, usableSpace.getAsLong());
            boolean complete = failed == 0;
            boolean safe = complete && usable >= required;
            String refusal = !complete
                    ? "Some winning textures could not be measured safely."
                    : usable < required
                            ? "Preparation needs up to " + humanBytes(upperAdditional)
                                    + " plus a " + humanBytes(margin) + " free-space reserve; only "
                                    + humanBytes(usable) + " is available."
                            : null;
            return new Plan(
                    index.profileFingerprint(), storage.optionValue(), cacheRoot, packPath,
                    candidates.size(), hashed.size(), unique.size(), supported, unsupported, failed,
                    uniqueSourceBytes, uniquePixelBytes, reusableLoose, selectedReusableLoose,
                    predictedLoose, upperLoose, predictedPack, upperPack,
                    predictedMetadata, upperMetadata, predictedAdditional, upperAdditional,
                    margin, required, usable, Math.max(0, usable - upperAdditional),
                    packHit, complete, safe, refusal, List.copyOf(diagnostics),
                    System.nanoTime() - started);
        } finally {
            executor.shutdownNow();
            executor.awaitTermination(30, TimeUnit.SECONDS);
        }
    }

    private static ProbeResult probe(
            TextureBatchBuilder.BlobKey key,
            TextureBatchBuilder.HashedCandidate candidate) {
        try {
            TextureBatchBuilder.Dimensions dimensions = TextureBatchBuilder.probe(candidate.source());
            long pixels = Math.multiplyExact(
                    Math.multiplyExact((long) dimensions.width(), dimensions.height()),
                    dimensions.channels());
            PreparedTextureIO.rawFileBytes(pixels);
            return ProbeResult.success(key, pixels);
        } catch (TextureBatchBuilder.UnsupportedImageException unsupported) {
            return ProbeResult.unsupported(key);
        } catch (Exception error) {
            String message = error.getMessage();
            return ProbeResult.failure(key,
                    message == null || message.isBlank() ? error.getClass().getSimpleName() : message);
        }
    }

    private static BlobEstimate estimateBlob(
            Path cacheRoot,
            TextureBatchBuilder.BlobKey key,
            long sourceBytes,
            long pixelBytes,
            TextureStoragePolicy storage) throws IOException {
        long rawFileBytes = PreparedTextureIO.rawFileBytes(pixelBytes);
        String rawPath = TextureBatchBuilder.blobRelativePath(key, PreparedTextureIO.StorageCodec.RAW);
        Path raw = cacheRoot.resolve(rawPath);
        if (storage == TextureStoragePolicy.FASTEST) {
            ExistingBlob existingRaw = existingBlob(raw, key, pixelBytes);
            if (existingRaw != null) {
                return new BlobEstimate(
                        rawPath, existingRaw.fileBytes(), rawFileBytes, 0, 0,
                        existingRaw.fileBytes(), existingRaw.fileBytes());
            }
            return new BlobEstimate(rawPath, rawFileBytes, rawFileBytes,
                    rawFileBytes, rawFileBytes, 0, 0);
        }

        String lz4Path = TextureBatchBuilder.blobRelativePath(key, PreparedTextureIO.StorageCodec.LZ4);
        Path lz4 = cacheRoot.resolve(lz4Path);
        ExistingBlob existingLz4 = existingBlob(lz4, key, pixelBytes);
        if (existingLz4 != null) {
            if (TextureBatchBuilder.compressionRatio(pixelBytes, existingLz4.storedPixelBytes())
                    >= TextureBatchBuilder.BALANCED_RAW_BELOW_RATIO) {
                return new BlobEstimate(lz4Path, existingLz4.fileBytes(), rawFileBytes,
                        0, 0, existingLz4.fileBytes(), existingLz4.fileBytes());
            }
            ExistingBlob existingRaw = existingBlob(raw, key, pixelBytes);
            if (existingRaw != null) {
                return new BlobEstimate(rawPath, existingRaw.fileBytes(), rawFileBytes,
                        0, 0, saturatedAdd(existingLz4.fileBytes(), existingRaw.fileBytes()),
                        existingRaw.fileBytes());
            }
            return new BlobEstimate(rawPath, rawFileBytes, rawFileBytes,
                    rawFileBytes, rawFileBytes, existingLz4.fileBytes(), 0);
        }

        long predictedLz4 = Math.min(rawFileBytes,
                saturatedAdd(Math.round(sourceBytes * BALANCED_ENCODED_FACTOR), 120));
        long predictedStoredPixels = Math.max(1, predictedLz4 - 120);
        boolean predictedEffective = TextureBatchBuilder.compressionRatio(pixelBytes, predictedStoredPixels)
                >= TextureBatchBuilder.BALANCED_RAW_BELOW_RATIO;
        long lz4Upper = saturatedAdd(rawFileBytes, Math.max(64, pixelBytes / 255 + 16));
        if (predictedEffective) {
            // The encoded-size heuristic affects the prediction only. The builder still has to
            // write the LZ4 candidate before measuring it and may then write a raw fallback.
            return new BlobEstimate(lz4Path, predictedLz4, rawFileBytes,
                    predictedLz4, saturatedAdd(lz4Upper, rawFileBytes), 0, 0);
        }
        // Balanced has to write the LZ4 candidate before it can prove raw is preferable.
        ExistingBlob existingRaw = existingBlob(raw, key, pixelBytes);
        long retainedReusable = existingRaw == null ? 0 : existingRaw.fileBytes();
        return new BlobEstimate(rawPath, rawFileBytes, rawFileBytes,
                saturatedAdd(predictedLz4, rawFileBytes),
                saturatedAdd(lz4Upper, rawFileBytes),
                retainedReusable, 0);
    }

    private static ExistingBlob existingBlob(
            Path path, TextureBatchBuilder.BlobKey key, long expectedPixelBytes) {
        if (!Files.isRegularFile(path)) {
            return null;
        }
        try {
            PreparedTexture texture = PreparedTextureIO.read(path);
            if (!texture.sourceSha256().equals(key.sourceSha256())
                    || texture.transformation() != key.transformation()
                    || texture.pixelBytes() != expectedPixelBytes) {
                return null;
            }
            return new ExistingBlob(Files.size(path), PreparedTextureIO.storedPixelBytes(path));
        } catch (IOException | RuntimeException ignored) {
            return null;
        }
    }

    private static List<String> preferredPackOrder(
            Path cacheRoot, String profile, List<String> logicalOrder) {
        Path sidecar = PreparedTexturePackOrderIO.path(cacheRoot, profile);
        if (!Files.isRegularFile(sidecar)) {
            return logicalOrder;
        }
        try {
            Set<String> available = Set.copyOf(logicalOrder);
            Map<String, String> availableByContent = new HashMap<>();
            Set<String> ambiguousContent = new java.util.HashSet<>();
            for (String path : logicalOrder) {
                String identity = codecIndependentBlobPath(path);
                String previous = availableByContent.putIfAbsent(identity, path);
                if (previous != null && !previous.equals(path)) {
                    ambiguousContent.add(identity);
                }
            }
            LinkedHashSet<String> ordered = new LinkedHashSet<>();
            for (String observed : PreparedTexturePackOrderIO.read(sidecar, profile)) {
                if (available.contains(observed)) {
                    ordered.add(observed);
                    continue;
                }
                String identity = codecIndependentBlobPath(observed);
                if (!ambiguousContent.contains(identity)) {
                    String equivalent = availableByContent.get(identity);
                    if (equivalent != null) {
                        ordered.add(equivalent);
                    }
                }
            }
            ordered.addAll(logicalOrder);
            return List.copyOf(ordered);
        } catch (IOException | IllegalArgumentException ignored) {
            return logicalOrder;
        }
    }

    private static String codecIndependentBlobPath(String path) {
        for (PreparedTextureIO.StorageCodec codec : PreparedTextureIO.StorageCodec.values()) {
            if (codec == PreparedTextureIO.StorageCodec.RAW) {
                continue;
            }
            String suffix = "-" + codec.suffix() + ".spft";
            if (path.endsWith(suffix)) {
                return path.substring(0, path.length() - suffix.length()) + ".spft";
            }
        }
        return path;
    }

    private static Path nearestExisting(Path path) throws IOException {
        Path candidate = path;
        while (candidate != null && !Files.exists(candidate)) {
            candidate = candidate.getParent();
        }
        if (candidate == null) {
            throw new IOException("Could not identify the cache filesystem for " + path);
        }
        return candidate;
    }

    private static long saturatedAdd(long left, long right) {
        return left > Long.MAX_VALUE - right ? Long.MAX_VALUE : left + right;
    }

    static String humanBytes(long bytes) {
        if (bytes < 1024) return bytes + " B";
        String[] units = {"KiB", "MiB", "GiB", "TiB"};
        double value = bytes;
        int unit = -1;
        do {
            value /= 1024;
            unit++;
        } while (value >= 1024 && unit < units.length - 1);
        return String.format(java.util.Locale.ROOT, value >= 10 ? "%.1f %s" : "%.2f %s", value, units[unit]);
    }

    record Plan(
            String profileFingerprint,
            String textureStorage,
            Path cacheDirectory,
            Path packPath,
            long candidateEntries,
            long hashedEntries,
            long uniqueContent,
            long supportedContent,
            long unsupportedContent,
            long failedContent,
            long uniqueSourceBytes,
            long uniquePixelBytes,
            long reusableLooseBytes,
            long selectedReusableLooseBytes,
            long predictedLooseBytes,
            long upperLooseBytes,
            long predictedPackBytes,
            long upperPackBytes,
            long predictedMetadataBytes,
            long upperMetadataBytes,
            long predictedAdditionalBytes,
            long upperBoundAdditionalBytes,
            long safetyReserveBytes,
            long requiredFreeBytes,
            long usableBytes,
            long remainingAfterUpperBoundBytes,
            boolean packHit,
            boolean complete,
            boolean safeToPrepare,
            String refusalReason,
            List<String> diagnostics,
            long durationNanos) {

        Map<String, Object> toMap() {
            Map<String, Object> values = new LinkedHashMap<>();
            values.put("format", "preflight-preparation-storage-plan-v1");
            values.put("profileFingerprint", profileFingerprint);
            values.put("textureStorage", textureStorage);
            values.put("cacheDirectory", cacheDirectory);
            values.put("packPath", packPath);
            values.put("candidateEntries", candidateEntries);
            values.put("hashedEntries", hashedEntries);
            values.put("uniqueContent", uniqueContent);
            values.put("supportedContent", supportedContent);
            values.put("unsupportedContent", unsupportedContent);
            values.put("failedContent", failedContent);
            values.put("uniqueSourceBytes", uniqueSourceBytes);
            values.put("uniquePixelBytes", uniquePixelBytes);
            values.put("reusableLooseBytes", reusableLooseBytes);
            values.put("selectedReusableLooseBytes", selectedReusableLooseBytes);
            values.put("predictedLooseBytes", predictedLooseBytes);
            values.put("upperLooseBytes", upperLooseBytes);
            values.put("predictedPackBytes", predictedPackBytes);
            values.put("upperPackBytes", upperPackBytes);
            values.put("predictedMetadataBytes", predictedMetadataBytes);
            values.put("upperMetadataBytes", upperMetadataBytes);
            values.put("predictedAdditionalBytes", predictedAdditionalBytes);
            values.put("upperBoundAdditionalBytes", upperBoundAdditionalBytes);
            values.put("safetyReserveBytes", safetyReserveBytes);
            values.put("requiredFreeBytes", requiredFreeBytes);
            values.put("usableBytes", usableBytes);
            values.put("remainingAfterUpperBoundBytes", remainingAfterUpperBoundBytes);
            values.put("packHit", packHit);
            values.put("complete", complete);
            values.put("safeToPrepare", safeToPrepare);
            values.put("refusalReason", refusalReason);
            values.put("diagnostics", diagnostics);
            values.put("durationMs", durationNanos / 1_000_000.0);
            return values;
        }
    }

    private record ProbeResult(
            TextureBatchBuilder.BlobKey key, long pixelBytes, boolean unsupported, String failure) {
        static ProbeResult success(TextureBatchBuilder.BlobKey key, long pixelBytes) {
            return new ProbeResult(key, pixelBytes, false, null);
        }

        static ProbeResult unsupported(TextureBatchBuilder.BlobKey key) {
            return new ProbeResult(key, 0, true, null);
        }

        static ProbeResult failure(TextureBatchBuilder.BlobKey key, String failure) {
            return new ProbeResult(key, 0, false, failure);
        }
    }

    private record BlobEstimate(
            String selectedPath,
            long predictedSelectedFileBytes,
            long upperSelectedFileBytes,
            long predictedAdditionalBytes,
            long upperAdditionalBytes,
            long reusableBytes,
            long selectedReusableBytes) {
    }

    private record ExistingBlob(long fileBytes, long storedPixelBytes) {
    }
}
