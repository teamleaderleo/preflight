package dev.starsector.preflight.cli;

import dev.starsector.preflight.core.Hashes;
import dev.starsector.preflight.core.Json;
import java.io.IOException;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.attribute.PosixFilePermission;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/** Named enabled-mod profiles with preview-first, backed-up activation. */
final class ProfileCommand {
    private static final String FORMAT = "starsector-preflight-profile-v1";
    private static final String LIST_FORMAT = "starsector-preflight-profile-list-v1";
    private static final String ACTIVATION_REVIEW_FORMAT = "starsector-preflight-profile-activation-review-v1";
    private static final Duration ACTIVATION_REVIEW_MAX_AGE = Duration.ofMinutes(30);

    private ProfileCommand() {
    }

    static int execute(String[] args, int offset) throws Exception {
        if (offset >= args.length || "--help".equals(args[offset]) || "-h".equals(args[offset])) {
            PreflightCli.commandUsage("profile", System.out);
            return offset >= args.length ? 2 : 0;
        }
        String operation = args[offset];
        String name = null;
        String targetName = null;
        int optionsAt = offset + 1;
        if ("save".equals(operation) || "activate".equals(operation) || "delete".equals(operation)) {
            if (optionsAt >= args.length || args[optionsAt].startsWith("--")) {
                throw new IllegalArgumentException("profile " + operation + " requires a name");
            }
            name = validateName(args[optionsAt++]);
        } else if ("rename".equals(operation)) {
            if (optionsAt + 1 >= args.length
                    || args[optionsAt].startsWith("--")
                    || args[optionsAt + 1].startsWith("--")) {
                throw new IllegalArgumentException("profile rename requires the current and new names");
            }
            name = validateName(args[optionsAt++]);
            targetName = validateName(args[optionsAt++]);
        } else if (!"list".equals(operation)) {
            throw new IllegalArgumentException("Expected: profile <list|save|activate|rename|delete> ...");
        }

        Options options = Options.parse(args, optionsAt);
        boolean mutation = "rename".equals(operation) || "delete".equals(operation);
        if (options.confirmed() && !("activate".equals(operation) || mutation)) {
            throw new IllegalArgumentException("--yes is only valid for profile activate, rename, or delete");
        }
        if (options.expectedProfile() != null && !mutation) {
            throw new IllegalArgumentException("--expected-profile is only valid for profile rename or delete");
        }
        if (mutation && options.confirmed() && options.expectedProfile() == null) {
            throw new IllegalArgumentException(
                    "profile " + operation + " --yes requires --expected-profile from its preview");
        }
        LaunchTarget target = discover(options.game(), options.launcher());
        PreflightHome home = PreflightHome.current();
        return switch (operation) {
            case "list" -> list(home, target.installRoot(), options.json(), System.out);
            case "save" -> save(home, target.installRoot(), name, options.json(), System.out);
            case "activate" -> activate(
                    home, target.installRoot(), name, options.confirmed(), options.json(), System.out);
            case "rename" -> rename(
                    home,
                    target.installRoot(),
                    name,
                    targetName,
                    options.expectedProfile(),
                    options.confirmed(),
                    options.json(),
                    System.out);
            case "delete" -> delete(
                    home,
                    target.installRoot(),
                    name,
                    options.expectedProfile(),
                    options.confirmed(),
                    options.json(),
                    System.out);
            default -> throw new IllegalStateException("Unexpected profile operation " + operation);
        };
    }

    static int save(PreflightHome home, Path installRoot, String name, boolean json, PrintStream out)
            throws Exception {
        name = validateName(name);
        GameLayout layout = GameLayout.locate(installRoot);
        List<String> enabled = readEnabled(layout.enabledModsFile());
        Set<String> installed = installedModIds(layout.modsDirectory());
        List<String> missing = enabled.stream().filter(id -> !installed.contains(id)).toList();
        if (!missing.isEmpty()) {
            throw new IOException("Cannot save a profile with missing installed mods: "
                    + String.join(", ", missing));
        }
        String fingerprint = ResourceIndexBuilder.build(layout.installRoot()).index().profileFingerprint();
        SavedProfile profile = new SavedProfile(
                name,
                layout.installRoot(),
                enabled,
                fingerprint,
                Instant.now().toString(),
                profilePath(home, name));
        atomicWrite(profile.file(), Json.object(profile.persisted()) + System.lineSeparator());
        if (json) {
            out.println(Json.object(profile.view(layout.installRoot(), enabled, installed)));
        } else {
            out.printf(Locale.ROOT, "Saved profile '%s' with %,d enabled mods.%n", name, enabled.size());
            out.println("  fingerprint: " + fingerprint);
            out.println("  file:        " + profile.file());
        }
        return 0;
    }

    static int list(PreflightHome home, Path installRoot, boolean json, PrintStream out) throws Exception {
        GameLayout layout = GameLayout.locate(installRoot);
        List<String> current = readEnabled(layout.enabledModsFile());
        Set<String> installed = installedModIds(layout.modsDirectory());
        LoadedProfiles loaded = loadProfiles(home);
        List<Map<String, Object>> profiles = loaded.profiles().stream()
                .map(profile -> profile.view(layout.installRoot(), current, installed))
                .toList();
        if (json) {
            Map<String, Object> report = new LinkedHashMap<>();
            report.put("format", LIST_FORMAT);
            report.put("installRoot", layout.installRoot());
            report.put("enabledMods", current);
            report.put("profiles", profiles);
            report.put("diagnostics", loaded.diagnostics());
            out.println(Json.object(report));
            return 0;
        }

        out.printf(Locale.ROOT, "Named profiles (%d):%n", profiles.size());
        if (profiles.isEmpty()) {
            out.println("  None. Save the current mod set with `preflight profile save <name>`. ");
        }
        for (Map<String, Object> profile : profiles) {
            String marker = Boolean.TRUE.equals(profile.get("active")) ? "active" : "saved";
            @SuppressWarnings("unchecked")
            List<String> missing = (List<String>) profile.get("missingMods");
            out.printf(Locale.ROOT, "  %-24s %-7s %,d mods%s%n",
                    profile.get("name"), marker, profile.get("modCount"),
                    missing.isEmpty() ? "" : ", missing " + missing.size());
        }
        for (String diagnostic : loaded.diagnostics()) {
            out.println("  warning: " + diagnostic);
        }
        return 0;
    }

    static int activate(
            PreflightHome home,
            Path installRoot,
            String name,
            boolean confirmed,
            boolean json,
            PrintStream out) throws Exception {
        name = validateName(name);
        if (!confirmed) {
            return activateOwned(home, installRoot, name, false, json, out);
        }
        OperationLease.Acquisition ownership = OperationLease.acquire(home, "switching-profile", installRoot);
        try (OperationLease ignored = ownership.lease()) {
            return activateOwned(home, installRoot, name, true, json, out);
        }
    }

    static int rename(
            PreflightHome home,
            Path installRoot,
            String name,
            String targetName,
            String expectedProfile,
            boolean confirmed,
            boolean json,
            PrintStream out) throws Exception {
        name = validateName(name);
        targetName = validateName(targetName);
        if (name.equals(targetName)) {
            throw new IllegalArgumentException("The new profile name must be different");
        }
        if (!confirmed) {
            return renameOwned(home, installRoot, name, targetName, null, false, json, out);
        }
        OperationLease.Acquisition ownership = OperationLease.acquire(home, "renaming-profile", installRoot);
        try (OperationLease ignored = ownership.lease()) {
            return renameOwned(home, installRoot, name, targetName, expectedProfile, true, json, out);
        }
    }

    private static int renameOwned(
            PreflightHome home,
            Path installRoot,
            String name,
            String targetName,
            String expectedProfile,
            boolean confirmed,
            boolean json,
            PrintStream out) throws Exception {
        GameLayout layout = GameLayout.locate(installRoot);
        SavedProfile profile = readProfile(profilePath(home, name));
        requireProfileName(profile, name);
        Path target = profilePath(home, targetName);
        if (Files.exists(target)) {
            throw new IOException("A named profile already exists: " + targetName);
        }
        Map<String, Object> plan = mutationPlan("rename", profile, layout, targetName);
        if (!confirmed) {
            emitMutation(plan, json, out);
            return 0;
        }
        requireExpectedProfile(profile, expectedProfile);
        SavedProfile renamed = new SavedProfile(
                targetName,
                profile.installRoot(),
                profile.enabledMods(),
                profile.profileFingerprint(),
                profile.savedAt(),
                target);
        atomicWrite(target, Json.object(renamed.persisted()) + System.lineSeparator());
        try {
            Files.delete(profile.file());
        } catch (IOException deleteFailure) {
            Files.deleteIfExists(target);
            throw deleteFailure;
        }
        plan.put("applied", true);
        emitMutation(plan, json, out);
        return 0;
    }

    static int delete(
            PreflightHome home,
            Path installRoot,
            String name,
            String expectedProfile,
            boolean confirmed,
            boolean json,
            PrintStream out) throws Exception {
        name = validateName(name);
        if (!confirmed) {
            return deleteOwned(home, installRoot, name, null, false, json, out);
        }
        OperationLease.Acquisition ownership = OperationLease.acquire(home, "deleting-profile", installRoot);
        try (OperationLease ignored = ownership.lease()) {
            return deleteOwned(home, installRoot, name, expectedProfile, true, json, out);
        }
    }

    private static int deleteOwned(
            PreflightHome home,
            Path installRoot,
            String name,
            String expectedProfile,
            boolean confirmed,
            boolean json,
            PrintStream out) throws Exception {
        GameLayout layout = GameLayout.locate(installRoot);
        SavedProfile profile = readProfile(profilePath(home, name));
        requireProfileName(profile, name);
        Map<String, Object> plan = mutationPlan("delete", profile, layout, null);
        if (!confirmed) {
            emitMutation(plan, json, out);
            return 0;
        }
        requireExpectedProfile(profile, expectedProfile);
        Path backup = backupProfile(home, profile);
        Files.delete(profile.file());
        plan.put("applied", true);
        plan.put("backup", backup);
        emitMutation(plan, json, out);
        return 0;
    }

    private static Map<String, Object> mutationPlan(
            String operation, SavedProfile profile, GameLayout layout, String targetName) throws IOException {
        List<String> current = readEnabled(layout.enabledModsFile());
        Map<String, Object> plan = new LinkedHashMap<>();
        plan.put("format", "starsector-preflight-profile-mutation-v1");
        plan.put("operation", operation);
        plan.put("name", profile.name());
        plan.put("targetName", targetName);
        plan.put("profileFingerprint", profile.profileFingerprint());
        plan.put("active", profile.installRoot().equals(layout.installRoot())
                && profile.enabledMods().equals(current));
        plan.put("modCount", profile.enabledMods().size());
        plan.put("applied", false);
        plan.put("preparedDataKept", true);
        return plan;
    }

    private static void requireProfileName(SavedProfile profile, String requestedName) throws IOException {
        if (!profile.name().equals(requestedName)) {
            throw new IOException("Named profile file does not match requested profile: " + requestedName);
        }
    }

    private static void requireExpectedProfile(SavedProfile profile, String expectedProfile)
            throws IOException {
        if (expectedProfile == null
                || !profile.profileFingerprint().equals(expectedProfile.toLowerCase(Locale.ROOT))) {
            throw new IOException("Named profile changed since review; review it again");
        }
    }

    private static Path backupProfile(PreflightHome home, SavedProfile profile) throws IOException {
        Files.createDirectories(home.profileBackups());
        Path backup = Files.createTempFile(
                home.profileBackups(),
                "deleted-profile-" + Instant.now().toEpochMilli() + "-",
                ".json");
        Files.copy(profile.file(), backup, StandardCopyOption.REPLACE_EXISTING);
        return backup.toAbsolutePath().normalize();
    }

    private static void emitMutation(Map<String, Object> plan, boolean json, PrintStream out) {
        if (json) {
            out.println(Json.object(plan));
            return;
        }
        String operation = String.valueOf(plan.get("operation"));
        if ("rename".equals(operation)) {
            out.printf(Locale.ROOT, "Rename profile '%s' to '%s': %s%n",
                    plan.get("name"), plan.get("targetName"),
                    Boolean.TRUE.equals(plan.get("applied")) ? "applied" : "preview only");
        } else {
            out.printf(Locale.ROOT, "Delete profile '%s': %s%n",
                    plan.get("name"),
                    Boolean.TRUE.equals(plan.get("applied")) ? "applied" : "preview only");
        }
        out.println("  prepared data is kept");
        if (plan.get("backup") != null) {
            out.println("  backup: " + plan.get("backup"));
        }
    }

    private static int activateOwned(
            PreflightHome home,
            Path installRoot,
            String name,
            boolean confirmed,
            boolean json,
            PrintStream out) throws Exception {
        GameLayout layout = GameLayout.locate(installRoot);
        SavedProfile profile = readProfile(profilePath(home, name));
        requireProfileName(profile, name);
        byte[] sourceState = Files.readAllBytes(layout.enabledModsFile());
        String sourceStateSha256 = Hashes.sha256(sourceState);
        List<String> current = readEnabled(sourceState);
        Set<String> installed = installedModIds(layout.modsDirectory());
        List<String> missing = profile.enabledMods().stream().filter(id -> !installed.contains(id)).toList();
        List<String> enable = difference(profile.enabledMods(), current);
        List<String> disable = difference(current, profile.enabledMods());
        boolean sameInstall = profile.installRoot().equals(layout.installRoot());
        boolean active = current.equals(profile.enabledMods());
        boolean canActivate = sameInstall && missing.isEmpty();

        ActivationReview review = confirmed ? readActivationReview(home, layout.installRoot(), name) : null;
        boolean sourceChanged = confirmed && review != null
                && !review.sourceStateSha256().equals(sourceStateSha256);
        boolean profileChanged = confirmed && review != null
                && !review.profileFingerprint().equals(profile.profileFingerprint());
        boolean reviewChanged = confirmed && (review == null || sourceChanged || profileChanged);

        Map<String, Object> plan = new LinkedHashMap<>();
        plan.put("format", "starsector-preflight-profile-activation-v1");
        plan.put("name", profile.name());
        plan.put("installRoot", layout.installRoot());
        plan.put("savedInstallRoot", profile.installRoot());
        plan.put("sameInstall", sameInstall);
        plan.put("active", active);
        plan.put("canActivate", canActivate);
        plan.put("applied", false);
        plan.put("enable", enable);
        plan.put("disable", disable);
        plan.put("missingMods", missing);
        plan.put("sourceStateSha256", sourceStateSha256);
        plan.put("sourceChanged", sourceChanged);
        plan.put("profileChanged", profileChanged);
        plan.put("reviewChanged", reviewChanged);

        if (!confirmed) {
            if (canActivate && !active) {
                writeActivationReview(home, layout.installRoot(), profile, sourceStateSha256);
            } else {
                deleteActivationReview(home, layout.installRoot(), name);
            }
            emitActivation(plan, json, out);
            if (!json && canActivate && !active) {
                out.println("Close Starsector, review this plan, then repeat with --yes to apply it.");
            }
            return canActivate ? 0 : 2;
        }

        if (reviewChanged) {
            if (canActivate && !active) {
                // The refused result is itself the fresh plan the caller now sees. Advance only
                // this caller session's review token so an unrelated desktop process cannot bless
                // another process's stale plan.
                writeActivationReview(home, layout.installRoot(), profile, sourceStateSha256);
            } else {
                deleteActivationReview(home, layout.installRoot(), name);
            }
            emitActivation(plan, json, out);
            return 2;
        }
        if (!canActivate) {
            deleteActivationReview(home, layout.installRoot(), name);
            emitActivation(plan, json, out);
            return 2;
        }
        if (active) {
            deleteActivationReview(home, layout.installRoot(), name);
            emitActivation(plan, json, out);
            return 0;
        }

        Path backup = backup(home, sourceState);
        byte[] replacement = (Json.object(Map.of("enabledMods", profile.enabledMods()))
                + System.lineSeparator()).getBytes(StandardCharsets.UTF_8);
        boolean atomicReplace = replaceIfUnchanged(layout.enabledModsFile(), sourceState, replacement);
        deleteActivationReview(home, layout.installRoot(), name);
        plan.put("applied", true);
        plan.put("atomicReplace", atomicReplace);
        plan.put("backup", backup);
        emitActivation(plan, json, out);
        return 0;
    }

    private static void emitActivation(Map<String, Object> plan, boolean json, PrintStream out) {
        if (json) {
            out.println(Json.object(plan));
            return;
        }
        out.println("Profile activation: " + plan.get("name"));
        if (Boolean.TRUE.equals(plan.get("reviewChanged"))) {
            if (Boolean.TRUE.equals(plan.get("sourceChanged"))) {
                out.println("  refused: enabled_mods.json changed since this switch was reviewed; review it again.");
            } else if (Boolean.TRUE.equals(plan.get("profileChanged"))) {
                out.println("  refused: the saved profile changed since this switch was reviewed; review it again.");
            } else {
                out.println("  refused: no current review exists for this profile switch; review it again.");
            }
            return;
        }
        if (!Boolean.TRUE.equals(plan.get("sameInstall"))) {
            out.println("  refused: profile belongs to " + plan.get("savedInstallRoot"));
            return;
        }
        @SuppressWarnings("unchecked")
        List<String> missing = (List<String>) plan.get("missingMods");
        if (!missing.isEmpty()) {
            out.println("  refused: missing installed mods: " + String.join(", ", missing));
            return;
        }
        if (Boolean.TRUE.equals(plan.get("active"))) {
            out.println("  already active; no file changed.");
            return;
        }
        @SuppressWarnings("unchecked")
        List<String> enable = (List<String>) plan.get("enable");
        @SuppressWarnings("unchecked")
        List<String> disable = (List<String>) plan.get("disable");
        out.println("  enable:  " + (enable.isEmpty() ? "none" : String.join(", ", enable)));
        out.println("  disable: " + (disable.isEmpty() ? "none" : String.join(", ", disable)));
        if (Boolean.TRUE.equals(plan.get("applied"))) {
            String method = Boolean.TRUE.equals(plan.get("atomicReplace"))
                    ? "applied with an atomic move"
                    : "applied with a staged same-directory replacement";
            out.println("  " + method + "; backup: " + plan.get("backup"));
        }
    }

    private static Path activationReviewPath(PreflightHome home, Path installRoot, String name) {
        String caller = callerIdentity();
        String key = caller + "\n" + installRoot.toAbsolutePath().normalize() + "\n" + name;
        return home.state()
                .resolve("profile-activation-reviews")
                .resolve(Hashes.sha256(key.getBytes(StandardCharsets.UTF_8)) + ".json")
                .toAbsolutePath()
                .normalize();
    }

    private static String callerIdentity() {
        ProcessHandle parent = ProcessHandle.current().parent().orElse(null);
        if (parent == null) {
            return "unknown-parent";
        }
        String started = parent.info().startInstant().map(Instant::toString).orElse("unknown-start");
        return parent.pid() + "@" + started;
    }

    private static void writeActivationReview(
            PreflightHome home,
            Path installRoot,
            SavedProfile profile,
            String sourceStateSha256) throws IOException {
        Path target = activationReviewPath(home, installRoot, profile.name());
        Map<String, Object> review = new LinkedHashMap<>();
        review.put("format", ACTIVATION_REVIEW_FORMAT);
        review.put("name", profile.name());
        review.put("installRoot", installRoot.toAbsolutePath().normalize());
        review.put("profileFingerprint", profile.profileFingerprint());
        review.put("sourceStateSha256", sourceStateSha256);
        review.put("reviewedAt", Instant.now().toString());
        atomicWrite(target, Json.object(review) + System.lineSeparator());
    }

    private static ActivationReview readActivationReview(
            PreflightHome home,
            Path installRoot,
            String name) throws IOException {
        Path file = activationReviewPath(home, installRoot, name);
        if (!Files.isRegularFile(file)) {
            return null;
        }
        try {
            String json = Files.readString(file, StandardCharsets.UTF_8);
            if (!ACTIVATION_REVIEW_FORMAT.equals(JsonText.string(json, "format"))) {
                return null;
            }
            String reviewedName = JsonText.string(json, "name");
            String reviewedInstall = JsonText.string(json, "installRoot");
            String profileFingerprint = JsonText.string(json, "profileFingerprint");
            String sourceStateSha256 = JsonText.string(json, "sourceStateSha256");
            String reviewedAtText = JsonText.string(json, "reviewedAt");
            if (!name.equals(reviewedName)
                    || reviewedInstall == null
                    || !installRoot.toAbsolutePath().normalize().equals(
                            Path.of(reviewedInstall).toAbsolutePath().normalize())
                    || profileFingerprint == null
                    || !profileFingerprint.matches("[0-9a-fA-F]{64}")
                    || sourceStateSha256 == null
                    || !sourceStateSha256.matches("[0-9a-fA-F]{64}")
                    || reviewedAtText == null) {
                return null;
            }
            Instant reviewedAt = Instant.parse(reviewedAtText);
            Duration age = Duration.between(reviewedAt, Instant.now());
            if (age.isNegative() || age.compareTo(ACTIVATION_REVIEW_MAX_AGE) > 0) {
                return null;
            }
            return new ActivationReview(
                    profileFingerprint.toLowerCase(Locale.ROOT),
                    sourceStateSha256.toLowerCase(Locale.ROOT));
        } catch (RuntimeException invalid) {
            return null;
        }
    }

    private static void deleteActivationReview(PreflightHome home, Path installRoot, String name)
            throws IOException {
        Files.deleteIfExists(activationReviewPath(home, installRoot, name));
    }

    private static Path backup(PreflightHome home, byte[] original) throws IOException {
        Files.createDirectories(home.profileBackups());
        Path target = Files.createTempFile(
                home.profileBackups(),
                "enabled_mods-" + Instant.now().toEpochMilli() + "-",
                ".json");
        Files.write(target, original);
        return target.toAbsolutePath().normalize();
    }

    private static boolean replaceIfUnchanged(Path target, byte[] expected, byte[] replacement)
            throws IOException {
        byte[] current = Files.readAllBytes(target);
        if (!Arrays.equals(expected, current)) {
            throw new IOException("enabled_mods.json changed while activation was being prepared; retry");
        }
        Path parent = target.toAbsolutePath().normalize().getParent();
        Path staged = Files.createTempFile(parent, ".preflight-enabled-mods-", ".json");
        try {
            Files.write(staged, replacement);
            copyPosixPermissions(target, staged);
            if (!Arrays.equals(expected, Files.readAllBytes(target))) {
                throw new IOException("enabled_mods.json changed before activation; retry");
            }
            return moveReplace(staged, target);
        } finally {
            Files.deleteIfExists(staged);
        }
    }

    private static void copyPosixPermissions(Path source, Path target) {
        try {
            Set<PosixFilePermission> permissions = Files.getPosixFilePermissions(source);
            Files.setPosixFilePermissions(target, permissions);
        } catch (UnsupportedOperationException | IOException ignored) {
            // Windows and non-POSIX providers keep their ordinary create permissions.
        }
    }

    private static void atomicWrite(Path target, String value) throws IOException {
        Path absolute = target.toAbsolutePath().normalize();
        Files.createDirectories(absolute.getParent());
        Path staged = Files.createTempFile(absolute.getParent(), ".preflight-profile-", ".json");
        try {
            Files.writeString(staged, value, StandardCharsets.UTF_8);
            moveReplace(staged, absolute);
        } finally {
            Files.deleteIfExists(staged);
        }
    }

    private static boolean moveReplace(Path source, Path target) throws IOException {
        try {
            Files.move(source, target, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
            return true;
        } catch (AtomicMoveNotSupportedException unsupported) {
            Files.move(source, target, StandardCopyOption.REPLACE_EXISTING);
            return false;
        }
    }

    private static LoadedProfiles loadProfiles(PreflightHome home) throws IOException {
        if (!Files.isDirectory(home.profiles())) {
            return new LoadedProfiles(List.of(), List.of());
        }
        List<SavedProfile> profiles = new ArrayList<>();
        List<String> diagnostics = new ArrayList<>();
        try (var files = Files.list(home.profiles())) {
            for (Path file : files.filter(path -> path.getFileName().toString().endsWith(".json"))
                    .sorted().toList()) {
                try {
                    profiles.add(readProfile(file));
                } catch (RuntimeException | IOException invalid) {
                    diagnostics.add("Could not read " + file.getFileName() + ": " + invalid.getMessage());
                }
            }
        }
        profiles.sort(Comparator.comparing(SavedProfile::name, String.CASE_INSENSITIVE_ORDER));
        return new LoadedProfiles(List.copyOf(profiles), List.copyOf(diagnostics));
    }

    /** Cache fingerprints named profiles explicitly ask retention policies to preserve. */
    static RetainedFingerprints retainedFingerprints(PreflightHome home) throws IOException {
        LoadedProfiles loaded = loadProfiles(home);
        Set<String> fingerprints = new LinkedHashSet<>();
        for (SavedProfile profile : loaded.profiles()) {
            fingerprints.add(profile.profileFingerprint());
        }
        return new RetainedFingerprints(
                Collections.unmodifiableSet(new LinkedHashSet<>(fingerprints)),
                loaded.diagnostics());
    }

    private static SavedProfile readProfile(Path file) throws IOException {
        if (!Files.isRegularFile(file)) {
            throw new IOException("Named profile not found: " + file);
        }
        String json = Files.readString(file, StandardCharsets.UTF_8);
        if (!FORMAT.equals(JsonText.string(json, "format"))) {
            throw new IOException("Unsupported named profile format in " + file);
        }
        String name = validateName(JsonText.string(json, "name"));
        String install = JsonText.string(json, "installRoot");
        String fingerprint = JsonText.string(json, "profileFingerprint");
        String savedAt = JsonText.string(json, "savedAt");
        if (install == null || fingerprint == null || savedAt == null
                || !fingerprint.matches("[0-9a-fA-F]{64}")) {
            throw new IOException("Incomplete named profile in " + file);
        }
        List<String> enabled = JsonText.stringArray(json, "enabledMods");
        rejectDuplicateMods(enabled);
        return new SavedProfile(
                name,
                Path.of(install).toAbsolutePath().normalize(),
                enabled,
                fingerprint.toLowerCase(Locale.ROOT),
                savedAt,
                file.toAbsolutePath().normalize());
    }

    private static Path profilePath(PreflightHome home, String name) {
        String digest = Hashes.sha256(name.getBytes(StandardCharsets.UTF_8));
        return home.profiles().resolve(digest + ".json").toAbsolutePath().normalize();
    }

    private static List<String> readEnabled(Path file) throws IOException {
        return readEnabled(Files.readAllBytes(file));
    }

    private static List<String> readEnabled(byte[] bytes) {
        String json = new String(bytes, StandardCharsets.UTF_8);
        List<String> enabled = JsonText.stringArray(json, "enabledMods");
        rejectDuplicateMods(enabled);
        return enabled;
    }

    private static void rejectDuplicateMods(List<String> mods) {
        Set<String> distinct = new LinkedHashSet<>();
        for (String mod : mods) {
            if (mod == null || mod.isBlank()) {
                throw new IllegalArgumentException("Enabled mod IDs must not be blank");
            }
            if (!distinct.add(mod)) {
                throw new IllegalArgumentException("Duplicate enabled mod ID: " + mod);
            }
        }
    }

    private static Set<String> installedModIds(Path modsDirectory) throws IOException {
        Set<String> ids = new LinkedHashSet<>();
        try (var entries = Files.list(modsDirectory)) {
            for (Path directory : entries.filter(Files::isDirectory).sorted().toList()) {
                Path info = directory.resolve("mod_info.json");
                String id = null;
                if (Files.isRegularFile(info)) {
                    try {
                        id = JsonText.string(Files.readString(info, StandardCharsets.UTF_8), "id");
                    } catch (RuntimeException unreadable) {
                        // Match the resource census: a directory name remains the fallback ID.
                    }
                }
                if (id == null || id.isBlank()) {
                    id = directory.getFileName().toString();
                }
                if (id != null && !id.isBlank()) {
                    ids.add(id);
                }
            }
        }
        return Set.copyOf(ids);
    }

    private static List<String> difference(List<String> left, List<String> right) {
        Set<String> rightSet = Set.copyOf(right);
        return left.stream().filter(value -> !rightSet.contains(value)).toList();
    }

    private static String validateName(String name) {
        if (name == null || name.isBlank()) {
            throw new IllegalArgumentException("Profile name must not be blank");
        }
        String trimmed = name.trim();
        if (trimmed.length() > 100 || trimmed.chars().anyMatch(Character::isISOControl)) {
            throw new IllegalArgumentException("Profile name must be 1-100 printable characters");
        }
        return trimmed;
    }

    private static LaunchTarget discover(Path game, Path launcher) throws IOException {
        DiscoveryResult discovery = StarsectorDiscovery.discover(
                Platform.current(),
                Path.of(System.getProperty("user.home")),
                Path.of(System.getProperty("user.dir")),
                System.getenv(),
                game,
                launcher);
        if (discovery.selected() == null) {
            throw new IOException("Could not discover Starsector. Use --game or --launcher.");
        }
        return discovery.selected();
    }

    private record Options(
            Path game, Path launcher, String expectedProfile, boolean confirmed, boolean json) {
        static Options parse(String[] args, int offset) {
            Path game = null;
            Path launcher = null;
            String expectedProfile = null;
            boolean confirmed = false;
            boolean json = false;
            for (int index = offset; index < args.length; index++) {
                switch (args[index]) {
                    case "--game" -> game = Path.of(requireValue(args, ++index, "--game"));
                    case "--launcher" -> launcher = Path.of(requireValue(args, ++index, "--launcher"));
                    case "--expected-profile" -> expectedProfile =
                            requireValue(args, ++index, "--expected-profile").toLowerCase(Locale.ROOT);
                    case "--yes" -> confirmed = true;
                    case "--json" -> json = true;
                    default -> throw new IllegalArgumentException("Unknown profile option: " + args[index]);
                }
            }
            if (expectedProfile != null && !expectedProfile.matches("[0-9a-f]{64}")) {
                throw new IllegalArgumentException("--expected-profile must be a 64-character SHA-256");
            }
            return new Options(game, launcher, expectedProfile, confirmed, json);
        }

        private static String requireValue(String[] args, int index, String option) {
            if (index >= args.length) {
                throw new IllegalArgumentException(option + " requires a value");
            }
            return args[index];
        }
    }

    private record LoadedProfiles(List<SavedProfile> profiles, List<String> diagnostics) {
    }

    record RetainedFingerprints(Set<String> fingerprints, List<String> diagnostics) {
    }

    private record ActivationReview(String profileFingerprint, String sourceStateSha256) {
    }

    private record SavedProfile(
            String name,
            Path installRoot,
            List<String> enabledMods,
            String profileFingerprint,
            String savedAt,
            Path file) {
        Map<String, Object> persisted() {
            Map<String, Object> value = new LinkedHashMap<>();
            value.put("format", FORMAT);
            value.put("name", name);
            value.put("installRoot", installRoot);
            value.put("enabledMods", enabledMods);
            value.put("profileFingerprint", profileFingerprint);
            value.put("savedAt", savedAt);
            return value;
        }

        Map<String, Object> view(Path currentInstallRoot, List<String> current, Set<String> installed) {
            boolean sameInstall = installRoot.equals(currentInstallRoot);
            List<String> missing = enabledMods.stream().filter(id -> !installed.contains(id)).toList();
            Map<String, Object> value = new LinkedHashMap<>();
            value.put("name", name);
            value.put("installRoot", installRoot);
            value.put("enabledMods", enabledMods);
            value.put("modCount", enabledMods.size());
            value.put("profileFingerprint", profileFingerprint);
            value.put("savedAt", savedAt);
            value.put("sameInstall", sameInstall);
            value.put("active", sameInstall && enabledMods.equals(current));
            value.put("canActivate", sameInstall && missing.isEmpty());
            value.put("missingMods", missing);
            value.put("file", file);
            return value;
        }
    }
}
