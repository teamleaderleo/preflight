package dev.starsector.preflight.cli;

import dev.starsector.preflight.core.Json;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.nio.file.attribute.PosixFilePermission;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.prefs.Preferences;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Inspects and transactionally relocates platform-specific mod roots embedded in saves. */
final class SaveCommand {
    private static final String FORMAT = "starsector-preflight-save-relocation-v1";
    private static final String CONFIRM = "--confirm-game-closed";
    private static final Pattern MOD_PATH = Pattern.compile("<path>([^<\\r\\n]+)</path>");
    private static final Pattern SLOT_CREATED = Pattern.compile(
            "<slotCreationTimestamp>(\\d+)</slotCreationTimestamp>");
    private static final Set<String> SAVE_FILES = Set.of(
            "descriptor.xml", "descriptor.xml.bak", "campaign.xml", "campaign.xml.bak");
    private static final DateTimeFormatter BACKUP_TIME = DateTimeFormatter
            .ofPattern("uuuuMMdd-HHmmss-SSS").withZone(ZoneOffset.UTC);

    private SaveCommand() {
    }

    static int execute(String[] args, int offset) throws Exception {
        if (offset >= args.length || !"relocate".equals(args[offset])) {
            throw new IllegalArgumentException("Expected `save relocate`");
        }
        Path game = null;
        boolean apply = false;
        boolean confirmed = false;
        boolean json = false;
        for (int index = offset + 1; index < args.length; index++) {
            String argument = args[index];
            switch (argument) {
                case "--game" -> game = Path.of(requireValue(args, ++index, argument));
                case "--apply" -> apply = true;
                case CONFIRM -> confirmed = true;
                case "--json" -> json = true;
                default -> throw new IllegalArgumentException("Unknown option: " + argument);
            }
        }
        if (confirmed && !apply) {
            throw new IllegalArgumentException(CONFIRM + " is used only with --apply");
        }
        if (apply && !confirmed) {
            throw new IllegalArgumentException(
                    "Save relocation requires " + CONFIRM + " after Starsector is closed");
        }
        Path install = InstallRoot.resolve(game);
        Path backupRoot = Path.of(System.getProperty("user.home"))
                .resolve(".starsector-preflight/save-relocation-backups");
        Report report = relocate(install, backupRoot, apply);
        Preferences preferences = Preferences.userRoot().node("/com/fs/starfarer");
        ContinuePlan continuePlan = planContinue(install, preferences.get("continue", null));
        if (apply && continuePlan.changeRequired()) {
            Path backup = report.backup() == null
                    ? createPreferenceBackup(backupRoot, install)
                    : report.backup();
            backupContinuePreference(backup, continuePlan);
            preferences.put("continue", continuePlan.target());
            preferences.sync();
            if (!continuePlan.target().equals(preferences.get("continue", null))) {
                throw new IOException("Starsector continue preference did not persist");
            }
            report = new Report(report.status(), report.filesChanged(), report.pathsChanged(),
                    backup, "repaired", continuePlan.target());
        } else {
            report = new Report(report.status(), report.filesChanged(), report.pathsChanged(),
                    report.backup(), continuePlan.changeRequired() ? "planned" : "current",
                    continuePlan.target());
        }
        if (json) {
            System.out.println(Json.object(report.view()));
        } else {
            System.out.printf("Save relocation %s: %d file(s), %d path(s)%n",
                    report.status(), report.filesChanged(), report.pathsChanged());
            if (report.backup() != null) System.out.println("Backup: " + report.backup());
            System.out.println("Continue pointer: " + report.continueStatus()
                    + (report.continueSave() == null ? "" : " (" + report.continueSave() + ")"));
            if (!apply && report.pathsChanged() > 0) {
                System.out.println("Re-run with --apply " + CONFIRM + " to write the validated plan.");
            }
        }
        return 0;
    }

    static Report relocate(Path installRoot, Path backupRoot, boolean apply) throws Exception {
        Path install = installRoot.toAbsolutePath().normalize();
        Path saves = install.resolve("saves");
        Path mods = install.resolve("mods");
        if (!Files.isDirectory(saves, LinkOption.NOFOLLOW_LINKS)) {
            throw new IOException("Save directory is unavailable: " + saves);
        }
        if (!Files.isDirectory(mods)) {
            throw new IOException("Mod directory is unavailable: " + mods);
        }

        List<Path> files;
        try (var stream = Files.walk(saves, 2)) {
            files = stream.filter(path -> Files.isRegularFile(path, LinkOption.NOFOLLOW_LINKS))
                    .filter(path -> path.getParent() != null
                            && path.getParent().getParent() != null
                            && path.getParent().getParent().equals(saves)
                            && path.getParent().getFileName().toString().startsWith("save_")
                            && SAVE_FILES.contains(path.getFileName().toString()))
                    .sorted(Comparator.comparing(path -> saves.relativize(path).toString()))
                    .toList();
        }

        List<Change> changes = new ArrayList<>();
        List<String> missing = new ArrayList<>();
        for (Path file : files) {
            String original = Files.readString(file, StandardCharsets.UTF_8);
            Matcher matcher = MOD_PATH.matcher(original);
            StringBuilder rewritten = new StringBuilder(original.length());
            int replacements = 0;
            while (matcher.find()) {
                String embedded = matcher.group(1);
                String normalized = embedded.replace('\\', '/');
                int separator = normalized.lastIndexOf('/');
                String directory = separator < 0 ? normalized : normalized.substring(separator + 1);
                if (directory.isBlank() || directory.contains("&")) {
                    missing.add(embedded + " (unsupported path encoding)");
                    continue;
                }
                Path target = mods.resolve(directory).normalize();
                if (!target.getParent().equals(mods) || !Files.isDirectory(target)) {
                    missing.add(embedded + " -> " + target);
                    continue;
                }
                String replacement = target.toString();
                if (!embedded.equals(replacement)) {
                    matcher.appendReplacement(rewritten, Matcher.quoteReplacement(
                            "<path>" + replacement + "</path>"));
                    replacements++;
                }
            }
            if (replacements > 0) {
                matcher.appendTail(rewritten);
                changes.add(new Change(file, rewritten.toString(), replacements));
            }
        }
        if (!missing.isEmpty()) {
            throw new IOException("Save relocation has " + missing.size()
                    + " unmapped mod path(s); first: " + missing.get(0));
        }
        int paths = changes.stream().mapToInt(Change::replacements).sum();
        if (!apply || changes.isEmpty()) {
            return new Report(changes.isEmpty() ? "unchanged" : "planned",
                    changes.size(), paths, null, "not-checked", null);
        }

        Files.createDirectories(backupRoot);
        Path backup = backupRoot.resolve(BACKUP_TIME.format(Instant.now()) + "-"
                + UUID.randomUUID().toString().substring(0, 8));
        Files.createDirectory(backup);
        List<Path> staged = new ArrayList<>();
        try {
            for (Change change : changes) {
                Path relative = saves.relativize(change.file());
                Path captured = backup.resolve("saves").resolve(relative);
                Files.createDirectories(captured.getParent());
                Files.copy(change.file(), captured, StandardCopyOption.COPY_ATTRIBUTES);

                Path temporary = change.file().resolveSibling(change.file().getFileName()
                        + ".preflight-relocate-" + UUID.randomUUID() + ".tmp");
                Files.writeString(temporary, change.rewritten(), StandardCharsets.UTF_8,
                        StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE);
                copyPermissions(change.file(), temporary);
                staged.add(temporary);
            }
            Map<String, Object> manifest = new LinkedHashMap<>();
            manifest.put("format", FORMAT);
            manifest.put("createdAt", Instant.now().toString());
            manifest.put("installRoot", install.toString());
            manifest.put("files", changes.stream().map(change -> Map.of(
                    "path", saves.relativize(change.file()).toString().replace('\\', '/'),
                    "replacements", change.replacements())).toList());
            Files.writeString(backup.resolve("manifest.json"), Json.object(manifest)
                    + System.lineSeparator(), StandardCharsets.UTF_8,
                    StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE);

            for (int index = 0; index < changes.size(); index++) {
                atomicReplace(staged.get(index), changes.get(index).file());
            }
        } catch (Exception failure) {
            for (Change change : changes) {
                Path captured = backup.resolve("saves").resolve(saves.relativize(change.file()));
                if (Files.isRegularFile(captured, LinkOption.NOFOLLOW_LINKS)) {
                    Path restore = change.file().resolveSibling(change.file().getFileName()
                            + ".preflight-restore-" + UUID.randomUUID() + ".tmp");
                    Files.copy(captured, restore);
                    copyPermissions(captured, restore);
                    atomicReplace(restore, change.file());
                }
            }
            throw failure;
        } finally {
            for (Path temporary : staged) Files.deleteIfExists(temporary);
        }
        return new Report("applied", changes.size(), paths, backup, "not-checked", null);
    }

    static ContinuePlan planContinue(Path installRoot, String current) throws IOException {
        Path install = installRoot.toAbsolutePath().normalize();
        Path saves = install.resolve("saves");
        if (current != null && !current.isBlank()) {
            Path selected = install.resolve(current).normalize();
            if (selected.getParent() != null && selected.getParent().equals(saves)
                    && Files.isRegularFile(selected.resolve("descriptor.xml"),
                            LinkOption.NOFOLLOW_LINKS)) {
                return new ContinuePlan(current, current, false);
            }
        }
        List<SaveCandidate> candidates = new ArrayList<>();
        try (var stream = Files.list(saves)) {
            for (Path directory : stream.filter(path -> Files.isDirectory(
                    path, LinkOption.NOFOLLOW_LINKS)).toList()) {
                if (!directory.getFileName().toString().startsWith("save_")) continue;
                Path descriptor = directory.resolve("descriptor.xml");
                if (!Files.isRegularFile(descriptor, LinkOption.NOFOLLOW_LINKS)) continue;
                Matcher timestamp = SLOT_CREATED.matcher(
                        Files.readString(descriptor, StandardCharsets.UTF_8));
                long created = timestamp.find() ? Long.parseLong(timestamp.group(1)) : 0;
                candidates.add(new SaveCandidate(directory.getFileName().toString(), created));
            }
        }
        SaveCandidate newest = candidates.stream().max(Comparator
                .comparingLong(SaveCandidate::created)
                .thenComparing(SaveCandidate::directory)).orElseThrow(
                        () -> new IOException("No live save descriptor can repair Continue"));
        return new ContinuePlan(current, "./saves/" + newest.directory(), true);
    }

    private static Path createPreferenceBackup(Path backupRoot, Path install) throws IOException {
        Files.createDirectories(backupRoot);
        Path backup = backupRoot.resolve(BACKUP_TIME.format(Instant.now()) + "-"
                + UUID.randomUUID().toString().substring(0, 8));
        Files.createDirectory(backup);
        Map<String, Object> manifest = new LinkedHashMap<>();
        manifest.put("format", FORMAT);
        manifest.put("createdAt", Instant.now().toString());
        manifest.put("installRoot", install.toString());
        manifest.put("files", List.of());
        Files.writeString(backup.resolve("manifest.json"), Json.object(manifest)
                + System.lineSeparator(), StandardCharsets.UTF_8,
                StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE);
        return backup;
    }

    private static void backupContinuePreference(Path backup, ContinuePlan plan) throws IOException {
        Path preferenceFile = Path.of(System.getProperty("user.home"))
                .resolve(".java/.userPrefs/com/fs/starfarer/prefs.xml");
        if (Files.isRegularFile(preferenceFile, LinkOption.NOFOLLOW_LINKS)) {
            Path captured = backup.resolve("java-prefs/com/fs/starfarer/prefs.xml");
            Files.createDirectories(captured.getParent());
            Files.copy(preferenceFile, captured, StandardCopyOption.COPY_ATTRIBUTES);
        }
        Map<String, Object> receipt = new LinkedHashMap<>();
        receipt.put("format", FORMAT);
        receipt.put("previous", plan.previous());
        receipt.put("target", plan.target());
        Files.writeString(backup.resolve("continue-preference.json"), Json.object(receipt)
                + System.lineSeparator(), StandardCharsets.UTF_8,
                StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE);
    }

    private static void atomicReplace(Path source, Path target) throws IOException {
        try {
            Files.move(source, target, StandardCopyOption.ATOMIC_MOVE,
                    StandardCopyOption.REPLACE_EXISTING);
        } catch (AtomicMoveNotSupportedException unsupported) {
            throw new IOException("Atomic save replacement is unavailable for " + target, unsupported);
        }
    }

    private static void copyPermissions(Path source, Path target) throws IOException {
        try {
            Set<PosixFilePermission> permissions = Files.getPosixFilePermissions(source);
            Files.setPosixFilePermissions(target, permissions);
        } catch (UnsupportedOperationException ignored) {
            // The same-directory atomic replacement remains safe on non-POSIX filesystems.
        }
    }

    private static String requireValue(String[] args, int index, String option) {
        if (index >= args.length) throw new IllegalArgumentException("Missing value for " + option);
        return args[index];
    }

    record Report(String status, int filesChanged, int pathsChanged, Path backup,
                  String continueStatus, String continueSave) {
        Map<String, Object> view() {
            Map<String, Object> value = new LinkedHashMap<>();
            value.put("format", FORMAT);
            value.put("status", status);
            value.put("filesChanged", filesChanged);
            value.put("pathsChanged", pathsChanged);
            value.put("backup", backup == null ? null : backup.toString());
            value.put("continueStatus", continueStatus);
            value.put("continueSave", continueSave);
            return value;
        }
    }

    record ContinuePlan(String previous, String target, boolean changeRequired) {
    }

    private record SaveCandidate(String directory, long created) {
    }

    private record Change(Path file, String rewritten, int replacements) {
    }
}
