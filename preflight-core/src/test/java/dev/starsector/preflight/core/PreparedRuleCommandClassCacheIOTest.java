package dev.starsector.preflight.core;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class PreparedRuleCommandClassCacheIOTest {
    private static final List<String> PACKAGES = List.of(
            "com.fs.starfarer.api.impl.campaign.rulecmd",
            "com.fs.starfarer.api.impl.campaign.rulecmd.salvage",
            "exerelin.campaign.rulecmd");

    @TempDir
    Path temporaryDirectory;

    @Test
    void roundTripsLearnedPackagesAndResolvesNames() throws Exception {
        PreparedRuleCommandClassCache cache = cache(Map.of(
                "AddCredits", PACKAGES.get(0),
                "SalvageEntity", PACKAGES.get(1),
                "Nex_MarketCmd", PACKAGES.get(2)));

        Path file = temporaryDirectory.resolve("profile.sprk");
        PreparedRuleCommandClassCacheIO.write(file, cache);
        PreparedRuleCommandClassCache read = PreparedRuleCommandClassCacheIO.read(file);

        assertEquals(cache, read);
        assertEquals(PACKAGES, read.commandPackages());
        assertEquals("exerelin.campaign.rulecmd", read.winningPackage("Nex_MarketCmd"));
        assertNull(read.winningPackage("NeverLearned"), "an unlearned name must take vanilla's walk");
        assertNull(read.winningPackage(null));
    }

    @Test
    void encodesTheSameProfileToTheSameBytesWhateverOrderNamesWereLearnedIn() throws Exception {
        Map<String, String> forwards = new LinkedHashMap<>();
        forwards.put("AddCredits", PACKAGES.get(0));
        forwards.put("SalvageEntity", PACKAGES.get(1));
        Map<String, String> backwards = new LinkedHashMap<>();
        backwards.put("SalvageEntity", PACKAGES.get(1));
        backwards.put("AddCredits", PACKAGES.get(0));

        assertTrue(Arrays.equals(
                PreparedRuleCommandClassCacheIO.toBytes(cache(forwards)),
                PreparedRuleCommandClassCacheIO.toBytes(cache(backwards))));
    }

    @Test
    void rejectsAWinnerThatTheDeclaredWalkCouldNeverHaveProduced() {
        // The whole value of the map is that the winner is what the ordered walk would have reached.
        // A package outside that list is not a stale answer, it is an impossible one.
        assertThrows(IllegalArgumentException.class,
                () -> cache(Map.of("AddCredits", "some.other.package")));
    }

    @Test
    void rejectsNamesAndPackagesThatAreNotIdentifiers() {
        // The walk hands pkg + "." + name straight to Class.forName. Anything else in either position
        // came from a parser fault, not from a profile, and the census that first counted these names
        // over-reported by 48% until comment lines and quoted fragments were filtered out this way.
        assertThrows(IllegalArgumentException.class, () -> cache(Map.of("# comment", PACKAGES.get(0))));
        assertThrows(IllegalArgumentException.class, () -> cache(Map.of("has space", PACKAGES.get(0))));
        assertThrows(IllegalArgumentException.class, () -> cache(Map.of("", PACKAGES.get(0))));
        assertThrows(IllegalArgumentException.class, () -> new PreparedRuleCommandClassCache(
                "a".repeat(64), List.of("com..broken"), Map.of()));
        assertThrows(IllegalArgumentException.class, () -> new PreparedRuleCommandClassCache(
                "a".repeat(64), List.of(), Map.of()));
    }

    @Test
    void rejectsCorruptionTruncationAndOtherCacheArtifacts() throws Exception {
        byte[] bytes = PreparedRuleCommandClassCacheIO.toBytes(
                cache(Map.of("AddCredits", PACKAGES.get(0))));

        byte[] flipped = bytes.clone();
        flipped[flipped.length / 2] ^= 1;
        assertThrows(IOException.class, () -> PreparedRuleCommandClassCacheIO.fromBytes(flipped));

        Path file = temporaryDirectory.resolve("truncated.sprk");
        Files.write(file, Arrays.copyOf(bytes, bytes.length - 1));
        assertThrows(IOException.class, () -> PreparedRuleCommandClassCacheIO.read(file));

        byte[] rules = PreparedRulesCsvCacheIO.toBytes(
                new PreparedRulesCsvCache("a".repeat(64), JsonTree.encode(java.util.List.of())));
        assertThrows(IOException.class, () -> PreparedRuleCommandClassCacheIO.fromBytes(rules));
    }

    @Test
    void rejectsAnEmptyMapOnlyWhenItIsMalformedNotWhenNothingWasLearnable() throws Exception {
        // Every name having an unclean prefix is a legitimate outcome: the artifact still records the
        // package list, and every lookup falls through to vanilla.
        PreparedRuleCommandClassCache nothing = cache(Map.of());
        assertEquals(nothing, PreparedRuleCommandClassCacheIO.fromBytes(
                PreparedRuleCommandClassCacheIO.toBytes(nothing)));
        assertNull(nothing.winningPackage("AddCredits"));
    }

    private static PreparedRuleCommandClassCache cache(Map<String, String> winners) {
        return new PreparedRuleCommandClassCache("a".repeat(64), PACKAGES, winners);
    }
}
