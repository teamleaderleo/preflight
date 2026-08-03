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
                "a".repeat(64), "[{\"id\":\"rule-a\"},{\"id\":\"rule-b\"}]");

        byte[] first = PreparedRulesCsvCacheIO.toBytes(cache);
        byte[] second = PreparedRulesCsvCacheIO.toBytes(new PreparedRulesCsvCache(
                "A".repeat(64), cache.mergedJson()));
        assertTrue(java.util.Arrays.equals(first, second));

        Path file = temporaryDirectory.resolve("profile.sprc");
        PreparedRulesCsvCacheIO.write(file, cache);
        assertEquals(cache, PreparedRulesCsvCacheIO.read(file));
    }

    @Test
    void rejectsCorruptionWrongJsonAndWrongProfile() throws Exception {
        PreparedRulesCsvCache cache = new PreparedRulesCsvCache("b".repeat(64), "[]");
        byte[] bytes = PreparedRulesCsvCacheIO.toBytes(cache);
        bytes[bytes.length / 2] ^= 1;
        assertThrows(IOException.class, () -> PreparedRulesCsvCacheIO.fromBytes(bytes));
        assertThrows(IllegalArgumentException.class, () -> new PreparedRulesCsvCache("bad", "[]"));
        assertThrows(IllegalArgumentException.class,
                () -> new PreparedRulesCsvCache("b".repeat(64), ""));
        assertThrows(IllegalArgumentException.class,
                () -> new PreparedRulesCsvCache("b".repeat(64), "{}"));
    }

    @Test
    void rejectsTruncationAndOtherCacheArtifacts() throws Exception {
        byte[] bytes = PreparedRulesCsvCacheIO.toBytes(
                new PreparedRulesCsvCache("c".repeat(64), "[{\"id\":\"rule\"}]"));
        Path file = temporaryDirectory.resolve("truncated.sprc");
        Files.write(file, java.util.Arrays.copyOf(bytes, bytes.length - 1));
        assertThrows(IOException.class, () -> PreparedRulesCsvCacheIO.read(file));

        byte[] hull = PreparedHullJsonCacheIO.toBytes(new PreparedHullJsonCache(
                "c".repeat(64), Map.of("data/hulls/a.ship", JsonTree.encode(Map.of()))));
        assertThrows(IOException.class, () -> PreparedRulesCsvCacheIO.fromBytes(hull));
    }
}
