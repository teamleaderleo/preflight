package dev.starsector.preflight.agent;

import dev.starsector.preflight.core.PreparedHullJsonCacheIO;
import dev.starsector.preflight.core.PreparedProjectileJsonCacheIO;
import dev.starsector.preflight.core.PreparedVariantJsonCacheIO;
import dev.starsector.preflight.core.PreparedWeaponJsonCacheIO;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.TreeSet;
import org.json.JSONArray;
import org.json.JSONObject;

/**
 * Offline fidelity gate for the merged-read cache's real bridge and tagged-tree implementation.
 *
 * <p>Compile with the system JDK, then run with Starsector's JVM and its own json.jar. The corpus is
 * the four live prepared spec caches because they contain thousands of large, real game and mod JSON
 * objects rather than fixtures. Comparison is recursive and type-exact; object insertion order is
 * deliberately ignored because the installed JSONObject is backed by a HashMap.
 */
final class MergedReadJsonFidelity {
    private static long nodes;

    public static void main(String[] args) throws Exception {
        Path root = Path.of(System.getProperty("user.home"),
                ".starsector-preflight", "cache", "spec-store");
        List<String> texts = new ArrayList<>();
        texts.addAll(PreparedVariantJsonCacheIO.read(
                find(root, "variant-json", ".spvj")).entries().values());
        texts.addAll(PreparedWeaponJsonCacheIO.read(
                find(root, "weapon-json", ".spwj")).entries().values());
        texts.addAll(PreparedHullJsonCacheIO.read(
                find(root, "hull-json", ".sphj")).entries().values());
        texts.addAll(PreparedProjectileJsonCacheIO.read(
                find(root, "projectile-json", ".sppj")).entries().values());

        GameJson bridge = GameJson.bridge();
        long treeBytes = 0;
        for (int index = 0; index < texts.size(); index++) {
            JSONObject original = new JSONObject(texts.get(index));
            byte[] tree = bridge.encode(original);
            treeBytes += tree.length;
            Object restored = bridge.decode(tree);
            compare(original, restored, "$[" + index + "]");
        }

        System.out.printf("PASS: %,d entries, %,d values, %.1f MB tagged trees%n",
                texts.size(), nodes, treeBytes / 1e6);
        System.out.printf("JVM: %s (%s); json.jar JSONObject: %s%n",
                System.getProperty("java.version"), System.getProperty("os.arch"),
                JSONObject.class.getProtectionDomain().getCodeSource().getLocation());
    }

    private static Path find(Path root, String directory, String suffix) throws Exception {
        try (var files = Files.list(root.resolve(directory))) {
            return files.filter(path -> path.toString().endsWith(suffix))
                    .findFirst().orElseThrow();
        }
    }

    private static void compare(Object expected, Object actual, String path) throws Exception {
        nodes++;
        if (isNull(expected) || isNull(actual)) {
            if (!isNull(expected) || !isNull(actual)) {
                mismatch(path, expected, actual);
            }
            return;
        }
        if (expected instanceof JSONObject left && actual instanceof JSONObject right) {
            TreeSet<String> leftKeys = keys(left);
            TreeSet<String> rightKeys = keys(right);
            if (!leftKeys.equals(rightKeys)) {
                throw new AssertionError(path + " keys differ: " + leftKeys + " != " + rightKeys);
            }
            for (String key : leftKeys) {
                compare(left.get(key), right.get(key), path + "." + key);
            }
            return;
        }
        if (expected instanceof JSONArray left && actual instanceof JSONArray right) {
            if (left.length() != right.length()) {
                throw new AssertionError(path + " lengths differ: "
                        + left.length() + " != " + right.length());
            }
            for (int index = 0; index < left.length(); index++) {
                compare(left.get(index), right.get(index), path + "[" + index + "]");
            }
            return;
        }
        if (expected.getClass() != actual.getClass() || !expected.equals(actual)) {
            mismatch(path, expected, actual);
        }
        if (expected instanceof Double left && actual instanceof Double right
                && Double.doubleToRawLongBits(left) != Double.doubleToRawLongBits(right)) {
            mismatch(path, expected, actual);
        }
    }

    private static TreeSet<String> keys(JSONObject object) {
        TreeSet<String> keys = new TreeSet<>();
        for (Iterator<?> iterator = object.sortedKeys(); iterator.hasNext(); ) {
            keys.add((String) iterator.next());
        }
        return keys;
    }

    private static boolean isNull(Object value) {
        return value == null || value == JSONObject.NULL;
    }

    private static void mismatch(String path, Object expected, Object actual) {
        throw new AssertionError(path + " differs: " + describe(expected) + " != " + describe(actual));
    }

    private static String describe(Object value) {
        return value == null ? "<java null>"
                : value.getClass().getName() + "(" + value + ")";
    }
}
