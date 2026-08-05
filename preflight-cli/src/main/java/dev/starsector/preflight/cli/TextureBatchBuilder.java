package dev.starsector.preflight.cli;

import dev.starsector.preflight.core.Hashes;
import dev.starsector.preflight.core.PreparedTexture;
import dev.starsector.preflight.core.PreparedTextureIO;
import dev.starsector.preflight.core.PreparedTexturePack;
import dev.starsector.preflight.core.PreparedTexturePackIO;
import dev.starsector.preflight.core.PreparedTexturePackOrderIO;
import dev.starsector.preflight.core.ResourceIndex;
import dev.starsector.preflight.core.TextureManifest;
import dev.starsector.preflight.core.TextureManifestIO;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.TreeMap;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorCompletionService;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import javax.imageio.ImageIO;
import javax.imageio.ImageReader;
import javax.imageio.stream.ImageInputStream;

final class TextureBatchBuilder {
    private static final Set<String> IMAGE_EXTENSIONS = Set.of(
            "png", "jpg", "jpeg", "bmp", "gif", "wbmp", "webp", "tga");
    private static final Set<String> IMAGE_IO_READER_EXTENSIONS = imageIoReaderExtensions();
    private static final long ESTIMATED_BUILD_BYTES_PER_PIXEL = 24L;
    private static final long ESTIMATED_BLOB_READ_MULTIPLIER = 3L;

    private TextureBatchBuilder() {
    }

    static Result build(ResourceIndex index, Path cacheDirectory, Options options)
            throws IOException, InterruptedException {
        return build(
                index,
                cacheDirectory,
                options,
                BulkTexturePreprocessor::readSnapshot,
                Hashes::sha256);
    }

    static Result build(
            ResourceIndex index,
            Path cacheDirectory,
            Options options,
            SnapshotReader snapshotReader) throws IOException, InterruptedException {
        return build(index, cacheDirectory, options, snapshotReader, Hashes::sha256);
    }

    static Result build(
            ResourceIndex index,
            Path cacheDirectory,
            Options options,
            SnapshotReader snapshotReader,
            SourceHasher sourceHasher) throws IOException, InterruptedException {
        Objects.requireNonNull(snapshotReader, "snapshotReader");
        Objects.requireNonNull(sourceHasher, "sourceHasher");
        long started = System.nanoTime();
        Path cacheRoot = cacheDirectory.toAbsolutePath().normalize();
        Files.createDirectories(cacheRoot);

        ExecutorService executor = Executors.newFixedThreadPool(options.workers(), workerFactory());
        try {
            List<String> diagnostics = new ArrayList<>();
            List<Candidate> candidates = collectCandidates(index);
            List<HashedCandidate> hashed =
                    hashCandidates(candidates, diagnostics, executor, sourceHasher);
            Map<BlobKey, List<HashedCandidate>> groups = new LinkedHashMap<>();
            for (HashedCandidate candidate : hashed) {
                groups.computeIfAbsent(
                        new BlobKey(candidate.sourceSha256(), PreparedTexture.Transformation.IDENTITY),
                        ignored -> new ArrayList<>()).add(candidate);
            }

            MemoryBudget budget = new MemoryBudget(options.memoryBudgetBytes());
            ExecutorCompletionService<BlobResult> completion = new ExecutorCompletionService<>(executor);
            for (Map.Entry<BlobKey, List<HashedCandidate>> group : groups.entrySet()) {
                completion.submit(blobTask(
                        cacheRoot,
                        group.getKey(),
                        group.getValue().get(0),
                        budget,
                        snapshotReader,
                        options.storageCodec()));
            }

            Map<BlobKey, BlobResult> blobs = new HashMap<>();
            long unexpectedFailures = 0;
            for (int i = 0; i < groups.size(); i++) {
                try {
                    BlobResult result = completion.take().get();
                    blobs.put(result.key(), result);
                    if (result.diagnostic() != null) {
                        diagnostics.add(result.diagnostic());
                    }
                } catch (ExecutionException error) {
                    unexpectedFailures++;
                    Throwable cause = error.getCause() == null ? error : error.getCause();
                    diagnostics.add("Unexpected texture worker failure: " + cause.getMessage());
                }
            }

            TreeMap<String, TextureManifest.Entry> manifestEntries = new TreeMap<>();
            long cacheHitBlobs = 0;
            long builtBlobs = 0;
            long failedBlobs = unexpectedFailures;
            long skippedUnsupportedBlobs = 0;
            long quarantinedBlobs = 0;
            long pixelBytes = 0;
            long blobBytes = 0;
            for (BlobResult result : blobs.values()) {
                if (result.success()) {
                    cacheHitBlobs += result.cacheHit() ? 1 : 0;
                    builtBlobs += result.cacheHit() ? 0 : 1;
                    quarantinedBlobs += result.quarantined() ? 1 : 0;
                    pixelBytes += result.metadata().pixelBytes();
                    blobBytes += result.blobBytes();
                } else if (result.unsupported()) {
                    skippedUnsupportedBlobs++;
                    quarantinedBlobs += result.quarantined() ? 1 : 0;
                } else {
                    failedBlobs++;
                    quarantinedBlobs += result.quarantined() ? 1 : 0;
                }
            }

            for (HashedCandidate candidate : hashed.stream()
                    .sorted(Comparator.comparing(HashedCandidate::logicalPath))
                    .toList()) {
                BlobKey key = new BlobKey(candidate.sourceSha256(), PreparedTexture.Transformation.IDENTITY);
                BlobResult result = blobs.get(key);
                if (result == null || !result.success()) {
                    continue;
                }
                TextureMetadata metadata = result.metadata();
                manifestEntries.put(candidate.logicalPath(), new TextureManifest.Entry(
                        metadata.sourceSha256(),
                        metadata.transformation(),
                        result.blobRelativePath(),
                        metadata.width(),
                        metadata.height(),
                        metadata.channels(),
                        metadata.pixelBytes()));
            }

            TextureManifest manifest = new TextureManifest(index.profileFingerprint(), manifestEntries);
            Path manifestPath = cacheRoot.resolve("manifests")
                    .resolve(index.profileFingerprint() + ".spfm");
            TextureManifestIO.write(manifestPath, manifest);
            PackResult pack = ensurePack(cacheRoot, manifest);

            long sourceBytes = hashed.stream().mapToLong(HashedCandidate::sourceBytes).sum();
            return new Result(
                    manifest,
                    manifestPath,
                    pack.path(),
                    pack.hit(),
                    pack.bytes(),
                    pack.entries(),
                    pack.durationNanos(),
                    candidates.size(),
                    hashed.size(),
                    groups.size(),
                    cacheHitBlobs,
                    builtBlobs,
                    failedBlobs,
                    skippedUnsupportedBlobs,
                    quarantinedBlobs,
                    hashed.size() - groups.size(),
                    sourceBytes,
                    pixelBytes,
                    blobBytes,
                    List.copyOf(new LinkedHashSet<>(diagnostics)),
                    System.nanoTime() - started);
        } finally {
            executor.shutdownNow();
            executor.awaitTermination(30, TimeUnit.SECONDS);
        }
    }

    private static PackResult ensurePack(Path cacheRoot, TextureManifest manifest) throws IOException {
        long started = System.nanoTime();
        List<String> logicalOrder = manifest.entries().values().stream()
                .map(TextureManifest.Entry::blobRelativePath)
                .distinct()
                .toList();
        List<String> blobs = preferredPackOrder(cacheRoot, manifest.profileFingerprint(), logicalOrder);
        Path path = PreparedTexturePackIO.path(cacheRoot, manifest.profileFingerprint());
        if (blobs.isEmpty()) {
            return new PackResult(path, false, 0, 0, System.nanoTime() - started);
        }
        if (Files.isRegularFile(path)) {
            try (PreparedTexturePack existing =
                    PreparedTexturePackIO.open(path, manifest.profileFingerprint(), blobs)) {
                if (existing.hasEntryOrder(blobs)) {
                    return new PackResult(
                            path, true, existing.fileBytes(), existing.entryCount(),
                            System.nanoTime() - started);
                }
            } catch (IOException ignored) {
                // The loose content-addressed blobs remain authoritative and rebuild the pack.
            }
        }
        PreparedTexturePackIO.write(path, manifest.profileFingerprint(), cacheRoot, blobs);
        try (PreparedTexturePack built =
                PreparedTexturePackIO.open(path, manifest.profileFingerprint(), blobs)) {
            return new PackResult(
                    path, false, built.fileBytes(), built.entryCount(),
                    System.nanoTime() - started);
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
            LinkedHashSet<String> ordered = new LinkedHashSet<>();
            for (String observed : PreparedTexturePackOrderIO.read(sidecar, profile)) {
                if (available.contains(observed)) {
                    ordered.add(observed);
                }
            }
            ordered.addAll(logicalOrder);
            return List.copyOf(ordered);
        } catch (IOException | IllegalArgumentException ignored) {
            return logicalOrder;
        }
    }

    private static List<Candidate> collectCandidates(ResourceIndex index) {
        List<Candidate> candidates = new ArrayList<>();
        for (Map.Entry<String, List<ResourceIndex.Provider>> entry : index.entries().entrySet()) {
            if (!IMAGE_EXTENSIONS.contains(extension(entry.getKey()))) {
                continue;
            }
            ResourceIndex.Provider winner = entry.getValue().get(entry.getValue().size() - 1);
            ResourceIndex.Root root = index.roots().get(winner.rootIndex());
            candidates.add(new Candidate(
                    entry.getKey(),
                    index.resolve(winner),
                    root.id(),
                    winner.size()));
        }
        return List.copyOf(candidates);
    }

    private static List<HashedCandidate> hashCandidates(
            List<Candidate> candidates,
            List<String> diagnostics,
            ExecutorService executor,
            SourceHasher sourceHasher) throws IOException, InterruptedException {
        LinkedHashMap<Path, List<Candidate>> candidatesBySource = new LinkedHashMap<>();
        for (Candidate candidate : candidates) {
            candidatesBySource
                    .computeIfAbsent(sourceKey(candidate.source()), ignored -> new ArrayList<>())
                    .add(candidate);
        }

        ExecutorCompletionService<HashResult> completion = new ExecutorCompletionService<>(executor);
        for (Path source : candidatesBySource.keySet()) {
            completion.submit(hashTask(source, sourceHasher));
        }

        Map<Path, HashResult> results = new HashMap<>();
        for (int i = 0; i < candidatesBySource.size(); i++) {
            try {
                HashResult result = completion.take().get();
                results.put(result.source(), result);
            } catch (ExecutionException error) {
                Throwable cause = error.getCause() == null ? error : error.getCause();
                throw new IOException("Unexpected texture hash worker failure: " + cause.getMessage(), cause);
            }
        }

        List<HashedCandidate> hashed = new ArrayList<>();
        for (Candidate candidate : candidates) {
            HashResult result = results.get(sourceKey(candidate.source()));
            if (result == null) {
                throw new IOException("Texture hash worker produced no result for " + candidate.source());
            }
            if (result.sourceSha256() == null) {
                diagnostics.add("Could not hash " + candidate.logicalPath() + " from "
                        + candidate.rootId() + ": " + result.errorMessage());
                continue;
            }
            hashed.add(new HashedCandidate(
                    candidate.logicalPath(),
                    candidate.source(),
                    candidate.rootId(),
                    candidate.sourceBytes(),
                    result.sourceSha256()));
        }
        return List.copyOf(hashed);
    }

    private static Callable<HashResult> hashTask(Path source, SourceHasher sourceHasher) {
        return () -> {
            try {
                String hash = Objects.requireNonNull(sourceHasher.hash(source), "source hash");
                Hashes.decodeSha256(hash);
                return HashResult.success(source, hash.toLowerCase(Locale.ROOT));
            } catch (Exception error) {
                String message = error.getMessage();
                if (message == null || message.isBlank()) {
                    message = error.getClass().getSimpleName();
                }
                return HashResult.failure(source, message);
            }
        };
    }

    private static Path sourceKey(Path source) {
        return source.toAbsolutePath().normalize();
    }

    private static Callable<BlobResult> blobTask(
            Path cacheRoot,
            BlobKey key,
            HashedCandidate representative,
            MemoryBudget budget,
            SnapshotReader snapshotReader,
            PreparedTextureIO.StorageCodec storageCodec) {
        return () -> {
            String relative = blobRelativePath(key, storageCodec);
            Path blob = cacheRoot.resolve(relative).normalize();
            if (!blob.startsWith(cacheRoot)) {
                return BlobResult.failure(key, relative, false, "Blob path escaped the cache root");
            }

            // Format policy is part of cache eligibility, not merely build eligibility. Check it
            // before accepting an older blob so tightening the fidelity boundary cannot reuse an
            // artifact produced under the previous policy.
            if ("webp".equals(extension(representative.logicalPath()))) {
                try (ImageInputStream input = ImageIO.createImageInputStream(representative.source().toFile())) {
                    if (input == null) {
                        throw new IOException("ImageIO could not open the source");
                    }
                    requireExactlyDecodableWebp(input);
                } catch (UnsupportedImageException error) {
                    return BlobResult.unsupported(
                            key,
                            relative,
                            false,
                            "Skipped unsupported texture " + representative.logicalPath() + ": " + error.getMessage());
                }
            }

            boolean quarantined = false;
            if (Files.isRegularFile(blob)) {
                long blobSize = Files.size(blob);
                // Checked LZ4 validation temporarily owns the encoded file, checked payload and
                // decoded pixels. The compression ratio is intentionally unbounded, so serialize
                // these preparation-only reads inside the declared budget instead of estimating
                // decoded memory from compressed bytes and risking concurrent high-ratio blobs.
                long estimatedReadBytes = storageCodec == PreparedTextureIO.StorageCodec.LZ4
                        ? budget.maximum()
                        : saturatedMultiply(blobSize, ESTIMATED_BLOB_READ_MULTIPLIER);
                long reservation = budget.acquire(estimatedReadBytes);
                try {
                    PreparedTexture existing = PreparedTextureIO.read(blob);
                    if (existing.sourceSha256().equals(key.sourceSha256())
                            && existing.transformation() == key.transformation()) {
                        return BlobResult.success(
                                key,
                                relative,
                                metadata(existing),
                                true,
                                false,
                                blobSize);
                    }
                    quarantined = quarantine(cacheRoot, blob, "identity-mismatch");
                } catch (IOException error) {
                    quarantined = quarantine(cacheRoot, blob, "corrupt");
                } finally {
                    budget.release(reservation);
                }
            }

            long encodedBytes;
            try {
                encodedBytes = Files.size(representative.source());
            } catch (IOException error) {
                return BlobResult.failure(
                        key,
                        relative,
                        quarantined,
                        "Could not size " + representative.logicalPath() + ": " + error.getMessage());
            }
            if (encodedBytes > budget.maximum()) {
                return BlobResult.failure(
                        key,
                        relative,
                        quarantined,
                        "Encoded source " + representative.logicalPath() + " is " + encodedBytes
                                + " bytes, exceeding the texture worker memory budget of "
                                + budget.maximum() + " bytes");
            }

            long estimatedBuildBytes;
            try {
                Dimensions dimensions = probe(representative.source());
                estimatedBuildBytes = Math.multiplyExact(
                        Math.multiplyExact((long) dimensions.width(), dimensions.height()),
                        ESTIMATED_BUILD_BYTES_PER_PIXEL);
            } catch (UnsupportedImageException error) {
                return BlobResult.unsupported(
                        key,
                        relative,
                        quarantined,
                        "Skipped unsupported texture " + representative.logicalPath() + ": " + error.getMessage());
            } catch (Exception error) {
                return BlobResult.failure(
                        key,
                        relative,
                        quarantined,
                        "Could not probe " + representative.logicalPath() + ": " + error.getMessage());
            }

            long reservation = budget.acquire(saturatedAdd(estimatedBuildBytes, encodedBytes));
            try {
                byte[] encoded = snapshotReader.read(
                        representative.source(), encodedBytes, budget.maximum());
                if (encoded.length != encodedBytes) {
                    return BlobResult.failure(
                            key,
                            relative,
                            quarantined,
                            "Source size changed while snapshotting " + representative.logicalPath());
                }
                PreparedTexture texture = BulkTexturePreprocessor.prepareSnapshot(
                        encoded,
                        key.sourceSha256(),
                        key.transformation());
                PreparedTextureIO.write(blob, texture, storageCodec);
                return BlobResult.success(
                        key,
                        relative,
                        metadata(texture),
                        false,
                        quarantined,
                        Files.size(blob));
            } catch (Exception error) {
                return BlobResult.failure(
                        key,
                        relative,
                        quarantined,
                        "Could not prepare " + representative.logicalPath() + ": " + error.getMessage());
            } finally {
                budget.release(reservation);
            }
        };
    }

    private static TextureMetadata metadata(PreparedTexture texture) {
        return new TextureMetadata(
                texture.sourceSha256(),
                texture.transformation(),
                texture.uploadWidth(),
                texture.uploadHeight(),
                texture.channels(),
                texture.pixelBytes());
    }

    private static Dimensions probe(Path source) throws IOException {
        try (ImageInputStream input = ImageIO.createImageInputStream(source.toFile())) {
            if (input == null) {
                throw new IOException("ImageIO could not open the source");
            }
            requireExactlyDecodableWebp(input);
            var readers = ImageIO.getImageReaders(input);
            if (!readers.hasNext()) {
                String sourceExtension = extension(source.getFileName().toString());
                if (IMAGE_IO_READER_EXTENSIONS.contains(sourceExtension)) {
                    throw new IOException("No ImageIO reader accepted the encoded source");
                }
                throw new UnsupportedImageException("No ImageIO reader is available");
            }
            ImageReader reader = readers.next();
            try {
                reader.setInput(input, true, true);
                int width = reader.getWidth(0);
                int height = reader.getHeight(0);
                if (width <= 0 || height <= 0) {
                    throw new IOException("Image dimensions are invalid");
                }
                return new Dimensions(width, height);
            } finally {
                reader.dispose();
            }
        }
    }

    /**
     * The pure-Java and game-native readers are pixel-identical for simple lossless VP8L files.
     * They are not identical for the reviewed extended lossy-alpha file, so those stay on the
     * game's authoritative decoder instead of being baked into the exact prepared cache.
     */
    private static void requireExactlyDecodableWebp(ImageInputStream input) throws IOException {
        long position = input.getStreamPosition();
        byte[] header = new byte[16];
        int offset = 0;
        while (offset < header.length) {
            int read = input.read(header, offset, header.length - offset);
            if (read < 0) {
                break;
            }
            offset += read;
        }
        input.seek(position);
        boolean webp = offset >= 12
                && header[0] == 'R' && header[1] == 'I' && header[2] == 'F' && header[3] == 'F'
                && header[8] == 'W' && header[9] == 'E' && header[10] == 'B' && header[11] == 'P';
        boolean simpleLossless = offset >= 16
                && header[12] == 'V' && header[13] == 'P' && header[14] == '8' && header[15] == 'L';
        if (webp && !simpleLossless) {
            throw new UnsupportedImageException(
                    "Only simple lossless VP8L WebP is pixel-identical to the game decoder");
        }
    }

    private static boolean quarantine(Path cacheRoot, Path blob, String reason) {
        try {
            Path directory = cacheRoot.resolve("quarantine");
            Files.createDirectories(directory);
            String name = blob.getFileName() + "." + reason + "." + Instant.now().toEpochMilli();
            Files.move(blob, directory.resolve(name), StandardCopyOption.REPLACE_EXISTING);
            return true;
        } catch (IOException error) {
            try {
                Files.deleteIfExists(blob);
                return true;
            } catch (IOException ignored) {
                return false;
            }
        }
    }

    private static long saturatedAdd(long left, long right) {
        if (left > Long.MAX_VALUE - right) {
            return Long.MAX_VALUE;
        }
        return left + right;
    }

    private static long saturatedMultiply(long value, long multiplier) {
        if (value <= 0) {
            return 1;
        }
        if (value > Long.MAX_VALUE / multiplier) {
            return Long.MAX_VALUE;
        }
        return value * multiplier;
    }

    private static String blobRelativePath(BlobKey key, PreparedTextureIO.StorageCodec storageCodec) {
        String suffix = key.transformation().name().toLowerCase(Locale.ROOT).replace('_', '-');
        String codecSuffix = storageCodec == PreparedTextureIO.StorageCodec.RAW
                ? ""
                : "-" + storageCodec.suffix();
        return "blobs/" + key.sourceSha256().substring(0, 2) + "/"
                + key.sourceSha256() + "-" + suffix + codecSuffix + ".spft";
    }

    private static String extension(String path) {
        int slash = path.lastIndexOf('/');
        int dot = path.lastIndexOf('.');
        return dot <= slash || dot == path.length() - 1
                ? ""
                : path.substring(dot + 1).toLowerCase(Locale.ROOT);
    }

    private static Set<String> imageIoReaderExtensions() {
        Set<String> extensions = new LinkedHashSet<>();
        for (String suffix : ImageIO.getReaderFileSuffixes()) {
            extensions.add(suffix.toLowerCase(Locale.ROOT));
        }
        return Set.copyOf(extensions);
    }

    private static ThreadFactory workerFactory() {
        AtomicInteger counter = new AtomicInteger();
        return task -> {
            Thread thread = new Thread(task, "Preflight-Texture-" + counter.incrementAndGet());
            thread.setDaemon(true);
            return thread;
        };
    }

    record Options(
            int workers,
            long memoryBudgetBytes,
            PreparedTextureIO.StorageCodec storageCodec) {
        Options(int workers, long memoryBudgetBytes) {
            this(workers, memoryBudgetBytes, PreparedTextureIO.StorageCodec.RAW);
        }

        Options {
            if (workers < 1 || workers > 64) {
                throw new IllegalArgumentException("Texture workers must be between 1 and 64");
            }
            if (memoryBudgetBytes < 16L * 1024 * 1024) {
                throw new IllegalArgumentException("Texture memory budget must be at least 16 MiB");
            }
            Objects.requireNonNull(storageCodec, "storageCodec");
        }
    }

    record Result(
            TextureManifest manifest,
            Path manifestPath,
            Path packPath,
            boolean packHit,
            long packBytes,
            int packedBlobs,
            long packDurationNanos,
            long candidateEntries,
            long hashedEntries,
            long uniqueContent,
            long cacheHitBlobs,
            long builtBlobs,
            long failedBlobs,
            long skippedUnsupportedBlobs,
            long quarantinedBlobs,
            long deduplicatedEntries,
            long sourceBytes,
            long uniquePixelBytes,
            long uniqueBlobBytes,
            List<String> diagnostics,
            long durationNanos) {
        double durationMillis() {
            return durationNanos / 1_000_000.0;
        }

        double packDurationMillis() {
            return packDurationNanos / 1_000_000.0;
        }
    }

    private record PackResult(
            Path path, boolean hit, long bytes, int entries, long durationNanos) {
    }

    @FunctionalInterface
    interface SnapshotReader {
        byte[] read(Path source, long expectedBytes, long maximumBytes) throws IOException;
    }

    @FunctionalInterface
    interface SourceHasher {
        String hash(Path source) throws IOException;
    }

    private record Candidate(String logicalPath, Path source, String rootId, long sourceBytes) {
    }

    private record HashedCandidate(
            String logicalPath,
            Path source,
            String rootId,
            long sourceBytes,
            String sourceSha256) {
    }

    private record HashResult(Path source, String sourceSha256, String errorMessage) {
        static HashResult success(Path source, String sourceSha256) {
            return new HashResult(source, sourceSha256, null);
        }

        static HashResult failure(Path source, String errorMessage) {
            return new HashResult(source, null, errorMessage);
        }
    }

    private record BlobKey(String sourceSha256, PreparedTexture.Transformation transformation) {
    }

    private record Dimensions(int width, int height) {
    }

    private record TextureMetadata(
            String sourceSha256,
            PreparedTexture.Transformation transformation,
            int width,
            int height,
            int channels,
            int pixelBytes) {
    }

    private record BlobResult(
            BlobKey key,
            String blobRelativePath,
            TextureMetadata metadata,
            boolean success,
            boolean unsupported,
            boolean cacheHit,
            boolean quarantined,
            long blobBytes,
            String diagnostic) {
        static BlobResult success(
                BlobKey key,
                String relative,
                TextureMetadata metadata,
                boolean cacheHit,
                boolean quarantined,
                long blobBytes) {
            return new BlobResult(key, relative, metadata, true, false, cacheHit, quarantined, blobBytes, null);
        }

        static BlobResult failure(BlobKey key, String relative, boolean quarantined, String diagnostic) {
            return new BlobResult(key, relative, null, false, false, false, quarantined, 0, diagnostic);
        }

        static BlobResult unsupported(BlobKey key, String relative, boolean quarantined, String diagnostic) {
            return new BlobResult(key, relative, null, false, true, false, quarantined, 0, diagnostic);
        }
    }

    private static final class UnsupportedImageException extends IOException {
        UnsupportedImageException(String message) {
            super(message);
        }
    }

    private static final class MemoryBudget {
        private final long maximum;
        private long used;

        MemoryBudget(long maximum) {
            this.maximum = maximum;
        }

        synchronized long maximum() {
            return maximum;
        }

        synchronized long acquire(long requested) throws InterruptedException {
            long reservation = Math.max(1, Math.min(maximum, requested));
            while (reservation > maximum - used) {
                wait();
            }
            used += reservation;
            return reservation;
        }

        synchronized void release(long reservation) {
            used -= reservation;
            notifyAll();
        }
    }
}
