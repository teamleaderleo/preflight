package dev.starsector.preflight.cli;

import dev.starsector.preflight.core.Json;
import dev.starsector.preflight.core.PreparedTexture;
import dev.starsector.preflight.core.PreparedTextureIO;
import dev.starsector.preflight.core.PreparedTexturePack;
import dev.starsector.preflight.core.PreparedTexturePackIO;
import dev.starsector.preflight.core.ResourceIndex;
import dev.starsector.preflight.core.ResourceIndexIO;
import dev.starsector.preflight.core.TextureManifest;
import dev.starsector.preflight.core.TextureManifestIO;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Builds an isolated partial prepared-texture cache for issue #1065 launch comparisons.
 *
 * <p>The output cache should start as a copy of the comparison's Minimal cache so every nontexture
 * artifact remains identical. This utility publishes the partial texture artifacts first and only
 * then removes the exact Minimal marker. A failed build therefore leaves the output in Minimal
 * mode rather than exposing a half-built selective mode.
 */
final class SelectivePreparedTextureExperiment {
    private SelectivePreparedTextureExperiment() {
    }

    public static void main(String[] args) throws Exception {
        if (args.length != 4) {
            throw new IllegalArgumentException(
                    "Usage: SOURCE_FULL_CACHE PROFILE LOGICAL_PATHS_FILE OUTPUT_MINIMAL_CACHE");
        }
        Report report = build(Path.of(args[0]), args[1], Path.of(args[2]), Path.of(args[3]));
        System.out.println(Json.object(report.asMap()));
    }

    static Report build(
            Path sourceCache,
            String profile,
            Path logicalPathsFile,
            Path outputCache) throws Exception {
        Path source = sourceCache.toAbsolutePath().normalize();
        Path output = outputCache.toAbsolutePath().normalize();
        if (source.equals(output)) {
            throw new IllegalArgumentException("Source and output caches must differ");
        }
        if (!Files.isDirectory(source) || !Files.isDirectory(output)) {
            throw new IllegalArgumentException("Source and output caches must already exist");
        }
        Path minimalMarker = MinimalPreparationMarker.path(output, profile);
        if (!Files.isRegularFile(minimalMarker)) {
            throw new IllegalArgumentException(
                    "Output cache must begin as the exact profile's Minimal cache");
        }
        MinimalPreparationMarker.validate(minimalMarker, profile);

        Path sourceManifestPath = TextureManifestIO.directory(source).resolve(profile + ".spfm");
        Path sourceIndexPath = ResourceIndexIO.directory(source).resolve(profile + ".spfi");
        TextureManifest sourceManifest = TextureManifestIO.read(sourceManifestPath);
        ResourceIndex sourceIndex = ResourceIndexIO.read(sourceIndexPath);
        if (!profile.equals(sourceManifest.profileFingerprint())
                || !profile.equals(sourceIndex.profileFingerprint())) {
            throw new IllegalArgumentException("Source texture artifacts do not match the profile");
        }

        Set<String> requested = readLogicalPaths(logicalPathsFile);
        Map<String, TextureManifest.Entry> selectedEntries = new LinkedHashMap<>();
        Set<String> missing = new LinkedHashSet<>();
        for (String logicalPath : requested) {
            TextureManifest.Entry entry = sourceManifest.entry(logicalPath).orElse(null);
            if (entry == null) {
                missing.add(logicalPath);
            } else {
                selectedEntries.put(logicalPath, entry);
            }
        }
        if (!missing.isEmpty()) {
            throw new IllegalArgumentException("Selection names paths absent from the manifest: " + missing);
        }
        if (selectedEntries.isEmpty()) {
            throw new IllegalArgumentException("Selection must contain at least one prepared texture");
        }

        List<String> sourceBlobPaths = sourceManifest.entries().values().stream()
                .map(TextureManifest.Entry::blobRelativePath)
                .distinct()
                .toList();
        List<String> selectedBlobPaths = selectedEntries.values().stream()
                .map(TextureManifest.Entry::blobRelativePath)
                .distinct()
                .toList();
        Map<String, TextureManifest.Entry> entriesByBlob = new LinkedHashMap<>();
        for (TextureManifest.Entry entry : selectedEntries.values()) {
            TextureManifest.Entry previous = entriesByBlob.putIfAbsent(entry.blobRelativePath(), entry);
            if (previous != null && !previous.equals(entry)) {
                throw new IllegalArgumentException(
                        "Selected logical paths disagree about blob identity: " + entry.blobRelativePath());
            }
        }
        long looseBytes = 0;
        boolean needsPack = selectedBlobPaths.stream()
                .anyMatch(relative -> !Files.isRegularFile(source.resolve(relative)));
        PreparedTexturePack sourcePack = needsPack
                ? PreparedTexturePackIO.open(
                        PreparedTexturePackIO.path(source, profile), profile, sourceBlobPaths)
                : null;
        try {
            for (String relative : selectedBlobPaths) {
                Path loose = source.resolve(relative).normalize();
                PreparedTexture texture = Files.isRegularFile(loose)
                        ? PreparedTextureIO.read(loose)
                        : sourcePack.readTrusted(relative);
                if (!matches(entriesByBlob.get(relative), texture)) {
                    throw new IllegalArgumentException(
                            "Selected blob does not match its manifest entry: " + relative);
                }
                Path target = output.resolve(relative).normalize();
                if (!target.startsWith(output)) {
                    throw new IllegalArgumentException("Selected blob leaves the output cache: " + relative);
                }
                PreparedTextureIO.StorageCodec codec = relative.endsWith("-lz4.spft")
                        ? PreparedTextureIO.StorageCodec.LZ4
                        : PreparedTextureIO.StorageCodec.RAW;
                PreparedTextureIO.write(target, texture, codec);
                looseBytes = Math.addExact(looseBytes, Files.size(target));
            }
        } finally {
            if (sourcePack != null) {
                sourcePack.close();
            }
        }

        Path outputIndexPath = ResourceIndexIO.directory(output).resolve(profile + ".spfi");
        Path outputManifestPath = TextureManifestIO.directory(output).resolve(profile + ".spfm");
        Path outputPackPath = PreparedTexturePackIO.path(output, profile);
        ResourceIndexIO.write(outputIndexPath, sourceIndex);
        TextureManifestIO.write(
                outputManifestPath, new TextureManifest(profile, selectedEntries));
        PreparedTexturePackIO.write(outputPackPath, profile, output, selectedBlobPaths);

        Files.deleteIfExists(minimalMarker);
        return new Report(
                profile,
                selectedEntries.size(),
                selectedBlobPaths.size(),
                looseBytes,
                Files.size(outputPackPath),
                outputManifestPath,
                outputPackPath,
                minimalMarker);
    }

    private static Set<String> readLogicalPaths(Path source) throws Exception {
        Set<String> paths = new LinkedHashSet<>();
        for (String raw : Files.readAllLines(source)) {
            String line = selectedLogicalPath(raw).trim();
            if (line.isEmpty() || line.startsWith("#")) {
                continue;
            }
            paths.add(dev.starsector.preflight.core.ResourceIndex.normalizeLogicalPath(line));
        }
        return Set.copyOf(paths);
    }

    private static boolean matches(TextureManifest.Entry entry, PreparedTexture texture) {
        return entry != null
                && entry.sourceSha256().equals(texture.sourceSha256())
                && entry.transformation() == texture.transformation()
                && entry.width() == texture.uploadWidth()
                && entry.height() == texture.uploadHeight()
                && entry.channels() == texture.channels()
                && entry.pixelBytes() == texture.pixelBytes();
    }

    private static String selectedLogicalPath(String line) {
        String[] fields = line.split("\\t", -1);
        // Accept the exact launch-intersection census emitted during #1065 investigation:
        // source hash, pixel bytes, source bytes, blob, logical path, physical source.
        if (fields.length >= 6 && fields[0].matches("[0-9a-f]{64}")) {
            return fields[4];
        }
        return line;
    }

    record Report(
            String profile,
            int selectedEntries,
            int selectedBlobs,
            long looseBytes,
            long packBytes,
            Path manifest,
            Path pack,
            Path removedMinimalMarker) {
        Map<String, Object> asMap() {
            Map<String, Object> values = new LinkedHashMap<>();
            values.put("profile", profile);
            values.put("selectedEntries", selectedEntries);
            values.put("selectedBlobs", selectedBlobs);
            values.put("looseBytes", looseBytes);
            values.put("packBytes", packBytes);
            values.put("manifest", manifest);
            values.put("pack", pack);
            values.put("removedMinimalMarker", removedMinimalMarker);
            return values;
        }
    }
}
