package dev.starsector.preflight.agent;

import dev.starsector.preflight.core.PreparedRuleTokenCache;
import dev.starsector.preflight.core.PreparedRuleTokenCacheIO;
import java.lang.invoke.MethodHandle;
import java.lang.invoke.MethodHandles;
import java.lang.invoke.MethodType;
import java.lang.reflect.Field;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;

/**
 * A profile-bound persistent memo for the rule-expression tokenizer.
 *
 * <p>{@code Misc.tokenize} scans an expression character by character through a {@code StringBuffer},
 * so every {@code append}, {@code length}, {@code toString} and the clearing {@code delete} takes a
 * monitor. The campaign-rules loader calls it 62,340 times for <b>742ms</b> on the measured profile,
 * and 49.2% of those strings are exact repeats. See
 * {@code docs/evidence/2026-08-02-zero-percent-is-spec-store.md}.
 *
 * <p><b>Why a memo is equivalent here.</b> {@code tokenize} reads only its argument -- the rest of
 * its body is local state and two pure character predicates -- and every token it emits is
 * constructed as {@code new Misc$Token(String, TokenType)} with no later field write. The returned
 * list is therefore completely determined by the ordered {@code (string, type)} pairs, and replaying
 * those pairs through the same constructor reproduces it exactly, including the {@code varMemoryKey}
 * and {@code varNameWithoutMemoryKeyIfKeyIsValid} that constructor derives.
 *
 * <p><b>Why nothing is ever shared.</b> {@code Misc$Token} has four public non-final fields, and the
 * expression constructor mutates the list it receives ({@code remove}). A hit therefore allocates a
 * fresh {@code ArrayList} and a fresh {@code Token} per element; only the character scan is skipped.
 * The cached {@code TokenType} references are enum singletons, which is what vanilla stores too.
 *
 * <p>The persisted form contains only those ordered source pairs. It is checksummed and keyed by the
 * same exact ordered-rules identity as the prepared merged CSV. Unknown token enum names, a changed
 * profile, or any malformed artifact fall through to vanilla and are relearned only after startup
 * completes successfully.
 *
 * <p><b>Why this cannot fail closed.</b> The rewrite passes vanilla's own tokenizer in as a
 * {@code MethodHandle} constant, resolved by the JVM at the call site exactly as the original
 * {@code invokestatic} was. Every path that is not a confirmed hit -- gate off, miss, unexpected
 * token shape, any throwable while capturing or rebuilding -- calls that handle and returns its
 * list. A {@code RuleException} from a malformed expression propagates unwrapped.
 */
public final class RuleTokenCacheRuntime {
    static final String PLAN_ID = "rules-token-cache-v1";

    /**
     * Bounded only so that a pathological profile cannot grow this without limit. The measured
     * profile needs 31,816 entries and the game ships with a 512 MB heap.
     */
    private static final int MAX_ENTRIES = 131_072;

    private static volatile boolean enabled;
    private static volatile Path artifact;
    private static volatile String profileIdentity;
    private static volatile String status = "disabled";
    private static volatile boolean dirty;

    private static final Map<String, Shape> CACHE = new ConcurrentHashMap<>();
    private static final Map<String, List<PreparedRuleTokenCache.Token>> PERSISTED =
            new ConcurrentHashMap<>();
    private static final Set<String> PREPARED_KEYS = ConcurrentHashMap.newKeySet();
    private static final ClassValue<TokenLayout> LAYOUT = new ClassValue<>() {
        @Override
        protected TokenLayout computeValue(Class<?> token) {
            return TokenLayout.of(token);
        }
    };

    private static final AtomicLong CALLS = new AtomicLong();
    private static final AtomicLong HITS = new AtomicLong();
    private static final AtomicLong MISSES = new AtomicLong();
    private static final AtomicLong DECLINED = new AtomicLong();
    private static final AtomicLong PREPARED_HITS = new AtomicLong();
    private static final AtomicLong LOAD_FAILURES = new AtomicLong();
    private static final AtomicLong WRITES = new AtomicLong();
    private static final AtomicLong WRITE_FAILURES = new AtomicLong();
    private static final AtomicBoolean COMPLETED = new AtomicBoolean();

    private RuleTokenCacheRuntime() {
    }

    static void enable() {
        enabled = true;
        status = "session-only";
    }

    static void configure(Path rulesCsvArtifact) {
        enable();
        if (rulesCsvArtifact == null) {
            return;
        }
        Path rules = rulesCsvArtifact.toAbsolutePath().normalize();
        String name = rules.getFileName().toString();
        if (!name.matches("[0-9a-f]{64}\\.sprc")) {
            status = "invalid-profile-path";
            return;
        }
        profileIdentity = name.substring(0, 64);
        artifact = rules.resolveSibling(profileIdentity + ".sprt");
        status = "capture";
        if (!Files.isRegularFile(artifact)) {
            return;
        }
        try {
            PreparedRuleTokenCache prepared = PreparedRuleTokenCacheIO.read(artifact);
            if (!profileIdentity.equals(prepared.profileIdentitySha256())) {
                status = "profile-mismatch";
                return;
            }
            PERSISTED.putAll(prepared.entries());
            PREPARED_KEYS.addAll(prepared.entries().keySet());
            status = "hit";
        } catch (Exception error) {
            PERSISTED.clear();
            LOAD_FAILURES.incrementAndGet();
            status = "rejected:" + message(error);
        }
    }

    static boolean ready() {
        return enabled;
    }

    /**
     * Replaces {@code Misc.tokenize} at its single call site in the expression constructor.
     *
     * @param expression the trimmed expression text, exactly what vanilla receives
     * @param vanilla a JVM-resolved handle onto {@code Misc.tokenize(String)}
     * @param token {@code com.fs.starfarer.api.util.Misc$Token}, pushed as a class constant so the
     *     runtime never has to guess which loader holds the game
     */
    public static List<?> tokenize(String expression, MethodHandle vanilla, Class<?> token)
            throws Throwable {
        if (!enabled || expression == null) {
            return (List<?>) vanilla.invokeExact(expression);
        }
        CALLS.incrementAndGet();
        TokenLayout layout = LAYOUT.get(token);
        if (layout == null) {
            DECLINED.incrementAndGet();
            return (List<?>) vanilla.invokeExact(expression);
        }
        Shape cached = CACHE.get(expression);
        boolean prepared = PREPARED_KEYS.contains(expression);
        if (cached == null) {
            List<PreparedRuleTokenCache.Token> stored = PERSISTED.get(expression);
            if (stored != null) {
                try {
                    cached = Shape.fromPrepared(stored, layout);
                    if (cached != null) {
                        CACHE.putIfAbsent(expression, cached);
                    } else {
                        PERSISTED.remove(expression);
                        PREPARED_KEYS.remove(expression);
                        prepared = false;
                        dirty = true;
                    }
                } catch (ThreadDeath | VirtualMachineError fatal) {
                    throw fatal;
                } catch (Throwable ignored) {
                    PERSISTED.remove(expression);
                    PREPARED_KEYS.remove(expression);
                    prepared = false;
                    dirty = true;
                }
            }
        }
        if (cached != null) {
            try {
                List<Object> rebuilt = cached.rebuild(layout);
                HITS.incrementAndGet();
                if (prepared) {
                    PREPARED_HITS.incrementAndGet();
                }
                return rebuilt;
            } catch (ThreadDeath | VirtualMachineError fatal) {
                throw fatal;
            } catch (Throwable ignored) {
                // Rebuilding must never be why a rule fails to load.
                DECLINED.incrementAndGet();
                return (List<?>) vanilla.invokeExact(expression);
            }
        }
        // A miss returns vanilla's own list, so the first occurrence of any expression is exactly
        // what it always was and the capture below cannot affect it.
        List<?> tokens = (List<?>) vanilla.invokeExact(expression);
        MISSES.incrementAndGet();
        try {
            Shape shape = Shape.of(tokens, layout);
            if (shape == null) {
                DECLINED.incrementAndGet();
            } else if (CACHE.size() < MAX_ENTRIES) {
                CACHE.putIfAbsent(expression, shape);
                List<PreparedRuleTokenCache.Token> encoded = shape.toPrepared(layout);
                if (encoded != null && !encoded.equals(PERSISTED.put(expression, encoded))) {
                    dirty = true;
                }
            }
        } catch (ThreadDeath | VirtualMachineError fatal) {
            throw fatal;
        } catch (Throwable ignored) {
            DECLINED.incrementAndGet();
        }
        return tokens;
    }

    static Map<String, Object> telemetry() {
        Map<String, Object> values = new LinkedHashMap<>();
        values.put("enabled", enabled);
        values.put("status", status);
        values.put("artifact", artifact == null ? null : artifact.toString());
        values.put("calls", CALLS.get());
        values.put("hits", HITS.get());
        values.put("misses", MISSES.get());
        values.put("declined", DECLINED.get());
        values.put("entries", CACHE.size());
        values.put("prepared", PERSISTED.size());
        values.put("preparedHits", PREPARED_HITS.get());
        values.put("loadFailures", LOAD_FAILURES.get());
        values.put("writes", WRITES.get());
        values.put("writeFailures", WRITE_FAILURES.get());
        return values;
    }

    /** Publishes only after resource initialization returned successfully. */
    static void complete() {
        if (!enabled || !COMPLETED.compareAndSet(false, true)
                || !dirty || artifact == null || profileIdentity == null) {
            return;
        }
        try {
            PreparedRuleTokenCacheIO.write(artifact,
                    new PreparedRuleTokenCache(profileIdentity, new LinkedHashMap<>(PERSISTED)));
            dirty = false;
            WRITES.incrementAndGet();
            status = "published";
        } catch (Exception error) {
            WRITE_FAILURES.incrementAndGet();
            status = "write-failed:" + message(error);
        }
    }

    /** Test seam; the game never resets a session. */
    static void beginSession() {
        enabled = false;
        artifact = null;
        profileIdentity = null;
        status = "disabled";
        dirty = false;
        CACHE.clear();
        PERSISTED.clear();
        PREPARED_KEYS.clear();
        CALLS.set(0);
        HITS.set(0);
        MISSES.set(0);
        DECLINED.set(0);
        PREPARED_HITS.set(0);
        LOAD_FAILURES.set(0);
        WRITES.set(0);
        WRITE_FAILURES.set(0);
        COMPLETED.set(false);
    }

    /** The ordered {@code (string, type)} pairs one expression tokenizes to. */
    private record Shape(String[] strings, Object[] types) {
        static Shape of(List<?> tokens, TokenLayout layout) throws Throwable {
            if (tokens == null) {
                return null;
            }
            int size = tokens.size();
            String[] strings = new String[size];
            Object[] types = new Object[size];
            for (int index = 0; index < size; index++) {
                Object each = tokens.get(index);
                if (!layout.token.isInstance(each)) {
                    return null;
                }
                Object string = layout.string.get(each);
                if (string != null && !(string instanceof String)) {
                    return null;
                }
                strings[index] = (String) string;
                types[index] = layout.type.get(each);
            }
            return new Shape(strings, types);
        }

        List<Object> rebuild(TokenLayout layout) throws Throwable {
            MethodHandle constructor = layout.constructor;
            List<Object> tokens = new ArrayList<>(strings.length);
            for (int index = 0; index < strings.length; index++) {
                // invokeExact against the handle's own erased type: a generic invoke would run an
                // asType conversion at a call site the JIT cannot fold, which measured as most of
                // the difference between this memo and the ceiling the offline benchmark set.
                tokens.add((Object) constructor.invokeExact(strings[index], types[index]));
            }
            return tokens;
        }

        static Shape fromPrepared(
                List<PreparedRuleTokenCache.Token> prepared, TokenLayout layout) {
            String[] strings = new String[prepared.size()];
            Object[] types = new Object[prepared.size()];
            for (int index = 0; index < prepared.size(); index++) {
                PreparedRuleTokenCache.Token token = prepared.get(index);
                Object type = layout.typesByName.get(token.type());
                if (type == null) {
                    return null;
                }
                strings[index] = token.string();
                types[index] = type;
            }
            return new Shape(strings, types);
        }

        List<PreparedRuleTokenCache.Token> toPrepared(TokenLayout layout) {
            List<PreparedRuleTokenCache.Token> prepared = new ArrayList<>(strings.length);
            for (int index = 0; index < strings.length; index++) {
                Object type = types[index];
                if (!layout.type.getType().isInstance(type) || !(type instanceof Enum<?> value)) {
                    return null;
                }
                prepared.add(new PreparedRuleTokenCache.Token(strings[index], value.name()));
            }
            return List.copyOf(prepared);
        }
    }

    /** How to read and rebuild one {@code Misc$Token}, resolved once per loaded class. */
    private record TokenLayout(Class<?> token, Field string, Field type,
            MethodHandle constructor, Map<String, Object> typesByName) {
        static TokenLayout of(Class<?> token) {
            try {
                Field string = token.getField("string");
                Field type = token.getField("type");
                Object[] constants = type.getType().getEnumConstants();
                if (constants == null) {
                    return null;
                }
                Map<String, Object> typesByName = new HashMap<>();
                for (Object constant : constants) {
                    typesByName.put(((Enum<?>) constant).name(), constant);
                }
                MethodHandle constructor = MethodHandles.publicLookup().findConstructor(
                        token, MethodType.methodType(void.class, String.class, type.getType()));
                // Erased once, here, so the hot path can use invokeExact. The enum argument is
                // passed as Object and the constructor's own signature does the checking.
                constructor = constructor.asType(
                        MethodType.methodType(Object.class, String.class, Object.class));
                return new TokenLayout(token, string, type, constructor, Map.copyOf(typesByName));
            } catch (ThreadDeath | VirtualMachineError fatal) {
                throw fatal;
            } catch (Throwable ignored) {
                return null;
            }
        }
    }

    private static String message(Throwable error) {
        String value = error.getMessage();
        return value == null || value.isBlank() ? error.getClass().getSimpleName() : value;
    }
}
