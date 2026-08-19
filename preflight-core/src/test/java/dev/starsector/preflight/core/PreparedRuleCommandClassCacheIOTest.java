package dev.starsector.preflight.core;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class PreparedRuleCommandClassCacheIOTest {
    private static final String PROFILE = "a".repeat(64);
    private static final int PAYLOAD_LENGTH_OFFSET = 8;
    private static final int PAYLOAD_OFFSET = 12;
    private static final int PROFILE_BYTES = 32;
    private static final int CHECKSUM_BYTES = 32;
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
    void roundTripsValidUnicodeIdentifiers() throws Exception {
        List<String> packages = List.of("规则.命令");
        PreparedRuleCommandClassCache cache = new PreparedRuleCommandClassCache(
                PROFILE, packages, Map.of("执行", packages.get(0)));

        assertEquals(cache, PreparedRuleCommandClassCacheIO.fromBytes(
                PreparedRuleCommandClassCacheIO.toBytes(cache)));
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
                PROFILE, List.of("com..broken"), Map.of()));
        assertThrows(IllegalArgumentException.class, () -> new PreparedRuleCommandClassCache(
                PROFILE, List.of(), Map.of()));
    }

    @Test
    void checksumValidMalformedUtf8IsRejectedAtTheStringDecoder() throws Exception {
        PreparedRuleCommandClassCache packageCache = new PreparedRuleCommandClassCache(
                PROFILE, List.of("规"), Map.of());
        byte[] malformedPackage = PreparedRuleCommandClassCacheIO.toBytes(packageCache);
        replaceThreeByteIdentifierWithMalformedUtf8(malformedPackage, firstPackageBytesOffset(malformedPackage));

        IOException packageError = assertThrows(
                IOException.class,
                () -> PreparedRuleCommandClassCacheIO.fromBytes(malformedPackage));
        assertEquals("Prepared rule command string is not valid UTF-8", packageError.getMessage());

        PreparedRuleCommandClassCache nameCache = new PreparedRuleCommandClassCache(
                PROFILE, List.of("规"), Map.of("命", "规"));
        byte[] malformedName = PreparedRuleCommandClassCacheIO.toBytes(nameCache);
        replaceThreeByteIdentifierWithMalformedUtf8(malformedName, firstWinnerNameBytesOffset(malformedName));

        IOException nameError = assertThrows(
                IOException.class,
                () -> PreparedRuleCommandClassCacheIO.fromBytes(malformedName));
        assertEquals("Prepared rule command string is not valid UTF-8", nameError.getMessage());
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
                new PreparedRulesCsvCache(PROFILE, JsonTree.encode(java.util.List.of())));
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

    private static int firstPackageBytesOffset(byte[] bytes) {
        int packageCountOffset = PAYLOAD_OFFSET + PROFILE_BYTES;
        assertEquals(1, intAt(bytes, packageCountOffset));
        int packageLengthOffset = packageCountOffset + Integer.BYTES;
        assertEquals(3, intAt(bytes, packageLengthOffset));
        return packageLengthOffset + Integer.BYTES;
    }

    private static int firstWinnerNameBytesOffset(byte[] bytes) {
        int packageCountOffset = PAYLOAD_OFFSET + PROFILE_BYTES;
        assertEquals(1, intAt(bytes, packageCountOffset));
        int packageLengthOffset = packageCountOffset + Integer.BYTES;
        int packageLength = intAt(bytes, packageLengthOffset);
        int entryCountOffset = packageLengthOffset + Integer.BYTES + packageLength;
        assertEquals(1, intAt(bytes, entryCountOffset));
        int nameLengthOffset = entryCountOffset + Integer.BYTES;
        assertEquals(3, intAt(bytes, nameLengthOffset));
        return nameLengthOffset + Integer.BYTES;
    }

    private static void replaceThreeByteIdentifierWithMalformedUtf8(byte[] bytes, int offset) {
        // ED A0 80 encodes a UTF-16 surrogate code point and is malformed UTF-8. The old decoder
        // replacement-decoded it before the model rejected U+FFFD as an invalid Java identifier.
        // Re-signing the payload proves rejection comes from the authenticated inner string decoder.
        bytes[offset] = (byte) 0xed;
        bytes[offset + 1] = (byte) 0xa0;
        bytes[offset + 2] = (byte) 0x80;
        resignPayload(bytes);
    }

    private static void resignPayload(byte[] bytes) {
        int payloadLength = intAt(bytes, PAYLOAD_LENGTH_OFFSET);
        byte[] payload = Arrays.copyOfRange(bytes, PAYLOAD_OFFSET, PAYLOAD_OFFSET + payloadLength);
        byte[] checksum = Hashes.sha256Bytes(payload);
        System.arraycopy(
                checksum,
                0,
                bytes,
                PAYLOAD_OFFSET + payloadLength,
                CHECKSUM_BYTES);
    }

    private static int intAt(byte[] bytes, int offset) {
        return ByteBuffer.wrap(bytes).order(ByteOrder.BIG_ENDIAN).getInt(offset);
    }

    private static PreparedRuleCommandClassCache cache(Map<String, String> winners) {
        return new PreparedRuleCommandClassCache(PROFILE, PACKAGES, winners);
    }
}
