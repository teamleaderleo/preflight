package dev.starsector.preflight.core.resources;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.starsector.preflight.core.GpuTextureFootprint;
import java.io.ByteArrayOutputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Base64;
import java.util.List;
import java.util.zip.CRC32;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class ResourceCostInspectorTest {

    @TempDir
    Path tempDir;

    @Test
    void emptyProfileReportsZeroCosts() throws IOException {
        Path install = tempDir.resolve("empty-game");
        Files.createDirectories(install.resolve("mods"));
        Files.writeString(install.resolve("mods/enabled_mods.json"), "{\"enabledMods\":[]}");

        ResourceCostReport report = ResourceCostInspector.inspect(install);

        assertEquals(ResourceCostReport.FORMAT_VERSION, report.format());
        assertNotNull(report.profileFingerprint());
        assertEquals(0, report.summary().enabledModCount());
        assertEquals(0, report.summary().totalDiskBytes());
        assertEquals(0, report.summary().totalEstimatedMemoryBytes());
        assertEquals(0, report.summary().textureVram().textureCount());
        assertEquals(0, report.summary().audioPcm().soundCount());
        assertEquals(0, report.summary().bytecode().jarCount());
        assertTrue(report.mods().isEmpty());
        assertTrue(report.diagnostics().isEmpty());
    }

    @Test
    void calculatesExactTextureVramAndPadding() throws IOException {
        Path install = tempDir.resolve("texture-game");
        Path modDir = install.resolve("mods/samplemod");
        Files.createDirectories(modDir.resolve("graphics/ships"));
        Files.writeString(modDir.resolve("mod_info.json"), "{\"id\":\"samplemod\",\"name\":\"Sample Mod\",\"version\":\"1.0.0\"}");
        Files.writeString(install.resolve("mods/enabled_mods.json"), "{\"enabledMods\":[\"samplemod\"]}");

        // Create a 288x384 RGBA PNG file
        byte[] pngBytes = createPng(288, 384, 6);
        Path shipPng = modDir.resolve("graphics/ships/onslaught.png");
        Files.write(shipPng, pngBytes);

        ResourceCostReport report = ResourceCostInspector.inspect(install);

        assertEquals(1, report.summary().enabledModCount());
        assertEquals(1, report.summary().textureVram().textureCount());
        assertEquals(pngBytes.length, report.summary().textureVram().diskBytes());

        // 288x384 RGBA -> upload 512x512 * 4 = 1,048,576
        long expectedDecoded = 288L * 384L * 4L;
        long expectedResident = 512L * 512L * 4L;
        long expectedPadding = expectedResident - (288L * 384L * 4L);
        long expectedMips = GpuTextureFootprint.residentBytesWithMipChain(288, 384);

        assertEquals(expectedDecoded, report.summary().textureVram().decodedBaseBytes());
        assertEquals(expectedResident, report.summary().textureVram().residentGpuBytes());
        assertEquals(expectedPadding, report.summary().textureVram().paddingWasteBytes());
        assertEquals(expectedMips, report.summary().textureVram().mipChainUpperBoundBytes());

        ModResourceCost mod = report.mods().get(0);
        assertEquals("samplemod", mod.id());
        assertEquals(1, mod.texture().count());
        assertEquals(expectedResident, mod.texture().residentBytes());
        assertEquals(expectedPadding, mod.texture().paddingWasteBytes());
        assertEquals(0, mod.texture().unmeasuredCount());

        // Verify largest allocation
        assertEquals(1, report.largestAllocations().textures().size());
        var largest = report.largestAllocations().textures().get(0);
        assertEquals("graphics/ships/onslaught.png", largest.logicalPath());
        assertEquals(288, largest.width());
        assertEquals(384, largest.height());
        assertEquals(4, largest.channels());
        assertEquals(expectedResident, largest.residentBytes());
        assertEquals(expectedPadding, largest.paddingWasteBytes());
        assertEquals("samplemod", largest.winnerModId());
    }

    @Test
    void categorizesAudioEffectsVsMusicVsUnreferenced() throws IOException {
        Path install = tempDir.resolve("audio-game");
        Path modDir = install.resolve("mods/audiomod");
        Files.createDirectories(modDir.resolve("data/config"));
        Files.createDirectories(modDir.resolve("sounds/weapons"));
        Files.createDirectories(modDir.resolve("sounds/music"));
        Files.createDirectories(modDir.resolve("sounds/unused"));

        Files.writeString(modDir.resolve("mod_info.json"), "{\"id\":\"audiomod\",\"name\":\"Audio Mod\",\"version\":\"2.0.0\"}");
        Files.writeString(install.resolve("mods/enabled_mods.json"), "{\"enabledMods\":[\"audiomod\"]}");

        byte[] oggBytes = loadAudioFixture("mono-22050.ogg");
        Path effectFile = modDir.resolve("sounds/weapons/laser.ogg");
        Path musicFile = modDir.resolve("sounds/music/ambient.ogg");
        Path unreferencedFile = modDir.resolve("sounds/unused/scrap.ogg");

        Files.write(effectFile, oggBytes);
        Files.write(musicFile, oggBytes);
        Files.write(unreferencedFile, oggBytes);

        String soundsJson = """
                {
                    "sounds": [
                        {"id": "laser_fire", "file": "sounds/weapons/laser.ogg"}
                    ],
                    "music": {
                        "ambient_track": {"file": "sounds/music/ambient.ogg"}
                    }
                }
                """;
        Files.writeString(modDir.resolve("data/config/sounds.json"), soundsJson);

        ResourceCostReport report = ResourceCostInspector.inspect(install);

        assertEquals(3, report.summary().audioPcm().soundCount());
        assertEquals(1, report.summary().audioPcm().effectCount());
        assertEquals(1, report.summary().audioPcm().musicCount());
        assertEquals(1, report.summary().audioPcm().unreferencedCount());

        // mono-22050 has 2048 frames -> 4096 bytes PCM
        long expectedEffectPcm = 4096L;
        assertEquals(expectedEffectPcm, report.summary().audioPcm().effectPcmBytes());
        assertEquals(oggBytes.length, report.summary().audioPcm().musicDiskBytes());
        assertEquals(oggBytes.length, report.summary().audioPcm().unreferencedDiskBytes());

        ModResourceCost mod = report.mods().get(0);
        assertEquals(3, mod.audio().count());
        assertEquals(expectedEffectPcm, mod.audio().effectPcmBytes());
        assertEquals(oggBytes.length, mod.audio().musicBytes());
        assertEquals(oggBytes.length, mod.audio().unreferencedBytes());
    }

    @Test
    void auditsBytecodeSizesAndClassCounts() throws IOException {
        Path install = tempDir.resolve("bytecode-game");
        Path mod1 = install.resolve("mods/mod1");
        Path mod2 = install.resolve("mods/mod2");
        Files.createDirectories(mod1.resolve("jars"));
        Files.createDirectories(mod2.resolve("jars"));

        Files.writeString(mod1.resolve("mod_info.json"), "{\"id\":\"mod1\",\"name\":\"Mod One\",\"version\":\"1.0\"}");
        Files.writeString(mod2.resolve("mod_info.json"), "{\"id\":\"mod2\",\"name\":\"Mod Two\",\"version\":\"1.0\"}");
        Files.writeString(install.resolve("mods/enabled_mods.json"), "{\"enabledMods\":[\"mod1\",\"mod2\"]}");

        createJar(mod1.resolve("jars/mod1.jar"), List.of("com.example.SharedHelper", "com.example.Mod1Main"));
        createJar(mod2.resolve("jars/mod2.jar"), List.of("com.example.SharedHelper", "com.example.Mod2Main"));

        ResourceCostReport report = ResourceCostInspector.inspect(install);

        assertEquals(2, report.summary().bytecode().jarCount());
        assertEquals(4, report.summary().bytecode().classCount());
        assertEquals(1, report.summary().bytecode().duplicateClasses()); // com.example.SharedHelper

        ModResourceCost cost1 = report.mods().stream().filter(m -> m.id().equals("mod1")).findFirst().orElseThrow();
        assertEquals(1, cost1.bytecode().jarCount());
        assertEquals(2, cost1.bytecode().classCount());
        assertEquals(1, cost1.bytecode().duplicateClassCount());

        ModResourceCost cost2 = report.mods().stream().filter(m -> m.id().equals("mod2")).findFirst().orElseThrow();
        assertEquals(1, cost2.bytecode().jarCount());
        assertEquals(2, cost2.bytecode().classCount());
        assertEquals(1, cost2.bytecode().duplicateClassCount());
    }

    @Test
    void computesOverrideWinningAndShadowedAssets() throws IOException {
        Path install = tempDir.resolve("override-game");
        Path modBase = install.resolve("mods/base_mod");
        Path modOverride = install.resolve("mods/override_mod");
        Files.createDirectories(modBase.resolve("graphics/fx"));
        Files.createDirectories(modOverride.resolve("graphics/fx"));

        Files.writeString(modBase.resolve("mod_info.json"), "{\"id\":\"base_mod\",\"name\":\"Base Mod\",\"version\":\"1.0\"}");
        Files.writeString(modOverride.resolve("mod_info.json"), "{\"id\":\"override_mod\",\"name\":\"Override Mod\",\"version\":\"1.0\"}");
        Files.writeString(install.resolve("mods/enabled_mods.json"), "{\"enabledMods\":[\"base_mod\",\"override_mod\"]}");

        // Base mod provides 100x100 texture
        byte[] basePng = createPng(100, 100, 6);
        Files.write(modBase.resolve("graphics/fx/beam.png"), basePng);

        // Override mod provides 200x200 texture overriding the same logical path
        byte[] overridePng = createPng(200, 200, 6);
        Files.write(modOverride.resolve("graphics/fx/beam.png"), overridePng);

        ResourceCostReport report = ResourceCostInspector.inspect(install);

        ModResourceCost baseCost = report.mods().stream().filter(m -> m.id().equals("base_mod")).findFirst().orElseThrow();
        ModResourceCost overrideCost = report.mods().stream().filter(m -> m.id().equals("override_mod")).findFirst().orElseThrow();

        // Base mod texture is shadowed: residentBytes = 0, shadowedByOverrides = 1
        assertEquals(0, baseCost.texture().residentBytes());
        assertEquals(1, baseCost.shadowedByOverrides().texturesOverridden());
        long baseVram = GpuTextureFootprint.residentBytes(100, 100);
        assertEquals(baseVram, baseCost.shadowedByOverrides().vramShadowedBytes());

        // Override mod texture won: residentBytes = GpuTextureFootprint(200, 200)
        long overrideVram = GpuTextureFootprint.residentBytes(200, 200);
        assertEquals(overrideVram, overrideCost.texture().residentBytes());
        assertEquals(0, overrideCost.shadowedByOverrides().texturesOverridden());

        // Total profile resident VRAM equals overrideVram (base mod VRAM not double counted)
        assertEquals(overrideVram, report.summary().textureVram().residentGpuBytes());
    }

    @Test
    void handlesCorruptOrUnreadableAssetsGracefully() throws IOException {
        Path install = tempDir.resolve("corrupt-game");
        Path modDir = install.resolve("mods/corruptmod");
        Files.createDirectories(modDir.resolve("graphics"));
        Files.writeString(modDir.resolve("mod_info.json"), "{\"id\":\"corruptmod\",\"name\":\"Corrupt Mod\",\"version\":\"1.0\"}");
        Files.writeString(install.resolve("mods/enabled_mods.json"), "{\"enabledMods\":[\"corruptmod\"]}");

        Files.writeString(modDir.resolve("graphics/bad.png"), "not a valid png file header at all");

        ResourceCostReport report = ResourceCostInspector.inspect(install);

        ModResourceCost mod = report.mods().get(0);
        assertEquals(1, mod.texture().count());
        assertEquals(1, mod.texture().unmeasuredCount());
        assertEquals(0, mod.texture().residentBytes());
    }

    @Test
    void supportsStandaloneModInspection() throws IOException {
        Path modDir = tempDir.resolve("standalone_mod");
        Files.createDirectories(modDir.resolve("graphics/icons"));
        Files.writeString(modDir.resolve("mod_info.json"), "{\"id\":\"standalone_mod\",\"name\":\"Standalone Mod\",\"version\":\"3.2.1\"}");

        byte[] png = createPng(64, 64, 6);
        Files.write(modDir.resolve("graphics/icons/icon.png"), png);

        ResourceCostReport report = ResourceCostInspector.inspect(modDir);

        assertEquals(ResourceCostReport.FORMAT_VERSION, report.format());
        assertEquals(1, report.mods().size());
        assertEquals("standalone_mod", report.mods().get(0).id());
        assertEquals("3.2.1", report.mods().get(0).version());
        assertEquals(GpuTextureFootprint.residentBytes(64, 64), report.summary().textureVram().residentGpuBytes());
    }

    @Test
    void validatesJsonSchemaRoundTrip() throws IOException {
        Path modDir = tempDir.resolve("jsontest_mod");
        Files.createDirectories(modDir.resolve("graphics"));
        Files.writeString(modDir.resolve("mod_info.json"), "{\"id\":\"jsontest\",\"name\":\"Json Test\",\"version\":\"1.0\"}");

        Files.write(modDir.resolve("graphics/sprite.png"), createPng(32, 32, 6));

        ResourceCostReport report = ResourceCostInspector.inspect(modDir);
        String json = report.toJson();

        assertTrue(json.contains("\"format\":\"starsector-preflight-resource-cost-v1\""));
        assertTrue(json.contains("\"summary\":{"));
        assertTrue(json.contains("\"textureVram\":{"));
        assertTrue(json.contains("\"mods\":["));
        assertTrue(json.contains("\"largestAllocations\":{"));
    }

    private static byte[] createPng(int width, int height, int colorType) {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        // Signature
        out.writeBytes(new byte[] {(byte) 0x89, 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A});

        // IHDR
        ByteArrayOutputStream ihdr = new ByteArrayOutputStream();
        ihdr.writeBytes(new byte[] {'I', 'H', 'D', 'R'});
        ihdr.write((width >>> 24) & 0xFF);
        ihdr.write((width >>> 16) & 0xFF);
        ihdr.write((width >>> 8) & 0xFF);
        ihdr.write(width & 0xFF);
        ihdr.write((height >>> 24) & 0xFF);
        ihdr.write((height >>> 16) & 0xFF);
        ihdr.write((height >>> 8) & 0xFF);
        ihdr.write(height & 0xFF);
        ihdr.write(8); // bit depth
        ihdr.write(colorType);
        ihdr.write(0); // compression
        ihdr.write(0); // filter
        ihdr.write(0); // interlace

        byte[] ihdrBytes = ihdr.toByteArray();
        CRC32 crc = new CRC32();
        crc.update(ihdrBytes);
        long crcVal = crc.getValue();

        // Write length (13)
        out.write(0);
        out.write(0);
        out.write(0);
        out.write(13);
        out.writeBytes(ihdrBytes);
        out.write((int) ((crcVal >>> 24) & 0xFF));
        out.write((int) ((crcVal >>> 16) & 0xFF));
        out.write((int) ((crcVal >>> 8) & 0xFF));
        out.write((int) (crcVal & 0xFF));

        // IEND
        out.writeBytes(new byte[] {0, 0, 0, 0, 'I', 'E', 'N', 'D', (byte) 0xAE, 0x42, 0x60, (byte) 0x82});
        return out.toByteArray();
    }

    private static void createJar(Path jarPath, List<String> classNames) throws IOException {
        try (ZipOutputStream zos = new ZipOutputStream(new FileOutputStream(jarPath.toFile()))) {
            for (String className : classNames) {
                String entryPath = className.replace('.', '/') + ".class";
                ZipEntry entry = new ZipEntry(entryPath);
                byte[] dummyBytecode = ("bytecode for " + className).getBytes(StandardCharsets.UTF_8);
                entry.setSize(dummyBytecode.length);
                zos.putNextEntry(entry);
                zos.write(dummyBytecode);
                zos.closeEntry();
            }
        }
    }

    private static byte[] loadAudioFixture(String name) throws IOException {
        String base = "/audio/ogg-v1/" + name + ".b64";
        InputStream single = ResourceCostInspectorTest.class.getResourceAsStream(base);
        if (single != null) {
            try (single) {
                return Base64.getMimeDecoder().decode(single.readAllBytes());
            }
        }

        ByteArrayOutputStream encoded = new ByteArrayOutputStream();
        for (int i = 0; i < 100; i++) {
            String partName = base + ".part" + String.format("%02d", i);
            InputStream part = ResourceCostInspectorTest.class.getResourceAsStream(partName);
            if (part == null) {
                break;
            }
            try (part) {
                encoded.writeBytes(part.readAllBytes());
            }
        }
        if (encoded.size() == 0) {
            throw new IOException("Missing fixture resource: " + base);
        }
        return Base64.getMimeDecoder().decode(encoded.toByteArray());
    }
}
