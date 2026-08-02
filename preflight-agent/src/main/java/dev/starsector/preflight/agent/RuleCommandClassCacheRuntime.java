package dev.starsector.preflight.agent;

import dev.starsector.preflight.core.PreparedRuleCommandClassCache;
import dev.starsector.preflight.core.PreparedRuleCommandClassCacheIO;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Replaces vanilla's rule-command package walk with its own last step.
 *
 * <p>{@code getCommandClass(name)} walks 41 declared packages calling
 * {@code Class.forName(pkg + "." + name, false, loader)} and {@code newInstance()} until one
 * resolves. 671 distinct names reach that walk on the measured profile and it costs <b>641ms</b>,
 * which is roughly 0.9ms per name spent on failed lookups against the modded classpath. Knowing the
 * winner turns 41 attempts into 1.
 *
 * <h2>The side effect is performed, not skipped</h2>
 *
 * <p>A hit still runs {@code Class.forName} and {@code newInstance()} on the winning package, so the
 * class is loaded, initialised, and instantiated exactly as vanilla did, and the instance is
 * discarded exactly as vanilla discarded it. What is skipped is only the failing prefix. The
 * alternative -- pre-seeding vanilla's memo with the resolved name and never instantiating -- would
 * move a command class's static initialisation from load time to first use, and there is no evidence
 * that is safe.
 *
 * <h2>Which names may be shortcut, and how that is decided</h2>
 *
 * <p>{@code forName} is called with {@code initialize = false}, but {@code newInstance()}
 * initialises. So a package ahead of the winner that holds a same-named class which loads and then
 * fails to instantiate has already run that class's static initialiser, and skipping it would skip
 * the initialiser too. Rather than assume this never happens, the learning run records every failure
 * the walk catches: a name is admitted only when every one of its failures was a
 * {@code ClassNotFoundException}, which is the one outcome that provably touched nothing. Everything
 * else always walks.
 *
 * <h2>Why the declared package list is compared</h2>
 *
 * <p>The winner is only correct for the ordered list it was learned from. The artifact carries that
 * list and it is compared against the list the game actually built before any name is shortcut, so a
 * profile whose packages moved falls back wholesale rather than resolving the wrong class.
 *
 * <p>Every disagreement -- an unmatched list, a winner that no longer resolves, a corrupt artifact --
 * returns null and lets vanilla's untouched walk produce the answer.
 */
public final class RuleCommandClassCacheRuntime {
    public static final String PLAN_ID = "vanilla-rule-command-class-cache-v1";

    private static volatile State state = State.disabled();

    private RuleCommandClassCacheRuntime() {
    }

    static void beginSession() {
        state = State.disabled();
    }

    static void configure(Path artifact) {
        if (artifact == null) {
            state = State.disabled();
            return;
        }
        Path absolute = artifact.toAbsolutePath().normalize();
        String fileName = absolute.getFileName().toString().toLowerCase(Locale.ROOT);
        if (!fileName.matches("[0-9a-f]{64}\\.sprk")) {
            state = State.disabled();
            return;
        }
        String profile = fileName.substring(0, 64);
        PreparedRuleCommandClassCache prepared = null;
        String diagnostic = "capture";
        if (Files.isRegularFile(absolute)) {
            try {
                PreparedRuleCommandClassCache stored = PreparedRuleCommandClassCacheIO.read(absolute);
                if (profile.equals(stored.profileIdentitySha256())) {
                    prepared = stored;
                    diagnostic = "hit";
                } else {
                    diagnostic = "profile-mismatch";
                }
            } catch (Exception error) {
                diagnostic = "rejected:" + message(error);
            }
        }
        state = new State(absolute, profile, prepared, diagnostic);
    }

    static boolean ready() {
        return state.artifact != null;
    }

    static String status() {
        return state.diagnostic;
    }

    /**
     * Answers the walk that vanilla was about to perform, or null to let it perform it.
     *
     * <p>Woven at the branch target of the memo miss, so it never runs on the 25,091 calls a launch
     * that vanilla answers out of its static map.
     *
     * @param name the command name, exactly what vanilla received
     * @param packages the live ordered {@code ruleCommandPackages} list, read from vanilla's own field
     * @param loader the loader vanilla was about to use, produced by vanilla's own two instructions
     */
    public static String shortcut(String name, List<?> packages, ClassLoader loader) {
        State current = state;
        if (current.artifact == null || name == null) {
            return null;
        }
        String winner;
        try {
            current.observePackages(packages);
            winner = current.winningPackage(name);
        } catch (ThreadDeath | VirtualMachineError fatal) {
            throw fatal;
        } catch (Throwable error) {
            current.diagnose("prepared rule command packages could not be read: " + message(error));
            return null;
        }
        if (winner == null) {
            current.misses.incrementAndGet();
            return null;
        }

        String resolved = winner + "." + name;
        try {
            Class.forName(resolved, false, loader).newInstance();
        } catch (Exception disagreement) {
            // Vanilla catches exactly Exception here and continues its walk, so falling back
            // reproduces what it would have done. An Error is deliberately not caught: vanilla lets
            // it out of getCommandClass, and swallowing it here to retry would turn an
            // ExceptionInInitializerError into a NoClassDefFoundError on the second attempt.
            current.disagree(name, winner, disagreement);
            return null;
        }
        current.hits.incrementAndGet();
        return resolved;
    }

    /**
     * Records one caught failure from vanilla's walk.
     *
     * <p>Anything other than {@code ClassNotFoundException} means the class was found, loaded, and
     * initialised before it failed to instantiate, so this name can never be shortcut.
     */
    public static void noteFailure(String name, String candidatePackage, Throwable failure) {
        State current = state;
        if (current.artifact == null || name == null) {
            return;
        }
        if (!(failure instanceof ClassNotFoundException)) {
            current.unclean.add(name);
            current.uncleanNames.incrementAndGet();
        }
    }

    /** Records the package vanilla's walk actually resolved {@code name} in. */
    public static void noteWinner(String name, String winningPackage, String resolved) {
        State current = state;
        if (current.artifact == null || name == null || winningPackage == null) {
            return;
        }
        if (current.unclean.contains(name)) {
            return;
        }
        if (current.learned.size() >= PreparedRuleCommandClassCache.MAX_ENTRIES) {
            return;
        }
        if (!winningPackage.equals(current.learned.put(name, winningPackage))) {
            current.captures.incrementAndGet();
        }
    }

    /** Publishes what this run observed, once, after vanilla's rules loader returns normally. */
    public static void complete() {
        State current = state;
        if (current.artifact == null || !current.completed.compareAndSet(false, true)) {
            return;
        }
        if (current.captures.get() == 0) {
            // A fully warm run learned nothing new, so the artifact on disk is already correct.
            return;
        }
        List<String> packages = current.observedPackages;
        if (packages == null || packages.isEmpty()) {
            return;
        }
        try {
            Map<String, String> winners = new LinkedHashMap<>(current.learned);
            winners.keySet().removeAll(current.unclean);
            PreparedRuleCommandClassCacheIO.write(
                    current.artifact,
                    new PreparedRuleCommandClassCache(current.profileIdentity, packages, winners));
            current.writes.incrementAndGet();
        } catch (ThreadDeath | VirtualMachineError fatal) {
            throw fatal;
        } catch (Throwable error) {
            current.diagnose("rule command class cache could not be written: " + message(error));
        }
    }

    static Map<String, Object> telemetry() {
        State current = state;
        Map<String, Object> values = new LinkedHashMap<>();
        values.put("status", current.diagnostic);
        values.put("profileIdentity", current.profileIdentity);
        values.put("artifact", current.artifact);
        values.put("prepared", current.prepared == null ? 0 : current.prepared.winningPackages().size());
        values.put("packagesDeclared", current.observedPackages == null ? 0 : current.observedPackages.size());
        values.put("packagesMatched", current.packagesMatched);
        values.put("hits", current.hits.get());
        values.put("misses", current.misses.get());
        values.put("captures", current.captures.get());
        values.put("uncleanNames", current.uncleanNames.get());
        values.put("disagreements", current.disagreements.get());
        values.put("writes", current.writes.get());
        return values;
    }

    private static String message(Throwable error) {
        String text = error.getMessage();
        return text == null || text.isBlank() ? error.getClass().getSimpleName() : text;
    }

    private static final class State {
        private final Path artifact;
        private final String profileIdentity;
        private final PreparedRuleCommandClassCache prepared;
        private final String diagnostic;

        private final Map<String, String> learned = new ConcurrentHashMap<>();
        private final Set<String> unclean = ConcurrentHashMap.newKeySet();
        private volatile List<String> observedPackages;
        private volatile boolean packagesMatched;
        private final AtomicBoolean packagesObserved = new AtomicBoolean();
        private final AtomicBoolean disagreed = new AtomicBoolean();
        private final AtomicBoolean completed = new AtomicBoolean();
        private final AtomicBoolean diagnosed = new AtomicBoolean();
        private final AtomicLong hits = new AtomicLong();
        private final AtomicLong misses = new AtomicLong();
        private final AtomicLong captures = new AtomicLong();
        private final AtomicLong uncleanNames = new AtomicLong();
        private final AtomicLong disagreements = new AtomicLong();
        private final AtomicLong writes = new AtomicLong();

        private State(
                Path artifact,
                String profileIdentity,
                PreparedRuleCommandClassCache prepared,
                String diagnostic) {
            this.artifact = artifact;
            this.profileIdentity = profileIdentity;
            this.prepared = prepared;
            this.diagnostic = diagnostic;
        }

        private static State disabled() {
            return new State(null, "", null, "disabled");
        }

        /**
         * Reads the live package list once and decides whether the artifact describes it.
         *
         * <p>Seeding the learned map from a matching artifact is what keeps {@link #complete()} from
         * replacing a good map with only the handful of names a warm run happened to walk.
         */
        private void observePackages(List<?> packages) {
            if (!packagesObserved.compareAndSet(false, true)) {
                return;
            }
            List<String> declared = new ArrayList<>();
            if (packages != null) {
                for (Object each : packages) {
                    if (each instanceof String text) {
                        declared.add(text);
                    }
                }
                if (declared.size() != packages.size()) {
                    declared.clear();
                }
            }
            observedPackages = List.copyOf(declared);
            if (prepared != null && prepared.commandPackages().equals(observedPackages)) {
                packagesMatched = true;
                learned.putAll(prepared.winningPackages());
            }
        }

        private String winningPackage(String name) {
            return prepared == null || !packagesMatched || disagreed.get()
                    ? null
                    : prepared.winningPackage(name);
        }

        private void disagree(String name, String winner, Throwable failure) {
            disagreements.incrementAndGet();
            misses.incrementAndGet();
            // One wrong answer means the artifact no longer describes this install, and every later
            // shortcut would risk instantiating a class twice: once here and once in the walk that
            // follows. Stop after the first.
            if (disagreed.compareAndSet(false, true)) {
                diagnose("prepared rule command package " + winner + " no longer resolves ["
                        + name + "]: " + message(failure));
            }
        }

        private void diagnose(String problem) {
            if (diagnosed.compareAndSet(false, true)) {
                System.err.println("[Preflight] " + problem + "; vanilla fallback remains active");
            }
        }
    }
}
