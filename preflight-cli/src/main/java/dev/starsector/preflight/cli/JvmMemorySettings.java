package dev.starsector.preflight.cli;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.nio.file.attribute.BasicFileAttributes;
import java.nio.file.attribute.FileTime;
import java.nio.file.attribute.PosixFilePermission;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Finds and safely changes the heap flags owned by the launcher that will actually run. */
final class JvmMemorySettings {
    private static final long MAX_SETTINGS_BYTES = 512 * 1024;
    private static final int MIN_HEAP_MIB = 512;
    private static final int MAX_HEAP_MIB = 32 * 1024;
    private static final Pattern MAX_HEAP = Pattern.compile("(?<!\\S)-Xmx(\\d+)([kKmMgG])(?=\\s|$)");
    private static final Pattern INITIAL_HEAP = Pattern.compile("(?<!\\S)-Xms(\\d+)([kKmMgG])(?=\\s|$)");
    private static final Pattern RESPONSE_FILE = Pattern.compile("(?:^|\\s)@(?:\"([^\"]+)\"|'([^']+)'|([^\\s]+))");
    private static final DateTimeFormatter BACKUP_TIME = DateTimeFormatter
            .ofPattern("uuuuMMdd-HHmmss-SSS")
            .withZone(ZoneOffset.UTC);
    private static final Pattern BACKUP_FILE = Pattern.compile(
            "\\d{8}-\\d{6}-\\d{3}-[0-9a-f]{8}-[A-Za-z0-9._-]+");
    private static final String SETTINGS_CHANGED =
            "The heap settings changed while they were being reviewed; review them again before applying.";

    private JvmMemorySettings() {
    }

    static Snapshot inspect(Path installRoot) {
        // Discovery can name a launcher while the caller still has no root to resolve it against.
        // Saying so beats the NullPointerException message this used to hand the user.
        if (installRoot == null) {
            return Snapshot.unavailable(
                    "No Starsector installation was found. Set STARSECTOR_HOME or use --game.");
        }
        try {
            DiscoveryResult discovery = StarsectorDiscovery.discover(
                    Platform.current(),
                    Path.of(System.getProperty("user.home")),
                    Path.of(System.getProperty("user.dir")),
                    System.getenv(),
                    installRoot,
                    null);
            if (discovery.selected() == null) {
                return Snapshot.unavailable("Preflight couldn't identify the launcher that owns the heap setting");
            }
            return inspect(installRoot, discovery.selected());
        } catch (Exception failure) {
            return Snapshot.unavailable("Preflight couldn't inspect the launcher heap setting: "
                    + failure.getMessage());
        }
    }

    static Snapshot inspect(Path installRoot, LaunchTarget target) {
        return inspect(installRoot, target, path -> Files.readString(path, StandardCharsets.UTF_8));
    }

    /** Test seam proving containment is established before a candidate is opened. */
    static Snapshot inspect(Path installRoot, LaunchTarget target, TextReader reader) {
        Path root = installRoot.toAbsolutePath().normalize();
        Path launcher = target.launcher().toAbsolutePath().normalize();
        List<String> diagnostics = new ArrayList<>();
        Path realRoot;
        try {
            realRoot = root.toRealPath();
        } catch (IOException | RuntimeException unavailable) {
            return Snapshot.unavailable(
                    "Preflight couldn't establish the selected installation directory",
                    diagnostics);
        }
        Path realLauncher = containedFile(realRoot, launcher, diagnostics, "selected launcher");
        if (realLauncher == null) {
            return Snapshot.unavailable(
                    "The selected launcher isn't a regular file inside the selected installation",
                    diagnostics);
        }

        String launcherText = textLauncher(realLauncher)
                ? boundedText(realLauncher, diagnostics, reader)
                : null;
        Snapshot direct = snapshot(realLauncher, launcherText, "launcher", diagnostics);
        if (direct != null) return direct;

        if (launcherText != null) {
            List<Snapshot> referenced = new ArrayList<>();
            Matcher references = RESPONSE_FILE.matcher(launcherText);
            while (references.find()) {
                String name = firstNonNull(references.group(1), references.group(2), references.group(3));
                Path candidate = realLauncher.getParent().resolve(name).normalize();
                if (!candidate.startsWith(realRoot)) {
                    diagnostics.add("Skipped a launcher response file outside the selected installation");
                    continue;
                }
                Path contained = containedFile(
                        realRoot, candidate, diagnostics, "launcher response file");
                Snapshot found = contained == null ? null : snapshot(
                        contained,
                        boundedText(contained, diagnostics, reader),
                        "launcher response file",
                        diagnostics);
                if (found != null) referenced.add(found);
            }
            Snapshot selected = unique(referenced, diagnostics, "referenced VM-parameter files");
            if (selected != null) return selected;
        }

        LinkedHashSet<Path> known = new LinkedHashSet<>();
        known.add(realLauncher.resolveSibling("fr.vmparams"));
        known.add(realLauncher.resolveSibling("starsector.vmparams"));
        known.add(realLauncher.resolveSibling("vmparams"));
        known.add(realRoot.resolve("starsector-core/starsector.vmparams"));
        known.add(realRoot.resolve("starsector-core/vmparams"));
        known.add(realRoot.resolve("starsector.vmparams"));
        known.add(realRoot.resolve("vmparams"));
        known.add(realRoot.resolve("Contents/MacOS/starsector_mac.sh"));

        List<Snapshot> discovered = new ArrayList<>();
        for (Path candidate : known) {
            Path normalized = candidate.toAbsolutePath().normalize();
            if (normalized.equals(realLauncher)) continue;
            Path contained = containedFile(
                    realRoot, normalized, diagnostics, "VM-parameter file");
            Snapshot found = contained == null ? null : snapshot(
                    contained,
                    boundedText(contained, diagnostics, reader),
                    "VM-parameter file",
                    diagnostics);
            if (found != null) discovered.add(found);
        }
        Snapshot selected = unique(discovered, diagnostics, "candidate VM-parameter files");
        if (selected != null) return selected;
        if (discovered.size() > 1) {
            return Snapshot.unavailable("Multiple files contain heap settings; choose the launcher explicitly",
                    diagnostics);
        }
        return Snapshot.unavailable("No effective -Xmx setting was found for the selected launcher", diagnostics);
    }

    static UpdateResult update(Path installRoot, int memoryMiB) throws IOException {
        LaunchTarget target;
        try {
            DiscoveryResult discovery = StarsectorDiscovery.discover(
                    Platform.current(),
                    Path.of(System.getProperty("user.home")),
                    Path.of(System.getProperty("user.dir")),
                    System.getenv(),
                    installRoot,
                    null);
            target = discovery.selected();
        } catch (Exception failure) {
            throw new IOException("Preflight couldn't identify the launcher that owns the heap setting", failure);
        }
        if (target == null) {
            throw new IOException("Preflight couldn't identify the launcher that owns the heap setting");
        }
        return update(installRoot, target, memoryMiB, PreflightHome.current().launcherFileBackups());
    }

    static UpdateResult update(
            Path installRoot,
            LaunchTarget target,
            int memoryMiB,
            Path backupDirectory) throws IOException {
        return update(
                installRoot,
                target,
                memoryMiB,
                backupDirectory,
                ignored -> {},
                ignored -> {},
                ignored -> {},
                false);
    }

    /** Test seam for deterministic external-edit races around publication; production uses no-op hooks. */
    static UpdateResult update(
            Path installRoot,
            LaunchTarget target,
            int memoryMiB,
            Path backupDirectory,
            UpdateHook beforePublish,
            UpdateHook afterPublish) throws IOException {
        return update(
                installRoot,
                target,
                memoryMiB,
                backupDirectory,
                beforePublish,
                ignored -> {},
                afterPublish,
                false);
    }

    /**
     * Test seam at the last content-and-identity reread, plus the non-atomic replacement fallback.
     * Production supplies no-op hooks and uses the platform's ordinary atomic-move attempt.
     */
    static UpdateResult update(
            Path installRoot,
            LaunchTarget target,
            int memoryMiB,
            Path backupDirectory,
            UpdateHook beforePublish,
            UpdateHook beforeCommitCheck,
            UpdateHook afterPublish,
            boolean forceReplaceFallback) throws IOException {
        if (beforePublish == null || beforeCommitCheck == null || afterPublish == null) {
            throw new IllegalArgumentException("Heap update hooks are required");
        }
        if (memoryMiB < MIN_HEAP_MIB || memoryMiB > MAX_HEAP_MIB || memoryMiB % 256 != 0) {
            throw new IllegalArgumentException("Memory must be 512-32768 MiB in 256 MiB steps");
        }
        Snapshot before = inspect(installRoot, target);
        if (!before.available() || !before.editable() || before.source() == null) {
            throw new IOException(before.reason() == null
                    ? "The selected launcher's heap setting isn't safely editable"
                    : before.reason());
        }
        if (Integer.valueOf(memoryMiB).equals(before.maxHeapMiB())
                && (before.initialHeapMiB() == null || Integer.valueOf(memoryMiB).equals(before.initialHeapMiB()))) {
            return new UpdateResult(before, null, false);
        }

        Path source = before.source();
        SourceState original = readStableSource(source);
        if (original.bytes().length > MAX_SETTINGS_BYTES) {
            throw new IOException("The heap settings file is too large to edit safely: " + source);
        }
        String text = new String(original.bytes(), StandardCharsets.UTF_8);
        if (count(MAX_HEAP.matcher(text)) != 1 || count(INITIAL_HEAP.matcher(text)) > 1) {
            throw new IOException(SETTINGS_CHANGED);
        }
        String replacement = MAX_HEAP.matcher(text).replaceFirst("-Xmx" + memoryMiB + "m");
        if (INITIAL_HEAP.matcher(replacement).find()) {
            replacement = INITIAL_HEAP.matcher(replacement).replaceFirst("-Xms" + memoryMiB + "m");
        }
        byte[] replacementBytes = replacement.getBytes(StandardCharsets.UTF_8);

        // Refuse an external edit before creating a recovery artifact. Publication rechecks once
        // more after the backup write, so a change during backup creation is still fail-closed.
        beforePublish.run(source);
        requireUnchanged(source, original);
        Path backup = writeBackup(backupDirectory, source, original.bytes());
        boolean published = false;
        SourceState publishedState = null;
        try {
            publishIfUnchanged(
                    source, original, replacementBytes, beforeCommitCheck, forceReplaceFallback);
            published = true;
            publishedState = readStableSource(source);
            if (!Arrays.equals(replacementBytes, publishedState.bytes())) {
                throw new IOException("The heap settings changed immediately after Preflight updated them");
            }

            afterPublish.run(source);
            Snapshot after = inspect(installRoot, target);
            if (!after.available() || !Integer.valueOf(memoryMiB).equals(after.maxHeapMiB())
                    || (after.initialHeapMiB() != null
                            && !Integer.valueOf(memoryMiB).equals(after.initialHeapMiB()))) {
                throw new IOException("The launcher didn't report the requested heap after the edit");
            }
            return new UpdateResult(after, backup, true);
        } catch (Exception failed) {
            if (published) {
                try {
                    if (publishedState == null) {
                        throw new IOException(
                                "Preflight could not prove its published heap settings were unchanged; backup retained");
                    }
                    publishIfUnchanged(
                            source, publishedState, original.bytes(), ignored -> {}, false);
                } catch (Exception rollbackFailed) {
                    failed.addSuppressed(rollbackFailed);
                }
            } else {
                try {
                    Files.deleteIfExists(backup);
                } catch (IOException cleanupFailed) {
                    failed.addSuppressed(cleanupFailed);
                }
            }
            if (failed instanceof IOException io) throw io;
            throw new IOException("Could not update the launcher heap setting", failed);
        }
    }

    private static Snapshot snapshot(
            Path source,
            String text,
            String sourceKind,
            List<String> diagnostics) {
        if (text == null) return null;
        List<Integer> maximums = values(MAX_HEAP.matcher(text));
        if (maximums.isEmpty()) return null;
        List<Integer> initials = values(INITIAL_HEAP.matcher(text));
        boolean editable = maximums.size() == 1 && initials.size() <= 1 && Files.isWritable(source);
        String reason = null;
        if (maximums.size() != 1) reason = "The selected file contains more than one -Xmx setting";
        else if (initials.size() > 1) reason = "The selected file contains more than one -Xms setting";
        else if (!Files.isWritable(source)) reason = "The heap settings file isn't writable";
        return new Snapshot(
                true,
                editable,
                maximums.get(maximums.size() - 1),
                initials.isEmpty() ? null : initials.get(initials.size() - 1),
                source.toAbsolutePath().normalize(),
                sourceKind,
                reason,
                List.copyOf(diagnostics));
    }

    private static Snapshot unique(List<Snapshot> values, List<String> diagnostics, String label) {
        if (values.size() == 1) return values.get(0);
        if (values.size() > 1) diagnostics.add("Found heap settings in multiple " + label);
        return null;
    }

    private static List<Integer> values(Matcher matcher) {
        List<Integer> values = new ArrayList<>();
        while (matcher.find()) values.add(mebibytes(matcher.group(1), matcher.group(2)));
        return values;
    }

    private static int count(Matcher matcher) {
        int count = 0;
        while (matcher.find()) count++;
        return count;
    }

    private static int mebibytes(String number, String unit) {
        long value = Long.parseLong(number);
        long mib = switch (Character.toLowerCase(unit.charAt(0))) {
            case 'k' -> (value + 1023) / 1024;
            case 'm' -> value;
            case 'g' -> Math.multiplyExact(value, 1024L);
            default -> throw new IllegalArgumentException("Unknown heap unit: " + unit);
        };
        if (mib > Integer.MAX_VALUE) throw new IllegalArgumentException("Heap size is too large");
        return (int) mib;
    }

    private static String boundedText(
            Path path, List<String> diagnostics, TextReader reader) {
        try {
            if (!Files.isRegularFile(path, LinkOption.NOFOLLOW_LINKS)) return null;
            long bytes = Files.size(path);
            if (bytes > MAX_SETTINGS_BYTES) {
                diagnostics.add("Skipped oversized heap settings file: " + path);
                return null;
            }
            return reader.read(path);
        } catch (IOException | RuntimeException unreadable) {
            diagnostics.add("Could not read heap settings from " + path + ": " + unreadable.getMessage());
            return null;
        }
    }

    private static Path containedFile(
            Path realRoot, Path candidate, List<String> diagnostics, String label) {
        try {
            Path real = candidate.toRealPath();
            if (!real.startsWith(realRoot)) {
                diagnostics.add("Skipped " + label + " outside the selected installation");
                return null;
            }
            if (!Files.isRegularFile(real, LinkOption.NOFOLLOW_LINKS)) {
                return null;
            }
            return real;
        } catch (IOException | RuntimeException unreadable) {
            return null;
        }
    }

    private static boolean textLauncher(Path launcher) {
        String name = launcher.getFileName().toString().toLowerCase(java.util.Locale.ROOT);
        return name.endsWith(".sh") || name.endsWith(".command") || name.endsWith(".bat")
                || name.endsWith(".cmd") || (!name.contains(".") && Files.isExecutable(launcher));
    }

    private static String firstNonNull(String... values) {
        for (String value : values) if (value != null) return value;
        throw new IllegalArgumentException("Response file reference has no path");
    }

    @FunctionalInterface
    interface TextReader {
        String read(Path path) throws IOException;
    }

    private static Path writeBackup(Path directory, Path source, byte[] original) throws IOException {
        directory = SafetyArtifactRetention.requireRealDirectory(directory);
        String safeName = source.getFileName().toString().replaceAll("[^A-Za-z0-9._-]", "_");
        Path backup = directory.resolve(BACKUP_TIME.format(Instant.now()) + "-"
                + UUID.randomUUID().toString().substring(0, 8) + "-" + safeName);
        Files.write(backup, original, StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE);
        SafetyArtifactRetention.retainNewest(
                directory, BACKUP_FILE, SafetyArtifactRetention.MAX_BACKUPS_PER_DIRECTORY);
        return backup.toAbsolutePath().normalize();
    }

    private static SourceState readStableSource(Path source) throws IOException {
        BasicFileAttributes before = Files.readAttributes(
                source, BasicFileAttributes.class, LinkOption.NOFOLLOW_LINKS);
        byte[] bytes = Files.readAllBytes(source);
        BasicFileAttributes after = Files.readAttributes(
                source, BasicFileAttributes.class, LinkOption.NOFOLLOW_LINKS);
        FileIdentity beforeIdentity = FileIdentity.from(before);
        FileIdentity afterIdentity = FileIdentity.from(after);
        if (!beforeIdentity.equals(afterIdentity)) {
            throw new IOException(SETTINGS_CHANGED);
        }
        return new SourceState(bytes, afterIdentity);
    }

    private static void requireUnchanged(Path source, SourceState expected) throws IOException {
        SourceState current = readStableSource(source);
        if (!expected.identity().equals(current.identity())
                || !Arrays.equals(expected.bytes(), current.bytes())) {
            throw new IOException(SETTINGS_CHANGED);
        }
    }

    private static void publishIfUnchanged(
            Path destination,
            SourceState expected,
            byte[] bytes,
            UpdateHook beforeCommitCheck,
            boolean forceReplaceFallback) throws IOException {
        Path absolute = destination.toAbsolutePath().normalize();
        Path temporary = Files.createTempFile(absolute.getParent(), ".preflight-heap-", ".tmp");
        try {
            Files.write(temporary, bytes, StandardOpenOption.TRUNCATE_EXISTING);
            try {
                Set<PosixFilePermission> permissions = Files.getPosixFilePermissions(absolute);
                Files.setPosixFilePermissions(temporary, permissions);
            } catch (UnsupportedOperationException ignored) {
                // Windows has no POSIX permissions; replacing a vmparams file needs none.
            }
            beforeCommitCheck.run(absolute);
            // Explicit Apply keeps outside settings tools quiescent across this final interval. A
            // stable content-and-identity reread is Java 17's strongest available pre-publication
            // check. Preflight's lease coordinates Preflight processes only; the pathname move has
            // no compare-and-swap primitive against an independent external writer.
            requireUnchanged(absolute, expected);
            if (forceReplaceFallback) {
                // Model the second publication attempt reached after an atomic move is rejected.
                beforeCommitCheck.run(absolute);
                requireUnchanged(absolute, expected);
                Files.move(temporary, absolute, StandardCopyOption.REPLACE_EXISTING);
            } else {
                try {
                    Files.move(temporary, absolute,
                            StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
                } catch (AtomicMoveNotSupportedException unsupported) {
                    beforeCommitCheck.run(absolute);
                    requireUnchanged(absolute, expected);
                    Files.move(temporary, absolute, StandardCopyOption.REPLACE_EXISTING);
                }
            }
        } finally {
            Files.deleteIfExists(temporary);
        }
    }

    @FunctionalInterface
    interface UpdateHook {
        void run(Path source) throws IOException;
    }

    private record SourceState(byte[] bytes, FileIdentity identity) {
        SourceState {
            bytes = bytes.clone();
        }

        @Override
        public byte[] bytes() {
            return bytes.clone();
        }
    }

    private record FileIdentity(
            long size,
            FileTime modified,
            Object fileKey,
            boolean regularFile,
            boolean symbolicLink) {
        static FileIdentity from(BasicFileAttributes attributes) {
            return new FileIdentity(
                    attributes.size(),
                    attributes.lastModifiedTime(),
                    attributes.fileKey(),
                    attributes.isRegularFile(),
                    attributes.isSymbolicLink());
        }
    }

    record Snapshot(
            boolean available,
            boolean editable,
            Integer maxHeapMiB,
            Integer initialHeapMiB,
            Path source,
            String sourceKind,
            String reason,
            List<String> diagnostics) {
        Snapshot {
            diagnostics = List.copyOf(diagnostics);
        }

        static Snapshot unavailable(String reason) {
            return unavailable(reason, List.of());
        }

        static Snapshot unavailable(String reason, List<String> diagnostics) {
            return new Snapshot(false, false, null, null, null, null, reason, diagnostics);
        }

        Map<String, Object> toMap() {
            Map<String, Object> values = new LinkedHashMap<>();
            values.put("available", available);
            values.put("editable", editable);
            values.put("maxHeapMiB", maxHeapMiB);
            values.put("initialHeapMiB", initialHeapMiB);
            values.put("source", source);
            values.put("sourceKind", sourceKind);
            values.put("reason", reason);
            values.put("diagnostics", diagnostics);
            return values;
        }
    }

    record UpdateResult(Snapshot snapshot, Path backup, boolean changed) {
    }
}
