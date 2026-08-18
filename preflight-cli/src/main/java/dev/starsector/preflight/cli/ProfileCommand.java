package dev.starsector.preflight.cli;

import dev.starsector.preflight.core.Hashes;
import dev.starsector.preflight.core.Json;
import java.io.IOException;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.FileAlreadyExistsException;
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
import java.util.regex.Pattern;

/** Named enabled-mod profiles with preview-first, backed-up activation. */
final class ProfileCommand {
    private static final String FORMAT = "starsector-preflight-profile-v1";
    private static final String LIST_FORMAT = "starsector-preflight-profile-list-v1";
    private static final String ACTIVATION_REVIEW_FORMAT = "starsector-preflight-profile-activation-review-v1";
    private static final Duration ACTIVATION_REVIEW_MAX_AGE = Duration.ofMinutes(30);
    private static final Pattern PROFILE_BACKUP_FILE = Pattern.compile(
            "(?:enabled_mods|deleted-profile|conflicted-profile)-\\d+-.*\\.json");
    private static final Pattern ACTIVATION_REVIEW_FILE = Pattern.compile("[0-9a-f]{64}\\.json");
    private static final DuplicatePublicationHook NO_DUPLICATE_PUBLICATION_HOOK = target -> {
    };
    private static final ProfileMutationTransaction.Hook NO_PROFILE_MUTATION_HOOK =
            new ProfileMutationTransaction.Hook() {};

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
        if ("save".equals(operation)
                || "update".equals(operation)
                || "activate".equals(operation)
                || "delete".equals(operation)) {
            if (optionsAt >= args.length || args[optionsAt].startsWith("--")) {
                throw new IllegalArgumentException("profile " + operation + " requires a name");
            }
            name = validateName(args[optionsAt++]);
        } else if ("rename".equals(operation) || "duplicate".equals(operation)) {
            if (optionsAt + 1 >= args.length
                    || args[optionsAt].startsWith("--")
                    || args[optionsAt + 1].startsWith("--")) {
                throw new IllegalArgumentException("profile " + operation + " requires the current and new names");
            }
            name = validateName(args[optionsAt++]);
            targetName = validateName(args[optionsAt++]);
        } else if (!"list".equals(operation)) {
            throw new IllegalArgumentException(
                    "Expected: profile <list|save|update|activate|rename|duplicate|delete> ...");
        }

        Options options = Options.parse(args, optionsAt);
        boolean mutation = "update".equals(operation)
                || "rename".equals(operation)
                || "duplicate".equals(operation)
                || "delete".equals(operation);
        if (options.confirmed() && !("activate".equals(operation) || mutation)) {
            throw new IllegalArgumentException(
                    "--yes is only valid for profile update, activate, rename, duplicate, or delete");
        }
        if (options.expectedProfile() != null && !mutation) {
            throw new IllegalArgumentException(
                    "--expected-profile is only valid for profile update, rename, duplicate, or delete");
        }
        if (options.expectedReplacement() != null && !"update".equals(operation)) {
            throw new IllegalArgumentException("--expected-replacement is only valid for profile update");
        }
        if ("update".equals(operation) && options.confirmed()
                && (options.expectedProfile() == null || options.expectedReplacement() == null)) {
            throw new IllegalArgumentException(
                    "profile update --yes requires --expected-profile and --expected-replacement from its preview");
        }
        if (!"update".equals(operation)
                && mutation
                && options.confirmed()
                && options.expectedProfile() == null) {
            throw new IllegalArgumentException(
                    "profile " + operation + " --yes requires --expected-profile from its preview");
        }
        LaunchTarget target = discover(options.game(), options.launcher());
        PreflightHome home = PreflightHome.current();
        return switch (operation) {
            case "list" -> list(home, target.installRoot(), options.json(), System.out);
            case "save" -> save(home, target.installRoot(), name, options.json(), System.out);
            case "update" -> update(
                    home,
                    target.installRoot(),
                    name,
                    options.expectedProfile(),
                    options.expectedReplacement(),
                    options.confirmed(),
                    options.json(),
                    System.out);
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
            case "duplicate" -> duplicate(
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
        return save(home, installRoot, name, json, out, NO_DUPLICATE_PUBLICATION_HOOK);
    }

    static int save(
            PreflightHome home,
            Path installRoot,
            String name,
            boolean json,
            PrintStream out,
            DuplicatePublicationHook publicationHook) throws Exception {
        name = validateName(name);
        OperationLease.Acquisition ownership = OperationLease.acquire(home, "saving-profile", installRoot);
        try (OperationLease ignored = ownership.lease()) {
            recoverProfileTransactions(home);
            return saveOwned(home, installRoot, name, json, out, publicationHook);
        }
    }

    private static int saveOwned(
            PreflightHome home,
            Path installRoot,
            String name,
            boolean json,
            PrintStream out,
            DuplicatePublicationHook publicationHook) throws Exception {
        GameLayout layout = GameLayout.locate(installRoot);
        Path target = profilePath(home, name);
        if (Files.exists(target)) {
            throw existingProfile(name);
        }
        SavedProfile profile = currentProfile(layout, name, target);
        try {
            atomicCreate(
                    target,
                    Json.object(profile.persisted()) + System.lineSeparator(),
                    publicationHook);
        } catch (FileAlreadyExistsException collision) {
            throw existingProfile(name, collision);
        }
        if (json) {
            Map<String, Object> result = new LinkedHashMap<>(profile.view(
                    layout.installRoot(), profile.enabledMods(), installedModIds(layout.modsDirectory())));
            result.put("writeAction", "created");
            out.println(Json.object(result));
        } else {
            out.printf(Locale.ROOT, "Created profile '%s' with %,d enabled mods.%n", name, profile.enabledMods().size());
            out.println("  fingerprint: " + profile.profileFingerprint());
            out.println("  file:        " + profile.file());
        }
        return 0;
    }

    static int update(
            PreflightHome home,
            Path installRoot,
            String name,
            String expectedProfile,
            String expectedReplacement,
            boolean confirmed,
            boolean json,
            PrintStream out) throws Exception {
        return update(
                home,
                installRoot,
                name,
                expectedProfile,
                expectedReplacement,
                confirmed,
                json,
                out,
                NO_PROFILE_MUTATION_HOOK);
    }

    static int update(
            PreflightHome home,
            Path installRoot,
            String name,
            String expectedProfile,
            String expectedReplacement,
            boolean confirmed,
            boolean json,
            PrintStream out,
            ProfileMutationTransaction.Hook mutationHook) throws Exception {
        name = validateName(name);
        if (!confirmed) {
            return updateOwned(
                    home, installRoot, name, null, null, false, json, out, mutationHook);
        }
        OperationLease.Acquisition ownership = OperationLease.acquire(home, "updating-profile", installRoot);
        try (OperationLease ignored = ownership.lease()) {
            recoverProfileTransactions(home);
            return updateOwned(
                    home,
                    installRoot,
                    name,
                    expectedProfile,
                    expectedReplacement,
                    true,
                    json,
                    out,
                    mutationHook);
        }
    }

    private static int updateOwned(
            PreflightHome home,
            Path installRoot,
            String name,
            String expectedProfile,
            String expectedReplacement,
            boolean confirmed,
            boolean json,
            PrintStream out,
            ProfileMutationTransaction.Hook mutationHook) throws Exception {
        GameLayout layout = GameLayout.locate(installRoot);
        Path source = profilePath(home, name);
        ProfileRecordFiles.requireRegularRecord(source);
        byte[] existingBytes = Files.readAllBytes(source);
        SavedProfile existing = readProfile(source, existingBytes);
        requireProfileName(existing, name);
        String existingToken = Hashes.sha256(existingBytes);
        SavedProfile proposed = currentProfile(layout, name, source);
        String replacementToken = updateProposalFingerprint(proposed);
        Map<String, Object> plan = updatePlan(existing, proposed, layout, existingToken, replacementToken);

        if (!confirmed) {
            emitUpdate(plan, json, out);
            return 0;
        }
        if (expectedProfile == null || !existingToken.equals(expectedProfile.toLowerCase(Locale.ROOT))) {
            throw new IOException("Named profile changed since review; review it again");
        }
        if (expectedReplacement == null
                || !replacementToken.equals(expectedReplacement.toLowerCase(Locale.ROOT))) {
            throw new IOException("The current mod selection changed since review; review the update again");
        }

        byte[] replacement = (Json.object(proposed.persisted()) + System.lineSeparator())
                .getBytes(StandardCharsets.UTF_8);
        ProfileMutationTransaction.UpdateResult result = ProfileMutationTransaction.update(
                home.profiles(),
                home.profileBackups(),
                source,
                replacement,
                bytes -> requireReviewedUpdateGeneration(source, name, expectedProfile, bytes),
                mutationHook);
        plan.put("applied", true);
        plan.put("cleanupPending", result.cleanupPending());
        plan.put("savedAt", proposed.savedAt());
        emitUpdate(plan, json, out);
        return 0;
    }

    private static SavedProfile currentProfile(GameLayout layout, String name, Path target) throws Exception {
        List<String> enabled = readEnabled(layout.enabledModsFile());
        Set<String> installed = installedModIds(layout.modsDirectory());
        List<String> missing = enabled.stream().filter(id -> !installed.contains(id)).toList();
        if (!missing.isEmpty()) {
            throw new IOException("Cannot save a profile with missing installed mods: "
                    + String.join(", ", missing));
        }
        String fingerprint = ResourceIndexBuilder.build(layout.installRoot()).index().profileFingerprint();
        return new SavedProfile(
                name,
                layout.installRoot(),
                enabled,
                fingerprint,
                Instant.now().toString(),
                target);
    }

    private static Map<String, Object> updatePlan(
            SavedProfile existing,
            SavedProfile proposed,
            GameLayout layout,
            String existingToken,
            String replacementToken) throws IOException {
        List<String> current = readEnabled(layout.enabledModsFile());
        Map<String, Object> plan = new LinkedHashMap<>();
        plan.put("format", "starsector-preflight-profile-update-v1");
        plan.put("operation", "update");
        plan.put("name", existing.name());
        plan.put("profileFingerprint", existingToken);
        plan.put("replacementFingerprint", replacementToken);
        plan.put("existingInstallRoot", existing.installRoot());
        plan.put("existingModCount", existing.enabledMods().size());
        plan.put("existingProfileFingerprint", existing.profileFingerprint());
        plan.put("existingSavedAt", existing.savedAt());
        plan.put("proposedInstallRoot", proposed.installRoot());
        plan.put("proposedModCount", proposed.enabledMods().size());
        plan.put("proposedProfileFingerprint", proposed.profileFingerprint());
        plan.put("active", existing.installRoot().equals(layout.installRoot())
                && existing.enabledMods().equals(current));
        plan.put("applied", false);
        plan.put("preparedDataKept", true);
        plan.put("file", existing.file());
        return plan;
    }

    private static String updateProposalFingerprint(SavedProfile proposed) {
        Map<String, Object> reviewed = new LinkedHashMap<>();
        reviewed.put("name", proposed.name());
        reviewed.put("installRoot", proposed.installRoot());
        reviewed.put("enabledMods", proposed.enabledMods());
        reviewed.put("profileFingerprint", proposed.profileFingerprint());
        return Hashes.sha256(Json.object(reviewed).getBytes(StandardCharsets.UTF_8));
    }

    private static void emitUpdate(Map<String, Object> plan, boolean json, PrintStream out) {
        if (json) {
            out.println(Json.object(plan));
            return;
        }
        out.printf(Locale.ROOT, "Update profile '%s': %s%n",
                plan.get("name"), Boolean.TRUE.equals(plan.get("applied")) ? "applied" : "preview only");
        out.printf(Locale.ROOT, "  existing: %,d mods from %s%n",
                plan.get("existingModCount"), plan.get("existingInstallRoot"));
        out.printf(Locale.ROOT, "  proposed: %,d mods from %s%n",
                plan.get("proposedModCount"), plan.get("proposedInstallRoot"));
        out.println("  prepared data is kept");
        if (Boolean.TRUE.equals(plan.get("cleanupPending"))) {
            out.println("  profile update committed; interrupted cleanup will resume before the next profile write.");
        }
        if (!Boolean.TRUE.equals(plan.get("applied"))) {
            out.println("  apply with: --expected-profile " + plan.get("profileFingerprint")
                    + " --expected-replacement " + plan.get("replacementFingerprint") + " --yes");
        }
    }

    private static IOException existingProfile(String name) {
        return new IOException(
                "A named profile already exists: " + name + ". Review `preflight profile update " + name
                        + "` before replacing it.");
    }

    private static IOException existingProfile(String name, Throwable cause) {
        IOException failure = existingProfile(name);
        failure.initCause(cause);
        return failure;
    }

    static int list(PreflightHome home, Path installRoot, boolean json, PrintStream out) throws Exception {
        Map<String, Object> report = describeList(home, installRoot);
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> profiles = (List<Map<String, Object>>) report.get("profiles");
        @SuppressWarnings("unchecked")
        List<String> diagnostics = (List<String>) report.get("diagnostics");
        if (json) {
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
        for (String diagnostic : diagnostics) {
            out.println("  warning: " + diagnostic);
        }
        return 0;
    }

    /** The read-only document used by both the public command and the desktop bootstrap. */
    static Map<String, Object> describeList(PreflightHome home, Path installRoot) throws Exception {
        GameLayout layout = GameLayout.locate(installRoot);
        List<String> current = readEnabled(layout.enabledModsFile());
        Set<String> installed = installedModIds(layout.modsDirectory());
        LoadedProfiles loaded = loadProfiles(home);
        List<Map<String, Object>> profiles = loaded.profiles().stream()
                .map(profile -> profile.view(layout.installRoot(), current, installed))
                .toList();
        Map<String, Object> report = new LinkedHashMap<>();
        report.put("format", LIST_FORMAT);
        report.put("installRoot", layout.installRoot());
        report.put("enabledMods", current);
        report.put("profiles", profiles);
        report.put("diagnostics", loaded.diagnostics());
        return report;
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
            recoverProfileTransactions(home);
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
        return rename(
                home,
                installRoot,
                name,
                targetName,
                expectedProfile,
                confirmed,
                json,
                out,
                NO_PROFILE_MUTATION_HOOK);
    }

    static int rename(
            PreflightHome home,
            Path installRoot,
            String name,
            String targetName,
            String expectedProfile,
            boolean confirmed,
            boolean json,
            PrintStream out,
            ProfileMutationTransaction.Hook mutationHook) throws Exception {
        name = validateName(name);
        targetName = validateName(targetName);
        if (name.equals(targetName)) {
            throw new IllegalArgumentException("The new profile name must be different");
        }
        if (!confirmed) {
            return renameOwned(
                    home, installRoot, name, targetName, null, false, json, out, mutationHook);
        }
        OperationLease.Acquisition ownership = OperationLease.acquire(home, "renaming-profile", installRoot);
        try (OperationLease ignored = ownership.lease()) {
            recoverProfileTransactions(home);
            return renameOwned(
                    home, installRoot, name, targetName, expectedProfile, true, json, out, mutationHook);
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
            PrintStream out,
            ProfileMutationTransaction.Hook mutationHook) throws Exception {
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
        profile = requireReviewedProfile(profile, name, expectedProfile);
        if (Files.exists(target)) {
            throw new IOException("A named profile already exists: " + targetName);
        }
        Path source = profile.file();
        SavedProfile renamed = new SavedProfile(
                targetName,
                profile.installRoot(),
                profile.enabledMods(),
                profile.profileFingerprint(),
                profile.savedAt(),
                target);
        byte[] replacement = (Json.object(renamed.persisted()) + System.lineSeparator())
                .getBytes(StandardCharsets.UTF_8);
        ProfileMutationTransaction.RenameResult result;
        try {
            result = ProfileMutationTransaction.rename(
                    home.profiles(),
                    home.profileBackups(),
                    source,
                    target,
                    replacement,
                    bytes -> requireReviewedGeneration(source, name, expectedProfile, bytes),
                    mutationHook);
        } catch (IOException failure) {
            if (failure.getMessage() != null && failure.getMessage().startsWith("A named profile already exists:")) {
                throw new IOException("A named profile already exists: " + targetName, failure);
            }
            throw failure;
        }
        plan.put("applied", true);
        plan.put("cleanupPending", result.cleanupPending());
        emitMutation(plan, json, out);
        return 0;
    }

    static int duplicate(
            PreflightHome home,
            Path installRoot,
            String name,
            String targetName,
            String expectedProfile,
            boolean confirmed,
            boolean json,
            PrintStream out) throws Exception {
        return duplicate(
                home,
                installRoot,
                name,
                targetName,
                expectedProfile,
                confirmed,
                json,
                out,
                NO_DUPLICATE_PUBLICATION_HOOK);
    }

    static int duplicate(
            PreflightHome home,
            Path installRoot,
            String name,
            String targetName,
            String expectedProfile,
            boolean confirmed,
            boolean json,
            PrintStream out,
            DuplicatePublicationHook publicationHook) throws Exception {
        name = validateName(name);
        targetName = validateName(targetName);
        if (name.equals(targetName)) {
            throw new IllegalArgumentException("The new profile name must be different");
        }
        if (!confirmed) {
            return duplicateOwned(
                    home, installRoot, name, targetName, null, false, json, out, publicationHook);
        }
        OperationLease.Acquisition ownership = OperationLease.acquire(home, "duplicating-profile", installRoot);
        try (OperationLease ignored = ownership.lease()) {
            recoverProfileTransactions(home);
            return duplicateOwned(
                    home, installRoot, name, targetName, expectedProfile, true, json, out, publicationHook);
        }
    }

    private static int duplicateOwned(
            PreflightHome home,
            Path installRoot,
            String name,
            String targetName,
            String expectedProfile,
            boolean confirmed,
            boolean json,
            PrintStream out,
            DuplicatePublicationHook publicationHook) throws Exception {
        GameLayout layout = GameLayout.locate(installRoot);
        SavedProfile profile = readProfile(profilePath(home, name));
        requireProfileName(profile, name);
        Path target = profilePath(home, targetName);
        if (Files.exists(target)) {
            throw new IOException("A named profile already exists: " + targetName);
        }
        Map<String, Object> plan = mutationPlan("duplicate", profile, layout, targetName);
        if (!confirmed) {
            emitMutation(plan, json, out);
            return 0;
        }
        profile = requireReviewedProfile(profile, name, expectedProfile);
        if (Files.exists(target)) {
            throw new IOException("A named profile already exists: " + targetName);
        }
        SavedProfile duplicated = new SavedProfile(
                targetName,
                profile.installRoot(),
                profile.enabledMods(),
                profile.profileFingerprint(),
                Instant.now().toString(),
                target);
        try {
            atomicCreate(
                    target,
                    Json.object(duplicated.persisted()) + System.lineSeparator(),
                    publicationHook);
        } catch (FileAlreadyExistsException collision) {
            throw new IOException("A named profile already exists: " + targetName, collision);
        }
        plan.put("applied", true);
        plan.put("file", target.toString());
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
        return delete(
                home,
                installRoot,
                name,
                expectedProfile,
                confirmed,
                json,
                out,
                NO_PROFILE_MUTATION_HOOK);
    }

    static int delete(
            PreflightHome home,
            Path installRoot,
            String name,
            String expectedProfile,
            boolean confirmed,
            boolean json,
            PrintStream out,
            ProfileMutationTransaction.Hook mutationHook) throws Exception {
        name = validateName(name);
        if (!confirmed) {
            return deleteOwned(home, installRoot, name, null, false, json, out, mutationHook);
        }
        OperationLease.Acquisition ownership = OperationLease.acquire(home, "deleting-profile", installRoot);
        try (OperationLease ignored = ownership.lease()) {
            recoverProfileTransactions(home);
            return deleteOwned(home, installRoot, name, expectedProfile, true, json, out, mutationHook);
        }
    }

    private static int deleteOwned(
            PreflightHome home,
            Path installRoot,
            String name,
            String expectedProfile,
            boolean confirmed,
            boolean json,
            PrintStream out,
            ProfileMutationTransaction.Hook mutationHook) throws Exception {
        GameLayout layout = GameLayout.locate(installRoot);
        SavedProfile profile = readProfile(profilePath(home, name));
        requireProfileName(profile, name);
        Map<String, Object> plan = mutationPlan("delete", profile, layout, null);
        if (!confirmed) {
            emitMutation(plan, json, out);
            return 0;
        }
        profile = requireReviewedProfile(profile, name, expectedProfile);
        Path source = profile.file();
        ProfileMutationTransaction.DeleteResult result = ProfileMutationTransaction.delete(
                home.profiles(),
                home.profileBackups(),
                source,
                bytes -> requireReviewedGeneration(source, name, expectedProfile, bytes),
                mutationHook);
        boolean cleanupPending = result.cleanupPending();
        try {
            retainProfileBackups(home);
        } catch (IOException retentionFailure) {
            cleanupPending = true;
            plan.put("cleanupWarning", message(retentionFailure));
        }
        plan.put("applied", true);
        plan.put("backup", result.backup());
        plan.put("cleanupPending", cleanupPending);
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
        plan.put("profileFingerprint", mutationFingerprint(profile));
        plan.put("sourceProfileFingerprint", profile.profileFingerprint());
        plan.put("active", profile.installRoot().equals(layout.installRoot())
                && profile.enabledMods().equals(current));
        plan.put("modCount", profile.enabledMods().size());
        plan.put("applied", false);
        plan.put("preparedDataKept", true);
        return plan;
    }

    private static String mutationFingerprint(SavedProfile profile) {
        String persisted = Json.object(profile.persisted()) + System.lineSeparator();
        return Hashes.sha256(persisted.getBytes(StandardCharsets.UTF_8));
    }

    private static void requireProfileName(SavedProfile profile, String requestedName) throws IOException {
        if (!profile.name().equals(requestedName)) {
            throw new IOException("Named profile file does not match requested profile: " + requestedName);
        }
    }

    private static void requireExpectedProfile(SavedProfile profile, String expectedProfile)
            throws IOException {
        if (expectedProfile == null
                || !mutationFingerprint(profile).equals(expectedProfile.toLowerCase(Locale.ROOT))) {
            throw new IOException("Named profile changed since review; review it again");
        }
    }

    private static SavedProfile requireReviewedProfile(
            SavedProfile profile, String requestedName, String expectedProfile) throws IOException {
        requireExpectedProfile(profile, expectedProfile);
        SavedProfile current = readProfile(profile.file());
        requireProfileName(current, requestedName);
        requireExpectedProfile(current, expectedProfile);
        return current;
    }

    private static void requireReviewedGeneration(
            Path canonicalFile,
            String requestedName,
            String expectedProfile,
            byte[] bytes) throws IOException {
        SavedProfile current = readProfile(canonicalFile, bytes);
        requireProfileName(current, requestedName);
        requireExpectedProfile(current, expectedProfile);
    }

    private static void requireReviewedUpdateGeneration(
            Path canonicalFile,
            String requestedName,
            String expectedProfile,
            byte[] bytes) throws IOException {
        SavedProfile current = readProfile(canonicalFile, bytes);
        requireProfileName(current, requestedName);
        if (expectedProfile == null
                || !Hashes.sha256(bytes).equals(expectedProfile.toLowerCase(Locale.ROOT))) {
            throw new IOException("Named profile changed since review; review it again");
        }
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
        } else if ("duplicate".equals(operation)) {
            out.printf(Locale.ROOT, "Duplicate profile '%s' to '%s': %s%n",
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
        if (Boolean.TRUE.equals(plan.get("cleanupPending"))) {
            out.println("  profile change committed; interrupted cleanup will resume before the next profile write.");
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
        if (confirmed) {
            layout = layout.requireMutationSafe();
        }
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
        pruneActivationReviews(home);
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
        Path directory = SafetyArtifactRetention.requireRealDirectory(home.profileBackups());
        Path target = Files.createTempFile(
                directory,
                "enabled_mods-" + Instant.now().toEpochMilli() + "-",
                ".json");
        Files.write(target, original);
        retainProfileBackups(home);
        return target.toAbsolutePath().normalize();
    }

    private static void retainProfileBackups(PreflightHome home) throws IOException {
        SafetyArtifactRetention.retainNewest(
                home.profileBackups(),
                PROFILE_BACKUP_FILE,
                SafetyArtifactRetention.MAX_BACKUPS_PER_DIRECTORY);
    }

    private static void pruneActivationReviews(PreflightHome home) throws IOException {
        Path directory = home.state().resolve("profile-activation-reviews");
        SafetyArtifactRetention.deleteOlderThan(
                directory, ACTIVATION_REVIEW_FILE, Instant.now().minus(ACTIVATION_REVIEW_MAX_AGE));
        SafetyArtifactRetention.retainNewest(
                directory, ACTIVATION_REVIEW_FILE, SafetyArtifactRetention.MAX_ACTIVATION_REVIEWS);
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
        Path parent = SafetyArtifactRetention.requireRealDirectory(absolute.getParent());
        Path staged = ProfileRecordFiles.createStagingFile(parent, "write");
        try {
            Files.writeString(staged, value, StandardCharsets.UTF_8);
            moveReplace(staged, absolute);
        } finally {
            Files.deleteIfExists(staged);
        }
    }

    /**
     * Publishes a completed profile only if its final pathname is still absent.
     *
     * The staged inode lives beside the destination, so a hard link is a single same-filesystem
     * directory operation: the final name can appear only after every byte is written, and an
     * independently-created final name is never replaced. A filesystem without hard-link support
     * fails closed instead of falling back to a copying publication that could expose partial data.
     */
    private static void atomicCreate(
            Path target, String value, DuplicatePublicationHook publicationHook) throws IOException {
        Path absolute = target.toAbsolutePath().normalize();
        Path parent = SafetyArtifactRetention.requireRealDirectory(absolute.getParent());
        Path staged = ProfileRecordFiles.createStagingFile(parent, "create");
        boolean published = false;
        Throwable failure = null;
        try {
            Files.writeString(staged, value, StandardCharsets.UTF_8);
            publicationHook.beforePublication(absolute);
            try {
                Files.createLink(absolute, staged);
                published = true;
            } catch (UnsupportedOperationException unsupported) {
                throw new IOException(
                        "Filesystem cannot publish a named profile safely: " + absolute,
                        unsupported);
            }
        } catch (IOException | RuntimeException error) {
            failure = error;
            throw error;
        } finally {
            try {
                publicationHook.cleanupStagedProfile(staged);
            } catch (IOException cleanupFailure) {
                if (!published) {
                    if (failure != null) {
                        failure.addSuppressed(cleanupFailure);
                    } else {
                        throw cleanupFailure;
                    }
                }
            }
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
        ProfileRecordFiles.Scan scan = ProfileRecordFiles.scan(home.profiles());
        List<SavedProfile> profiles = new ArrayList<>();
        List<String> diagnostics = new ArrayList<>(scan.diagnostics());
        for (Path file : scan.records()) {
            try {
                profiles.add(readProfile(file));
            } catch (RuntimeException | IOException invalid) {
                diagnostics.add("Could not read " + file.getFileName() + ": " + invalid.getMessage());
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
        ProfileRecordFiles.requireRegularRecord(file);
        return readProfile(file, Files.readAllBytes(file));
    }

    private static SavedProfile readProfile(Path canonicalFile, byte[] bytes) throws IOException {
        String json = new String(bytes, StandardCharsets.UTF_8);
        if (!FORMAT.equals(JsonText.string(json, "format"))) {
            throw new IOException("Unsupported named profile format in " + canonicalFile);
        }
        String name = validateName(JsonText.string(json, "name"));
        ProfileRecordFiles.requireNameMatchesFilename(canonicalFile, name);
        String install = JsonText.string(json, "installRoot");
        String fingerprint = JsonText.string(json, "profileFingerprint");
        String savedAt = JsonText.string(json, "savedAt");
        if (install == null || fingerprint == null || savedAt == null
                || !fingerprint.matches("[0-9a-fA-F]{64}")) {
            throw new IOException("Incomplete named profile in " + canonicalFile);
        }
        List<String> enabled = JsonText.stringArray(json, "enabledMods");
        rejectDuplicateMods(enabled);
        return new SavedProfile(
                name,
                Path.of(install).toAbsolutePath().normalize(),
                enabled,
                fingerprint.toLowerCase(Locale.ROOT),
                savedAt,
                canonicalFile.toAbsolutePath().normalize());
    }

    private static Path profilePath(PreflightHome home, String name) {
        return home.profiles()
                .resolve(ProfileRecordFiles.canonicalFileName(name))
                .toAbsolutePath()
                .normalize();
    }

    private static void recoverProfileTransactions(PreflightHome home) throws IOException {
        ProfileMutationTransaction.recover(home.profiles(), home.profileBackups());
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

    private static String message(Throwable error) {
        String value = error.getMessage();
        return value == null || value.isBlank() ? error.getClass().getSimpleName() : value;
    }

    private record Options(
            Path game,
            Path launcher,
            String expectedProfile,
            String expectedReplacement,
            boolean confirmed,
            boolean json) {
        static Options parse(String[] args, int offset) {
            Path game = null;
            Path launcher = null;
            String expectedProfile = null;
            String expectedReplacement = null;
            boolean confirmed = false;
            boolean json = false;
            for (int index = offset; index < args.length; index++) {
                switch (args[index]) {
                    case "--game" -> game = Path.of(requireValue(args, ++index, "--game"));
                    case "--launcher" -> launcher = Path.of(requireValue(args, ++index, "--launcher"));
                    case "--expected-profile" -> expectedProfile =
                            requireValue(args, ++index, "--expected-profile").toLowerCase(Locale.ROOT);
                    case "--expected-replacement" -> expectedReplacement =
                            requireValue(args, ++index, "--expected-replacement").toLowerCase(Locale.ROOT);
                    case "--yes" -> confirmed = true;
                    case "--json" -> json = true;
                    default -> throw new IllegalArgumentException("Unknown profile option: " + args[index]);
                }
            }
            if (expectedProfile != null && !expectedProfile.matches("[0-9a-f]{64}")) {
                throw new IllegalArgumentException("--expected-profile must be a 64-character SHA-256");
            }
            if (expectedReplacement != null && !expectedReplacement.matches("[0-9a-f]{64}")) {
                throw new IllegalArgumentException("--expected-replacement must be a 64-character SHA-256");
            }
            return new Options(game, launcher, expectedProfile, expectedReplacement, confirmed, json);
        }

        private static String requireValue(String[] args, int index, String option) {
            if (index >= args.length) {
                throw new IllegalArgumentException(option + " requires a value");
            }
            return args[index];
        }
    }

    @FunctionalInterface
    interface DuplicatePublicationHook {
        void beforePublication(Path target) throws IOException;

        default void cleanupStagedProfile(Path staged) throws IOException {
            Files.deleteIfExists(staged);
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
