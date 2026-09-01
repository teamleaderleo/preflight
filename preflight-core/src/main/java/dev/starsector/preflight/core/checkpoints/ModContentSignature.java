package dev.starsector.preflight.core.checkpoints;

import dev.starsector.preflight.core.Hashes;
import java.io.ByteArrayOutputStream;
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
import java.util.Set;
import java.util.regex.Pattern;
import java.util.stream.Stream;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

/**
 * Deterministic, content-aware signature of a Starsector mod directory.
 *
 * <p>Excludes filesystem modification timestamps and transient runtime log files
 * so that clean file copies and logging do not trigger false positive content drift.</p>
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
        Map<String, FileEntrySignature> criticalFileSignatures,
        boolean metadataCorrupt) {

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

    public record JarSignature(String relativePath, String sha256, long size, int classFileCount, String bytecodeDigest) {
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

        public JarSignature(String relativePath, String sha256, long size) {
            this(relativePath, sha256, size, 0, null);
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
    private static final Set<String> TRANSIENT_EXTENSIONS = Set.of(".log", ".lck", ".tmp", ".bak");
    private static final Set<String> IGNORED_FILENAMES = Set.of(".ds_store", "thumbs.db", "desktop.ini");
    private static final Pattern JSON_COMMENT_LINE = Pattern.compile("^\\s*//.*$", Pattern.MULTILINE);
    private static final Pattern JSON_COMMENT_BLOCK = Pattern.compile("/\\*.*?\\*/", Pattern.DOTALL);
    private static final Pattern TRAILING_COMMA = Pattern.compile(",\\s*([}\\]])");

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
        String declaredVersion = "unknown";
        String modInfoSha256 = null;
        boolean corrupt = false;

        if (Files.isRegularFile(modInfoPath)) {
            byte[] modInfoBytes = Files.readAllBytes(modInfoPath);
            modInfoSha256 = Hashes.sha256(modInfoBytes);
            try {
                String rawText = new String(modInfoBytes, StandardCharsets.UTF_8);
                String sanitized = sanitizeJson5(rawText);
                Map<String, Object> map = JsonParser.parseObject(sanitized);
                if (map.get("id") != null && !String.valueOf(map.get("id")).isBlank()) {
                    modId = String.valueOf(map.get("id")).trim();
                }
                if (map.get("name") != null && !String.valueOf(map.get("name")).isBlank()) {
                    declaredName = String.valueOf(map.get("name")).trim();
                }
                if (map.get("version") != null && !String.valueOf(map.get("version")).isBlank()) {
                    declaredVersion = String.valueOf(map.get("version")).trim();
                }
            } catch (Exception e) {
                corrupt = true;
            }
        } else {
            corrupt = true;
        }

        // 2. Walk eligible files
        List<Path> allFiles;
        try (Stream<Path> stream = Files.walk(absolute)) {
            allFiles = stream
                    .filter(Files::isRegularFile)
                    .filter(p -> !isIgnoredPath(p))
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

            // Update content digest
            digest.update(relPath.getBytes(StandardCharsets.UTF_8));
            digest.update(ENTRY_SEPARATOR);
            digest.update(Long.toString(size).getBytes(StandardCharsets.US_ASCII));
            digest.update(ENTRY_SEPARATOR);

            MessageDigest fileDigest = sha256Digest();
            try (InputStream input = Files.newInputStream(file)) {
                int read;
                while ((read = input.read(buffer)) >= 0) {
                    if (read > 0) {
                        digest.update(buffer, 0, read);
                        fileDigest.update(buffer, 0, read);
                    }
                }
            }
            digest.update(ENTRY_SEPARATOR);

            String fileSha = HexFormat.of().formatHex(fileDigest.digest());

            if (relPath.toLowerCase(Locale.ROOT).endsWith(".jar")) {
                JarSignature jarSig = inspectJar(file, relPath, fileSha, size);
                jarSignatures.add(jarSig);
            }

            if (isCriticalFile(relPath)) {
                FileEntrySignature entrySig = new FileEntrySignature(relPath, fileSha, size, mtime);
                criticalSignatures.put(relPath, entrySig);
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
                criticalSignatures,
                corrupt
        );
    }

    public Checkpoint.ModSignature toCheckpointModSignature() {
        return new Checkpoint.ModSignature(
                modId,
                declaredName,
                declaredVersion,
                contentSha256,
                fileCount,
                totalBytes
        );
    }

    private static JarSignature inspectJar(Path jarFile, String relPath, String fileSha, long size) {
        int classCount = 0;
        MessageDigest bytecodeDigest = sha256Digest();
        try (InputStream in = Files.newInputStream(jarFile);
             ZipInputStream zip = new ZipInputStream(in)) {
            ZipEntry entry;
            List<String> classEntries = new ArrayList<>();
            while ((entry = zip.getNextEntry()) != null) {
                String name = entry.getName();
                if (name.endsWith(".class") && !name.equals("module-info.class")) {
                    classCount++;
                    classEntries.add(name);
                    bytecodeDigest.update(name.getBytes(StandardCharsets.UTF_8));
                    bytecodeDigest.update(ENTRY_SEPARATOR);
                    bytecodeDigest.update(Long.toString(Math.max(0, entry.getSize())).getBytes(StandardCharsets.US_ASCII));
                    bytecodeDigest.update(ENTRY_SEPARATOR);
                }
            }
            String bcSha = HexFormat.of().formatHex(bytecodeDigest.digest());
            return new JarSignature(relPath, fileSha, size, classCount, bcSha);
        } catch (Exception e) {
            return new JarSignature(relPath, fileSha, size, 0, fileSha);
        }
    }

    private static boolean isIgnoredPath(Path file) {
        String fileName = file.getFileName().toString().toLowerCase(Locale.ROOT);
        if (IGNORED_FILENAMES.contains(fileName) || fileName.startsWith(".")) {
            return true;
        }
        for (String ext : TRANSIENT_EXTENSIONS) {
            if (fileName.endsWith(ext) || fileName.contains(".log.")) {
                return true;
            }
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

    private static String sanitizeJson5(String raw) {
        String noComments = JSON_COMMENT_LINE.matcher(raw).replaceAll("");
        noComments = JSON_COMMENT_BLOCK.matcher(noComments).replaceAll("");
        return TRAILING_COMMA.matcher(noComments).replaceAll("$1");
    }

    private static MessageDigest sha256Digest() {
        try {
            return MessageDigest.getInstance("SHA-256");
        } catch (NoSuchAlgorithmException impossible) {
            throw new IllegalStateException("SHA-256 is unavailable", impossible);
        }
    }
}
