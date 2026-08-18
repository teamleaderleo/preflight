package dev.starsector.preflight.agent;

import dev.starsector.preflight.core.PreparedHullJsonCache;
import dev.starsector.preflight.core.PreparedHullJsonCacheIO;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;

/** Learns merged hull JSON once and reconstructs fresh JSON objects on an exact-profile hit. */
public final class HullJsonCacheRuntime {
    public static final String PLAN_ID = "vanilla-hull-merged-json-cache-v1";
    static final int MAX_LEARNED_ENTRY_BYTES = 8 * 1024 * 1024;
    static final long MAX_LEARNED_BYTES = 16L * 1024 * 1024;
    static final int MAX_LEARNED_ENTRIES = 100_000;

    private static volatile State state = State.disabled();
    /**
     * How long a hit spends rebuilding the object, separated from finding it.
     *
     * <p>The loader's own subphase label charges a hit the whole call, and an offline replay of
     * these exact artifacts parses all 12,584 values in about 0.16s. The same seam measured 394ms
     * in the game, and tagged-tree replay reduces its own rebuild work by roughly five to six times.
     */
    private static final SeamTimer REHYDRATE_CLOCK = new SeamTimer();

    private HullJsonCacheRuntime() {
    }

    static void beginSession() {
        state = State.disabled();
        REHYDRATE_CLOCK.reset();
        GameJson.forget();
    }

    static void configure(Path artifact) {
        if (artifact == null) {
            state = State.disabled();
            return;
        }
        Path absolute = artifact.toAbsolutePath().normalize();
        String fileName = absolute.getFileName().toString().toLowerCase(Locale.ROOT);
        if (!fileName.matches("[0-9a-f]{64}\\.sphj")) {
            state = State.disabled();
            return;
        }
        String profile = fileName.substring(0, 64);
        Map<String, byte[]> entries = Map.of();
        String diagnostic = "capture";
        if (Files.isRegularFile(absolute)) {
            try {
                PreparedHullJsonCache stored = PreparedHullJsonCacheIO.read(absolute);
                if (profile.equals(stored.profileIdentitySha256())) {
                    entries = stored.entries();
                    diagnostic = "hit:" + entries.size();
                } else {
                    diagnostic = "profile-mismatch";
                }
            } catch (Exception error) {
                diagnostic = "rejected:" + message(error);
            }
        }
        state = new State(absolute, profile, entries, diagnostic);
    }

    static boolean ready() {
        return state.artifact != null;
    }

    static String status() {
        return state.diagnostic;
    }

    /** Returns a fresh game JSON object, or null so the woven call site executes vanilla. */
    public static Object cached(String rawPath) {
        State current = state;
        if (current.artifact == null) {
            return null;
        }
        String path = normalizeHullPath(rawPath);
        if (path == null || current.badEntries.contains(path)) {
            current.misses.incrementAndGet();
            return null;
        }
        byte[] tree = current.entries.get(path);
        if (tree == null) {
            current.misses.incrementAndGet();
            return null;
        }
        try {
            long entry = REHYDRATE_CLOCK.enter();
            Object result;
            try {
                result = GameJson.bridge().decode(tree);
            } finally {
                REHYDRATE_CLOCK.exit(entry);
            }
            current.hits.incrementAndGet();
            return result;
        } catch (ThreadDeath | VirtualMachineError fatal) {
            throw fatal;
        } catch (Throwable error) {
            current.badEntries.add(path);
            current.misses.incrementAndGet();
            current.diagnose("cached hull JSON could not be reconstructed for "
                    + path + ": " + message(error));
            return null;
        }
    }

    /** Captures only values produced by the original loader on a miss. */
    public static void capture(Object json, String rawPath) {
        State current = state;
        // Once a launch has exceeded the bounded learning budget, do not keep serializing later
        // vanilla trees just to discard their bytes. The first oversized tree may still cost one
        // encoding pass; after that rejection is a hard stop for this learning session.
        if (current.artifact == null || current.learningRejected.get() || json == null) {
            return;
        }
        String path = normalizeHullPath(rawPath);
        if (path == null) {
            return;
        }
        try {
            GameJson bridge = GameJson.bridge();
            if (bridge.isGameJson(json)) {
                byte[] encoded = bridge.encode(json);
                // Dropping the install prefix from an absolute key means two different files could
                // in principle claim it. Nothing observed does, so this refuses the key rather than
                // picking a winner: a collision that is never served cannot serve the wrong spec.
                current.capture(path, encoded);
            }
        } catch (ThreadDeath | VirtualMachineError fatal) {
            throw fatal;
        } catch (Throwable error) {
            current.diagnose("vanilla hull JSON could not be captured for "
                    + path + ": " + message(error));
        }
    }

    /** Publishes only after vanilla finishes the entire hull loader normally. */
    public static void complete() {
        State current = state;
        if (current.artifact == null || current.learningRejected.get() || current.learned.isEmpty()
                || !current.completed.compareAndSet(false, true)) {
            return;
        }
        try {
            Map<String, byte[]> combined = new LinkedHashMap<>(current.entries);
            combined.putAll(current.learned);
            combined.keySet().removeAll(current.collidingKeys);
            PreparedHullJsonCacheIO.write(
                    current.artifact,
                    new PreparedHullJsonCache(current.profileIdentity, combined));
            current.writes.incrementAndGet();
        } catch (ThreadDeath | VirtualMachineError fatal) {
            throw fatal;
        } catch (Throwable error) {
            current.diagnose("merged hull JSON cache could not be written: " + message(error));
        }
    }

    static Map<String, Object> telemetry() {
        State current = state;
        Map<String, Object> values = new LinkedHashMap<>();
        values.put("status", current.diagnostic);
        values.put("profileIdentity", current.profileIdentity);
        values.put("artifact", current.artifact);
        values.put("preparedEntries", current.entries.size());
        values.put("hits", current.hits.get());
        values.put("misses", current.misses.get());
        values.put("captures", current.captures.get());
        values.put("writes", current.writes.get());
        values.put("keyCollisions", current.collisions.get());
        values.put("learnedBytes", current.learnedBytes);
        values.put("learningRejected", current.learningRejected.get());
        values.put("absoluteEntries", current.entries.keySet().stream()
                .filter(key -> key.startsWith(SpecCacheKey.ABSOLUTE_PREFIX)).count());
        values.putAll(REHYDRATE_CLOCK.snapshot("rehydrate"));
        return values;
    }

    /** The directories and extension this cache may answer for. */
    private static final List<String> DIRECTORIES = List.of("data/hulls/");

    private static String normalizeHullPath(String rawPath) {
        return SpecCacheKey.of(rawPath, DIRECTORIES, ".ship");
    }

    private static String message(Throwable error) {
        Throwable cause = error instanceof java.lang.reflect.InvocationTargetException invocation
                && invocation.getCause() != null ? invocation.getCause() : error;
        String text = cause.getMessage();
        return text == null || text.isBlank() ? cause.getClass().getSimpleName() : text;
    }

    private static final class State {
        private final Path artifact;
        private final String profileIdentity;
        private final Map<String, byte[]> entries;
        private final String diagnostic;
        private final Map<String, byte[]> learned = new ConcurrentHashMap<>();
        private final Set<String> badEntries = ConcurrentHashMap.newKeySet();
        private final AtomicBoolean completed = new AtomicBoolean();
        private final AtomicBoolean learningRejected = new AtomicBoolean();
        private final AtomicBoolean diagnosed = new AtomicBoolean();
        private final AtomicLong hits = new AtomicLong();
        private final AtomicLong misses = new AtomicLong();
        private final AtomicLong captures = new AtomicLong();
        private final AtomicLong writes = new AtomicLong();
        private final AtomicLong collisions = new AtomicLong();
        private final Set<String> collidingKeys = ConcurrentHashMap.newKeySet();
        private long learnedBytes;

        private State(Path artifact, String profileIdentity, Map<String, byte[]> entries,
                String diagnostic) {
            this.artifact = artifact;
            this.profileIdentity = profileIdentity;
            this.entries = entries;
            this.diagnostic = diagnostic;
        }

        private static State disabled() {
            return new State(null, "", Map.of(), "disabled");
        }

        private synchronized void capture(String path, byte[] encoded) {
            if (learningRejected.get() || collidingKeys.contains(path)) {
                return;
            }
            byte[] previous = learned.get(path);
            if (previous != null) {
                if (!java.util.Arrays.equals(previous, encoded)) {
                    learned.remove(path);
                    learnedBytes -= previous.length;
                    collidingKeys.add(path);
                    badEntries.add(path);
                    collisions.incrementAndGet();
                    diagnose("two different merged values claim the cache key " + path);
                } else {
                    captures.incrementAndGet();
                }
                return;
            }
            if (encoded.length > MAX_LEARNED_ENTRY_BYTES
                    || learned.size() >= MAX_LEARNED_ENTRIES
                    || learnedBytes + encoded.length > MAX_LEARNED_BYTES) {
                learningRejected.set(true);
                learned.clear();
                learnedBytes = 0;
                diagnose("merged hull JSON exceeded the in-memory learning limit");
                return;
            }
            learned.put(path, encoded);
            learnedBytes += encoded.length;
            captures.incrementAndGet();
        }

        private void diagnose(String problem) {
            if (diagnosed.compareAndSet(false, true)) {
                System.err.println("[Preflight] " + problem + "; vanilla fallback remains active");
            }
        }
    }
}
