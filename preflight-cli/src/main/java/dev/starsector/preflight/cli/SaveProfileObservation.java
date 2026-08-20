package dev.starsector.preflight.cli;

import dev.starsector.preflight.core.Json;
import java.io.IOException;
import java.io.PrintStream;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.channels.FileLock;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.FileAlreadyExistsException;
import java.nio.file.FileVisitResult;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.nio.file.attribute.BasicFileAttributes;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.locks.ReentrantLock;
import java.util.function.BooleanSupplier;

/**
 * Local-only history connecting a save to the exact Preflight profile observed while it changed.
 *
 * <p>The claim is intentionally narrow. The observer snapshots filesystem metadata only beneath the
 * selected installation's {@code saves} directory. A changed save is associated with the profile
 * active for a Preflight-owned child-process lifetime. It does not inspect save contents and does
 * not claim creation, compatibility, or requirements.
 */
final class SaveProfileObservation {
    static final String FORMAT = "starsector-preflight-save-observations-v1";
    static final String FILE_NAME = "save-observations-v1.json";
    static final int MAX_RECORDS = 400;
    static final int MAX_MODS = 96;
    static final Duration MAX_AGE = Duration.ofDays(180);
    static final Duration MISSING_RETENTION = Duration.ofDays(30);
    static final Duration POLL_INTERVAL = Duration.ofMillis(250);
    static final int MAX_SAVES_PER_POLL = 400;
    static final int MAX_ENTRIES_PER_SAVE = 512;
    static final int MAX_SAVE_TREE_DEPTH = 6;
    static final int MAX_AGGREGATE_ENTRIES_PER_POLL = 8_192;

    private SaveProfileObservation() {
    }

    /**
     * Captures the exact setup identity before the child process exists.
     *
     * <p>This deliberately happens before {@link ProcessBuilder#start()} so a later manual edit to
     * the enabled-mod file cannot relabel a running game. The save baseline itself is captured only
     * after process start by {@link #start(Prepared, Process, PrintStream)}.
     */
    static Prepared prepare(ProcessBuilder builder, PrintStream diagnostics) {
        Objects.requireNonNull(builder, "builder");
        Objects.requireNonNull(diagnostics, "diagnostics");
        try {
            Context context = Context.from(builder);
            if (context == null) return Prepared.disabled();
            SessionIdentity identity = resolveIdentity(context);
            if (identity == null) {
                diagnostics.println(
                        "Preflight skipped local save/profile observation: exact launch profile fingerprint is unavailable");
                return Prepared.disabled();
            }
            return new Prepared(context, identity);
        } catch (Exception error) {
            diagnostics.println("Preflight could not prepare local save/profile observation: " + bounded(error));
            return Prepared.disabled();
        }
    }

    /** Starts the filesystem lifetime only after the Preflight-owned child exists. */
    static Observer start(Prepared prepared, Process process, PrintStream diagnostics) {
        Objects.requireNonNull(prepared, "prepared");
        Objects.requireNonNull(process, "process");
        Objects.requireNonNull(diagnostics, "diagnostics");
        if (prepared.context() == null || prepared.identity() == null) return Observer.disabled();
        try {
            Context context = prepared.context();
            Instant startedAt = Instant.now();
            Snapshot baseline =
                    snapshot(context.installRoot(), context.caseInsensitivePaths(), () -> true);
            Ledger ledger = new Ledger(context.home());
            ledger.reconcileBaseline(
                    context.installationKey(), baseline.states(), baseline.complete(), startedAt);
            Session session = new Session(
                    ledger,
                    context.installRoot(),
                    context.installationKey(),
                    context.caseInsensitivePaths(),
                    baseline.states(),
                    prepared.identity(),
                    startedAt);
            return Observer.running(process, session, diagnostics);
        } catch (Exception error) {
            diagnostics.println("Preflight could not start local save/profile observation: " + bounded(error));
            return Observer.disabled();
        }
    }

    static Path storeFile(PreflightHome home) {
        return home.root().resolve("runtime").resolve(FILE_NAME);
    }

    static Session testSession(
            PreflightHome home,
            Path installRoot,
            SessionIdentity identity,
            Instant startedAt,
            boolean caseInsensitivePaths) throws IOException {
        String installKey = pathKey(installRoot, caseInsensitivePaths);
        Snapshot baseline = snapshot(installRoot, caseInsensitivePaths, () -> true);
        Ledger ledger = new Ledger(home);
        ledger.reconcileBaseline(installKey, baseline.states(), baseline.complete(), startedAt);
        return new Session(
                ledger,
                installRoot,
                installKey,
                caseInsensitivePaths,
                baseline.states(),
                identity,
                startedAt);
    }

    static Prepared testPrepare(
            PreflightHome home,
            Path installRoot,
            String profileFingerprint,
            boolean caseInsensitivePaths) throws IOException {
        String installKey = pathKey(installRoot, caseInsensitivePaths);
        SavedProfileMatch saved = savedProfileMatch(
                home, installKey, profileFingerprint, caseInsensitivePaths);
        return new Prepared(null, new SessionIdentity(
                profileFingerprint, saved.displayName(), "test-build", saved.mods()));
    }

    static List<Observation> observations(PreflightHome home) throws IOException {
        return new Ledger(home).read().stream()
                .sorted(Comparator.comparing(Observation::observedAt).reversed()
                        .thenComparing(Observation::saveKey))
                .toList();
    }

    static List<Difference> differences(Observation observed, SessionIdentity current) {
        List<Difference> differences = new ArrayList<>(3);
        if (!Objects.equals(observed.profileFingerprint(), current.profileFingerprint())) {
            differences.add(Difference.PROFILE_FINGERPRINT);
        }
        if (!Objects.equals(observed.gameBuild(), current.gameBuild())) {
            differences.add(Difference.GAME_BUILD);
        }
        if (observed.mods() == null || current.mods() == null) {
            differences.add(Difference.MOD_METADATA_UNAVAILABLE);
        } else if (!Objects.equals(observed.mods(), current.mods())) {
            differences.add(Difference.MOD_METADATA);
        }
        return List.copyOf(differences);
    }

    enum Difference {
        PROFILE_FINGERPRINT,
        GAME_BUILD,
        MOD_METADATA,
        MOD_METADATA_UNAVAILABLE
    }

    record Mod(String id, String displayName, String version) {
        Mod {
            id = boundedText(id, 128);
            displayName = boundedNullable(displayName, 160);
            version = boundedNullable(version, 80);
            if (id.isBlank()) {
                throw new IllegalArgumentException("Mod id is required");
            }
        }
    }

    record SessionIdentity(
            String profileFingerprint,
            String profileDisplayName,
            String gameBuild,
            List<Mod> mods) {
        SessionIdentity {
            profileFingerprint = boundedText(profileFingerprint, 256);
            profileDisplayName = boundedNullable(profileDisplayName, 160);
            gameBuild = boundedText(gameBuild, 256);
            mods = boundedMods(mods);
            if (profileFingerprint.isBlank()) {
                throw new IllegalArgumentException("Profile fingerprint is required");
            }
            if (gameBuild.isBlank()) {
                throw new IllegalArgumentException("Game build identity is required");
            }
        }
    }

    record Observation(
            String installationKey,
            String saveKey,
            String saveName,
            String stateToken,
            Instant observedAt,
            Instant missingSince,
            String profileFingerprint,
            String profileDisplayName,
            String gameBuild,
            List<Mod> mods) {
        String lastObservedMessage() {
            String label = profileDisplayName;
            if (label == null || label.isBlank()) {
                label = "profile " + shortFingerprint(profileFingerprint);
            }
            return "This save was last observed with ‘" + label + "’.";
        }
    }

    record Prepared(Context context, SessionIdentity identity) {
        static Prepared disabled() {
            return new Prepared(null, null);
        }
    }

    static final class Session {
        private final Ledger ledger;
        private final Path installRoot;
        private final String installationKey;
        private final boolean caseInsensitivePaths;
        private final Map<String, SaveState> baseline;
        private final SessionIdentity identity;
        private final Instant startedAt;
        private final Set<String> changed = new LinkedHashSet<>();
        private final AtomicBoolean finished = new AtomicBoolean();

        Session(
                Ledger ledger,
                Path installRoot,
                String installationKey,
                boolean caseInsensitivePaths,
                Map<String, SaveState> baseline,
                SessionIdentity identity,
                Instant startedAt) {
            this.ledger = ledger;
            this.installRoot = installRoot;
            this.installationKey = installationKey;
            this.caseInsensitivePaths = caseInsensitivePaths;
            this.baseline = Map.copyOf(baseline);
            this.identity = identity;
            this.startedAt = startedAt;
        }

        synchronized void scanWhileOwned() throws IOException {
            scanWhileOwned(() -> true);
        }

        synchronized void scanWhileOwned(BooleanSupplier stillOwned) throws IOException {
            if (finished.get() || !stillOwned.getAsBoolean()) return;
            Snapshot current = snapshot(installRoot, caseInsensitivePaths, stillOwned);
            // A poll that began before termination and finished after termination is discarded.
            if (!stillOwned.getAsBoolean()) return;
            detectChanges(current.states());
        }

        synchronized void finish(Instant endedAt) throws IOException {
            if (!finished.compareAndSet(false, true)) return;
            Map<String, SaveState> finalState =
                    snapshot(installRoot, caseInsensitivePaths, () -> true).states();
            detectChanges(finalState);
            List<SaveState> observed = changed.stream()
                    .map(finalState::get)
                    .filter(Objects::nonNull)
                    .filter(state -> !state.maxModified().isAfter(endedAt))
                    .sorted(Comparator.comparing(SaveState::key))
                    .toList();
            if (!observed.isEmpty()) {
                ledger.record(installationKey, observed, identity, endedAt);
            } else {
                ledger.prune(endedAt);
            }
        }

        synchronized Set<String> changedSaveKeys() {
            return Set.copyOf(changed);
        }

        Instant startedAt() {
            return startedAt;
        }

        private void detectChanges(Map<String, SaveState> current) {
            for (SaveState state : current.values()) {
                SaveState before = baseline.get(state.key());
                if (before == null || !before.token().equals(state.token())) {
                    changed.add(state.key());
                }
            }
        }
    }

    static final class Observer implements AutoCloseable {
        private final Process process;
        private final Session session;
        private final ScheduledExecutorService poller;
        private final CompletableFuture<Instant> processEndedAt;
        private final PrintStream diagnostics;
        private final AtomicBoolean closed = new AtomicBoolean();

        private Observer(
                Process process,
                Session session,
                ScheduledExecutorService poller,
                CompletableFuture<Instant> processEndedAt,
                PrintStream diagnostics) {
            this.process = process;
            this.session = session;
            this.poller = poller;
            this.processEndedAt = processEndedAt;
            this.diagnostics = diagnostics;
        }

        static Observer disabled() {
            return new Observer(null, null, null, null, null);
        }

        static Observer running(Process process, Session session, PrintStream diagnostics) {
            ScheduledExecutorService poller = Executors.newSingleThreadScheduledExecutor(task -> daemonThread(
                    task, "preflight-save-observation"));
            CompletableFuture<Instant> processEndedAt = new CompletableFuture<>();
            Thread endWatcher = daemonThread(() -> {
                try {
                    process.waitFor();
                    processEndedAt.complete(Instant.now());
                } catch (InterruptedException interrupted) {
                    Thread.currentThread().interrupt();
                    processEndedAt.completeExceptionally(interrupted);
                }
            }, "preflight-save-observation-process-end");
            endWatcher.start();
            Observer observer = new Observer(process, session, poller, processEndedAt, diagnostics);
            poller.scheduleWithFixedDelay(
                    observer::poll,
                    POLL_INTERVAL.toMillis(),
                    POLL_INTERVAL.toMillis(),
                    TimeUnit.MILLISECONDS);
            return observer;
        }

        private void poll() {
            if (process == null || !process.isAlive() || closed.get()) return;
            try {
                session.scanWhileOwned(process::isAlive);
            } catch (IOException error) {
                if (diagnostics != null) {
                    diagnostics.println("Preflight could not scan local saves: " + bounded(error));
                }
            }
        }

        void processEnded(Instant fallbackEndedAt) {
            if (session == null || !closed.compareAndSet(false, true)) return;
            poller.shutdownNow();
            Instant endedAt = fallbackEndedAt;
            if (processEndedAt != null && process != null && !process.isAlive()) {
                try {
                    endedAt = processEndedAt.join();
                } catch (RuntimeException ignored) {
                    // The caller's post-wait timestamp remains a conservative fallback boundary.
                }
            }
            try {
                session.finish(endedAt);
            } catch (IOException error) {
                diagnostics.println("Preflight could not record local save/profile observation: " + bounded(error));
            }
        }

        @Override
        public void close() {
            processEnded(Instant.now());
        }
    }

    private record Context(
            PreflightHome home,
            Path installRoot,
            String installationKey,
            boolean caseInsensitivePaths,
            String launchProfileFingerprint) {
        static Context from(ProcessBuilder builder) throws IOException {
            String runDirectory = builder.environment().get("PREFLIGHT_RUN_DIR");
            if (runDirectory == null || runDirectory.isBlank()) return null;
            Path metadata = Path.of(runDirectory).resolve("run.json");
            if (!Files.isRegularFile(metadata)) return null;
            Map<String, Object> values = StrictJson.object(Files.readString(metadata));
            Object installRootValue = values.get("installRoot");
            if (!(installRootValue instanceof String installRootText) || installRootText.isBlank()) return null;
            Path installRoot = Path.of(installRootText).toAbsolutePath().normalize();
            boolean caseInsensitive = SaveProfileObservation.caseInsensitivePaths();
            String launchFingerprint = values.get("textureProfileFingerprint") instanceof String value
                    && !value.isBlank() ? value : null;
            return new Context(
                    PreflightHome.current(),
                    installRoot,
                    pathKey(installRoot, caseInsensitive),
                    caseInsensitive,
                    launchFingerprint);
        }
    }

    private static SessionIdentity resolveIdentity(Context context) throws Exception {
        String fingerprint = context.launchProfileFingerprint();
        if (fingerprint == null) {
            return null;
        }
        List<Path> gameJars = PrepareAudioCommand.jars(context.installRoot());
        String gameBuild = PrepareAudioCommand.starsectorBuildIdentity(gameJars);
        SavedProfileMatch saved = savedProfileMatch(
                context.home(), context.installationKey(), fingerprint, context.caseInsensitivePaths());
        return new SessionIdentity(fingerprint, saved.displayName(), gameBuild, saved.mods());
    }

    private static SavedProfileMatch savedProfileMatch(
            PreflightHome home,
            String installationKey,
            String profileFingerprint,
            boolean caseInsensitivePaths) throws IOException {
        Path directory = home.profiles();
        if (!Files.isDirectory(directory)) return new SavedProfileMatch(null, null);
        List<SavedProfileCandidate> matches = new ArrayList<>();
        try (var stream = Files.list(directory)) {
            for (Path file : stream.filter(path -> path.getFileName().toString().endsWith(".json"))
                    .sorted()
                    .limit(MAX_RECORDS)
                    .toList()) {
                try {
                    Map<String, Object> values = StrictJson.object(Files.readString(file));
                    if (!(values.get("installRoot") instanceof String root)
                            || !installationKey.equals(pathKey(Path.of(root), caseInsensitivePaths))) {
                        continue;
                    }
                    if (!profileFingerprint.equalsIgnoreCase(String.valueOf(values.get("profileFingerprint")))) {
                        continue;
                    }
                    String name = values.get("name") instanceof String value ? boundedNullable(value, 160) : null;
                    Instant savedAt = parseInstant(values.get("savedAt"));
                    List<Mod> mods = parseSavedModIds(values.get("enabledMods"));
                    matches.add(new SavedProfileCandidate(name, savedAt, mods));
                } catch (RuntimeException ignored) {
                    // One malformed historical profile cannot establish or erase an association.
                }
            }
        }
        if (matches.isEmpty()) return new SavedProfileMatch(null, null);
        matches.sort(Comparator.comparing(
                        SavedProfileCandidate::savedAt,
                        Comparator.nullsLast(Comparator.reverseOrder()))
                .thenComparing(candidate -> candidate.displayName() == null ? "" : candidate.displayName()));
        Set<String> names = new LinkedHashSet<>();
        for (SavedProfileCandidate match : matches) {
            if (match.displayName() != null && !match.displayName().isBlank()) names.add(match.displayName());
        }
        String displayName = names.size() == 1 ? names.iterator().next() : null;
        return new SavedProfileMatch(displayName, matches.get(0).mods());
    }

    private record SavedProfileCandidate(String displayName, Instant savedAt, List<Mod> mods) {
    }

    private record SavedProfileMatch(String displayName, List<Mod> mods) {
    }

    private record SaveState(String key, String displayName, String token, Instant maxModified) {
    }

    private record Snapshot(Map<String, SaveState> states, boolean complete) {
        private Snapshot {
            states = Map.copyOf(states);
        }

        static Snapshot unavailable() {
            return new Snapshot(Map.of(), false);
        }
    }

    private static Snapshot snapshot(
            Path installRoot,
            boolean caseInsensitivePaths,
            BooleanSupplier stillOwned) throws IOException {
        Path saves = installRoot.resolve("saves");
        if (!Files.isDirectory(saves)) return new Snapshot(Map.of(), true);
        if (!stillOwned.getAsBoolean()) return Snapshot.unavailable();
        Map<String, SaveState> states = new HashMap<>();
        int[] aggregateWork = {0};
        boolean complete = true;
        try (var stream = Files.list(saves)) {
            List<Path> candidates = stream.filter(path ->
                            Files.isDirectory(path, java.nio.file.LinkOption.NOFOLLOW_LINKS))
                    .filter(path -> !Files.isSymbolicLink(path))
                    .sorted()
                    .limit(MAX_SAVES_PER_POLL + 1L)
                    .toList();
            if (candidates.size() > MAX_SAVES_PER_POLL) {
                complete = false;
                candidates = candidates.subList(0, MAX_SAVES_PER_POLL);
            }
            for (Path save : candidates) {
                if (!stillOwned.getAsBoolean()) {
                    return Snapshot.unavailable();
                }
                if (aggregateWork[0] >= MAX_AGGREGATE_ENTRIES_PER_POLL) {
                    complete = false;
                    break;
                }
                String directoryEntryName = save.getFileName().toString();
                String displayName = boundedText(directoryEntryName, 255);
                String key = saveKey(directoryEntryName);
                SaveDigest digest = digestMetadata(save, stillOwned, aggregateWork);
                if (digest != null) {
                    states.put(key, new SaveState(key, displayName, digest.token(), digest.maxModified()));
                } else {
                    complete = false;
                }
            }
        }
        return new Snapshot(states, complete);
    }

    private record SaveDigest(String token, Instant maxModified) {
    }

    private static SaveDigest digestMetadata(
            Path saveRoot,
            BooleanSupplier stillOwned,
            int[] aggregateWork) throws IOException {
        MessageDigest digest = sha256();
        Instant[] newest = {Instant.EPOCH};
        int[] saveEntries = {0};
        boolean[] excluded = {false};
        Files.walkFileTree(saveRoot, new SimpleFileVisitor<>() {
            @Override
            public FileVisitResult preVisitDirectory(Path dir, BasicFileAttributes attrs)
                    throws IOException {
                if (!stillOwned.getAsBoolean()) return FileVisitResult.TERMINATE;
                if (!dir.equals(saveRoot) && attrs.isSymbolicLink()) return FileVisitResult.SKIP_SUBTREE;
                int depth = dir.equals(saveRoot) ? 0 : saveRoot.relativize(dir).getNameCount();
                if (depth == MAX_SAVE_TREE_DEPTH) {
                    try (var children = Files.list(dir)) {
                        if (children.findAny().isPresent()) {
                            excluded[0] = true;
                            return FileVisitResult.TERMINATE;
                        }
                    }
                }
                if (!claimEntry(saveEntries, aggregateWork)) {
                    excluded[0] = true;
                    return FileVisitResult.TERMINATE;
                }
                updateDigest(digest, saveRoot.relativize(dir), attrs, (byte) 'D');
                newest[0] = latest(newest[0], attrs.lastModifiedTime().toInstant());
                return depth == MAX_SAVE_TREE_DEPTH
                        ? FileVisitResult.SKIP_SUBTREE
                        : FileVisitResult.CONTINUE;
            }

            @Override
            public FileVisitResult visitFile(Path file, BasicFileAttributes attrs) {
                if (!stillOwned.getAsBoolean()) return FileVisitResult.TERMINATE;
                if (attrs.isSymbolicLink()) return FileVisitResult.CONTINUE;
                if (!claimEntry(saveEntries, aggregateWork)) {
                    excluded[0] = true;
                    return FileVisitResult.TERMINATE;
                }
                updateDigest(digest, saveRoot.relativize(file), attrs, (byte) 'F');
                newest[0] = latest(newest[0], attrs.lastModifiedTime().toInstant());
                return FileVisitResult.CONTINUE;
            }
        });
        if (excluded[0] || !stillOwned.getAsBoolean()) {
            return null;
        }
        return new SaveDigest(hex(digest.digest()), newest[0]);
    }

    private static boolean claimEntry(int[] saveEntries, int[] aggregateWork) {
        saveEntries[0]++;
        aggregateWork[0]++;
        return saveEntries[0] <= MAX_ENTRIES_PER_SAVE
                && aggregateWork[0] <= MAX_AGGREGATE_ENTRIES_PER_POLL;
    }

    private static void updateDigest(
            MessageDigest digest,
            Path relative,
            BasicFileAttributes attrs,
            byte kind) {
        digest.update(kind);
        byte[] path = relative.toString().replace('\\', '/').getBytes(StandardCharsets.UTF_8);
        digest.update(ByteBuffer.allocate(Integer.BYTES).putInt(path.length).array());
        digest.update(path);
        digest.update(ByteBuffer.allocate(Long.BYTES).putLong(attrs.size()).array());
        digest.update(ByteBuffer.allocate(Long.BYTES).putLong(attrs.lastModifiedTime().toMillis()).array());
    }

    private static final class Ledger {
        private static final ReentrantLock JVM_LOCK = new ReentrantLock();
        private final PreflightHome home;

        Ledger(PreflightHome home) {
            this.home = home;
        }

        List<Observation> read() throws IOException {
            return withLock(this::readUnlocked);
        }

        void reconcileBaseline(
                String installationKey,
                Map<String, SaveState> baseline,
                boolean baselineComplete,
                Instant now) throws IOException {
            withLock(() -> {
                List<Observation> current = readUnlocked();
                List<Observation> next = new ArrayList<>();
                for (Observation observation : current) {
                    if (!observation.installationKey().equals(installationKey)) {
                        next.add(observation);
                        continue;
                    }
                    SaveState state = baseline.get(observation.saveKey());
                    if (state == null) {
                        if (!baselineComplete) {
                            next.add(observation);
                            continue;
                        }
                        Instant missing = observation.missingSince() == null ? now : observation.missingSince();
                        if (missing.plus(MISSING_RETENTION).isAfter(now)) {
                            next.add(copyWithMissing(observation, missing));
                        }
                        continue;
                    }
                    if (!observation.stateToken().equals(state.token())) {
                        // The save changed while Preflight did not own a game process. Its old profile
                        // association is no longer an observed fact about the current save identity.
                        continue;
                    }
                    next.add(copyWithMissing(observation, null));
                }
                writeUnlocked(retained(next, now));
                return null;
            });
        }

        void record(
                String installationKey,
                List<SaveState> states,
                SessionIdentity identity,
                Instant observedAt) throws IOException {
            withLock(() -> {
                Map<String, Observation> byKey = new LinkedHashMap<>();
                for (Observation observation : readUnlocked()) {
                    byKey.put(observation.installationKey() + "\n" + observation.saveKey(), observation);
                }
                for (SaveState state : states) {
                    Observation observation = new Observation(
                            installationKey,
                            state.key(),
                            state.displayName(),
                            state.token(),
                            observedAt,
                            null,
                            identity.profileFingerprint(),
                            identity.profileDisplayName(),
                            identity.gameBuild(),
                            identity.mods());
                    byKey.put(installationKey + "\n" + state.key(), observation);
                }
                writeUnlocked(retained(new ArrayList<>(byKey.values()), observedAt));
                return null;
            });
        }

        void prune(Instant now) throws IOException {
            withLock(() -> {
                writeUnlocked(retained(readUnlocked(), now));
                return null;
            });
        }

        private List<Observation> retained(List<Observation> observations, Instant now) {
            return observations.stream()
                    .filter(observation -> observation.observedAt().plus(MAX_AGE).isAfter(now))
                    .filter(observation -> observation.missingSince() == null
                            || observation.missingSince().plus(MISSING_RETENTION).isAfter(now))
                    .sorted(Comparator.comparing(Observation::observedAt).reversed()
                            .thenComparing(Observation::installationKey)
                            .thenComparing(Observation::saveKey))
                    .limit(MAX_RECORDS)
                    .toList();
        }

        private List<Observation> readUnlocked() throws IOException {
            Path file = ledgerFile(false);
            if (file == null) return List.of();
            Map<String, Object> root;
            try {
                root = StrictJson.object(Files.readString(file));
            } catch (RuntimeException malformed) {
                return List.of();
            }
            if (!FORMAT.equals(root.get("format"))) return List.of();
            if (!(root.get("records") instanceof List<?> records)) return List.of();
            List<Observation> observations = new ArrayList<>();
            for (Object raw : records) {
                if (!(raw instanceof Map<?, ?> map)) continue;
                Observation observation = parseObservation(map);
                if (observation != null) observations.add(observation);
                if (observations.size() >= MAX_RECORDS * 2) break;
            }
            return List.copyOf(observations);
        }

        private void writeUnlocked(List<Observation> observations) throws IOException {
            Path file = ledgerFile(true);
            Path directory = file.getParent();
            Map<String, Object> root = new LinkedHashMap<>();
            root.put("format", FORMAT);
            root.put("records", observations.stream().map(SaveProfileObservation::observationMap).toList());
            Path temporary = Files.createTempFile(directory, FILE_NAME + ".", ".tmp");
            try {
                Files.writeString(temporary, Json.object(root) + System.lineSeparator());
                LaunchLedger.requireRegularFileIfPresent(file, "Save-observation ledger");
                try {
                    Files.move(temporary, file,
                            StandardCopyOption.ATOMIC_MOVE,
                            StandardCopyOption.REPLACE_EXISTING);
                } catch (AtomicMoveNotSupportedException unsupported) {
                    Files.move(temporary, file, StandardCopyOption.REPLACE_EXISTING);
                }
            } finally {
                Files.deleteIfExists(temporary);
            }
        }

        private <T> T withLock(IoSupplier<T> operation) throws IOException {
            JVM_LOCK.lock();
            try {
                Path directory = storeDirectory(true);
                Path lockFile = directory.resolve(FILE_NAME + ".lock");
                LaunchLedger.requireRegularFileIfPresent(lockFile, "Save-observation lock");
                try (FileChannel channel = FileChannel.open(
                                lockFile,
                                StandardOpenOption.CREATE,
                                StandardOpenOption.WRITE,
                                LinkOption.NOFOLLOW_LINKS);
                        FileLock ignored = channel.lock()) {
                    return operation.get();
                }
            } finally {
                JVM_LOCK.unlock();
            }
        }

        private Path ledgerFile(boolean createDirectory) throws IOException {
            Path directory = storeDirectory(createDirectory);
            if (directory == null) return null;
            Path file = directory.resolve(FILE_NAME);
            if (!Files.exists(file, LinkOption.NOFOLLOW_LINKS)) {
                return createDirectory ? file : null;
            }
            LaunchLedger.requireRegularFileIfPresent(file, "Save-observation ledger");
            return file;
        }

        private Path storeDirectory(boolean create) throws IOException {
            Path root = home.root().toAbsolutePath().normalize();
            if (!Files.exists(root, LinkOption.NOFOLLOW_LINKS)) {
                if (!create) return null;
                Files.createDirectories(root);
            }
            Path realRoot = SafetyArtifactRetention.requireRealDirectory(root).toRealPath();
            Path runtime = realRoot.resolve("runtime");
            if (!Files.exists(runtime, LinkOption.NOFOLLOW_LINKS)) {
                if (!create) return null;
                try {
                    Files.createDirectory(runtime);
                } catch (FileAlreadyExistsException createdByAnotherProcess) {
                    // Type and containment checks below decide whether the raced result is safe.
                }
            }
            Path realRuntime = SafetyArtifactRetention.requireRealDirectory(runtime).toRealPath();
            if (!realRuntime.getParent().equals(realRoot)) {
                throw new IOException("Save-observation directory escapes the Preflight home");
            }
            return realRuntime;
        }
    }

    @FunctionalInterface
    private interface IoSupplier<T> {
        T get() throws IOException;
    }

    private static Map<String, Object> observationMap(Observation observation) {
        Map<String, Object> value = new LinkedHashMap<>();
        value.put("installationKey", observation.installationKey());
        value.put("saveKey", observation.saveKey());
        value.put("saveName", observation.saveName());
        value.put("stateToken", observation.stateToken());
        value.put("observedAt", observation.observedAt());
        value.put("missingSince", observation.missingSince());
        value.put("profileFingerprint", observation.profileFingerprint());
        value.put("profileDisplayName", observation.profileDisplayName());
        value.put("gameBuild", observation.gameBuild());
        if (observation.mods() != null) {
            value.put("modsOrdered", true);
            value.put("mods", observation.mods().stream().map(mod -> {
                Map<String, Object> row = new LinkedHashMap<>();
                row.put("id", mod.id());
                if (mod.displayName() != null) {
                    row.put("displayName", mod.displayName());
                }
                if (mod.version() != null) {
                    row.put("version", mod.version());
                }
                return row;
            }).toList());
        }
        return value;
    }

    private static Observation parseObservation(Map<?, ?> raw) {
        try {
            String installationKey = string(raw.get("installationKey"));
            String saveKey = nonEmptyString(raw.get("saveKey"));
            String saveName = boundedText(string(raw.get("saveName")), 255);
            String stateToken = string(raw.get("stateToken"));
            Instant observedAt = parseInstant(raw.get("observedAt"));
            Instant missingSince = parseInstant(raw.get("missingSince"));
            String fingerprint = string(raw.get("profileFingerprint"));
            String profileName = raw.get("profileDisplayName") instanceof String value
                    ? boundedNullable(value, 160) : null;
            String gameBuild = string(raw.get("gameBuild"));
            List<Mod> mods = Boolean.TRUE.equals(raw.get("modsOrdered")) && raw.containsKey("mods")
                    ? parseMods(raw.get("mods"))
                    : null;
            if (installationKey == null || saveKey == null || stateToken == null || observedAt == null
                    || fingerprint == null || gameBuild == null) return null;
            return new Observation(
                    installationKey,
                    saveKey,
                    saveName,
                    stateToken,
                    observedAt,
                    missingSince,
                    fingerprint,
                    profileName,
                    gameBuild,
                    mods);
        } catch (RuntimeException malformed) {
            return null;
        }
    }

    private static Observation copyWithMissing(Observation observation, Instant missingSince) {
        return new Observation(
                observation.installationKey(),
                observation.saveKey(),
                observation.saveName(),
                observation.stateToken(),
                observation.observedAt(),
                missingSince,
                observation.profileFingerprint(),
                observation.profileDisplayName(),
                observation.gameBuild(),
                observation.mods());
    }

    private static List<Mod> parseSavedModIds(Object raw) {
        if (!(raw instanceof List<?> list)) return null;
        List<Mod> mods = new ArrayList<>();
        for (Object item : list) {
            if (item instanceof String id && !id.isBlank()) {
                mods.add(new Mod(id, null, null));
            }
            if (mods.size() >= MAX_MODS) break;
        }
        return boundedMods(mods);
    }

    private static List<Mod> parseMods(Object raw) {
        if (!(raw instanceof List<?> list)) return null;
        List<Mod> mods = new ArrayList<>();
        for (Object item : list) {
            if (!(item instanceof Map<?, ?> map)) continue;
            String id = map.get("id") instanceof String value ? value : "";
            String displayName = map.get("displayName") instanceof String value ? boundedNullable(value, 160) : null;
            String version = map.get("version") instanceof String value ? boundedNullable(value, 80) : null;
            if (!id.isBlank()) mods.add(new Mod(id, displayName, version));
            if (mods.size() >= MAX_MODS) break;
        }
        return boundedMods(mods);
    }

    private static List<Mod> boundedMods(List<Mod> mods) {
        if (mods == null) return null;
        if (mods.isEmpty()) return List.of();
        return mods.stream()
                .filter(Objects::nonNull)
                .limit(MAX_MODS)
                .toList();
    }

    private static boolean caseInsensitivePaths() {
        String os = System.getProperty("os.name", "").toLowerCase(Locale.ROOT);
        return os.contains("win") || os.contains("mac");
    }

    private static String pathKey(Path path, boolean caseInsensitivePaths) {
        String value = path.toAbsolutePath().normalize().toString();
        return caseInsensitivePaths ? value.toLowerCase(Locale.ROOT) : value;
    }

    private static String saveKey(String directoryEntryName) {
        return directoryEntryName;
    }

    private static Instant parseInstant(Object value) {
        if (!(value instanceof String text) || text.isBlank()) return null;
        try {
            return Instant.parse(text);
        } catch (RuntimeException invalid) {
            return null;
        }
    }

    private static String string(Object value) {
        return value instanceof String text && !text.isBlank() ? text : null;
    }

    private static String nonEmptyString(Object value) {
        return value instanceof String text && !text.isEmpty() ? text : null;
    }

    private static String boundedText(String value, int max) {
        String text = value == null ? "" : value.strip();
        return text.length() <= max ? text : text.substring(0, max);
    }

    private static String boundedNullable(String value, int max) {
        String text = boundedText(value, max);
        return text.isBlank() ? null : text;
    }

    private static String shortFingerprint(String value) {
        if (value == null) return "unknown";
        return value.substring(0, Math.min(12, value.length()));
    }

    private static Instant latest(Instant left, Instant right) {
        return left.isAfter(right) ? left : right;
    }

    private static MessageDigest sha256() {
        try {
            return MessageDigest.getInstance("SHA-256");
        } catch (NoSuchAlgorithmException impossible) {
            throw new IllegalStateException("SHA-256 unavailable", impossible);
        }
    }

    private static String hex(byte[] bytes) {
        StringBuilder value = new StringBuilder(bytes.length * 2);
        for (byte item : bytes) value.append(String.format(Locale.ROOT, "%02x", item & 0xff));
        return value.toString();
    }

    private static Thread daemonThread(Runnable task, String name) {
        Thread thread = new Thread(task, name);
        thread.setDaemon(true);
        return thread;
    }

    private static String bounded(Throwable error) {
        String value = error.getMessage();
        if (value == null || value.isBlank()) value = error.getClass().getSimpleName();
        value = value.replaceAll("\\s+", " ").strip();
        return value.length() <= 400 ? value : value.substring(0, 397) + "...";
    }
}
