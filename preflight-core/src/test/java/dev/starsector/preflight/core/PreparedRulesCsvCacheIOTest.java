package dev.starsector.preflight.core;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class PreparedRulesCsvCacheIOTest {
    @TempDir
    Path temporaryDirectory;

    @Test
    void roundTripsMergedRulesDeterministicallyAndAtomically() throws Exception {
        PreparedRulesCsvCache cache = new PreparedRulesCsvCache(
                "a".repeat(64), rulesTree("rule-a", "rule-b"));

        byte[] first = PreparedRulesCsvCacheIO.toBytes(cache);
        byte[] second = PreparedRulesCsvCacheIO.toBytes(new PreparedRulesCsvCache(
                "A".repeat(64), cache.mergedTree()));
        assertTrue(java.util.Arrays.equals(first, second));

        Path file = temporaryDirectory.resolve("profile.sprc");
        PreparedRulesCsvCacheIO.write(file, cache);
        PreparedRulesCsvCache decoded = PreparedRulesCsvCacheIO.read(file);
        assertEquals(cache, decoded);
    }

    @Test
    void rejectsCorruptionWrongJsonAndWrongProfile() throws Exception {
        PreparedRulesCsvCache cache = new PreparedRulesCsvCache("b".repeat(64), rulesTree());
        byte[] bytes = PreparedRulesCsvCacheIO.toBytes(cache);
        bytes[bytes.length / 2] ^= 1;
        assertThrows(IOException.class, () -> PreparedRulesCsvCacheIO.fromBytes(bytes));
        assertThrows(IllegalArgumentException.class,
                () -> new PreparedRulesCsvCache("bad", rulesTree()));
        assertThrows(IllegalArgumentException.class,
                () -> new PreparedRulesCsvCache("b".repeat(64), new byte[0]));
    }

    @Test
    void rejectsTruncationAndOtherCacheArtifacts() throws Exception {
        byte[] bytes = PreparedRulesCsvCacheIO.toBytes(
                new PreparedRulesCsvCache("c".repeat(64), rulesTree("rule")));
        Path file = temporaryDirectory.resolve("truncated.sprc");
        Files.write(file, java.util.Arrays.copyOf(bytes, bytes.length - 1));
        assertThrows(IOException.class, () -> PreparedRulesCsvCacheIO.read(file));

        byte[] hull = PreparedHullJsonCacheIO.toBytes(new PreparedHullJsonCache(
                "c".repeat(64), Map.of("data/hulls/a.ship", JsonTree.encode(Map.of()))));
        assertThrows(IOException.class, () -> PreparedRulesCsvCacheIO.fromBytes(hull));
    }

    @Test
    void readStopsAtConfiguredBoundBeforeParsing() throws Exception {
        Path file = temporaryDirectory.resolve("bounded.sprc");
        Files.write(file, PreparedRulesCsvCacheIO.toBytes(
                new PreparedRulesCsvCache("d".repeat(64), rulesTree("rule-a", "rule-b"))));

        IOException oversized = assertThrows(IOException.class, () -> PreparedRulesCsvCacheIO.read(file, 64));
        assertTrue(oversized.getMessage().contains("64 byte safety limit"));
        assertThrows(IllegalArgumentException.class, () -> PreparedRulesCsvCacheIO.read(file, 32));
    }

    private static byte[] rulesTree(String... ids) {
        return JsonTree.encode(java.util.Arrays.stream(ids)
                .map(id -> Map.<String, Object>of("id", id)).toList());
    }
}
