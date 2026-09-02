package dev.starsector.preflight.agent;

import dev.starsector.preflight.core.MergedReadKey;
import dev.starsector.preflight.core.PreparedMergedReadCache;
import dev.starsector.preflight.core.PreparedMergedReadCacheIO;
import java.lang.invoke.MethodHandle;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Serves the two methods every merged data read in the game funnels through, plus unrestricted
 * single-file JSON first requested after resource initialization.
 *
 * <p>The five caches before this one each pinned a loader and cached that loader's reads, which
 * removed nine seconds from {@code SpecStore} and could not touch anything nobody had pinned. The
 * probe that measured what was left found <b>2.1s across 1,471 merged reads</b> spread over
 * factions, ship systems, hull mods, descriptions, missions, skins, skills, the three big
 * spreadsheets, and whatever mod callbacks decide to read. Pinning a sixth loader would have taken
 * the largest of those and left the rest.
 *
 * <p>So this one is not a loader's cache. It sits at {@code LoadingUtils}' two merged readers and
 * keys on the request, which means it answers for callers that do not exist yet and for mod code
 * that was never written with us in mind.
 *
 * <p>What keeps that safe is the same discipline as the other five, in four parts:
 *
 * <ul>
 *   <li><b>The identity gates it.</b> The artifact is named after a digest of every file under
 *       {@code data/} in every enabled root, in override order, by content. Any change to any of
 *       them names a different artifact, and this one is never read again.
 *   <li><b>Only {@code data/}.</b> A request the identity does not cover gets no key and runs
 *       vanilla, so the cache can never answer for a file its identity did not hash.
 *   <li><b>Collisions are refused, not resolved.</b> If two different values ever claim one key the
 *       key is dropped from the artifact entirely, so a doubtful key costs a fallback rather than
 *       serving the wrong merge.
 *   <li><b>It publishes only after completed startup.</b> The initial publication waits for
 *       {@code resource-init-complete}; a shutdown publication can then add lazy campaign reads.
 * </ul>
 */
public final class MergedReadCacheRuntime {
    public static final String PLAN_ID = "vanilla-merged-read-cache-v1";
    static final String WINDOWS_OVERSIZED_KEYS_PROPERTY =
            "preflight.mergedReads.windowsOversizedKeys";

    /** Caps what one launch can learn so a pathological mod cannot grow the artifact without end. */
    private static final int MAX_LEARNED = 100_000;

    private static volatile State state = State.disabled();
    /** How long a hit spends rebuilding the value, separated from finding it. */
    private static final SeamTimer REHYDRATE_CLOCK = new SeamTimer();

    private MergedReadCacheRuntime() {
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
        if (!fileName.matches("[0-9a-f]{64}\\.spmr")) {
            state = State.disabled();
            return;
        }
        String profile = fileName.substring(0, 64);
        Map<String, byte[]> entries = Map.of();
        int prunableEntries = 0;
        String diagnostic = "capture";
        if (Files.isRegularFile(absolute)) {
            try {
                PreparedMergedReadCache stored = PreparedMergedReadCacheIO.read(absolute);
                if (profile.equals(stored.profileIdentitySha256())) {
                    entries = stored.entries();
                    prunableEntries = (int) entries.keySet().stream()
                            .filter(MergedReadKey::ownedBySpecJsonCache)
                            .count();
                    diagnostic = "hit:" + entries.size();
                } else {
                    diagnostic = "profile-mismatch";
                }
            } catch (Exception error) {
                diagnostic = "rejected:" + message(error);
            }
        }
        state = new State(absolute, profile, entries, prunableEntries, diagnostic);
    }

    static boolean ready() {
        return state.artifact != null;
    }

    static String status() {
        return state.diagnostic;
    }

    /**
     * Serves one merged CSV read, or runs the original and learns what it produced.
     *
     * <p>The handle is what {@link MergedReadCachePlan} renamed vanilla to, so a miss is the call the
     * game would have made with one branch in front of it.
     */
    public static Object mergedCsvRead(
            Object columns, String path, boolean first, boolean second, MethodHandle vanilla)
            throws Throwable {
        State current = state;
        if (current.artifact == null) {
            return timed("csv", path, () -> vanilla.invoke(columns, path, first, second));
        }
        String key = MergedReadKey.csv(path, first, second, strings(columns));
        Object hit = cached(current, key);
        if (hit != null) {
            return hit;
        }
        Object produced = timed("csv", path, () -> vanilla.invoke(columns, path, first, second));
        capture(current, key, produced);
        return produced;
    }

    /** Serves one merged JSON read, or runs the original and learns what it produced. */
    public static Object mergedJsonRead(String path, Object mergeKeys, MethodHandle vanilla)
            throws Throwable {
        State current = state;
        if (current.artifact == null) {
            return timed("json", path, () -> vanilla.invoke(path, mergeKeys));
        }
        // Vanilla uses this set only to ask whether it contains a key, so sorting it makes two
        // requests that differ in nothing but iteration order share one entry rather than two.
        List<String> canonicalItems = sorted(mergeKeys);
        String key = MergedReadKey.json(path, canonicalItems);
        boolean oversized = false;
        if (key == null && oversizedKeysEnabled()) {
            current.oversizedAttempts.incrementAndGet();
            key = MergedReadKey.jsonWithHashedOversizedItems(path, canonicalItems);
            oversized = key != null;
            if (oversized) {
                current.oversizedAccepted.incrementAndGet();
            } else {
                current.oversizedDeclines.incrementAndGet();
                recordOversizedDecline(current, path, mergeKeys, canonicalItems);
            }
        }
        Object hit = cached(current, key);
        if (hit != null) {
            if (oversized) current.oversizedHits.incrementAndGet();
            return hit;
        }
        if (oversized) current.oversizedMisses.incrementAndGet();
        Object produced = timed("json", path, () -> vanilla.invoke(path, mergeKeys));
        if (capture(current, key, produced) && oversized) {
            current.oversizedCaptures.incrementAndGet();
        }
        return produced;
    }

    private static boolean oversizedKeysEnabled() {
        return Boolean.getBoolean(WINDOWS_OVERSIZED_KEYS_PROPERTY)
                && System.getProperty("os.name", "").toLowerCase(Locale.ROOT).startsWith("windows");
    }

    private static void recordOversizedDecline(
            State current, String path, Object mergeKeys, List<String> canonicalItems) {
        String reason;
        if (MergedReadKey.path(path) == null) {
            current.oversizedPathDeclines.incrementAndGet();
            reason = "path:" + bounded(path);
        } else if (canonicalItems == null) {
            current.oversizedItemDeclines.incrementAndGet();
            reason = "items:" + describeItems(mergeKeys);
        } else {
            current.oversizedBoundDeclines.incrementAndGet();
            reason = "valid-nonoversized-or-over-limit:path=" + bounded(path)
                    + ",items=" + canonicalItems.size();
        }
        synchronized (current.oversizedDeclineSamples) {
            if (current.oversizedDeclineSamples.size() < 8) {
                current.oversizedDeclineSamples.add(reason);
            }
        }
    }

    private static String describeItems(Object value) {
        if (value == null) return "null";
        if (!(value instanceof Collection<?> items)) {
            return "not-collection:" + value.getClass().getName();
        }
        for (Object item : items) {
            if (!(item instanceof String)) {
                return "collection:" + value.getClass().getName() + ",size=" + items.size()
                        + ",non-string=" + (item == null ? "null" : item.getClass().getName())
                        + ",value=" + bounded(String.valueOf(item));
            }
        }
        return "collection:" + value.getClass().getName() + ",size=" + items.size();
    }

    private static String bounded(String value) {
        if (value == null) return "null";
        return value.length() <= 160 ? value : value.substring(0, 160) + "...";
    }

    /** What the original call cost, reported only when the probe asked for it. */
    private static Object timed(String kind, String path, Call call) throws Throwable {
        if (!StartupPhaseRuntime.mergedReadProbeEnabled()) {
            return call.invoke();
        }
        long start = System.nanoTime();
        try {
            return call.invoke();
        } finally {
            StartupPhaseRuntime.recordMergedRead(kind, path, System.nanoTime() - start);
        }
    }

    private interface Call {
        Object invoke() throws Throwable;
    }

    /** Returns one prepared unrestricted JSON file, or null when this request stays vanilla. */
    static Object singleJson(String path) {
        State current = state;
        if (current.artifact == null) return null;
        Object value = cached(current, MergedReadKey.singleJson(path));
        if (value == null) {
            current.singleJsonMisses.incrementAndGet();
        } else {
            current.singleJsonHits.incrementAndGet();
        }
        return value;
    }

    static boolean singleJsonEligible(String path) {
        return state.artifact != null && MergedReadKey.singleJson(path) != null;
    }

    /** Learns one unrestricted profile-stable JSON file under the full-data profile identity. */
    static boolean captureSingleJson(String path, Object produced) {
        State current = state;
        if (current.artifact == null) return false;
        boolean captured = capture(current, MergedReadKey.singleJson(path), produced);
        if (captured) current.singleJsonCaptures.incrementAndGet();
        return captured;
    }

    /**
     * Publishes what this launch learned after a completed startup and again at orderly shutdown.
     * The second publication adds lazy campaign reads without weakening the profile identity or
     * collision rules; an unchanged revision is a no-op.
     */
    public static void complete() {
        State current = state;
        if (current.artifact == null) {
            return;
        }
        synchronized (current.publishLock) {
            long revision = current.revision.get();
            if ((current.learned.isEmpty() && current.prunableEntries == 0)
                    || (current.publishedRevision == revision && current.pruningPublished)) {
                return;
            }
            try {
                Map<String, byte[]> combined = new LinkedHashMap<>(current.entries);
                combined.putAll(current.learned);
                combined.keySet().removeAll(current.collidingKeys);
                combined.keySet().removeIf(MergedReadKey::ownedBySpecJsonCache);
                PreparedMergedReadCacheIO.write(
                        current.artifact,
                        new PreparedMergedReadCache(current.profileIdentity, combined));
                current.writes.incrementAndGet();
                current.publishedRevision = revision;
                current.pruningPublished = true;
            } catch (ThreadDeath | VirtualMachineError fatal) {
                throw fatal;
            } catch (Throwable error) {
                current.diagnose("prepared merged reads could not be written: " + message(error));
            }
        }
    }

    private static Object cached(State current, String key) {
        if (key == null) {
            current.unkeyed.incrementAndGet();
            return null;
        }
        if (current.badEntries.contains(key)) {
            current.misses.incrementAndGet();
            return null;
        }
        byte[] tree = current.entries.get(key);
        if (tree == null) {
            current.misses.incrementAndGet();
            return null;
        }
        long entry = REHYDRATE_CLOCK.enter();
        try {
            Object value = GameJson.bridge().decode(tree);
            current.hits.incrementAndGet();
            return value;
        } catch (ThreadDeath | VirtualMachineError fatal) {
            throw fatal;
        } catch (Throwable error) {
            current.badEntries.add(key);
            current.misses.incrementAndGet();
            current.diagnose("a prepared merged read could not be rebuilt: " + message(error));
            return null;
        } finally {
            REHYDRATE_CLOCK.exit(entry);
        }
    }

    private static boolean capture(State current, String key, Object produced) {
        if (key == null || produced == null || current.learned.size() >= MAX_LEARNED) {
            return false;
        }
        // Four higher caches own these domains. Retaining one from an older artifact lets a miss
        // still fall through to this cache for the current run, but publishing a second permanent
        // copy only bloats the lower artifact: warm launches always stop at the dedicated cache.
        if (MergedReadKey.ownedBySpecJsonCache(key)) {
            current.dedicatedSpecCapturesSkipped.incrementAndGet();
            return false;
        }
        try {
            GameJson bridge = GameJson.bridge();
            if (!bridge.isGameJson(produced)) {
                return false;
            }
            byte[] encoded = bridge.encode(produced);
            byte[] previous = current.learned.putIfAbsent(key, encoded);
            if (previous != null && !java.util.Arrays.equals(previous, encoded)) {
                // Two different merges claim one key. Rather than pick a winner, the key stops
                // existing: a fallback is always correct, and serving the wrong merge never is.
                current.learned.remove(key);
                current.collidingKeys.add(key);
                current.badEntries.add(key);
                current.collisions.incrementAndGet();
                current.revision.incrementAndGet();
                current.diagnose("two different merged reads claim the cache key " + readable(key));
                return false;
            }
            if (previous == null) current.revision.incrementAndGet();
            current.captures.incrementAndGet();
            return true;
        } catch (ThreadDeath | VirtualMachineError fatal) {
            throw fatal;
        } catch (Throwable error) {
            current.unstorable.incrementAndGet();
            current.diagnose("a merged read could not be stored: " + message(error));
            return false;
        }
    }

    /** The key-column list vanilla was handed, or null if it was not a list of strings. */
    private static List<String> strings(Object value) {
        if (value == null) {
            return List.of();
        }
        if (!(value instanceof Collection<?> items)) {
            return null;
        }
        List<String> copy = new ArrayList<>(items.size());
        for (Object item : items) {
            if (!(item instanceof String text)) {
                return null;
            }
            copy.add(text);
        }
        return copy;
    }

    private static List<String> sorted(Object value) {
        List<String> items = strings(value);
        if (items == null || items.isEmpty()) {
            return items;
        }
        Set<String> ordered = new TreeSet<>(items);
        return new ArrayList<>(ordered);
    }

    static Map<String, Object> telemetry() {
        State current = state;
        Map<String, Object> values = new LinkedHashMap<>();
        values.put("status", current.diagnostic);
        values.put("windowsOversizedKeysProperty", WINDOWS_OVERSIZED_KEYS_PROPERTY);
        values.put("windowsOversizedKeysRequested",
                Boolean.getBoolean(WINDOWS_OVERSIZED_KEYS_PROPERTY));
        values.put("windowsOversizedKeysEnabled", oversizedKeysEnabled());
        values.put("profileIdentity", current.profileIdentity);
        values.put("artifact", current.artifact);
        values.put("preparedEntries", current.entries.size());
        values.put("hits", current.hits.get());
        values.put("misses", current.misses.get());
        values.put("captures", current.captures.get());
        values.put("unkeyedReads", current.unkeyed.get());
        values.put("unstorableReads", current.unstorable.get());
        values.put("writes", current.writes.get());
        values.put("keyCollisions", current.collisions.get());
        values.put("dedicatedSpecEntriesPruned", current.prunableEntries);
        values.put("dedicatedSpecCapturesSkipped", current.dedicatedSpecCapturesSkipped.get());
        values.put("singleJsonHits", current.singleJsonHits.get());
        values.put("singleJsonMisses", current.singleJsonMisses.get());
        values.put("singleJsonCaptures", current.singleJsonCaptures.get());
        values.put("oversizedKeyAttempts", current.oversizedAttempts.get());
        values.put("oversizedKeyAccepted", current.oversizedAccepted.get());
        values.put("oversizedKeyHits", current.oversizedHits.get());
        values.put("oversizedKeyMisses", current.oversizedMisses.get());
        values.put("oversizedKeyCaptures", current.oversizedCaptures.get());
        values.put("oversizedKeyDeclines", current.oversizedDeclines.get());
        values.put("oversizedKeyPathDeclines", current.oversizedPathDeclines.get());
        values.put("oversizedKeyItemDeclines", current.oversizedItemDeclines.get());
        values.put("oversizedKeyBoundDeclines", current.oversizedBoundDeclines.get());
        synchronized (current.oversizedDeclineSamples) {
            values.put("oversizedKeyDeclineSamples", List.copyOf(current.oversizedDeclineSamples));
        }
        values.putAll(REHYDRATE_CLOCK.snapshot("rehydrate"));
        return values;
    }

    /** A key with its separators made printable, for a diagnostic a person has to read. */
    private static String readable(String key) {
        return key.replace((char) 0, ' ').replace((char) 1, ',');
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
        private final int prunableEntries;
        private final String diagnostic;
        private final Map<String, byte[]> learned = new ConcurrentHashMap<>();
        private final Set<String> badEntries = ConcurrentHashMap.newKeySet();
        private final Set<String> collidingKeys = ConcurrentHashMap.newKeySet();
        private final AtomicBoolean diagnosed = new AtomicBoolean();
        private final Object publishLock = new Object();
        private final AtomicLong hits = new AtomicLong();
        private final AtomicLong misses = new AtomicLong();
        private final AtomicLong captures = new AtomicLong();
        private final AtomicLong unkeyed = new AtomicLong();
        private final AtomicLong unstorable = new AtomicLong();
        private final AtomicLong writes = new AtomicLong();
        private final AtomicLong collisions = new AtomicLong();
        private final AtomicLong dedicatedSpecCapturesSkipped = new AtomicLong();
        private final AtomicLong singleJsonHits = new AtomicLong();
        private final AtomicLong singleJsonMisses = new AtomicLong();
        private final AtomicLong singleJsonCaptures = new AtomicLong();
        private final AtomicLong oversizedAttempts = new AtomicLong();
        private final AtomicLong oversizedAccepted = new AtomicLong();
        private final AtomicLong oversizedHits = new AtomicLong();
        private final AtomicLong oversizedMisses = new AtomicLong();
        private final AtomicLong oversizedCaptures = new AtomicLong();
        private final AtomicLong oversizedDeclines = new AtomicLong();
        private final AtomicLong oversizedPathDeclines = new AtomicLong();
        private final AtomicLong oversizedItemDeclines = new AtomicLong();
        private final AtomicLong oversizedBoundDeclines = new AtomicLong();
        private final List<String> oversizedDeclineSamples = new ArrayList<>();
        private final AtomicLong revision = new AtomicLong();
        private long publishedRevision = Long.MIN_VALUE;
        private boolean pruningPublished;

        private State(Path artifact, String profileIdentity, Map<String, byte[]> entries,
                int prunableEntries, String diagnostic) {
            this.artifact = artifact;
            this.profileIdentity = profileIdentity;
            this.entries = entries;
            this.prunableEntries = prunableEntries;
            this.diagnostic = diagnostic;
        }

        private static State disabled() {
            return new State(null, "", Map.of(), 0, "disabled");
        }

        private void diagnose(String problem) {
            if (diagnosed.compareAndSet(false, true)) {
                System.err.println("[Preflight] " + problem + "; vanilla fallback remains active");
            }
        }
    }
}
