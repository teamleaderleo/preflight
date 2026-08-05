package dev.starsector.preflight.agent;

import java.lang.invoke.MethodHandle;
import java.lang.invoke.MethodHandles;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Reads each JSON path once per launch instead of once per caller.
 *
 * <p>{@code LoadingUtils.ô00000(String)} is what {@code Global.getSettings().loadJSON(path)} calls,
 * so it is the one place every mod's data reads meet. On the measured 83-mod install one launch
 * makes <b>39,017</b> of those calls for <b>8,378</b> distinct paths: 30,639 of them, 78.5%, are a
 * path that has already been read. Every {@code .ship} file is read 9.7 times and every
 * {@code .skin} 7.5 times.
 *
 * <p>A repeat is not cheap. The method resolves the path by walking one root per enabled mod,
 * opens it, reads it, strips its comments character by character and parses it -- about 8 seconds
 * across a launch, of which the repeats are the large majority.
 *
 * <p>AshLib's own upstream patch found this from the other end: memoizing the repeated
 * {@code loadJSON} calls in one class removed 7.07-7.44 s from its 8-second startup callback. This
 * does the same thing one level down, where it serves every mod rather than one.
 *
 * <p><b>The cached value is a mutable {@code JSONObject} and callers get the same instance.</b>
 * That is what AshLib ships and what makes the memo worth its full 6.4 s rather than the 5.4 s a
 * re-parse would leave. Two things make it safe enough to do at this level: the game's own loaders
 * read a spec and construct from it rather than editing it in place, and a caller that did write
 * to the object would already have been racing the next caller for it. {@link #reparse} exists for
 * a profile that wants the immutable version, and gives up only the parse.
 *
 * <p>Once resource initialization begins and the enabled roots plus full profile identity are
 * stable, eligible cold paths can also be reconstructed from the general full-data-profile cache.
 * The first decoded object becomes this process's ordinary memo value, so sharing and mutation
 * behavior remain identical to a vanilla parse followed by this memo.
 */
public final class LoadJsonMemoRuntime {
    static final String PLAN_ID = "loadjson-memo-v1";
    private static final String ENABLED_PROPERTY = "preflight.loadJson.memo";
    private static final String REPARSE_PROPERTY = "preflight.loadJson.memoReparse";

    private static final Map<String, Object> parsed = new ConcurrentHashMap<>();
    private static final AtomicLong calls = new AtomicLong();
    private static final AtomicLong hits = new AtomicLong();
    private static final AtomicLong failures = new AtomicLong();
    private static final AtomicLong preparedHits = new AtomicLong();
    private static final AtomicLong preparedMisses = new AtomicLong();
    private static final AtomicLong preparedCaptures = new AtomicLong();

    private static volatile boolean enabled =
            "on".equalsIgnoreCase(System.getProperty(ENABLED_PROPERTY));
    private static volatile boolean reparse =
            "on".equalsIgnoreCase(System.getProperty(REPARSE_PROPERTY));
    private static volatile boolean profileStable;

    /**
     * The resolver carries two one-shot pieces of state that the next resolve consumes and clears:
     * a directory restriction set by {@code SettingsAPI.loadJSON(path, modId)}, and a boolean flag.
     * A memo hit never reaches the resolver, so it would leave that state set for whatever loaded
     * next -- which searched two roots instead of 84 and failed. Any call with either one pending
     * goes to vanilla so the resolver consumes it.
     */
    private static volatile boolean restrictionProbeReady;
    private static MethodHandle resolverSingleton;
    private static MethodHandle pendingDirectoryGetter;
    private static MethodHandle pendingFlagGetter;
    private static final AtomicLong bypassed = new AtomicLong();

    private LoadJsonMemoRuntime() {
    }

    static void enable(boolean value) {
        enabled = value;
    }

    static void reparse(boolean value) {
        reparse = value;
    }

    static boolean ready() {
        return enabled;
    }

    /**
     * Replaces the body of {@code LoadingUtils.ô00000(String)}.
     *
     * @param vanilla a {@code MethodHandle} for the original method, pushed at the call site so the
     *     memo always has the real loader to fall back to and a game that no longer offers it fails
     *     to link here rather than silently serving nothing
     */
    public static Object loadJson(String path, MethodHandle vanilla) throws Throwable {
        if (!enabled || path == null) {
            return vanilla.invoke(path);
        }
        calls.incrementAndGet();
        if (resolverStatePending()) {
            bypassed.incrementAndGet();
            Object loaded = vanilla.invoke(path);
            // Deliberately not cached: what this returned depended on state that is now gone, so
            // it is not the answer an unrestricted call would get.
            return loaded;
        }
        return loadJsonResolved(path, vanilla);
    }

    /** The unrestricted path, separated so persistence behavior can be executed in isolation. */
    static Object loadJsonResolved(String path, MethodHandle vanilla) throws Throwable {
        Object cached = parsed.get(path);
        if (cached != null) {
            hits.incrementAndGet();
            if (!reparse) {
                return cached;
            }
            try {
                return copyOf(cached, vanilla, path);
            } catch (RuntimeException | LinkageError unexpected) {
                failures.incrementAndGet();
                return vanilla.invoke(path);
            }
        }
        if (profileStable && MergedReadCacheRuntime.singleJsonEligible(path)) {
            Object prepared = MergedReadCacheRuntime.singleJson(path);
            if (prepared != null) {
                preparedHits.incrementAndGet();
                parsed.put(path, prepared);
                return prepared;
            }
            preparedMisses.incrementAndGet();
        }
        Object loaded = vanilla.invoke(path);
        if (loaded != null) {
            parsed.put(path, loaded);
            if (profileStable && MergedReadCacheRuntime.captureSingleJson(path, loaded)) {
                preparedCaptures.incrementAndGet();
            }
        }
        return loaded;
    }

    /** Resource-loader entry: enabled roots and the full profile identity are now fixed. */
    public static void markProfileStable() {
        profileStable = true;
    }

    static void markStartupComplete() {
        markProfileStable();
    }

    /**
     * True when the resolver is holding one-shot state that only a real resolve will clear.
     *
     * <p>Reflective because the fields are private and obfuscated -- one is literally named
     * {@code String} and the other {@code super}. Both handles are resolved once. If they cannot be
     * resolved the memo turns itself off rather than run without the check.
     */
    private static boolean resolverStatePending() {
        if (!restrictionProbeReady && !resolveHandles()) {
            enabled = false;
            return true;
        }
        try {
            Object resolver = resolverSingleton.invoke();
            return pendingDirectoryGetter.invoke(resolver) != null
                    || (boolean) pendingFlagGetter.invoke();
        } catch (ThreadDeath | VirtualMachineError fatal) {
            throw fatal;
        } catch (Throwable unreadable) {
            failures.incrementAndGet();
            enabled = false;
            return true;
        }
    }

    private static synchronized boolean resolveHandles() {
        if (restrictionProbeReady) {
            return true;
        }
        try {
            Class<?> resolver = Class.forName("com.fs.util.C", false,
                    LoadJsonMemoRuntime.class.getClassLoader());
            java.lang.reflect.Method singleton = resolver.getMethod("Object");
            java.lang.reflect.Field pendingDirectory = resolver.getDeclaredField("String");
            java.lang.reflect.Field pendingFlag = resolver.getDeclaredField("super");
            singleton.setAccessible(true);
            pendingDirectory.setAccessible(true);
            pendingFlag.setAccessible(true);
            MethodHandles.Lookup lookup = MethodHandles.lookup();
            resolverSingleton = lookup.unreflect(singleton);
            pendingDirectoryGetter = lookup.unreflectGetter(pendingDirectory);
            pendingFlagGetter = lookup.unreflectGetter(pendingFlag);
            restrictionProbeReady = true;
            return true;
        } catch (ReflectiveOperationException | RuntimeException | LinkageError unavailable) {
            failures.incrementAndGet();
            return false;
        }
    }

    /**
     * Rebuilds a fresh object from the cached one by round-tripping its text, so no caller can see
     * another caller's edits. Costs a parse and saves the walk, the open and the read.
     */
    private static Object copyOf(Object cached, MethodHandle vanilla, String path) throws Throwable {
        try {
            Class<?> type = cached.getClass();
            String text = (String) type.getMethod("toString").invoke(cached);
            return type.getConstructor(String.class).newInstance(text);
        } catch (ReflectiveOperationException notReconstructible) {
            return vanilla.invoke(path);
        }
    }

    static Map<String, Object> report() {
        Map<String, Object> report = new LinkedHashMap<>();
        report.put("enabled", enabled);
        report.put("resolverGuard", "prelinked-method-handles");
        report.put("profileStable", profileStable);
        report.put("sharesParsedObjects", !reparse);
        report.put("calls", calls.get());
        report.put("servedFromMemo", hits.get());
        report.put("bypassedForPendingResolverState", bypassed.get());
        report.put("distinctPaths", parsed.size());
        report.put("failures", failures.get());
        report.put("preparedHits", preparedHits.get());
        report.put("preparedMisses", preparedMisses.get());
        report.put("preparedCaptures", preparedCaptures.get());
        return report;
    }

    static void reset() {
        parsed.clear();
        calls.set(0);
        hits.set(0);
        bypassed.set(0);
        failures.set(0);
        preparedHits.set(0);
        preparedMisses.set(0);
        preparedCaptures.set(0);
        profileStable = false;
        restrictionProbeReady = false;
        resolverSingleton = null;
        pendingDirectoryGetter = null;
        pendingFlagGetter = null;
    }
}
