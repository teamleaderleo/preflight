package dev.starsector.preflight.cli;

import java.util.Collection;

/**
 * The "did you mean" behind mistyped commands and options.
 *
 * <p>Split out so both ask the same question the same way. A clearly unique partial spelling is
 * useful before edit distance: someone who typed {@code doct} already supplied enough intent for
 * {@code doctor}, while accepting the abbreviation itself would create a compatibility promise.
 * Otherwise the threshold scales with the length of what was typed: one edit is worth guessing at
 * for a short word, three for a long one, and beyond that a suggestion is noise wearing the shape
 * of help.
 */
final class Suggestions {
    private Suggestions() {
    }

    /** The nearest candidate, or null when nothing is near enough to be worth offering. */
    static String closest(String requested, Collection<String> candidates) {
        String prefixCompletion = uniquePrefixCompletion(requested, candidates);
        if (prefixCompletion != null) {
            return prefixCompletion;
        }

        String closest = null;
        int closestDistance = Integer.MAX_VALUE;
        for (String candidate : candidates) {
            int distance = editDistance(requested, candidate);
            if (distance < closestDistance) {
                closest = candidate;
                closestDistance = distance;
            }
        }
        int threshold = Math.max(1, Math.min(3, requested.length() / 2));
        return closestDistance <= threshold ? closest : null;
    }

    private static String uniquePrefixCompletion(String requested, Collection<String> candidates) {
        int meaningfulLength = requested.startsWith("--") ? requested.length() - 2 : requested.length();
        if (meaningfulLength < 3) {
            return null;
        }

        String completion = null;
        for (String candidate : candidates) {
            if (!candidate.startsWith(requested)) {
                continue;
            }
            if (completion != null) {
                return null;
            }
            completion = candidate;
        }
        return completion;
    }

    static int editDistance(String left, String right) {
        int[] prior = new int[right.length() + 1];
        int[] current = new int[right.length() + 1];
        for (int j = 0; j <= right.length(); j++) {
            prior[j] = j;
        }
        for (int i = 1; i <= left.length(); i++) {
            current[0] = i;
            for (int j = 1; j <= right.length(); j++) {
                int substitution = prior[j - 1]
                        + (left.charAt(i - 1) == right.charAt(j - 1) ? 0 : 1);
                current[j] = Math.min(
                        Math.min(prior[j] + 1, current[j - 1] + 1),
                        substitution);
            }
            int[] swap = prior;
            prior = current;
            current = swap;
        }
        return prior[right.length()];
    }
}
