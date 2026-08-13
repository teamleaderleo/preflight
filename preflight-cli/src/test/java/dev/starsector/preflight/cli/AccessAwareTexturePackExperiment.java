package dev.starsector.preflight.cli;

import dev.starsector.preflight.core.Json;
import dev.starsector.preflight.core.PreparedTexture;
import dev.starsector.preflight.core.PreparedTexturePack;
import dev.starsector.preflight.core.PreparedTexturePackIO;
import dev.starsector.preflight.core.PreparedTexturePackOrderIO;
import dev.starsector.preflight.core.TextureManifest;
import dev.starsector.preflight.core.TextureManifestIO;
import java.nio.ByteBuffer;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Exact-cache experiment harness for issue #324.
 *
 * <p>The profiling mode times paired balanced/raw reads only to rank candidates. The replay mode
 * performs one untimed-setup + timed whole-corpus replay so an operator can alternate fresh JVMs
 * between the empty balanced plan and each budget plan. Nothing here changes the product default,
 * active manifest, learned-order sidecar, or active pack.
 */
final class AccessAwareTexturePackExperiment {
    private static final String REPORT_FORMAT = "preflight-access-aware-texture-profile-v1";

    private AccessAwareTexturePackExperiment() {
    }

    public static void main(String[] args) throws Exception {
        if (args.length != 5) {
            throw new IllegalArgumentException(
                    "Usage: profile CACHE PROFILE OUTPUT_DIR PASSES | replay CACHE PROFILE PLAN_FILE OUTPUT_PACK");
        }
        switch (args[0]) {
            case "profile" -> profile(
                    Path.of(args[1]), args[2], Path.of(args[3]), Integer.parseInt(args[4]));
            case "replay" -> replay(
                    Path.of(args[1]), args[2], Path.of(args[3]), Path.of(args[4]));
            default -> throw new IllegalArgumentException("Expected profile or replay");
        }
    }

    private static void profile(Path cache, String profile, Path outputDirectory, int passes)
            throws Exception {
        if (passes < 3 || passes > 31) {
            throw new IllegalArgumentException("Profiling passes must be between 3 and 31");
        }
        CacheView view = cacheView(cache, profile);
        Path output = outputDirectory.toAbsolutePath().normalize();
        Files.createDirectories(output);

        Path balancedPackPath = output.resolve("balanced-reference.spfp");
        Path rawEligiblePackPath = output.resolve("all-raw-eligible.spfp");
        List<String> rawEligibleOrder = view.currentOrder().stream()
                .map(path -> rawCounterpartIfAvailable(view.cache(), path))
                .toList();
        PreparedTexturePackIO.write(balancedPackPath, profile, view.cache(), view.currentOrder());
        PreparedTexturePackIO.write(rawEligiblePackPath, profile, view.cache(), rawEligibleOrder);

        List<CandidateRef> refs = new ArrayList<>();
        int missingRawCounterparts = 0;
        for (String balancedPath : view.observedCurrent()) {
            if (!balancedPath.endsWith("-lz4.spft")) {
                continue;
            }
            String rawPath = codecIndependentPath(balancedPath);
            if (!Files.isRegularFile(view.cache().resolve(rawPath))) {
                missingRawCounterparts++;
                continue;
            }
            long extraBytes = Files.size(view.cache().resolve(rawPath))
                    - Files.size(view.cache().resolve(balancedPath));
            refs.add(new CandidateRef(codecIndependentPath(balancedPath), balancedPath, rawPath, extraBytes));
        }

        List<AccessAwareTextureStoragePlanner.Candidate> candidates = new ArrayList<>();
        try (PreparedTexturePack balanced = PreparedTexturePackIO.open(
                    balancedPackPath, profile, view.currentOrder());
                PreparedTexturePack raw = PreparedTexturePackIO.open(
                    rawEligiblePackPath, profile, rawEligibleOrder)) {
            // One unmeasured pass gets class loading and scratch allocation out of candidate ranking.
            for (CandidateRef ref : refs) {
                PreparedTexture left = balanced.readTrusted(ref.balancedPath());
                PreparedTexture right = raw.readTrusted(ref.rawPath());
                requireEqual(ref.identity(), left, right);
            }

            for (int candidateIndex = 0; candidateIndex < refs.size(); candidateIndex++) {
                CandidateRef ref = refs.get(candidateIndex);
                long[] balancedNanos = new long[passes];
                long[] rawNanos = new long[passes];
                for (int pass = 0; pass < passes; pass++) {
                    boolean rawFirst = ((pass + candidateIndex) & 1) != 0;
                    PreparedTexture left;
                    PreparedTexture right;
                    if (rawFirst) {
                        TimedRead rawRead = timedRead(raw, ref.rawPath());
                        TimedRead balancedRead = timedRead(balanced, ref.balancedPath());
                        rawNanos[pass] = rawRead.nanos();
                        balancedNanos[pass] = balancedRead.nanos();
                        right = rawRead.texture();
                        left = balancedRead.texture();
                    } else {
                        TimedRead balancedRead = timedRead(balanced, ref.balancedPath());
                        TimedRead rawRead = timedRead(raw, ref.rawPath());
                        balancedNanos[pass] = balancedRead.nanos();
                        rawNanos[pass] = rawRead.nanos();
                        left = balancedRead.texture();
                        right = rawRead.texture();
                    }
                    requireEqual(ref.identity(), left, right);
                }
                candidates.add(new AccessAwareTextureStoragePlanner.Candidate(
                        ref.identity(),
                        ref.balancedPath(),
                        ref.rawPath(),
                        Math.max(0, ref.extraBytes()),
                        median(balancedNanos),
                        median(rawNanos)));
            }
        }

        Path balancedPlan = output.resolve("plan-balanced.paths");
        Files.writeString(balancedPlan,
                "# Empty selection: current Balanced codecs, for alternating fresh-JVM replay.\n");

        List<Map<String, Object>> planReports = new ArrayList<>();
        for (AccessAwareTextureStoragePlanner.Plan plan
                : AccessAwareTextureStoragePlanner.defaultPlans(candidates)) {
            long budgetMib = plan.budgetBytes() / AccessAwareTextureStoragePlanner.MIB;
            Path selectionFile = output.resolve("plan-" + budgetMib + "m.paths");
            List<String> selected = plan.selected().stream()
                    .map(AccessAwareTextureStoragePlanner.Candidate::identity)
                    .toList();
            Files.writeString(selectionFile, String.join(System.lineSeparator(), selected)
                    + (selected.isEmpty() ? "" : System.lineSeparator()));
            Map<String, Object> report = new LinkedHashMap<>();
            report.put("budgetMiB", budgetMib);
            report.put("budgetBytes", plan.budgetBytes());
            report.put("usedBytes", plan.usedBytes());
            report.put("estimatedSavedMs", plan.estimatedSavedNanos() / 1_000_000.0);
            report.put("selectedCount", selected.size());
            report.put("selectionFile", selectionFile);
            report.put("selectedIdentities", selected);
            planReports.add(report);
        }

        List<Map<String, Object>> measurements = candidates.stream().map(candidate -> {
            Map<String, Object> value = new LinkedHashMap<>();
            value.put("identity", candidate.identity());
            value.put("balancedPath", candidate.balancedPath());
            value.put("rawPath", candidate.rawPath());
            value.put("additionalBytes", candidate.extraBytes());
            value.put("balancedMedianMs", candidate.balancedMedianNanos() / 1_000_000.0);
            value.put("rawMedianMs", candidate.rawMedianNanos() / 1_000_000.0);
            value.put("savedMs", candidate.savedNanos() / 1_000_000.0);
            value.put("savedMsPerAdditionalMiB", candidate.savedMillisPerAdditionalMib());
            return value;
        }).toList();

        Map<String, Object> report = new LinkedHashMap<>();
        report.put("format", REPORT_FORMAT);
        report.put("profile", profile);
        report.put("cache", view.cache());
        report.put("profilingPasses", passes);
        report.put("observedAccesses", view.observedCurrent().size());
        report.put("candidateCount", candidates.size());
        report.put("missingRawCounterparts", missingRawCounterparts);
        report.put("balancedPack", balancedPackPath);
        report.put("balancedPackBytes", Files.size(balancedPackPath));
        report.put("allRawEligiblePack", rawEligiblePackPath);
        report.put("allRawEligiblePackBytes", Files.size(rawEligiblePackPath));
        report.put("balancedReplayPlan", balancedPlan);
        report.put("measurements", measurements);
        report.put("plans", planReports);
        report.put("decision", "measurement-required");
        report.put("note",
                "Candidate profiling is selection input only. Alternate fresh JVM replay runs before drawing a seam conclusion, then require a real launch before changing the default.");
        Path reportPath = output.resolve("access-aware-profile.json");
        Files.writeString(reportPath, Json.object(report) + System.lineSeparator());
        System.out.println(reportPath);
    }

    private static void replay(Path cache, String profile, Path planFile, Path outputPack)
            throws Exception {
        CacheView view = cacheView(cache, profile);
        Set<String> selected = readSelection(planFile);
        Set<String> availableCandidates = view.currentOrder().stream()
                .filter(path -> path.endsWith("-lz4.spft"))
                .filter(path -> Files.isRegularFile(view.cache().resolve(codecIndependentPath(path))))
                .map(AccessAwareTexturePackExperiment::codecIndependentPath)
                .collect(java.util.stream.Collectors.toSet());
        if (!availableCandidates.containsAll(selected)) {
            Set<String> unknown = new HashSet<>(selected);
            unknown.removeAll(availableCandidates);
            throw new IllegalArgumentException("Plan names unavailable raw candidates: " + unknown);
        }

        List<String> candidateOrder = view.currentOrder().stream()
                .map(path -> selected.contains(codecIndependentPath(path))
                        ? codecIndependentPath(path)
                        : path)
                .toList();
        List<String> candidateReads = view.observedCurrent().stream()
                .map(path -> selected.contains(codecIndependentPath(path))
                        ? codecIndependentPath(path)
                        : path)
                .toList();
        Path pack = outputPack.toAbsolutePath().normalize();
        PreparedTexturePackIO.write(pack, profile, view.cache(), candidateOrder);

        long pixels = 0;
        long checksum = 1;
        long started = System.nanoTime();
        try (PreparedTexturePack opened = PreparedTexturePackIO.open(pack, profile, candidateOrder)) {
            for (String path : candidateReads) {
                PreparedTexture texture = opened.readTrusted(path);
                ByteBuffer bytes = texture.pixelsView();
                pixels += bytes.remaining();
                checksum = checksum * 31 + texture.sourceSha256().hashCode();
                checksum = checksum * 31 + texture.uploadWidth();
                checksum = checksum * 31 + texture.uploadHeight();
                checksum = checksum * 31 + texture.channels();
                if (bytes.hasRemaining()) {
                    checksum = checksum * 31 + bytes.get(0);
                    checksum = checksum * 31 + bytes.get(bytes.remaining() / 2);
                    checksum = checksum * 31 + bytes.get(bytes.remaining() - 1);
                }
            }
        }
        long elapsed = System.nanoTime() - started;
        Path activePack = PreparedTexturePackIO.path(view.cache(), profile);
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("format", "preflight-access-aware-texture-replay-v1");
        result.put("profile", profile);
        result.put("plan", planFile.toAbsolutePath().normalize());
        result.put("selectedRawCandidates", selected.size());
        result.put("entries", candidateReads.size());
        result.put("pixels", pixels);
        result.put("checksum", checksum);
        result.put("millis", elapsed / 1_000_000.0);
        result.put("pack", pack);
        result.put("packBytes", Files.size(pack));
        result.put("growthFromActiveBalancedBytes",
                Files.isRegularFile(activePack) ? Files.size(pack) - Files.size(activePack) : null);
        System.out.println(Json.object(result));
    }

    private static CacheView cacheView(Path cache, String profile) throws Exception {
        Path root = cache.toAbsolutePath().normalize();
        TextureManifest manifest = TextureManifestIO.read(
                root.resolve("manifests").resolve(profile + ".spfm"));
        if (!manifest.profileFingerprint().equals(profile)) {
            throw new IllegalArgumentException("Texture manifest profile does not match " + profile);
        }
        List<String> logical = manifest.entries().values().stream()
                .map(TextureManifest.Entry::blobRelativePath)
                .distinct()
                .toList();
        List<String> observed = PreparedTexturePackOrderIO.read(
                PreparedTexturePackOrderIO.path(root, profile), profile);
        List<String> currentOrder = preferredOrder(logical, observed);
        Map<String, String> byIdentity = uniqueByIdentity(logical);
        LinkedHashSet<String> observedCurrent = new LinkedHashSet<>();
        for (String path : observed) {
            String current = logical.contains(path) ? path : byIdentity.get(codecIndependentPath(path));
            if (current != null) {
                observedCurrent.add(current);
            }
        }
        if (observedCurrent.isEmpty()) {
            throw new IllegalArgumentException("Learned texture access order has no current manifest entries");
        }
        return new CacheView(root, List.copyOf(currentOrder), List.copyOf(observedCurrent));
    }

    private static List<String> preferredOrder(List<String> logical, List<String> observed) {
        Set<String> available = Set.copyOf(logical);
        Map<String, String> byIdentity = uniqueByIdentity(logical);
        LinkedHashSet<String> ordered = new LinkedHashSet<>();
        for (String path : observed) {
            if (available.contains(path)) {
                ordered.add(path);
                continue;
            }
            String current = byIdentity.get(codecIndependentPath(path));
            if (current != null) {
                ordered.add(current);
            }
        }
        ordered.addAll(logical);
        return List.copyOf(ordered);
    }

    private static Map<String, String> uniqueByIdentity(List<String> paths) {
        Map<String, String> values = new HashMap<>();
        Set<String> ambiguous = new HashSet<>();
        for (String path : paths) {
            String identity = codecIndependentPath(path);
            String previous = values.putIfAbsent(identity, path);
            if (previous != null && !previous.equals(path)) {
                ambiguous.add(identity);
            }
        }
        ambiguous.forEach(values::remove);
        return values;
    }

    private static String rawCounterpartIfAvailable(Path cache, String path) {
        if (!path.endsWith("-lz4.spft")) {
            return path;
        }
        String raw = codecIndependentPath(path);
        return Files.isRegularFile(cache.resolve(raw)) ? raw : path;
    }

    private static String codecIndependentPath(String path) {
        String suffix = "-lz4.spft";
        return path.endsWith(suffix)
                ? path.substring(0, path.length() - suffix.length()) + ".spft"
                : path;
    }

    private static TimedRead timedRead(PreparedTexturePack pack, String path) throws Exception {
        long started = System.nanoTime();
        PreparedTexture texture = pack.readTrusted(path);
        return new TimedRead(texture, System.nanoTime() - started);
    }

    private static void requireEqual(String identity, PreparedTexture left, PreparedTexture right) {
        if (!left.equals(right)) {
            throw new IllegalStateException("Raw/LZ4 decoded output differs for " + identity);
        }
    }

    private static long median(long[] values) {
        long[] sorted = values.clone();
        Arrays.sort(sorted);
        return sorted[sorted.length / 2];
    }

    private static Set<String> readSelection(Path file) throws Exception {
        Set<String> selected = new LinkedHashSet<>();
        for (String line : Files.readAllLines(file)) {
            String value = line.trim();
            if (!value.isEmpty() && !value.startsWith("#")) {
                selected.add(value);
            }
        }
        return Set.copyOf(selected);
    }

    private record CacheView(Path cache, List<String> currentOrder, List<String> observedCurrent) {
    }

    private record CandidateRef(String identity, String balancedPath, String rawPath, long extraBytes) {
    }

    private record TimedRead(PreparedTexture texture, long nanos) {
    }
}
