package dev.starsector.preflight.cli;

import dev.starsector.preflight.core.Hashes;
import dev.starsector.preflight.core.Json;
import dev.starsector.preflight.core.ModCompatibilityReadiness.ProfileChange;
import dev.starsector.preflight.core.ModCompatibilityReadiness.Result;
import java.io.IOException;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/** Explicit, review-bound application of direct dependency enablement proposals. */
final class ProfileDependencyRepair {
    private static final String FORMAT = "starsector-preflight-profile-dependency-repair-v1";
    private static final String REVIEW_FORMAT = "starsector-preflight-profile-dependency-repair-review-v1";
    private static final Duration REVIEW_MAX_AGE = Duration.ofMinutes(30);

    private ProfileDependencyRepair() {
    }

    static int execute(
            PreflightHome home,
            LaunchTarget target,
            boolean confirmed,
            boolean json,
            PrintStream out) throws Exception {
        if (!confirmed) {
            return repairOwned(home, target.installRoot(), target, false, json, out);
        }
        OperationLease.Acquisition ownership =
                OperationLease.acquire(home, "repairing-mod-dependencies", target.installRoot());
        try (OperationLease ignored = ownership.lease()) {
            return repairOwned(home, target.installRoot(), target, true, json, out);
        }
    }

    static int repair(
            PreflightHome home,
            Path installRoot,
            boolean confirmed,
            boolean json,
            PrintStream out) throws Exception {
        if (!confirmed) {
            return repairOwned(home, installRoot, null, false, json, out);
        }
        OperationLease.Acquisition ownership =
                OperationLease.acquire(home, "repairing-mod-dependencies", installRoot);
        try (OperationLease ignored = ownership.lease()) {
            return repairOwned(home, installRoot, null, true, json, out);
        }
    }

    private static int repairOwned(
            PreflightHome home,
            Path installRoot,
            LaunchTarget target,
            boolean confirmed,
            boolean json,
            PrintStream out) throws Exception {
        GameLayout layout = GameLayout.locate(installRoot);
        byte[] sourceState = Files.readAllBytes(layout.enabledModsFile());
        String sourceStateSha256 = Hashes.sha256(sourceState);
        Result readiness = target == null
                ? ModCompatibilityPrecheck.inspect(layout.installRoot())
                : ModCompatibilityPrecheck.inspect(layout.installRoot(), target);
        ProfileChange change = readiness.suggestedProfileChange();
        String proposalSha256 = change == null ? null : proposalSha256(change);

        Review review = confirmed ? readReview(home, layout.installRoot()) : null;
        boolean sourceChanged = confirmed
                && review != null
                && !review.sourceStateSha256().equals(sourceStateSha256);
        boolean proposalChanged = confirmed
                && review != null
                && !review.proposalSha256().equals(proposalSha256);
        boolean reviewChanged = confirmed && (review == null || sourceChanged || proposalChanged);

        Map<String, Object> plan = new LinkedHashMap<>();
        plan.put("format", FORMAT);
        plan.put("installRoot", layout.installRoot());
        plan.put("before", change == null ? readiness.enabledMods() : change.before());
        plan.put("enable", change == null ? List.of() : change.enable());
        plan.put("after", change == null ? readiness.enabledMods() : change.after());
        plan.put("sourceStateSha256", sourceStateSha256);
        plan.put("proposalSha256", proposalSha256);
        plan.put("evidenceComplete", readiness.evidenceComplete());
        plan.put("launchAllowed", readiness.launchAllowed());
        plan.put("sourceChanged", sourceChanged);
        plan.put("proposalChanged", proposalChanged);
        plan.put("reviewChanged", reviewChanged);
        plan.put("applied", false);

        if (change == null) {
            deleteReview(home, layout.installRoot());
            emit(plan, json, out);
            return 0;
        }

        if (!confirmed) {
            writeReview(home, layout.installRoot(), sourceStateSha256, proposalSha256);
            emit(plan, json, out);
            if (!json) {
                out.println("Review this exact change, then repeat with --yes to apply it.");
            }
            return 0;
        }

        if (reviewChanged) {
            writeReview(home, layout.installRoot(), sourceStateSha256, proposalSha256);
            emit(plan, json, out);
            return 2;
        }

        Path backup = ProfileCommand.backup(home, sourceState);
        byte[] replacement = (Json.object(Map.of("enabledMods", change.after()))
                        + System.lineSeparator())
                .getBytes(StandardCharsets.UTF_8);
        boolean atomicReplace =
                ProfileCommand.replaceIfUnchanged(layout.enabledModsFile(), sourceState, replacement);
        deleteReview(home, layout.installRoot());
        plan.put("applied", true);
        plan.put("atomicReplace", atomicReplace);
        plan.put("backup", backup);
        emit(plan, json, out);
        return 0;
    }

    private static String proposalSha256(ProfileChange change) {
        Map<String, Object> value = new LinkedHashMap<>();
        value.put("before", change.before());
        value.put("enable", change.enable());
        value.put("after", change.after());
        return Hashes.sha256(Json.object(value).getBytes(StandardCharsets.UTF_8));
    }

    private static Path reviewPath(PreflightHome home, Path installRoot) {
        String key = ProfileCommand.callerIdentity()
                + "\n"
                + installRoot.toAbsolutePath().normalize();
        return home.state()
                .resolve("profile-dependency-repair-reviews")
                .resolve(Hashes.sha256(key.getBytes(StandardCharsets.UTF_8)) + ".json")
                .toAbsolutePath()
                .normalize();
    }

    private static void writeReview(
            PreflightHome home,
            Path installRoot,
            String sourceStateSha256,
            String proposalSha256) throws IOException {
        Map<String, Object> review = new LinkedHashMap<>();
        review.put("format", REVIEW_FORMAT);
        review.put("installRoot", installRoot.toAbsolutePath().normalize());
        review.put("sourceStateSha256", sourceStateSha256);
        review.put("proposalSha256", proposalSha256);
        review.put("reviewedAt", Instant.now().toString());
        ProfileCommand.atomicWrite(reviewPath(home, installRoot), Json.object(review) + System.lineSeparator());
    }

    private static Review readReview(PreflightHome home, Path installRoot) throws IOException {
        Path file = reviewPath(home, installRoot);
        if (!Files.isRegularFile(file)) {
            return null;
        }
        try {
            String json = Files.readString(file, StandardCharsets.UTF_8);
            if (!REVIEW_FORMAT.equals(JsonText.string(json, "format"))) {
                return null;
            }
            String reviewedInstall = JsonText.string(json, "installRoot");
            String sourceStateSha256 = JsonText.string(json, "sourceStateSha256");
            String proposalSha256 = JsonText.string(json, "proposalSha256");
            String reviewedAtText = JsonText.string(json, "reviewedAt");
            if (reviewedInstall == null
                    || !installRoot.toAbsolutePath().normalize().equals(
                            Path.of(reviewedInstall).toAbsolutePath().normalize())
                    || !sha256(sourceStateSha256)
                    || !sha256(proposalSha256)
                    || reviewedAtText == null) {
                return null;
            }
            Instant reviewedAt = Instant.parse(reviewedAtText);
            Duration age = Duration.between(reviewedAt, Instant.now());
            if (age.isNegative() || age.compareTo(REVIEW_MAX_AGE) > 0) {
                return null;
            }
            return new Review(
                    sourceStateSha256.toLowerCase(Locale.ROOT),
                    proposalSha256.toLowerCase(Locale.ROOT));
        } catch (RuntimeException invalid) {
            return null;
        }
    }

    private static boolean sha256(String value) {
        return value != null && value.matches("[0-9a-fA-F]{64}");
    }

    private static void deleteReview(PreflightHome home, Path installRoot) throws IOException {
        Files.deleteIfExists(reviewPath(home, installRoot));
    }

    private static void emit(Map<String, Object> plan, boolean json, PrintStream out) {
        if (json) {
            out.println(Json.object(plan));
            return;
        }
        out.println("Dependency profile repair:");
        if (Boolean.TRUE.equals(plan.get("reviewChanged"))) {
            if (Boolean.TRUE.equals(plan.get("sourceChanged"))) {
                out.println("  refused: enabled_mods.json changed since review; review the fresh plan again.");
            } else if (Boolean.TRUE.equals(plan.get("proposalChanged"))) {
                out.println("  refused: dependency evidence changed since review; review the fresh plan again.");
            } else {
                out.println("  refused: no current review exists; review the plan first.");
            }
        }
        @SuppressWarnings("unchecked")
        List<String> before = (List<String>) plan.get("before");
        @SuppressWarnings("unchecked")
        List<String> enable = (List<String>) plan.get("enable");
        @SuppressWarnings("unchecked")
        List<String> after = (List<String>) plan.get("after");
        out.println("  before: " + before);
        out.println("  enable: " + (enable.isEmpty() ? "none" : enable));
        out.println("  after:  " + after);
        if (enable.isEmpty()) {
            out.println("  no direct disabled dependency enablements are currently proposed.");
        } else if (Boolean.TRUE.equals(plan.get("applied"))) {
            String method = Boolean.TRUE.equals(plan.get("atomicReplace"))
                    ? "atomic move"
                    : "staged same-directory replacement";
            out.println("  applied with " + method + "; backup: " + plan.get("backup"));
        }
    }

    private record Review(String sourceStateSha256, String proposalSha256) {
    }
}
