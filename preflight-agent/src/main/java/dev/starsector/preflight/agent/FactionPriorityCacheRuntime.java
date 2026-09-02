package dev.starsector.preflight.agent;

import dev.starsector.preflight.core.Hashes;
import dev.starsector.preflight.core.PathContainment;
import dev.starsector.preflight.core.PreparedFactionPriorityCache;
import dev.starsector.preflight.core.PreparedFactionPriorityCacheIO;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.IdentityHashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;

/** Learns the exact IDs emitted by faction priority-table walks and replays them next launch. */
public final class FactionPriorityCacheRuntime {
    public static final String PLAN_ID = "windows-faction-priority-cache-v1";
    public static final String ENABLE_PROPERTY = "preflight.startup.windowsFactionPriorityCache";
    private static final int MAX_IDS_PER_CALL = 250_000;
    private static final AtomicBoolean SHUTDOWN_HOOK_INSTALLED = new AtomicBoolean();
    private static final ThreadLocal<Capture> CAPTURE = new ThreadLocal<>();
    private static volatile State state = State.disabled("not-configured");

    private FactionPriorityCacheRuntime() {
    }

    static synchronized void beginSession() {
        complete();
        CAPTURE.remove();
        state = State.disabled("not-configured");
    }

    static boolean configure(Path cacheDirectory, String profileFingerprint) {
        boolean windows = System.getProperty("os.name", "").toLowerCase(java.util.Locale.ROOT)
                .contains("windows");
        return configure(cacheDirectory, profileFingerprint,
                windows && Boolean.getBoolean(ENABLE_PROPERTY));
    }

    static synchronized boolean configure(
            Path cacheDirectory, String profileFingerprint, boolean enabled) {
        CAPTURE.remove();
        if (!enabled) {
            state = State.disabled("disabled");
            return false;
        }
        if (cacheDirectory == null || profileFingerprint == null) {
            state = State.disabled("missing-profile-cache");
            return false;
        }
        try {
            Hashes.decodeSha256(profileFingerprint);
            Path cacheRoot = PathContainment.realDirectory(cacheDirectory);
            Path artifact = PreparedFactionPriorityCacheIO.path(cacheRoot, profileFingerprint);
            Map<String, List<String>> entries = Map.of();
            String status = "learning";
            if (Files.isRegularFile(artifact)) {
                try {
                    PreparedFactionPriorityCache stored =
                            PreparedFactionPriorityCacheIO.read(artifact);
                    if (profileFingerprint.equalsIgnoreCase(stored.profileIdentitySha256())) {
                        entries = stored.entries();
                        status = "loaded:" + entries.size();
                    } else {
                        status = "profile-mismatch";
                    }
                } catch (Exception error) {
                    status = "rejected:" + message(error);
                }
            }
            state = new State(artifact, profileFingerprint, entries, status);
            ensureShutdownHook();
            return true;
        } catch (Exception error) {
            state = State.disabled("configuration-failed:" + message(error));
            return false;
        }
    }

    static boolean ready() {
        return state.artifact != null;
    }

    /** Returns learned IDs for the exact faction/table, or null to execute and observe vanilla. */
    public static String[] replayOrBegin(
            Object json, Object callback, String section, String explicitIds, boolean fallbackToBase) {
        State current = state;
        if (current.artifact == null || json == null || callback == null
                || section == null || explicitIds == null) {
            return null;
        }
        current.attempts.incrementAndGet();
        String jsonIdentity;
        try {
            jsonIdentity = current.jsonIdentity(json);
        } catch (ThreadDeath | VirtualMachineError fatal) {
            throw fatal;
        } catch (Throwable error) {
            current.fingerprintFailures.incrementAndGet();
            current.diagnose("faction JSON identity failed: " + message(error));
            return null;
        }
        String key = key(jsonIdentity, callback.getClass(), section, explicitIds, fallbackToBase);
        String[] ids = current.loadedArrays.get(key);
        if (ids == null) {
            current.misses.incrementAndGet();
            CAPTURE.set(new Capture(key));
            return null;
        }
        current.hits.incrementAndGet();
        current.replayedIds.addAndGet(ids.length);
        CAPTURE.remove();
        return ids;
    }

    /** Woven immediately before each original callback add; the game still performs the add. */
    public static void record(String id) {
        Capture capture = CAPTURE.get();
        if (capture == null || id == null || capture.ids.size() >= MAX_IDS_PER_CALL) {
            if (capture != null) capture.overflow = true;
            return;
        }
        capture.ids.add(id);
    }

    /** Woven only on the original method's normal return. */
    public static void completeCall() {
        Capture capture = CAPTURE.get();
        CAPTURE.remove();
        State current = state;
        if (capture == null || capture.overflow || current.artifact == null) {
            if (capture != null && capture.overflow) current.captureDeclines.incrementAndGet();
            return;
        }
        synchronized (FactionPriorityCacheRuntime.class) {
            current.learnedEntries.put(capture.key, List.copyOf(capture.ids));
            current.capturedCalls.incrementAndGet();
            current.capturedIds.addAndGet(capture.ids.size());
            current.dirty = true;
        }
    }

    static synchronized void complete() {
        State current = state;
        if (current.artifact == null || !current.dirty) return;
        try {
            PreparedFactionPriorityCacheIO.write(
                    current.artifact,
                    new PreparedFactionPriorityCache(current.profile, current.combinedEntries()));
            current.writes.incrementAndGet();
            current.dirty = false;
            current.status = "written:" + current.combinedEntries().size();
        } catch (Exception error) {
            current.writeFailures.incrementAndGet();
            current.diagnose("artifact write failed: " + message(error));
        }
    }

    static Map<String, Object> telemetry() {
        State current = state;
        Map<String, Object> values = new LinkedHashMap<>();
        values.put("ready", current.artifact != null);
        values.put("status", current.status);
        values.put("loadedEntries", current.loadedEntries.size());
        values.put("learnedEntries", current.learnedEntries.size());
        values.put("artifactEntries", current.combinedEntries().size());
        values.put("attempts", current.attempts.get());
        values.put("hits", current.hits.get());
        values.put("misses", current.misses.get());
        values.put("capturedCalls", current.capturedCalls.get());
        values.put("capturedIds", current.capturedIds.get());
        values.put("replayedIds", current.replayedIds.get());
        values.put("replayFailures", current.replayFailures.get());
        values.put("captureDeclines", current.captureDeclines.get());
        values.put("fingerprintFailures", current.fingerprintFailures.get());
        values.put("writes", current.writes.get());
        values.put("writeFailures", current.writeFailures.get());
        values.put("declinedKeys", 0);
        values.put("diagnostic", current.diagnostic);
        return Map.copyOf(values);
    }

    private static String key(String jsonIdentity,
            Class<?> callbackClass, String section, String explicitIds, boolean fallbackToBase) {
        return jsonIdentity + '\u001f' + callbackClass.getName() + '\u001f'
                + section + '\u001f' + explicitIds + '\u001f'
                + (fallbackToBase ? '1' : '0');
    }

    private static void ensureShutdownHook() {
        if (!SHUTDOWN_HOOK_INSTALLED.compareAndSet(false, true)) return;
        Runtime.getRuntime().addShutdownHook(
                new Thread(FactionPriorityCacheRuntime::complete, "preflight-faction-priority-cache"));
    }

    private static String message(Throwable error) {
        String value = error == null ? null : error.getMessage();
        return value == null || value.isBlank()
                ? (error == null ? "unknown" : error.getClass().getSimpleName()) : value;
    }

    private static final class Capture {
        private final String key;
        private final List<String> ids = new ArrayList<>();
        private boolean overflow;

        private Capture(String key) {
            this.key = key;
        }
    }

    private static final class State {
        private final Path artifact;
        private final String profile;
        private final Map<String, List<String>> loadedEntries;
        private final Map<String, String[]> loadedArrays;
        private final Map<String, List<String>> learnedEntries = new LinkedHashMap<>();
        private final Map<Object, String> jsonIdentities = new IdentityHashMap<>();
        private final AtomicLong attempts = new AtomicLong();
        private final AtomicLong hits = new AtomicLong();
        private final AtomicLong misses = new AtomicLong();
        private final AtomicLong capturedCalls = new AtomicLong();
        private final AtomicLong capturedIds = new AtomicLong();
        private final AtomicLong replayedIds = new AtomicLong();
        private final AtomicLong replayFailures = new AtomicLong();
        private final AtomicLong captureDeclines = new AtomicLong();
        private final AtomicLong fingerprintFailures = new AtomicLong();
        private final AtomicLong writes = new AtomicLong();
        private final AtomicLong writeFailures = new AtomicLong();
        private volatile String status;
        private volatile String diagnostic = "";
        private volatile boolean dirty;

        private State(
                Path artifact,
                String profile,
                Map<String, List<String>> entries,
                String status) {
            this.artifact = artifact;
            this.profile = profile;
            this.loadedEntries = Map.copyOf(entries);
            Map<String, String[]> arrays = new LinkedHashMap<>();
            entries.forEach((key, ids) -> arrays.put(key, ids.toArray(String[]::new)));
            this.loadedArrays = Map.copyOf(arrays);
            this.status = status;
        }

        private static State disabled(String status) {
            return new State(null, null, Map.of(), status);
        }

        private void diagnose(String value) {
            diagnostic = value == null ? "" : value;
        }

        private synchronized String jsonIdentity(Object json) {
            return jsonIdentities.computeIfAbsent(json, value -> Hashes.sha256(
                    value.toString().getBytes(java.nio.charset.StandardCharsets.UTF_8)));
        }

        private synchronized Map<String, List<String>> combinedEntries() {
            Map<String, List<String>> combined = new LinkedHashMap<>(loadedEntries);
            combined.putAll(learnedEntries);
            return combined;
        }
    }
}
