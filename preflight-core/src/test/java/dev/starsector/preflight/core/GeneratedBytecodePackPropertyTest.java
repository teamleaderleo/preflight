package dev.starsector.preflight.core;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.io.IOException;
import java.io.InputStream;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.Random;
import org.junit.jupiter.api.Test;

class GeneratedBytecodePackPropertyTest {
    private static final long SEED = 0x5350_4a50_5041_434bL;

    @Test
    void seededBuilderOrdersProduceCanonicalBytesAndExactRoundTrips() throws Exception {
        byte[] alpha = classBytes(Alpha.class);
        byte[] beta = classBytes(Beta.class);
        Random random = new Random(SEED);

        for (int iteration = 0; iteration < 500; iteration++) {
            String context = randomSha256(random);
            boolean includeBetaRequest = random.nextBoolean();
            boolean alphaSeesBeta = random.nextBoolean();
            boolean betaSeesAlpha = random.nextBoolean();

            GeneratedBytecodePack left = buildPack(
                    context, alpha, beta, includeBetaRequest, alphaSeesBeta, betaSeesAlpha, false);
            GeneratedBytecodePack right = buildPack(
                    context.toUpperCase(Locale.ROOT),
                    alpha, beta, includeBetaRequest, alphaSeesBeta, betaSeesAlpha, true);

            byte[] leftBytes = GeneratedBytecodePack.toBytes(left);
            byte[] rightBytes = GeneratedBytecodePack.toBytes(right);
            assertArrayEquals(leftBytes, rightBytes, "canonical bytes iteration " + iteration);

            GeneratedBytecodePack decoded = GeneratedBytecodePack.fromBytes(leftBytes);
            assertEquals(context, decoded.contextKeySha256());
            assertArrayEquals(alpha, decoded.classesFor(Alpha.class.getName()).get(Alpha.class.getName()));
            if (alphaSeesBeta) {
                assertArrayEquals(beta, decoded.classesFor(Alpha.class.getName()).get(Beta.class.getName()));
            }
            if (includeBetaRequest) {
                assertArrayEquals(beta, decoded.classesFor(Beta.class.getName()).get(Beta.class.getName()));
                if (betaSeesAlpha) {
                    assertArrayEquals(alpha, decoded.classesFor(Beta.class.getName()).get(Alpha.class.getName()));
                }
            }
        }
    }

    @Test
    void everySeededSingleBitCorruptionRejectsDeterministically() throws Exception {
        byte[] encoded = GeneratedBytecodePack.toBytes(buildPack(
                "a".repeat(64),
                classBytes(Alpha.class),
                classBytes(Beta.class),
                true,
                true,
                true,
                false));
        Random random = new Random(SEED ^ 0x434f_5252_5550_54L);

        for (int iteration = 0; iteration < 1_000; iteration++) {
            byte[] corrupt = encoded.clone();
            int index = random.nextInt(corrupt.length);
            corrupt[index] ^= (byte) (1 << random.nextInt(8));

            IOException first = assertThrows(
                    IOException.class,
                    () -> GeneratedBytecodePack.fromBytes(corrupt),
                    "corruption iteration " + iteration + " at byte " + index);
            IOException second = assertThrows(IOException.class, () -> GeneratedBytecodePack.fromBytes(corrupt));
            assertEquals(first.getClass(), second.getClass());
            assertEquals(first.getMessage(), second.getMessage());
        }
    }

    @Test
    void seededTruncationsNeverDecodeAsUsablePacks() throws Exception {
        byte[] encoded = GeneratedBytecodePack.toBytes(buildPack(
                "b".repeat(64),
                classBytes(Alpha.class),
                classBytes(Beta.class),
                true,
                true,
                false,
                false));
        Random random = new Random(SEED ^ 0x5452_554e_4341_5445L);

        for (int iteration = 0; iteration < 500; iteration++) {
            int length = random.nextInt(encoded.length);
            byte[] truncated = Arrays.copyOf(encoded, length);
            assertThrows(IOException.class, () -> GeneratedBytecodePack.fromBytes(truncated));
        }
    }

    private static GeneratedBytecodePack buildPack(
            String context,
            byte[] alpha,
            byte[] beta,
            boolean includeBetaRequest,
            boolean alphaSeesBeta,
            boolean betaSeesAlpha,
            boolean reverseRecordingOrder) {
        GeneratedBytecodePack.Builder builder = new GeneratedBytecodePack.Builder(context);
        Map<String, byte[]> alphaMap = classMap(alpha, beta, alphaSeesBeta, true);
        Map<String, byte[]> betaMap = classMap(alpha, beta, betaSeesAlpha, false);

        if (reverseRecordingOrder && includeBetaRequest) {
            assertRecord(builder, Beta.class.getName(), betaMap);
            assertRecord(builder, Alpha.class.getName(), alphaMap);
        } else {
            assertRecord(builder, Alpha.class.getName(), alphaMap);
            if (includeBetaRequest) {
                assertRecord(builder, Beta.class.getName(), betaMap);
            }
        }
        GeneratedBytecodePack pack = builder.build();
        assertNotNull(pack);
        return pack;
    }

    private static Map<String, byte[]> classMap(
            byte[] alpha, byte[] beta, boolean includeOther, boolean requestedAlpha) {
        LinkedHashMap<String, byte[]> classes = new LinkedHashMap<>();
        if (requestedAlpha) {
            if (includeOther) classes.put(Beta.class.getName(), beta);
            classes.put(Alpha.class.getName(), alpha);
        } else {
            classes.put(Beta.class.getName(), beta);
            if (includeOther) classes.put(Alpha.class.getName(), alpha);
        }
        return classes;
    }

    private static void assertRecord(
            GeneratedBytecodePack.Builder builder, String requested, Map<String, byte[]> classes) {
        if (!builder.record(requested, classes)) {
            throw new AssertionError("builder rejected valid generated-bytecode map for " + requested);
        }
    }

    private static String randomSha256(Random random) {
        byte[] bytes = new byte[32];
        random.nextBytes(bytes);
        return java.util.HexFormat.of().formatHex(bytes);
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
