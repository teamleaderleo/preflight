package dev.starsector.preflight.core.drift;

import dev.starsector.preflight.core.Hashes;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

/**
 * Deterministic, content-aware signature of a Starsector mod directory.
 *
 * <p>Excludes filesystem modification timestamps and runtime log files so that clean copies
 * and runtime logging do not trigger false positive content drift.</p>
 */
public record ModContentSignature(
        String modId,
        String declaredName,
        String declaredVersion,
        String directoryName,
        String contentSha256,
        String modInfoSha256,
        long totalBytes,
        int fileCount,
        List<JarSignature> jarSignatures,
        Map<String, FileEntrySignature> criticalFileSignatures) {

    public ModContentSignature {
        if (modId == null || modId.isBlank()) {
            throw new IllegalArgumentException("modId is required");
        }
        if (directoryName == null || directoryName.isBlank()) {
            throw new IllegalArgumentException("directoryName is required");
        }
        if (contentSha256 == null || !contentSha256.matches("[0-9a-fA-F]{64}")) {
            throw new IllegalArgumentException("Invalid contentSha256: " + contentSha256);
        }
        jarSignatures = jarSignatures == null ? List.of() : List.copyOf(jarSignatures);
        criticalFileSignatures = criticalFileSignatures == null
                ? Map.of()
                : Collections.unmodifiableMap(new LinkedHashMap<>(criticalFileSignatures));
    }

    public record JarSignature(String relativePath, String sha256, long size) {
        public JarSignature {
            if (relativePath == null || relativePath.isBlank()) {
                throw new IllegalArgumentException("relativePath is required");
            }
            if (sha256 == null || !sha256.matches("[0-9a-fA-F]{64}")) {
                throw new IllegalArgumentException("Invalid jar sha256: " + sha256);
            }
            if (size < 0) {
                throw new IllegalArgumentException("size cannot be negative");
            }
        }
    }

    public record FileEntrySignature(String relativePath, String sha256, long size, long modifiedMillis) {
        public FileEntrySignature {
            if (relativePath == null || relativePath.isBlank()) {
                throw new IllegalArgumentException("relativePath is required");
            }
            if (sha256 == null || !sha256.matches("[0-9a-fA-F]{64}")) {
                throw new IllegalArgumentException("Invalid file sha256: " + sha256);
            }
            if (size < 0) {
                throw new IllegalArgumentException("size cannot be negative");
            }
        }
    }

    private static final byte[] ENTRY_SEPARATOR = new byte[]{0};

    public static ModContentSignature compute(Path modDirectory) throws IOException {
        Path absolute = modDirectory.toAbsolutePath().normalize();
        if (!Files.isDirectory(absolute)) {
            throw new IOException("Mod path is not a directory: " + absolute);
        }
        String dirName = absolute.getFileName() != null ? absolute.getFileName().toString() : "unknown";

        // 1. Inspect mod_info.json
        Path modInfoPath = absolute.resolve("mod_info.json");
        String modId = dirName;
        String declaredName = dirName;
        String declaredVersion = null;
        String modInfoSha256 = null;

        if (Files.isRegularFile(modInfoPath)) {
            try {
                byte[] modInfoBytes = Files.readAllBytes(modInfoPath);
                modInfoSha256 = Hashes.sha256(modInfoBytes);
                String jsonText = new String(modInfoBytes, StandardCharsets.UTF_8);

                String parsedId = extractJsonString(jsonText, "id");
                if (parsedId != null && !parsedId.isBlank()) {
                    modId = parsedId;
                }
                String parsedName = extractJsonString(jsonText, "name");
                if (parsedName != null && !parsedName.isBlank()) {
                    declaredName = parsedName;
                }
                declaredVersion = extractJsonString(jsonText, "version");
            } catch (Exception ignored) {
                // Fallbacks retained
            }
        }

        // 2. Walk directory files
        List<Path> allFiles;
        try (Stream<Path> stream = Files.walk(absolute)) {
            allFiles = stream
                    .filter(Files::isRegularFile)
                    .filter(p -> !isIgnoredPath(absolute, p))
                    .sorted(Comparator.comparing(p -> normalizeRelative(absolute, p)))
                    .toList();
        }

        MessageDigest digest = sha256Digest();
        long totalBytes = 0;
        List<JarSignature> jarSignatures = new ArrayList<>();
        Map<String, FileEntrySignature> criticalSignatures = new LinkedHashMap<>();

        byte[] buffer = new byte[64 * 1024];
        for (Path file : allFiles) {
            String relPath = normalizeRelative(absolute, file);
            long size = Files.size(file);
            long mtime = Math.max(0L, Files.getLastModifiedTime(file).toMillis());
            totalBytes += size;

            // Content fingerprint entry (relPath + size + contents)
            digest.update(relPath.getBytes(StandardCharsets.UTF_8));
            digest.update(ENTRY_SEPARATOR);
            digest.update(Long.toString(size).getBytes(StandardCharsets.US_ASCII));
            digest.update(ENTRY_SEPARATOR);

            MessageDigest fileDigest = isCriticalFile(relPath) ? sha256Digest() : null;
            try (InputStream input = Files.newInputStream(file)) {
                int read;
                while ((read = input.read(buffer)) >= 0) {
                    if (read > 0) {
                        digest.update(buffer, 0, read);
                        if (fileDigest != null) {
                            fileDigest.update(buffer, 0, read);
                        }
                    }
                }
            }
            digest.update(ENTRY_SEPARATOR);

            if (fileDigest != null) {
                String fileSha = HexFormat.of().formatHex(fileDigest.digest());
                FileEntrySignature entrySig = new FileEntrySignature(relPath, fileSha, size, mtime);
                criticalSignatures.put(relPath, entrySig);
                if (relPath.toLowerCase(Locale.ROOT).endsWith(".jar")) {
                    jarSignatures.add(new JarSignature(relPath, fileSha, size));
                }
            }
        }

        String contentSha256 = HexFormat.of().formatHex(digest.digest());
        return new ModContentSignature(
                modId,
                declaredName,
                declaredVersion,
                dirName,
                contentSha256,
                modInfoSha256,
                totalBytes,
                allFiles.size(),
                jarSignatures,
                criticalSignatures
        );
    }

    private static boolean isIgnoredPath(Path root, Path file) {
        String fileName = file.getFileName().toString();
        if (fileName.equals(".DS_Store") || fileName.equals("Thumbs.db") || fileName.startsWith(".")) {
            return true;
        }
        String lower = fileName.toLowerCase(Locale.ROOT);
        if (lower.endsWith(".log") || lower.endsWith(".log.lck")) {
            return true;
        }
        int marker = lower.indexOf(".log.");
        if (marker >= 0) {
            String suffix = lower.substring(marker + ".log.".length());
            return !suffix.isEmpty() && suffix.chars().allMatch(c -> c >= '0' && c <= '9');
        }
        return false;
    }

    private static boolean isCriticalFile(String relPath) {
        String lower = relPath.toLowerCase(Locale.ROOT);
        return lower.equals("mod_info.json")
                || lower.endsWith(".jar")
                || lower.endsWith(".json")
                || lower.endsWith(".csv")
                || lower.endsWith(".variant")
                || lower.endsWith(".wpn")
                || lower.endsWith(".ship")
                || lower.endsWith(".proj")
                || lower.endsWith(".system")
                || lower.endsWith(".skin")
                || lower.endsWith(".faction")
                || lower.endsWith(".skill");
    }

    private static String normalizeRelative(Path root, Path file) {
        return root.relativize(file).toString().replace(file.getFileSystem().getSeparator(), "/");
    }

    private static MessageDigest sha256Digest() {
        try {
            return MessageDigest.getInstance("SHA-256");
        } catch (NoSuchAlgorithmException impossible) {
            throw new IllegalStateException("SHA-256 is unavailable", impossible);
        }
    }

    private static String extractJsonString(String json, String key) {
        Pattern pattern = Pattern.compile(
                "\"" + Pattern.quote(key) + "\"\\s*:\\s*\"([^\"]*)\"");
        Matcher matcher = pattern.matcher(json);
        return matcher.find() ? matcher.group(1) : null;
    }

    public Map<String, Object> toMap() {
        Map<String, Object> map = new LinkedHashMap<>();
        map.put("modId", modId);
        map.put("declaredName", declaredName);
        map.put("declaredVersion", declaredVersion);
        map.put("directoryName", directoryName);
        map.put("contentSha256", contentSha256);
        map.put("modInfoSha256", modInfoSha256);
        map.put("totalBytes", totalBytes);
        map.put("fileCount", fileCount);
        map.put("jarSignatures", jarSignatures.stream().map(j -> Map.<String, Object>of(
                "relativePath", j.relativePath(),
                "sha256", j.sha256(),
                "size", j.size()
        )).toList());
        return map;
    }
}
