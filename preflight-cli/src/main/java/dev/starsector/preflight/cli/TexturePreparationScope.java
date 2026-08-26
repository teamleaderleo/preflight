package dev.starsector.preflight.cli;

import dev.starsector.preflight.core.PreparedTextureAccessOrderIO;
import dev.starsector.preflight.core.PreparedTexturePackOrderIO;
import dev.starsector.preflight.core.TextureManifest;
import dev.starsector.preflight.core.TextureManifestIO;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Locale;

/** Chooses whether preparation builds every texture or only paths observed in a real launch. */
enum TexturePreparationScope {
    FULL,
    LEARNED;

    static TexturePreparationScope parse(String value) {
        return switch (value.toLowerCase(Locale.ROOT)) {
            case "full" -> FULL;
            case "learned", "compact" -> LEARNED;
            default -> throw new IllegalArgumentException(
                    "Unknown texture scope: " + value + " (expected full or learned)");
        };
    }

    String optionValue() {
        return name().toLowerCase(Locale.ROOT);
    }

    List<String> selectedLogicalPaths(Path cacheRoot, String profileFingerprint) throws IOException {
        if (this == FULL) {
            return List.of();
        }
        Path access = PreparedTextureAccessOrderIO.path(cacheRoot, profileFingerprint);
        if (Files.isRegularFile(access)) {
            List<String> paths = PreparedTextureAccessOrderIO.read(access, profileFingerprint);
            if (!paths.isEmpty()) {
                return paths;
            }
        }
        List<String> migrated = migratePackOrder(cacheRoot, profileFingerprint);
        if (!migrated.isEmpty()) {
            PreparedTextureAccessOrderIO.write(access, profileFingerprint, migrated);
            return migrated;
        }
        throw new IOException(
                "Compact preparation needs one observed launch first. Launch once with Minimal disk use,"
                        + " then prepare Compact.");
    }

    private static List<String> migratePackOrder(Path cacheRoot, String profileFingerprint) {
        Path manifestPath = TextureManifestIO.directory(cacheRoot)
                .resolve(profileFingerprint + ".spfm");
        Path orderPath = PreparedTexturePackOrderIO.path(cacheRoot, profileFingerprint);
        if (!Files.isRegularFile(manifestPath) || !Files.isRegularFile(orderPath)) {
            return List.of();
        }
        try {
            TextureManifest manifest = TextureManifestIO.read(manifestPath);
            Map<String, List<String>> logicalByBlob = manifest.entries().entrySet().stream()
                    .collect(java.util.stream.Collectors.groupingBy(
                            entry -> entry.getValue().blobRelativePath(),
                            java.util.LinkedHashMap::new,
                            java.util.stream.Collectors.mapping(Map.Entry::getKey, java.util.stream.Collectors.toList())));
            LinkedHashSet<String> logical = new LinkedHashSet<>();
            for (String blob : PreparedTexturePackOrderIO.read(orderPath, profileFingerprint)) {
                logical.addAll(logicalByBlob.getOrDefault(blob, List.of()));
            }
            return List.copyOf(logical);
        } catch (IOException | IllegalArgumentException ignored) {
            return List.of();
        }
    }
}
