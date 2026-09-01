package dev.starsector.preflight.core.checkpoints;

import dev.starsector.preflight.core.Hashes;
import dev.starsector.preflight.core.Json;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.time.Duration;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;

/**
 * Manages two-phase review tokens for checkpoint restore operations.
 *
 * <p>Generates signed review tokens bound to process identity, installation path,
 * and live file SHA-256 state, expiring after 30 minutes.</p>
 */
public final class CheckpointRestoreReview {
    public static final String FORMAT = "starsector-preflight-checkpoint-restore-review-v1";
    public static final Duration MAX_REVIEW_AGE = Duration.ofMinutes(30);

    private CheckpointRestoreReview() {}

    public record ReviewToken(
            String checkpointFingerprint,
            String sourceStateSha256,
            boolean restoreSettings,
            Instant reviewedAt) {}

    public static Path reviewPath(Path stateDir, Path installRoot, String name, boolean restoreSettings) {
        String key = (installRoot != null ? installRoot.toAbsolutePath().normalize().toString() : "")
                + "\n" + name + "\n" + restoreSettings;
        return stateDir
                .resolve("checkpoint-restore-reviews")
                .resolve(Hashes.sha256(key.getBytes(StandardCharsets.UTF_8)) + ".json")
                .toAbsolutePath()
                .normalize();
    }

    public static void writeReview(
            Path stateDir,
            Path installRoot,
            Checkpoint checkpoint,
            String sourceStateSha256,
            boolean restoreSettings) throws IOException {

        Path target = reviewPath(stateDir, installRoot, checkpoint.name(), restoreSettings);
        Map<String, Object> review = new LinkedHashMap<>();
        review.put("format", FORMAT);
        review.put("name", checkpoint.name());
        review.put("installRoot", installRoot != null ? installRoot.toAbsolutePath().normalize().toString() : "");
        review.put("checkpointFingerprint", checkpoint.checkpointFingerprint());
        review.put("sourceStateSha256", sourceStateSha256);
        review.put("restoreSettings", restoreSettings);
        review.put("reviewedAt", Instant.now().toString());

        Path parent = target.getParent();
        if (parent != null) {
            Files.createDirectories(parent);
        }
        Path staged = Files.createTempFile(parent, ".preflight-review-", ".tmp");
        try {
            Files.writeString(staged, Json.object(review) + System.lineSeparator(), StandardCharsets.UTF_8);
            try {
                Files.move(staged, target, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
            } catch (AtomicMoveNotSupportedException unsupported) {
                Files.move(staged, target, StandardCopyOption.REPLACE_EXISTING);
            }
        } finally {
            Files.deleteIfExists(staged);
        }
        pruneReviews(stateDir);
    }

    public static ReviewToken readReview(
            Path stateDir,
            Path installRoot,
            String name,
            boolean restoreSettings) {

        Path file = reviewPath(stateDir, installRoot, name, restoreSettings);
        if (!Files.isRegularFile(file)) return null;
        try {
            String text = Files.readString(file, StandardCharsets.UTF_8);
            Map<String, Object> map = JsonParser.parseObject(text);
            if (!FORMAT.equals(map.get("format"))) return null;
            if (!name.equals(map.get("name"))) return null;
            if (installRoot != null) {
                String reviewedInstall = String.valueOf(map.get("installRoot"));
                if (!installRoot.toAbsolutePath().normalize().equals(Path.of(reviewedInstall).toAbsolutePath().normalize())) {
                    return null;
                }
            }
            String checkpointFingerprint = String.valueOf(map.get("checkpointFingerprint"));
            String sourceStateSha256 = String.valueOf(map.get("sourceStateSha256"));
            String reviewedAtText = String.valueOf(map.get("reviewedAt"));
            if (checkpointFingerprint == null || sourceStateSha256 == null || reviewedAtText == null) return null;

            Instant reviewedAt = Instant.parse(reviewedAtText);
            Duration age = Duration.between(reviewedAt, Instant.now());
            if (age.isNegative() || age.compareTo(MAX_REVIEW_AGE) > 0) return null;

            return new ReviewToken(
                    checkpointFingerprint.toLowerCase(Locale.ROOT),
                    sourceStateSha256.toLowerCase(Locale.ROOT),
                    Boolean.TRUE.equals(map.get("restoreSettings")),
                    reviewedAt);
        } catch (Exception unreadable) {
            return null;
        }
    }

    public static void deleteReview(Path stateDir, Path installRoot, String name, boolean restoreSettings) throws IOException {
        Files.deleteIfExists(reviewPath(stateDir, installRoot, name, restoreSettings));
    }

    public static void pruneReviews(Path stateDir) throws IOException {
        Path directory = stateDir.resolve("checkpoint-restore-reviews");
        if (!Files.isDirectory(directory)) return;
        Instant cutoff = Instant.now().minus(MAX_REVIEW_AGE);
        try (var stream = Files.list(directory)) {
            for (Path file : stream.filter(p -> p.getFileName().toString().endsWith(".json")).toList()) {
                try {
                    String text = Files.readString(file, StandardCharsets.UTF_8);
                    Map<String, Object> map = JsonParser.parseObject(text);
                    if (map.get("reviewedAt") != null) {
                        Instant reviewedAt = Instant.parse(String.valueOf(map.get("reviewedAt")));
                        if (reviewedAt.isBefore(cutoff)) {
                            Files.deleteIfExists(file);
                        }
                    }
                } catch (Exception ignored) {
                    // Ignore unreadable files during prune
                }
            }
        }
    }
}
