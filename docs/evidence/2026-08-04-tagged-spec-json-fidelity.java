package dev.starsector.preflight.agent;

import java.io.ByteArrayInputStream;
import java.io.DataInputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.TreeSet;
import org.json.JSONArray;
import org.json.JSONObject;

/** Installed-json fidelity and timing gate for moving the four V1 text caches to tagged trees. */
final class TaggedSpecJsonFidelity {
    private static final int CHECKSUM_BYTES = 32;
    private static long nodes;
    private static int consumed;

    public static void main(String[] args) throws Exception {
        Path root = Path.of(System.getProperty("user.home"),
                ".starsector-preflight", "cache", "spec-store");
        List<Entry> corpus = new ArrayList<>();
        read(corpus, find(root, "variant-json", ".spvj"), "SPVJ");
        read(corpus, find(root, "weapon-json", ".spwj"), "SPWJ");
        read(corpus, find(root, "hull-json", ".sphj"), "SPHJ");
        read(corpus, find(root, "projectile-json", ".sppj"), "SPPJ");

        GameJson bridge = GameJson.bridge();
        List<byte[]> trees = new ArrayList<>(corpus.size());
        long textBytes = 0;
        long treeBytes = 0;
        for (int index = 0; index < corpus.size(); index++) {
            Entry entry = corpus.get(index);
            JSONObject original = new JSONObject(entry.text());
            byte[] tree = bridge.encode(original);
            Object restored = bridge.decode(tree);
            compare(original, restored, "$[" + index + "] " + entry.path());
            trees.add(tree);
            textBytes += entry.text().getBytes(StandardCharsets.UTF_8).length;
            treeBytes += tree.length;
        }

        System.out.printf("PASS: %,d entries, %,d values%n", corpus.size(), nodes);
        System.out.printf("text %.1f MB; tagged trees %.1f MB%n", textBytes / 1e6, treeBytes / 1e6);
        System.out.printf("JVM: %s (%s); json.jar JSONObject: %s%n%n",
                System.getProperty("java.version"), System.getProperty("os.arch"),
                JSONObject.class.getProtectionDomain().getCodeSource().getLocation());

        for (int round = 0; round < 5; round++) {
            long parse = time(() -> {
                for (Entry entry : corpus) {
                    consumed += new JSONObject(entry.text()).length();
                }
            });
            long decode = time(() -> {
                for (byte[] tree : trees) {
                    consumed += ((JSONObject) bridge.decode(tree)).length();
                }
            });
            System.out.printf("round %d: parse %.3fs; tagged decode %.3fs; %.2fx; saves %.3fs%n",
                    round, parse / 1e9, decode / 1e9, (double) parse / decode,
                    (parse - decode) / 1e9);
        }
        System.out.println("consume=" + consumed);
    }

    /** Reads the checksummed text-based V1 artifact without depending on the now-V2 production IO. */
    private static void read(List<Entry> entries, Path source, String expectedMagic) throws Exception {
        byte[] file = Files.readAllBytes(source);
        try (DataInputStream input = new DataInputStream(new ByteArrayInputStream(file))) {
            String magic = new String(input.readNBytes(4), StandardCharsets.US_ASCII);
            int version = input.readInt();
            int payloadLength = input.readInt();
            if (!expectedMagic.equals(magic) || version != 1 || payloadLength < 0
                    || 4 + Integer.BYTES * 2 + payloadLength + CHECKSUM_BYTES != file.length) {
                throw new IllegalArgumentException("Not the expected V1 artifact: " + source);
            }
            byte[] payload = input.readNBytes(payloadLength);
            byte[] checksum = input.readNBytes(CHECKSUM_BYTES);
            if (!MessageDigest.isEqual(checksum,
                    MessageDigest.getInstance("SHA-256").digest(payload))) {
                throw new IllegalArgumentException("Checksum mismatch: " + source);
            }
            try (DataInputStream values = new DataInputStream(new ByteArrayInputStream(payload))) {
                values.readNBytes(32); // profile identity
                int count = values.readInt();
                for (int index = 0; index < count; index++) {
                    entries.add(new Entry(string(values), string(values)));
                }
                if (values.read() != -1) {
                    throw new IllegalArgumentException("Trailing payload bytes: " + source);
                }
            }
        }
    }

    private static String string(DataInputStream input) throws Exception {
        int length = input.readInt();
        if (length < 0 || length > 32 * 1024 * 1024) {
            throw new IllegalArgumentException("Invalid V1 string length: " + length);
        }
        byte[] bytes = input.readNBytes(length);
        if (bytes.length != length) {
            throw new IllegalArgumentException("Truncated V1 string");
        }
        return new String(bytes, StandardCharsets.UTF_8);
    }

    private static Path find(Path root, String directory, String suffix) throws Exception {
        try (var files = Files.list(root.resolve(directory))) {
            return files.filter(path -> path.toString().endsWith(suffix))
                    .findFirst().orElseThrow();
        }
    }

    private static long time(Work work) throws Exception {
        long start = System.nanoTime();
        work.run();
        return System.nanoTime() - start;
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
        return value == null ? "<java null>" : value.getClass().getName() + "(" + value + ")";
    }

    private record Entry(String path, String text) {
    }

    private interface Work {
        void run() throws Exception;
    }
}
