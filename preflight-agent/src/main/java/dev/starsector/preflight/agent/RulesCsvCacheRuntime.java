package dev.starsector.preflight.agent;

import dev.starsector.preflight.core.PreparedRulesCsvCache;
import dev.starsector.preflight.core.PreparedRulesCsvCacheIO;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;

/** Learns merged rules rows once and reconstructs a fresh JSONArray on an exact-profile hit. */
public final class RulesCsvCacheRuntime {
    public static final String PLAN_ID = "vanilla-rules-merged-csv-cache-v1";

    private static volatile State state = State.disabled();
    private static final SeamTimer REHYDRATE_CLOCK = new SeamTimer();

    private RulesCsvCacheRuntime() {
    }

    static void beginSession() {
        state = State.disabled();
        REHYDRATE_CLOCK.reset();
    }

    static void configure(Path artifact) {
        if (artifact == null) {
            state = State.disabled();
            return;
        }
        Path absolute = artifact.toAbsolutePath().normalize();
        String fileName = absolute.getFileName().toString().toLowerCase(Locale.ROOT);
        if (!fileName.matches("[0-9a-f]{64}\\.sprc")) {
            state = State.disabled();
            return;
        }
        String profile = fileName.substring(0, 64);
        byte[] tree = null;
        String diagnostic = "capture";
        if (Files.isRegularFile(absolute)) {
            try {
                PreparedRulesCsvCache stored = PreparedRulesCsvCacheIO.read(absolute);
                if (profile.equals(stored.profileIdentitySha256())) {
                    tree = stored.mergedTree();
                    diagnostic = "hit";
                } else {
                    diagnostic = "profile-mismatch";
                }
            } catch (Exception error) {
                diagnostic = "rejected:" + message(error);
            }
        }
        state = new State(absolute, profile, tree, diagnostic);
    }

    static boolean ready() {
        return state.artifact != null;
    }

    static String status() {
        return state.diagnostic;
    }

    /** Returns a fresh game JSONArray, or null so the woven call site executes vanilla. */
    public static Object cached() {
        State current = state;
        byte[] tree = current.mergedTree;
        if (current.artifact == null || tree == null || current.badEntry.get()) {
            current.misses.incrementAndGet();
            return null;
        }
        long entry = REHYDRATE_CLOCK.enter();
        try {
            GameJson bridge = GameJson.bridge();
            Object result = bridge.decode(tree);
            if (!bridge.isGameArray(result)) {
                throw new IllegalArgumentException("prepared rules tree is not an array");
            }
            current.hits.incrementAndGet();
            return result;
        } catch (ThreadDeath | VirtualMachineError fatal) {
            throw fatal;
        } catch (Throwable error) {
            current.badEntry.set(true);
            current.misses.incrementAndGet();
            current.diagnose("cached rules tree could not be reconstructed: " + message(error));
            return null;
        } finally {
            REHYDRATE_CLOCK.exit(entry);
        }
    }

    /** Captures only the value produced by the original loader on a miss. */
    public static void capture(Object json) {
        State current = state;
        if (current.artifact == null || json == null) {
            return;
        }
        try {
            GameJson bridge = GameJson.bridge();
            if (bridge.isGameArray(json)) {
                current.learnedTree = bridge.encode(json);
                current.captures.incrementAndGet();
            }
        } catch (ThreadDeath | VirtualMachineError fatal) {
            throw fatal;
        } catch (Throwable error) {
            current.diagnose("vanilla rules tree could not be captured: " + message(error));
        }
    }

    /** Publishes only after vanilla finishes the complete rules loader normally. */
    public static void complete() {
        State current = state;
        byte[] learned = current.learnedTree;
        if (current.artifact == null || learned == null
                || !current.completed.compareAndSet(false, true)) {
            return;
        }
        try {
            PreparedRulesCsvCacheIO.write(
                    current.artifact,
                    new PreparedRulesCsvCache(current.profileIdentity, learned));
            current.writes.incrementAndGet();
        } catch (ThreadDeath | VirtualMachineError fatal) {
            throw fatal;
        } catch (Throwable error) {
            current.diagnose("merged rules tree cache could not be written: " + message(error));
        }
    }

    static Map<String, Object> telemetry() {
        State current = state;
        Map<String, Object> values = new LinkedHashMap<>();
        values.put("status", current.diagnostic);
        values.put("profileIdentity", current.profileIdentity);
        values.put("artifact", current.artifact);
        values.put("prepared", current.mergedTree != null);
        values.put("hits", current.hits.get());
        values.put("misses", current.misses.get());
        values.put("captures", current.captures.get());
        values.put("writes", current.writes.get());
        values.putAll(REHYDRATE_CLOCK.snapshot("rehydrate"));
        return values;
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
        private final byte[] mergedTree;
        private final String diagnostic;
        private volatile byte[] learnedTree;
        private final AtomicBoolean badEntry = new AtomicBoolean();
        private final AtomicBoolean completed = new AtomicBoolean();
        private final AtomicBoolean diagnosed = new AtomicBoolean();
        private final AtomicLong hits = new AtomicLong();
        private final AtomicLong misses = new AtomicLong();
        private final AtomicLong captures = new AtomicLong();
        private final AtomicLong writes = new AtomicLong();

        private State(Path artifact, String profileIdentity, byte[] mergedTree, String diagnostic) {
            this.artifact = artifact;
            this.profileIdentity = profileIdentity;
            this.mergedTree = mergedTree;
            this.diagnostic = diagnostic;
        }

        private static State disabled() {
            return new State(null, "", null, "disabled");
        }

        private void diagnose(String problem) {
            if (diagnosed.compareAndSet(false, true)) {
                System.err.println("[Preflight] " + problem + "; vanilla fallback remains active");
            }
        }
    }
}
