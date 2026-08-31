package dev.starsector.preflight.cli;

import dev.starsector.preflight.core.ResourceIndex;
import dev.starsector.preflight.core.ResourceIndexIO;
import dev.starsector.preflight.core.TextureManifest;
import dev.starsector.preflight.core.TextureManifestIO;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/** Builds deterministic first-preparation selection candidates for issue #1065. */
final class DeterministicCompactTextureExperiment {
    private DeterministicCompactTextureExperiment() {
    }

    public static void main(String[] args) throws Exception {
        if (args.length != 4) {
            throw new IllegalArgumentException("Usage: FULL_CACHE PROFILE PACK_MIB OUTPUT_DIRECTORY");
        }
        Path cache = Path.of(args[0]).toAbsolutePath().normalize();
        String profile = args[1];
        long budget = Math.multiplyExact(Long.parseLong(args[2]), 1024L * 1024L);
        Path output = Path.of(args[3]).toAbsolutePath().normalize();
        Files.createDirectories(output);

        TextureManifest manifest = TextureManifestIO.read(
                TextureManifestIO.directory(cache).resolve(profile + ".spfm"));
        ResourceIndex index = ResourceIndexIO.read(
                ResourceIndexIO.directory(cache).resolve(profile + ".spfi"));
        if (!profile.equals(manifest.profileFingerprint())
                || !profile.equals(index.profileFingerprint())) {
            throw new IllegalArgumentException("Prepared artifacts do not match the requested profile");
        }

        Map<String, List<String>> pathsByBlob = new LinkedHashMap<>();
        for (Map.Entry<String, TextureManifest.Entry> item : manifest.entries().entrySet()) {
            pathsByBlob.computeIfAbsent(item.getValue().blobRelativePath(), ignored -> new ArrayList<>())
                    .add(item.getKey());
        }
        List<Candidate> candidates = new ArrayList<>();
        for (Map.Entry<String, List<String>> item : pathsByBlob.entrySet()) {
            String blob = item.getKey();
            TextureManifest.Entry entry = manifest.entry(item.getValue().get(0)).orElseThrow();
            ResourceIndex.Provider winner = index.winner(item.getValue().get(0)).orElseThrow();
            Path source = index.resolveExisting(winner);
            Path loose = cache.resolve(blob).normalize();
            if (!loose.startsWith(cache) || !Files.isRegularFile(loose)) {
                throw new IllegalArgumentException("Prepared blob is not available as a regular file: " + blob);
            }
            long blobBytes = Files.size(loose);
            long fixedCost = Math.multiplyExact((long) item.getValue().size(), 100_000L);
            long pixelCost = saturatedMultiply(entry.pixelBytes(), formatWeight(source));
            candidates.add(new Candidate(
                    blob,
                    List.copyOf(item.getValue()),
                    blobBytes,
                    saturatedAdd(fixedCost, pixelCost)));
        }

        writeSelection(output.resolve("compact-count.paths"), candidates, budget,
                Comparator.comparingDouble(Candidate::pathsPerByte).reversed()
                        .thenComparing(Candidate::blob));
        writeSelection(output.resolve("compact-decode.paths"), candidates, budget,
                Comparator.comparingDouble(Candidate::estimatedCostPerByte).reversed()
                        .thenComparing(Candidate::blob));
    }

    private static void writeSelection(
            Path output,
            List<Candidate> candidates,
            long budget,
            Comparator<Candidate> order) throws Exception {
        long used = 0;
        LinkedHashSet<String> paths = new LinkedHashSet<>();
        for (Candidate candidate : candidates.stream().sorted(order).toList()) {
            if (candidate.blobBytes() > budget - used) continue;
            used = Math.addExact(used, candidate.blobBytes());
            paths.addAll(candidate.logicalPaths());
        }
        List<String> lines = new ArrayList<>();
        lines.add("# packedBytes=" + used + " logicalPaths=" + paths.size());
        lines.addAll(paths);
        Files.write(output, lines, StandardCharsets.UTF_8);
        System.out.println(output + " packedBytes=" + used + " logicalPaths=" + paths.size());
    }

    private static int formatWeight(Path source) throws Exception {
        String name = source.getFileName().toString().toLowerCase(Locale.ROOT);
        if (name.endsWith(".jpg") || name.endsWith(".jpeg")) {
            return progressiveJpeg(source) ? 8 : 2;
        }
        if (name.endsWith(".webp")) return 4;
        return 1;
    }

    private static boolean progressiveJpeg(Path source) throws Exception {
        byte[] bytes = Files.readAllBytes(source);
        for (int index = 0; index + 1 < bytes.length; index++) {
            if ((bytes[index] & 0xff) == 0xff && (bytes[index + 1] & 0xff) == 0xc2) return true;
        }
        return false;
    }

    private static long saturatedMultiply(long left, long right) {
        return left > Long.MAX_VALUE / right ? Long.MAX_VALUE : left * right;
    }

    private static long saturatedAdd(long left, long right) {
        return left > Long.MAX_VALUE - right ? Long.MAX_VALUE : left + right;
    }

    private record Candidate(
            String blob,
            List<String> logicalPaths,
            long blobBytes,
            long estimatedCost) {
        double pathsPerByte() {
            return logicalPaths.size() / (double) blobBytes;
        }

        double estimatedCostPerByte() {
            return estimatedCost / (double) blobBytes;
        }
    }
}
