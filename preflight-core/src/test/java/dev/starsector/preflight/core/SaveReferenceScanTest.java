package dev.starsector.preflight.core;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;

class SaveReferenceScanTest {
    @Test
    void findsOnlyTheIdsThatAreActuallyPointedAt() {
        String document = """
                <CampaignEngine z="1">
                  <map z="16"><e><MStat z="17" b="0.0"></MStat></e></map>
                  <CIStack z="969"><c ref="17"></c></CIStack>
                  <other z="970"><c ref="17"></c><c ref="969"></c></other>
                </CampaignEngine>
                """;

        SaveReferenceScan.Result result = SaveReferenceScan.scan(bytes(document));

        assertTrue(result.usable());
        assertEquals(5, result.definedIds(), "z=1,16,17,969,970");
        assertEquals(3, result.referenceUses(), "ref= appears three times");
        assertEquals(2, result.referencedIds(), "but only two distinct targets");
        assertEquals(969, result.highestReferencedId());

        assertTrue(result.mustRegister(17));
        assertTrue(result.mustRegister(969));
        assertFalse(result.mustRegister(1));
        assertFalse(result.mustRegister(16));
        assertFalse(result.mustRegister(970));
    }

    @Test
    void redundancyIsTheFractionOfDefinitionsNobodyAsksFor() {
        SaveReferenceScan.Result result = SaveReferenceScan.scan(bytes(
                "<a z=\"1\"/><b z=\"2\"/><c z=\"3\"/><d z=\"4\"/><e ref=\"1\"/>"));

        assertEquals(4, result.definedIds());
        assertEquals(1, result.referencedIds());
        assertEquals(0.75, result.redundancy(), 1e-9);
    }

    @Test
    void anAttributeSplitAcrossEveryReadBoundaryIsStillFound() throws IOException {
        // The whole point of the byte-level state machine: a one-byte-at-a-time stream is the
        // worst case for any window- or carry-buffer-based matcher.
        byte[] content = bytes("<a z=\"12345\"><b ref=\"12345\"></b></a>");

        SaveReferenceScan.Result result = SaveReferenceScan.scan(oneByteAtATime(content));

        assertTrue(result.usable());
        assertEquals(1, result.definedIds());
        assertEquals(1, result.referenceUses());
        assertTrue(result.mustRegister(12345));
    }

    @Test
    void aValueThatIsNotAPlainIntegerMakesTheWholeScanUnusable() {
        SaveReferenceScan.Result result = SaveReferenceScan.scan(bytes(
                "<a z=\"1\"/><b ref=\"cafebabe\"/>"));

        assertFalse(result.usable(), "an id this class cannot parse must not narrow the set");
        assertTrue(result.mustRegister(1));
        assertTrue(result.mustRegister(999_999), "unusable means register everything");
        assertEquals(0.0, result.redundancy(), 1e-9, "and claim no redundancy");
    }

    @Test
    void anImplausiblyLargeIdMakesTheScanUnusableRatherThanAllocatingForIt() {
        SaveReferenceScan.Result result = SaveReferenceScan.scan(bytes(
                "<b ref=\"" + (SaveReferenceScan.MAX_SUPPORTED_ID + 1L) + "\"/>"));

        assertFalse(result.usable());
        assertTrue(result.mustRegister(0));
    }

    @Test
    void anEmptyIdIsNotSilentlyTreatedAsZero() {
        SaveReferenceScan.Result result = SaveReferenceScan.scan(bytes("<b ref=\"\"/>"));

        assertFalse(result.usable());
    }

    @Test
    void adjacentAttributesDoNotSwallowEachOthersStartPosition() {
        // " r ref=" exercises the restart path: the first 'r' begins a partial match that fails,
        // and the space that follows has to be usable as a fresh start.
        SaveReferenceScan.Result result = SaveReferenceScan.scan(bytes("<a r ref=\"7\"/>"));

        assertTrue(result.usable());
        assertEquals(1, result.referenceUses());
        assertTrue(result.mustRegister(7));
    }

    @Test
    void aDocumentWithNoReferencesRegistersNothing() {
        SaveReferenceScan.Result result = SaveReferenceScan.scan(bytes("<a z=\"1\"/><b z=\"2\"/>"));

        assertTrue(result.usable());
        assertEquals(2, result.definedIds());
        assertEquals(0, result.referencedIds());
        assertFalse(result.mustRegister(1));
        assertEquals(1.0, result.redundancy(), 1e-9);
    }

    @Test
    void theRedundancyHoldsOnARealCampaignSave() throws IOException {
        Path saves = Path.of("/Applications/Starsector.app/saves");
        assumeTrue(Files.isDirectory(saves), "no installed Starsector saves on this machine");
        Path campaign;
        try (var entries = Files.list(saves)) {
            campaign = entries
                    .map(directory -> directory.resolve("campaign.xml"))
                    .filter(Files::isRegularFile)
                    .findFirst()
                    .orElse(null);
        }
        assumeTrue(campaign != null, "no campaign.xml to scan");

        SaveReferenceScan.Result result = SaveReferenceScan.scan(campaign);

        assertTrue(result.usable(), "a real Starsector save must be understood: " + campaign);
        assertTrue(result.definedIds() > 1_000,
                "a real campaign defines many ids, found " + result.definedIds());
        assertTrue(result.referencedIds() < result.definedIds(),
                "some ids must be dead weight");
        assertTrue(result.redundancy() > 0.5,
                "the measured profile was 90.8% dead; found "
                        + String.format("%.1f%%", result.redundancy() * 100));
    }

    private static byte[] bytes(String value) {
        return value.getBytes(StandardCharsets.UTF_8);
    }

    private static InputStream oneByteAtATime(byte[] content) {
        return new ByteArrayInputStream(content) {
            @Override
            public synchronized int read(byte[] target, int offset, int length) {
                return super.read(target, offset, 1);
            }
        };
    }
}
