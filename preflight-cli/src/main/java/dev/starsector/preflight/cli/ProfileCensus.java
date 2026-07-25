package dev.starsector.preflight.cli;

import dev.starsector.preflight.core.ImageHeaderReader;
import dev.starsector.preflight.core.Json;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.BasicFileAttributes;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.OptionalInt;
import java.util.OptionalLong;
import java.util.PriorityQueue;
import java.util.Set;

final class ProfileCensus {
    private static final Set<String> IMAGE_EXTENSIONS = Set.of("png", "jpg", "jpeg", "webp", "bmp", "gif", "tga");
    private static final Set<String> SOUND_EXTENSIONS = Set.of("ogg", "wav", "mp3", "flac");
    private static final Set<String> DATA_EXTENSIONS = Set.of(
            "csv", "json", "faction", "variant", "ship", "skin", "weapon", "wpn", "proj", "system", "rules", "xml");
    private static final int LARGEST_LIMIT = 25;
    private static final int DUPLICATE_SAMPLE_LIMIT = 100;

    private ProfileCensus() {
    }

    static Result scan(Path installRoot) throws IOException {
        return scan(installRoot, Options.none());
    }

    static Result scan(Path installRoot, Options options) throws IOException {
        long scanStarted = System.nanoTime();
        GameLayout layout = GameLayout.locate(installRoot);
        List<String> diagnostics = new ArrayList<>(layout.diagnostics());
        String enabledJson = Files.readString(layout.enabledModsFile(), StandardCharsets.UTF_8);
        List<String> enabledIds = JsonText.stringArray(enabledJson, "enabledMods");
        if (enabledIds.isEmpty()) {
            diagnostics.add("enabled_mods.json contains no enabled mod IDs");
        }

        Map<String, Path> directoriesById = discoverModDirectories(layout.modsDirectory(), diagnostics);
        List<ResolvedMod> mods = new ArrayList<>();
        List<String> missing = new ArrayList<>();
        for (int i = 0; i < enabledIds.size(); i++) {
            String id = enabledIds.get(i);
            Path directory = directoriesById.get(id);
            if (directory == null) {
                missing.add(id);
                diagnostics.add("Enabled mod directory not found for ID: " + id);
            } else {
                mods.add(new ResolvedMod(id, directory, i));
            }
        }

        ScanAccumulator accumulator = new ScanAccumulator(layout, enabledIds, diagnostics);
        for (ResolvedMod mod : mods) {
            accumulator.scanMod(mod);
        }
        return accumulator.finish(mods, missing, System.nanoTime() - scanStarted, options);
    }

    /**
     * Opt-in analyses layered on top of the plain inventory. All-empty is the default and leaves the
     * report byte-identical to a bare scan, so adding a knob never perturbs an existing consumer.
     *
     * @param vramBudgetBytes grade the decoded working set against this budget
     * @param maxTextureSizePixels project what capping every winning texture's long edge would save
     */
    record Options(OptionalLong vramBudgetBytes, OptionalInt maxTextureSizePixels) {
        Options {
            // A non-positive cap is meaningless and would make the halving search shift past 31,
            // where Java masks the shift count and the loop never terminates.
            if (maxTextureSizePixels.isPresent() && maxTextureSizePixels.getAsInt() < 1) {
                throw new IllegalArgumentException(
                        "maxTextureSizePixels must be at least 1: " + maxTextureSizePixels.getAsInt());
            }
        }

        static Options none() {
            return new Options(OptionalLong.empty(), OptionalInt.empty());
        }

        static Options vramBudget(long bytes) {
            return new Options(OptionalLong.of(bytes), OptionalInt.empty());
        }
    }

    private static Map<String, Path> discoverModDirectories(Path modsDirectory, List<String> diagnostics) throws IOException {
        Map<String, Path> byId = new LinkedHashMap<>();
        try (var entries = Files.list(modsDirectory)) {
            for (Path directory : entries.filter(Files::isDirectory).sorted().toList()) {
                Path info = directory.resolve("mod_info.json");
                String id = null;
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
                Path prior = byId.putIfAbsent(id, directory.toAbsolutePath().normalize());
                if (prior != null) {
                    diagnostics.add("Duplicate mod ID " + id + " in " + prior + " and " + directory);
                }
            }
        }
        return byId;
    }

    record Result(Map<String, Object> values) {
        String toJson() {
            return Json.object(values);
        }
    }

    private record ResolvedMod(String id, Path directory, int order) {
    }

    private record Asset(String modId, String logicalPath, long bytes) {
        Map<String, Object> toMap() {
            Map<String, Object> values = new LinkedHashMap<>();
            values.put("modId", modId);
            values.put("path", logicalPath);
            values.put("bytes", bytes);
            return values;
        }
    }

    private record Provider(String modId, int order) {
    }

    /** The override-winning image at one logical path: the order that won it and its header facts. */
    private record WinnerImage(
            int order,
            String modId,
            String logicalPath,
            Optional<ImageHeaderReader.ImageDimensions> dimensions) {

        long decodedBytes() {
            return dimensions.map(ImageHeaderReader.ImageDimensions::decodedBytes).orElse(0L);
        }

        boolean measured() {
            return dimensions.isPresent();
        }
    }

    /** One texture's projected cost if its long edge were capped. Reported largest saving first. */
    private record Reduction(
            String modId,
            String logicalPath,
            ImageHeaderReader.ImageDimensions current,
            int projectedWidth,
            int projectedHeight) {

        long projectedBytes() {
            return (long) projectedWidth * (long) projectedHeight * (long) current.channels();
        }

        long savedBytes() {
            return current.decodedBytes() - projectedBytes();
        }

        Map<String, Object> toMap() {
            Map<String, Object> values = new LinkedHashMap<>();
            values.put("modId", modId);
            values.put("path", logicalPath);
            values.put("channels", current.channels());
            values.put("width", current.width());
            values.put("height", current.height());
            values.put("decodedBytes", current.decodedBytes());
            values.put("projectedWidth", projectedWidth);
            values.put("projectedHeight", projectedHeight);
            values.put("projectedDecodedBytes", projectedBytes());
            values.put("savedBytes", savedBytes());
            return values;
        }
    }

    private static final class ModStats {
        final String id;
        final Path directory;
        long files;
        long bytes;
        long imageFiles;
        long imageBytes;
        long decodedImageBytes;
        long measuredImageFiles;
        long unmeasuredImageFiles;
        long soundFiles;
        long soundBytes;
        long looseJavaFiles;
        long looseJavaBytes;
        long jarFiles;
        long jarBytes;
        long dataFiles;
        long dataBytes;

        ModStats(String id, Path directory) {
            this.id = id;
            this.directory = directory;
        }

        Map<String, Object> toMap() {
            Map<String, Object> values = new LinkedHashMap<>();
            values.put("id", id);
            values.put("directory", directory);
            values.put("files", files);
            values.put("bytes", bytes);
            values.put("imageFiles", imageFiles);
            values.put("imageBytes", imageBytes);
            values.put("decodedImageBytes", decodedImageBytes);
            values.put("measuredImageFiles", measuredImageFiles);
            values.put("unmeasuredImageFiles", unmeasuredImageFiles);
            values.put("soundFiles", soundFiles);
            values.put("soundBytes", soundBytes);
            values.put("looseJavaFiles", looseJavaFiles);
            values.put("looseJavaBytes", looseJavaBytes);
            values.put("jarFiles", jarFiles);
            values.put("jarBytes", jarBytes);
            values.put("dataFiles", dataFiles);
            values.put("dataBytes", dataBytes);
            return values;
        }
    }

    private static final class ScanAccumulator {
        private final GameLayout layout;
        private final List<String> enabledIds;
        private final List<String> diagnostics;
        private final Map<String, long[]> extensionTotals = new HashMap<>();
        private final Map<String, List<Provider>> providersByLogicalPath = new HashMap<>();
        private final Map<String, WinnerImage> winnerByPath = new HashMap<>();
        private final List<ModStats> modStats = new ArrayList<>();
        private final PriorityQueue<Asset> largest = new PriorityQueue<>(
                Comparator.comparingLong(Asset::bytes)
                        .thenComparing(Asset::modId)
                        .thenComparing(Asset::logicalPath));
        private final MessageDigest profileDigest = sha256();
        private long totalFiles;
        private long totalBytes;
        private long imageFiles;
        private long imageBytes;
        private long decodedImageBytes;
        private long measuredImageFiles;
        private long unmeasuredImageFiles;
        private long soundFiles;
        private long soundBytes;
        private long looseJavaFiles;
        private long looseJavaBytes;
        private long jarFiles;
        private long jarBytes;
        private long dataFiles;
        private long dataBytes;

        ScanAccumulator(GameLayout layout, List<String> enabledIds, List<String> diagnostics) {
            this.layout = layout;
            this.enabledIds = List.copyOf(enabledIds);
            this.diagnostics = diagnostics;
            updateDigest("preflight-profile-v1");
            for (String id : enabledIds) {
                updateDigest(id);
            }
        }

        void scanMod(ResolvedMod mod) throws IOException {
            ModStats stats = new ModStats(mod.id(), mod.directory());
            modStats.add(stats);
            updateDigest("mod");
            updateDigest(mod.id());
            updateDigest(Integer.toString(mod.order()));
            scanDirectory(mod, stats, mod.directory(), new LinkedHashSet<>());
        }

        private void scanDirectory(
                ResolvedMod mod,
                ModStats stats,
                Path directory,
                Set<Path> visitedDirectories) throws IOException {
            Path realDirectory;
            try {
                realDirectory = directory.toRealPath();
            } catch (IOException error) {
                diagnostics.add("Could not resolve " + directory + ": " + error.getMessage());
                return;
            }
            if (!visitedDirectories.add(realDirectory)) {
                diagnostics.add("Skipped directory cycle or duplicate link at " + directory);
                return;
            }

            List<Path> entries;
            try (var stream = Files.list(directory)) {
                entries = stream.sorted(Comparator.comparing(path -> path.getFileName().toString())).toList();
            } catch (IOException error) {
                diagnostics.add("Could not inspect " + directory + ": " + error.getMessage());
                return;
            }

            for (Path entry : entries) {
                try {
                    if (Files.isDirectory(entry)) {
                        scanDirectory(mod, stats, entry, visitedDirectories);
                    } else if (Files.isRegularFile(entry)) {
                        BasicFileAttributes attributes = Files.readAttributes(entry, BasicFileAttributes.class);
                        recordFile(mod, stats, entry, attributes);
                    }
                } catch (IOException error) {
                    diagnostics.add("Could not inspect " + entry + ": " + error.getMessage());
                }
            }
        }

        private void recordFile(ResolvedMod mod, ModStats stats, Path file, BasicFileAttributes attributes) {
            String logicalPath = normalize(mod.directory().relativize(file));
            long bytes = attributes.size();
            long modified = attributes.lastModifiedTime().toMillis();
            totalFiles++;
            totalBytes += bytes;
            stats.files++;
            stats.bytes += bytes;
            updateDigest(logicalPath);
            updateDigest(Long.toString(bytes));
            updateDigest(Long.toString(modified));

            if (!logicalPath.equalsIgnoreCase("mod_info.json")) {
                providersByLogicalPath
                        .computeIfAbsent(logicalPath.toLowerCase(Locale.ROOT), ignored -> new ArrayList<>())
                        .add(new Provider(mod.id(), mod.order()));
            }
            addLargest(new Asset(mod.id(), logicalPath, bytes));
            String extension = extension(logicalPath);
            classify(stats, extension, bytes);
            if (IMAGE_EXTENSIONS.contains(extension)) {
                recordDecodedImage(mod, stats, file, logicalPath);
            }
        }

        /**
         * Adds an image's exact decoded (VRAM) footprint to the per-mod and profile totals. Reads
         * dimensions from the header only. Unreadable or unsupported formats are counted as
         * unmeasured rather than guessed, so the decoded total stays an exact floor. This does not
         * feed the profile fingerprint — it is pure read-only accounting.
         *
         * <p>The all-providers totals ({@code decodedImageBytes}) count every enabled image, which
         * over-counts overridden paths. In parallel this records the winning provider per logical
         * path (highest enabled order) so {@link #finish} can also report the tighter
         * override-resolved working set — closer to what actually loads at each path.
         */
        private void recordDecodedImage(ResolvedMod mod, ModStats stats, Path file, String logicalPath) {
            Optional<ImageHeaderReader.ImageDimensions> dimensions;
            try {
                dimensions = ImageHeaderReader.read(file);
            } catch (IOException error) {
                dimensions = Optional.empty();
            }
            recordWinner(mod, logicalPath, dimensions);
            if (dimensions.isPresent()) {
                long decoded = dimensions.get().decodedBytes();
                stats.decodedImageBytes += decoded;
                stats.measuredImageFiles++;
                decodedImageBytes += decoded;
                measuredImageFiles++;
            } else {
                stats.unmeasuredImageFiles++;
                unmeasuredImageFiles++;
            }
        }

        /**
         * Keeps only the highest-enabled-order provider for each logical path — the override winner,
         * the one the game actually loads. Mods are scanned in ascending enabled order, but this
         * guards on {@code order} explicitly so it does not depend on that.
         */
        private void recordWinner(
                ResolvedMod mod,
                String logicalPath,
                Optional<ImageHeaderReader.ImageDimensions> dims) {
            String key = logicalPath.toLowerCase(Locale.ROOT);
            WinnerImage existing = winnerByPath.get(key);
            if (existing == null || mod.order() >= existing.order()) {
                winnerByPath.put(key, new WinnerImage(mod.order(), mod.id(), logicalPath, dims));
            }
        }

        Result finish(List<ResolvedMod> mods, List<String> missing, long scanNanos, Options options) {
            List<Map.Entry<String, List<Provider>>> duplicateEntries = providersByLogicalPath.entrySet().stream()
                    .filter(entry -> entry.getValue().size() > 1)
                    .sorted(Map.Entry.comparingByKey())
                    .toList();
            long duplicateLogicalPaths = duplicateEntries.size();
            long duplicateProviderEntries = duplicateEntries.stream()
                    .mapToLong(entry -> entry.getValue().size() - 1L)
                    .sum();
            List<Map<String, Object>> duplicateSamples = new ArrayList<>();
            for (Map.Entry<String, List<Provider>> entry : duplicateEntries.stream()
                    .limit(DUPLICATE_SAMPLE_LIMIT)
                    .toList()) {
                List<Provider> sorted = entry.getValue().stream()
                        .sorted(Comparator.comparingInt(Provider::order))
                        .toList();
                Map<String, Object> sample = new LinkedHashMap<>();
                sample.put("path", entry.getKey());
                sample.put("providers", sorted.stream().map(Provider::modId).toList());
                sample.put("probableWinner", sorted.get(sorted.size() - 1).modId());
                duplicateSamples.add(sample);
            }

            List<Map<String, Object>> modsByOrder = modStats.stream().map(ModStats::toMap).toList();
            List<Map<String, Object>> largestMods = modStats.stream()
                    .sorted(Comparator.comparingLong((ModStats stats) -> stats.bytes).reversed()
                            .thenComparing(stats -> stats.id))
                    .map(ModStats::toMap)
                    .toList();
            // Which mods actually cost the most VRAM once decoded — distinct from on-disk size,
            // since compression ratios vary wildly between mods.
            List<Map<String, Object>> largestDecodedMods = modStats.stream()
                    .filter(stats -> stats.decodedImageBytes > 0)
                    .sorted(Comparator.comparingLong((ModStats stats) -> stats.decodedImageBytes).reversed()
                            .thenComparing(stats -> stats.id))
                    .map(ModStats::toMap)
                    .toList();
            List<Map<String, Object>> largestAssets = largest.stream()
                    .sorted(Comparator.comparingLong(Asset::bytes).reversed()
                            .thenComparing(Asset::modId)
                            .thenComparing(Asset::logicalPath))
                    .map(Asset::toMap)
                    .toList();

            Map<String, Object> totals = new LinkedHashMap<>();
            totals.put("files", totalFiles);
            totals.put("bytes", totalBytes);
            totals.put("imageFiles", imageFiles);
            totals.put("imageBytes", imageBytes);
            totals.put("decodedImageBytes", decodedImageBytes);
            totals.put("measuredImageFiles", measuredImageFiles);
            totals.put("unmeasuredImageFiles", unmeasuredImageFiles);
            totals.put("soundFiles", soundFiles);
            totals.put("soundBytes", soundBytes);
            totals.put("looseJavaFiles", looseJavaFiles);
            totals.put("looseJavaBytes", looseJavaBytes);
            totals.put("jarFiles", jarFiles);
            totals.put("jarBytes", jarBytes);
            totals.put("dataFiles", dataFiles);
            totals.put("dataBytes", dataBytes);

            Map<String, Object> extensionReport = new LinkedHashMap<>();
            extensionTotals.entrySet().stream().sorted(Map.Entry.comparingByKey()).forEach(entry -> {
                Map<String, Object> values = new LinkedHashMap<>();
                values.put("files", entry.getValue()[0]);
                values.put("bytes", entry.getValue()[1]);
                extensionReport.put(entry.getKey(), values);
            });

            Map<String, Object> values = new LinkedHashMap<>();
            values.put("generatedAt", Instant.now());
            values.put("scanDurationMs", scanNanos / 1_000_000.0);
            values.put("installRoot", layout.installRoot());
            values.put("modsDirectory", layout.modsDirectory());
            values.put("enabledModsFile", layout.enabledModsFile());
            values.put("fingerprintKind", "ordered-path-size-mtime-v1");
            values.put("profileFingerprint", HexFormat.of().formatHex(profileDigest.digest()));
            values.put("enabledModIds", enabledIds);
            values.put("resolvedModCount", mods.size());
            values.put("missingModIds", missing);
            values.put("totals", totals);
            values.put("extensions", extensionReport);
            values.put("mods", modsByOrder);
            values.put("largestMods", largestMods);
            values.put("largestDecodedMods", largestDecodedMods);
            long winnerDecodedImageBytes = 0;
            long winnerMeasuredImageFiles = 0;
            long winnerUnmeasuredImageFiles = 0;
            for (WinnerImage winner : winnerByPath.values()) {
                if (winner.measured()) {
                    winnerDecodedImageBytes += winner.decodedBytes();
                    winnerMeasuredImageFiles++;
                } else {
                    winnerUnmeasuredImageFiles++;
                }
            }

            Map<String, Object> decodedWorkingSet = new LinkedHashMap<>();
            decodedWorkingSet.put("decodedImageBytes", decodedImageBytes);
            decodedWorkingSet.put("measuredImageFiles", measuredImageFiles);
            decodedWorkingSet.put("unmeasuredImageFiles", unmeasuredImageFiles);
            // Override-resolved: only the winning provider at each logical path, i.e. what the game
            // actually loads there. Equals the all-providers total when no paths collide.
            decodedWorkingSet.put("winnerDecodedImageBytes", winnerDecodedImageBytes);
            decodedWorkingSet.put("winnerMeasuredImageFiles", winnerMeasuredImageFiles);
            decodedWorkingSet.put("winnerUnmeasuredImageFiles", winnerUnmeasuredImageFiles);
            decodedWorkingSet.put("basis",
                    "exact width*height*channels from image headers; unmeasured formats excluded; "
                            + "winner* fields resolve override collisions to the loaded provider");
            // Grade against the override-resolved floor — the truthful "what loads" figure.
            long winnerFloor = winnerDecodedImageBytes;
            options.vramBudgetBytes().ifPresent(budget ->
                    decodedWorkingSet.put("budgetVerdict", budgetVerdict(winnerFloor, budget)));
            options.maxTextureSizePixels().ifPresent(cap -> decodedWorkingSet.put(
                    "reductionPlan", reductionPlan(cap, winnerFloor, options.vramBudgetBytes())));
            values.put("decodedWorkingSet", decodedWorkingSet);
            values.put("largestAssets", largestAssets);
            values.put("overrideSemantics", "probable-enabled-order-only");
            values.put("duplicateLogicalPaths", duplicateLogicalPaths);
            values.put("duplicateProviderEntries", duplicateProviderEntries);
            values.put("duplicateSamples", duplicateSamples);
            values.put("diagnostics", List.copyOf(new LinkedHashSet<>(diagnostics)));
            return new Result(values);
        }

        /**
         * Projects what capping every override-winning texture's long edge at {@code maxTextureSize}
         * would cost instead — the "what do I actually cut" half of a budget verdict, which on its own
         * only says how far over you are.
         *
         * <p>The modelled reduction is repeated exact halving: the long edge is halved until it fits
         * the cap. A 2x2 box reduction is the one resize that is exact (each output pixel is the mean
         * of four inputs), preserves power-of-two dimensions, and matches how the GPU builds mip
         * levels — so each step divides a texture's decoded cost by exactly four and the projection is
         * arithmetic, not an estimate. It is deliberately conservative: with a 2048 cap a 3000-pixel
         * edge halves once, to 1500, rather than resampling to exactly 2048.
         *
         * <p>This projects memory only. Whether shrinking a given texture is visually acceptable is a
         * judgement the operator makes; nothing here rewrites an asset.
         */
        private Map<String, Object> reductionPlan(int maxTextureSize, long currentFloor, OptionalLong budget) {
            long projectedFloor = 0;
            List<Reduction> reductions = new ArrayList<>();
            for (WinnerImage winner : winnerByPath.values()) {
                Optional<ImageHeaderReader.ImageDimensions> dimensions = winner.dimensions();
                if (dimensions.isEmpty()) {
                    continue;
                }
                ImageHeaderReader.ImageDimensions current = dimensions.get();
                int halvings = halvingsToFit(current.width(), current.height(), maxTextureSize);
                if (halvings == 0) {
                    projectedFloor = saturatedAdd(projectedFloor, current.decodedBytes());
                    continue;
                }
                Reduction reduction = new Reduction(
                        winner.modId(),
                        winner.logicalPath(),
                        current,
                        Math.max(1, current.width() >> halvings),
                        Math.max(1, current.height() >> halvings));
                projectedFloor = saturatedAdd(projectedFloor, reduction.projectedBytes());
                reductions.add(reduction);
            }

            List<Map<String, Object>> largestReductions = reductions.stream()
                    .sorted(Comparator.comparingLong(Reduction::savedBytes).reversed()
                            .thenComparing(Reduction::logicalPath))
                    .limit(LARGEST_LIMIT)
                    .map(Reduction::toMap)
                    .toList();

            long finalProjectedFloor = projectedFloor;
            Map<String, Object> plan = new LinkedHashMap<>();
            plan.put("maxTextureSizePixels", maxTextureSize);
            plan.put("method", "halve the long edge until it fits the cap; exact 2x2 box reduction, "
                    + "so each step divides decoded cost by 4 and power-of-two sizes stay power-of-two");
            plan.put("oversizedTextures", (long) reductions.size());
            plan.put("currentFloorBytes", currentFloor);
            plan.put("projectedFloorBytes", finalProjectedFloor);
            plan.put("savedBytes", currentFloor - finalProjectedFloor);
            budget.ifPresent(bytes ->
                    plan.put("projectedBudgetVerdict", budgetVerdict(finalProjectedFloor, bytes)));
            plan.put("largestReductions", largestReductions);
            return plan;
        }

        private void classify(ModStats stats, String extension, long bytes) {
            long[] totals = extensionTotals.computeIfAbsent(extension, ignored -> new long[2]);
            totals[0]++;
            totals[1] += bytes;
            if (IMAGE_EXTENSIONS.contains(extension)) {
                imageFiles++;
                imageBytes += bytes;
                stats.imageFiles++;
                stats.imageBytes += bytes;
            }
            if (SOUND_EXTENSIONS.contains(extension)) {
                soundFiles++;
                soundBytes += bytes;
                stats.soundFiles++;
                stats.soundBytes += bytes;
            }
            if (extension.equals("java")) {
                looseJavaFiles++;
                looseJavaBytes += bytes;
                stats.looseJavaFiles++;
                stats.looseJavaBytes += bytes;
            }
            if (extension.equals("jar")) {
                jarFiles++;
                jarBytes += bytes;
                stats.jarFiles++;
                stats.jarBytes += bytes;
            }
            if (DATA_EXTENSIONS.contains(extension)) {
                dataFiles++;
                dataBytes += bytes;
                stats.dataFiles++;
                stats.dataBytes += bytes;
            }
        }

        private void addLargest(Asset asset) {
            largest.add(asset);
            if (largest.size() > LARGEST_LIMIT) {
                largest.remove();
            }
        }

        private void updateDigest(String value) {
            profileDigest.update(value.getBytes(StandardCharsets.UTF_8));
            profileDigest.update((byte) 0);
        }
    }

    /**
     * Advisory capacity check of the decoded working set against a user-supplied VRAM budget.
     *
     * <p>The verdict is deliberately three-way and honest about what the floor is. {@code floorBytes}
     * is the override-resolved sum of base-level {@code width*height*channels} over the winning image
     * at each logical path. It ignores mip chains and NPOT padding and counts RGB at 3 bytes/px where
     * the GPU may pad to 4 (both raise real residency), and counts every winner image whether or not
     * it loads this session — so it is a coarse order-of-magnitude signal, not a precise prediction.
     * Against that:
     * <ul>
     *   <li>{@code over} — the floor alone already exceeds the budget; residency is certainly over.
     *   <li>{@code at-risk} — the base levels fit, but a full mip chain (an upper bound of
     *       {@code floor + floor/3}) would not; real cost lands somewhere in between.
     *   <li>{@code under} — even the full-mip upper bound fits the budget.
     * </ul>
     */
    private static Map<String, Object> budgetVerdict(long floorBytes, long budgetBytes) {
        long fullMipUpperBound = saturatedAdd(floorBytes, ceilDiv(floorBytes, 3));
        String verdict;
        if (floorBytes > budgetBytes) {
            verdict = "over";
        } else if (fullMipUpperBound > budgetBytes) {
            verdict = "at-risk";
        } else {
            verdict = "under";
        }
        Map<String, Object> values = new LinkedHashMap<>();
        values.put("verdict", verdict);
        values.put("budgetBytes", budgetBytes);
        values.put("floorBytes", floorBytes);
        values.put("fullMipChainUpperBoundBytes", fullMipUpperBound);
        values.put("headroomBytes", budgetBytes - floorBytes);
        values.put("note", "advisory: override-resolved floor; excludes mips/NPOT padding, "
                + "counts RGB as 3B/px (GPU may pad to 4), and counts every winner image whether or not loaded this session");
        return values;
    }

    /**
     * How many exact halvings bring the long edge to {@code maxTextureSize} or below. Terminates for
     * any positive cap because the shifted edge reaches zero within 31 steps.
     */
    private static int halvingsToFit(int width, int height, int maxTextureSize) {
        int longEdge = Math.max(width, height);
        int halvings = 0;
        while ((longEdge >> halvings) > maxTextureSize) {
            halvings++;
        }
        return halvings;
    }

    private static long ceilDiv(long value, long divisor) {
        return (value + divisor - 1) / divisor;
    }

    private static long saturatedAdd(long left, long right) {
        long sum = left + right;
        if (((left ^ sum) & (right ^ sum)) < 0) {
            return Long.MAX_VALUE;
        }
        return sum;
    }

    private static String normalize(Path path) {
        return path.toString().replace(path.getFileSystem().getSeparator(), "/");
    }

    private static String extension(String logicalPath) {
        int slash = logicalPath.lastIndexOf('/');
        int dot = logicalPath.lastIndexOf('.');
        if (dot <= slash || dot == logicalPath.length() - 1) {
            return "(none)";
        }
        return logicalPath.substring(dot + 1).toLowerCase(Locale.ROOT);
    }

    private static MessageDigest sha256() {
        try {
            return MessageDigest.getInstance("SHA-256");
        } catch (NoSuchAlgorithmException impossible) {
            throw new IllegalStateException("SHA-256 is unavailable", impossible);
        }
    }
}
