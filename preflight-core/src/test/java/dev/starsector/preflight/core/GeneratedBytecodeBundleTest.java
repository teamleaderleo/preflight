package dev.starsector.preflight.core;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class GeneratedBytecodeBundleTest {
    @TempDir
    Path temporaryDirectory;

    @Test
    void bundleBytesAreDeterministicAndRoundTripCompleteClassMaps() throws Exception {
        byte[] alpha = classBytes(Alpha.class);
        byte[] beta = classBytes(Beta.class);
        String requested = Alpha.class.getName();
        String context = "a".repeat(64);

        LinkedHashMap<String, byte[]> reversed = new LinkedHashMap<>();
        reversed.put(Beta.class.getName(), beta);
        reversed.put(Alpha.class.getName(), alpha);
        GeneratedBytecodeBundle left = new GeneratedBytecodeBundle(context, requested, reversed);

        LinkedHashMap<String, byte[]> ordered = new LinkedHashMap<>();
        ordered.put(Alpha.class.getName(), alpha);
        ordered.put(Beta.class.getName(), beta);
        GeneratedBytecodeBundle right = new GeneratedBytecodeBundle(context.toUpperCase(), requested, ordered);

        byte[] leftBytes = GeneratedBytecodeBundleIO.toBytes(left);
        byte[] rightBytes = GeneratedBytecodeBundleIO.toBytes(right);
        assertArrayEquals(leftBytes, rightBytes);

        GeneratedBytecodeBundle decoded = GeneratedBytecodeBundleIO.fromBytes(leftBytes);
        assertEquals(context, decoded.contextKeySha256());
        assertEquals(requested, decoded.requestedClassName());
        assertEquals(2, decoded.classCount());
        assertEquals((long) alpha.length + beta.length, decoded.totalBytecodeBytes());
        assertArrayEquals(alpha, decoded.bytecode(Alpha.class.getName()));
        assertArrayEquals(beta, decoded.bytecode(Beta.class.getName()));
        assertEquals(left.bytecodeSha256(), decoded.bytecodeSha256());
        assertEquals(Alpha.class.getName(), ClassFileIdentity.binaryName(alpha));
        assertEquals(Beta.class.getName(), ClassFileIdentity.binaryName(beta));

        alpha[4] ^= 1;
        assertNotEquals(alpha[4], decoded.bytecode(Alpha.class.getName())[4]);
        byte[] returned = decoded.bytecode(Alpha.class.getName());
        returned[5] ^= 1;
        assertNotEquals(returned[5], decoded.bytecode(Alpha.class.getName())[5]);
    }

    @Test
    void formatRejectsCorruptionTruncationTrailingDataAndInvalidModels() throws Exception {
        GeneratedBytecodeBundle bundle = bundle("b".repeat(64));
        byte[] encoded = GeneratedBytecodeBundleIO.toBytes(bundle);

        byte[] corrupt = encoded.clone();
        corrupt[corrupt.length / 2] ^= 1;
        IOException checksum = assertThrows(IOException.class, () -> GeneratedBytecodeBundleIO.fromBytes(corrupt));
        assertTrue(checksum.getMessage().contains("checksum"), checksum.getMessage());

        byte[] truncated = java.util.Arrays.copyOf(encoded, encoded.length - 3);
        assertThrows(IOException.class, () -> GeneratedBytecodeBundleIO.fromBytes(truncated));

        byte[] trailing = java.util.Arrays.copyOf(encoded, encoded.length + 1);
        assertThrows(IOException.class, () -> GeneratedBytecodeBundleIO.fromBytes(trailing));

        assertThrows(IllegalArgumentException.class, () -> new GeneratedBytecodeBundle(
                "c".repeat(64),
                "missing.Class",
                Map.of(Alpha.class.getName(), classBytes(Alpha.class))));
        assertThrows(IllegalArgumentException.class, () -> new GeneratedBytecodeBundle(
                "c".repeat(64),
                "../escape",
                Map.of("../escape", classBytes(Alpha.class))));
        assertThrows(IllegalArgumentException.class, () -> new GeneratedBytecodeBundle(
                "c".repeat(64),
                "dev/example/SlashName",
                Map.of("dev/example/SlashName", classBytes(Alpha.class))));
        assertThrows(IllegalArgumentException.class, () -> new GeneratedBytecodeBundle(
                "c".repeat(64),
                Alpha.class.getName() + " ",
                Map.of(Alpha.class.getName(), classBytes(Alpha.class))));
        assertThrows(IllegalArgumentException.class, () -> new GeneratedBytecodeBundle(
                "c".repeat(64),
                Alpha.class.getName(),
                Map.of(" " + Alpha.class.getName(), classBytes(Alpha.class))));
        IllegalArgumentException wrongIdentity = assertThrows(
                IllegalArgumentException.class,
                () -> new GeneratedBytecodeBundle(
                        "c".repeat(64),
                        Beta.class.getName(),
                        Map.of(Beta.class.getName(), classBytes(Alpha.class))));
        assertTrue(wrongIdentity.getMessage().contains("differs from classfile identity"), wrongIdentity.getMessage());
        assertThrows(IllegalArgumentException.class, () -> new GeneratedBytecodeBundle(
                "c".repeat(64),
                Alpha.class.getName(),
                Map.of(Alpha.class.getName(), new byte[] {0, 1, 2, 3, 4, 5, 6, 7})));
    }

    @Test
    void contentAddressedLookupIsHitMissAndCorruptWithoutThrowingIntoCaller() throws Exception {
        Path cache = temporaryDirectory.resolve("cache");
        GeneratedBytecodeBundle bundle = bundle("d".repeat(64));

        GeneratedBytecodeCache.Lookup miss = GeneratedBytecodeCache.lookup(
                cache, bundle.contextKeySha256(), bundle.requestedClassName());
        assertEquals(GeneratedBytecodeCache.Status.MISS, miss.status());

        GeneratedBytecodeCache.write(cache, bundle);
        GeneratedBytecodeCache.Lookup hit = GeneratedBytecodeCache.lookup(
                cache, bundle.contextKeySha256(), bundle.requestedClassName());
        assertEquals(GeneratedBytecodeCache.Status.HIT, hit.status());
        assertArrayEquals(bundle.bytecode(bundle.requestedClassName()),
                hit.bundle().bytecode(bundle.requestedClassName()));
        assertTrue(hit.path().startsWith(cache.toAbsolutePath().normalize()));

        Files.write(hit.path(), new byte[] {1, 2, 3});
        GeneratedBytecodeCache.Lookup corrupt = GeneratedBytecodeCache.lookup(
                cache, bundle.contextKeySha256(), bundle.requestedClassName());
        assertEquals(GeneratedBytecodeCache.Status.CORRUPT, corrupt.status());

        GeneratedBytecodeCache.Lookup invalid = GeneratedBytecodeCache.lookup(
                cache, "bad", bundle.requestedClassName());
        assertEquals(GeneratedBytecodeCache.Status.ERROR, invalid.status());
    }

    @Test
    void packDeduplicatesClassBytesAndRoundTripsIndependentCompleteMaps() throws Exception {
        String context = "e".repeat(64);
        byte[] alpha = classBytes(Alpha.class);
        byte[] beta = classBytes(Beta.class);
        LinkedHashMap<String, byte[]> both = new LinkedHashMap<>();
        both.put(Beta.class.getName(), beta);
        both.put(Alpha.class.getName(), alpha);

        GeneratedBytecodePack.Builder builder = new GeneratedBytecodePack.Builder(context);
        assertTrue(builder.record(Alpha.class.getName(), both));
        assertTrue(builder.record(Beta.class.getName(), both));
        GeneratedBytecodePack pack = builder.build();
        assertNotNull(pack);
        assertEquals(2, pack.requestCount());
        assertEquals(2, pack.classCount());
        assertEquals((long) alpha.length + beta.length, pack.uniqueBytecodeBytes());
        assertEquals(2L * (alpha.length + beta.length), pack.expandedBytecodeBytes());

        byte[] encoded = GeneratedBytecodePack.toBytes(pack);
        GeneratedBytecodePack decoded = GeneratedBytecodePack.fromBytes(encoded);
        Map<String, byte[]> first = decoded.classesFor(Alpha.class.getName());
        Map<String, byte[]> second = decoded.classesFor(Beta.class.getName());
        assertEquals(first.keySet(), second.keySet());
        first.put("synthetic.Extra", new byte[] {1});
        first.get(Alpha.class.getName())[4] ^= 1;
        assertTrue(!second.containsKey("synthetic.Extra"));
        assertArrayEquals(alpha, second.get(Alpha.class.getName()));

        Path target = GeneratedBytecodePack.path(temporaryDirectory, context);
        GeneratedBytecodePack.write(target, pack);
        assertEquals(context, GeneratedBytecodePack.read(target).contextKeySha256());
        assertTrue(target.startsWith(temporaryDirectory.toAbsolutePath().normalize()));

        byte[] corrupt = encoded.clone();
        corrupt[corrupt.length / 2] ^= 1;
        IOException checksum = assertThrows(
                IOException.class, () -> GeneratedBytecodePack.fromBytes(corrupt));
        assertTrue(checksum.getMessage().contains("checksum"), checksum.getMessage());
    }

    @Test
    void partialPackExpandsWithoutRewritingAnUnchangedPack() throws Exception {
        String context = "f".repeat(64);
        byte[] alpha = classBytes(Alpha.class);
        byte[] beta = classBytes(Beta.class);
        GeneratedBytecodePack.Builder initial = new GeneratedBytecodePack.Builder(context);
        assertTrue(initial.record(Alpha.class.getName(), Map.of(Alpha.class.getName(), alpha)));
        GeneratedBytecodePack partial = initial.build();
        assertNotNull(partial);

        GeneratedBytecodePack.Builder unchanged = new GeneratedBytecodePack.Builder(partial);
        assertTrue(unchanged.record(Alpha.class.getName(), Map.of(Alpha.class.getName(), alpha)));
        assertNull(unchanged.build());

        GeneratedBytecodePack.Builder expanded = new GeneratedBytecodePack.Builder(partial);
        assertTrue(expanded.record(Beta.class.getName(), Map.of(Beta.class.getName(), beta)));
        GeneratedBytecodePack complete = expanded.build();
        assertNotNull(complete);
        assertEquals(2, complete.requestCount());
        assertArrayEquals(alpha, complete.classesFor(Alpha.class.getName()).get(Alpha.class.getName()));
        assertArrayEquals(beta, complete.classesFor(Beta.class.getName()).get(Beta.class.getName()));
    }

    @Test
    void contextKeyIsCanonicalAndEveryRequiredComponentInvalidatesIt() {
        GeneratedBytecodeContext baseline = context();
        GeneratedBytecodeContext uppercase = new GeneratedBytecodeContext(
                "0".repeat(64).toUpperCase(),
                "1".repeat(64).toUpperCase(),
                "2".repeat(64).toUpperCase(),
                "3".repeat(64).toUpperCase(),
                "4".repeat(64).toUpperCase(),
                "5".repeat(64).toUpperCase(),
                "6".repeat(64).toUpperCase());
        assertEquals(baseline.keySha256(), uppercase.keySha256());
        assertEquals(baseline.keySha256(), baseline.components().get("keySha256"));

        for (int changed = 0; changed < 7; changed++) {
            String[] values = {
                "0".repeat(64), "1".repeat(64), "2".repeat(64), "3".repeat(64),
                "4".repeat(64), "5".repeat(64), "6".repeat(64)
            };
            values[changed] = "f".repeat(64);
            GeneratedBytecodeContext modified = new GeneratedBytecodeContext(
                    values[0], values[1], values[2], values[3], values[4], values[5], values[6]);
            assertNotEquals(baseline.keySha256(), modified.keySha256(), "component " + changed);
        }
    }

    private static GeneratedBytecodeContext context() {
        return new GeneratedBytecodeContext(
                "0".repeat(64),
                "1".repeat(64),
                "2".repeat(64),
                "3".repeat(64),
                "4".repeat(64),
                "5".repeat(64),
                "6".repeat(64));
    }

    private static GeneratedBytecodeBundle bundle(String context) throws IOException {
        LinkedHashMap<String, byte[]> classes = new LinkedHashMap<>();
        classes.put(Alpha.class.getName(), classBytes(Alpha.class));
        classes.put(Beta.class.getName(), classBytes(Beta.class));
        return new GeneratedBytecodeBundle(context, Alpha.class.getName(), classes);
    }

    private static byte[] classBytes(Class<?> type) throws IOException {
        String resource = "/" + type.getName().replace('.', '/') + ".class";
        try (InputStream input = type.getResourceAsStream(resource)) {
            if (input == null) throw new IOException("Missing test class resource " + resource);
            return input.readAllBytes();
        }
    }

    static final class Alpha {
        int value() {
            return 1;
        }
    }

    static final class Beta {
        int value() {
            return 2;
        }
    }
}
