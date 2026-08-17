package dev.starsector.preflight.cli;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.starsector.preflight.core.GpuTextureFootprint;
import dev.starsector.preflight.core.ModCostBreakdown;
import dev.starsector.preflight.core.ResourceIndex;
import dev.starsector.preflight.core.ResourceProviderComparison;
import java.awt.image.BufferedImage;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import javax.imageio.ImageIO;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class ProfileModCostAnalyzerTest {

    @TempDir
    Path temporaryDirectory;

    @Test
    void analyzesProfileModCostsWithReconciledTotalsAndExactGpuMemory() throws Exception {
        Path mods = temporaryDirectory.resolve("mods");
        Path alpha = mods.resolve("AlphaMod");
        Path beta = mods.resolve("BetaMod");
        Files.createDirectories(alpha.resolve("graphics"));
        Files.createDirectories(alpha.resolve("data"));
        Files.createDirectories(beta.resolve("graphics"));
        Files.createDirectories(beta.resolve("sounds"));

        Files.writeString(alpha.resolve("mod_info.json"), """
                {
                    "id": "alpha",
                    "name": "Alpha Expansion",
                    "version": "2.1.0",
                    "totalRAM": "4096"
                }
                """);
        Files.writeString(beta.resolve("mod_info.json"), """
                {
                    "id": "beta",
                    "name": "Beta Overhaul",
                    "version": "1.0.4",
                    "totalRAM": "6GB"
                }
                """);

        // Create a 100x100 texture in Alpha (padded to 128x128 = 65,536 bytes GPU resident)
        BufferedImage alphaImg = new BufferedImage(100, 100, BufferedImage.TYPE_INT_ARGB);
        ImageIO.write(alphaImg, "png", alpha.resolve("graphics/ship_alpha.png").toFile());

        // Create a 200x200 texture in Beta (padded to 256x256 = 262,144 bytes GPU resident)
        BufferedImage betaImg = new BufferedImage(200, 200, BufferedImage.TYPE_INT_ARGB);
        ImageIO.write(betaImg, "png", beta.resolve("graphics/ship_beta.png").toFile());

        Files.writeString(alpha.resolve("data/weapons.csv"), "id,name,tier\nlaser,Mining Laser,1\n");
        Files.writeString(beta.resolve("sounds/explosion.ogg"), "OGG_SOUND_DATA_BYTES_PLACEHOLDER");

        Path enabled = mods.resolve("enabled_mods.json");
        Files.writeString(enabled, "{\"enabledMods\":[\"alpha\",\"beta\"]}");

        ProfileCensus.Result census = ProfileCensus.scan(temporaryDirectory);
        ResourceIndex index = ResourceIndexBuilder.build(temporaryDirectory).index();
        ResourceProviderComparison.Result comparison = ResourceProviderComparison.analyze(
                index, ResourceProviderContentIdentity.direct(index));


        ModCostBreakdown.Report report = ProfileModCostAnalyzer.analyze(
                temporaryDirectory, census, index, comparison);

        assertEquals(2, report.mods().size());
        assertTrue(report.totalInstalledBytes() > 0);
        assertTrue(report.totalFiles() >= 6); // 2 mod_info, 2 images, 1 csv, 1 sound

        ModCostBreakdown.ModFootprint modAlpha = report.mods().stream()
                .filter(m -> m.modId().equals("alpha"))
                .findFirst()
                .orElseThrow();
        assertEquals("Alpha Expansion", modAlpha.displayName());
        assertEquals("2.1.0", modAlpha.version());
        assertEquals(4096L, modAlpha.declaredMemoryMb());
        assertEquals(1, modAlpha.classCounts().imageFiles());
        assertEquals(2, modAlpha.classCounts().dataFiles()); // weapons.csv + mod_info.json
        assertEquals(GpuTextureFootprint.residentBytes(100, 100), modAlpha.uncompressedTextureGpuBytes());

        ModCostBreakdown.ModFootprint modBeta = report.mods().stream()
                .filter(m -> m.modId().equals("beta"))
                .findFirst()
                .orElseThrow();
        assertEquals("Beta Overhaul", modBeta.displayName());
        assertEquals("1.0.4", modBeta.version());
        assertEquals(6144L, modBeta.declaredMemoryMb()); // 6GB parsed to 6144 MiB
        assertEquals(1, modBeta.classCounts().imageFiles());
        assertEquals(1, modBeta.classCounts().soundFiles());
        assertEquals(1, modBeta.classCounts().dataFiles()); // mod_info.json
        assertEquals(GpuTextureFootprint.residentBytes(200, 200), modBeta.uncompressedTextureGpuBytes());


        // Reconciled totals
        assertEquals(
                modAlpha.installedBytes() + modBeta.installedBytes(),
                report.totalInstalledBytes());
        assertEquals(
                modAlpha.totalFiles() + modBeta.totalFiles(),
                report.totalFiles());
        assertEquals(
                modAlpha.uncompressedTextureGpuBytes() + modBeta.uncompressedTextureGpuBytes(),
                report.totalUncompressedTextureGpuBytes());

        // Verify public map has no absolute paths
        Map<String, Object> publicMap = report.toPublicMap();
        assertNotNull(publicMap.get("mods"));
        String serialized = dev.starsector.preflight.core.Json.object(publicMap);
        assertFalse(serialized.contains("temporaryDirectory"));
        assertFalse(serialized.contains(temporaryDirectory.toString()));
    }
}
