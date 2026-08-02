package dev.starsector.preflight.core;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * The package each rule command name resolves to, learned from one complete vanilla walk.
 *
 * <p>Vanilla's {@code getCommandClass(name)} memoises {@code name -> fully-qualified class} in a
 * static map, and on a miss walks every declared {@code ruleCommandPackages} entry in order calling
 * {@code Class.forName(pkg + "." + name, false, loader)} followed by {@code newInstance()}, catching
 * {@code Exception} and continuing. On the measured profile 41 packages are declared and 671 distinct
 * names miss, which costs 641ms in failed classpath scans. Knowing the winner in advance replaces the
 * walk with its own last step.
 *
 * <h2>Why the declared package list is stored alongside the map</h2>
 *
 * <p>A winning package is only the right answer for the exact ordered list it was learned from:
 * insert a package ahead of the winner and vanilla would resolve a different class. The profile
 * identity already hashes the {@code settings.json} providers that list is merged from, but that is
 * an indirect argument. Storing the list lets the runtime compare it against the list the game
 * actually built and decline on any difference, which is a direct check that does not depend on the
 * identity being computed correctly.
 *
 * <h2>What is deliberately absent</h2>
 *
 * <p>Only names whose entire failing prefix threw {@code ClassNotFoundException} may be recorded. A
 * failure of any other kind means the class was found and initialised before it failed to
 * instantiate, so skipping it would skip a static initialiser that vanilla runs. Those names are
 * left out and always walk. See {@code docs/evidence/2026-08-02-zero-percent-is-spec-store.md}.
 */
public record PreparedRuleCommandClassCache(
        String profileIdentitySha256,
        List<String> commandPackages,
        Map<String, String> winningPackages) {
    public static final int FORMAT_VERSION = 1;

    /** The measured profile declares 41; the ceiling only has to be absurd, not tight. */
    public static final int MAX_PACKAGES = 4_096;

    /** The measured profile resolves 665 distinct names. */
    public static final int MAX_ENTRIES = 262_144;

    public static final int MAX_NAME_LENGTH = 512;

    public PreparedRuleCommandClassCache {
        Hashes.decodeSha256(profileIdentitySha256);
        profileIdentitySha256 = profileIdentitySha256.toLowerCase(Locale.ROOT);

        commandPackages = List.copyOf(commandPackages);
        if (commandPackages.isEmpty()) {
            throw new IllegalArgumentException("Prepared rule command cache declares no packages");
        }
        if (commandPackages.size() > MAX_PACKAGES) {
            throw new IllegalArgumentException(
                    "Prepared rule command cache declares too many packages: " + commandPackages.size());
        }
        for (String declared : commandPackages) {
            requirePackage(declared);
        }

        if (winningPackages.size() > MAX_ENTRIES) {
            throw new IllegalArgumentException(
                    "Prepared rule command cache holds too many names: " + winningPackages.size());
        }
        // Sorted so that the same profile always encodes to the same bytes regardless of the order
        // the names happened to be learned in.
        Map<String, String> ordered = new LinkedHashMap<>();
        for (String name : new java.util.TreeSet<>(winningPackages.keySet())) {
            requireCommandName(name);
            String winner = winningPackages.get(name);
            requirePackage(winner);
            // A winner outside the declared list could never have been produced by the walk, so an
            // artifact claiming one is inconsistent with itself and is rejected rather than used.
            if (!commandPackages.contains(winner)) {
                throw new IllegalArgumentException(
                        "Prepared rule command cache resolves [" + name + "] to an undeclared package");
            }
            ordered.put(name, winner);
        }
        winningPackages = Map.copyOf(ordered);
    }

    /** The winning package for {@code name}, or null when it must take vanilla's full walk. */
    public String winningPackage(String name) {
        return name == null ? null : winningPackages.get(name);
    }

    private static void requirePackage(String value) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException("Prepared rule command package is empty");
        }
        if (value.length() > MAX_NAME_LENGTH) {
            throw new IllegalArgumentException("Prepared rule command package is too long");
        }
        // The walk concatenates this with '.' and a name and hands the result to Class.forName, so
        // anything that is not a dotted identifier sequence could not have come from a real profile.
        for (String part : value.split("\\.", -1)) {
            requireIdentifier(part, "package");
        }
    }

    private static void requireCommandName(String value) {
        if (value == null || value.length() > MAX_NAME_LENGTH) {
            throw new IllegalArgumentException("Prepared rule command name is empty or too long");
        }
        requireIdentifier(value, "name");
    }

    private static void requireIdentifier(String value, String what) {
        if (value.isEmpty() || !Character.isJavaIdentifierStart(value.charAt(0))) {
            throw new IllegalArgumentException(
                    "Prepared rule command " + what + " is not an identifier: [" + value + "]");
        }
        for (int index = 1; index < value.length(); index++) {
            if (!Character.isJavaIdentifierPart(value.charAt(index))) {
                throw new IllegalArgumentException(
                        "Prepared rule command " + what + " is not an identifier: [" + value + "]");
            }
        }
    }
}
