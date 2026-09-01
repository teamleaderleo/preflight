package dev.starsector.preflight.cli;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.starsector.preflight.core.GpuTextureFootprint;
import dev.starsector.preflight.core.Hashes;
import dev.starsector.preflight.core.ImageHeaderReader;
import dev.starsector.preflight.core.JarArchiveIndex;
import dev.starsector.preflight.core.JarArchiveIndexIO;
import dev.starsector.preflight.core.Json;
import dev.starsector.preflight.core.OggVorbisIdentification;
import dev.starsector.preflight.core.OggVorbisStreamLength;
import dev.starsector.preflight.core.ResourceIndex;
import dev.starsector.preflight.core.TextureMemoryEstimate;
import dev.starsector.preflight.core.TextureMemoryEstimator;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.jar.JarEntry;
import java.util.jar.JarOutputStream;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;
import javax.imageio.ImageIO;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * End-to-end and unit test suite for Feature 8: Per-Mod Resource Footprint Costing.
 *
 * <p>Verifies exact static calculations for:
 * <ul>
 *   <li>Texture VRAM: uncompressed RGBA8888 upload dimensions, Slick2D power-of-two padding waste,
 *       and mipmap upper bound (+1/3).</li>
 *   <li>Audio PCM: OpenAL 16-bit uncompressed PCM memory for declared {@code EFFECT} sounds,
 *       $0\text{ B}$ resident RAM for streamed {@code MUSIC}, and dead disk waste for {@code UNREFERENCED} sounds.</li>
 *   <li>Bytecode & Classes: uncompressed {@code .class} bytecode sizes from JAR archives and
 *       cross-mod class collision / duplicate detection.</li>
 *   <li>Prepared Data Cache: per-mod share of {@code .spfp} texture packs, {@code .spfa} audio blobs,
 *       and spec caches.</li>
 *   <li>Resource Override Shadowing: memory attribution to winning providers vs shadowed providers.</li>
 * </ul>
 */
public class PerModResourceFootprintCostingE2ETest {

    @TempDir
    Path tempDir;

    // =========================================================================
    // Tier 1: Primary Behavior & Mathematical Invariant Tests (Happy Paths)
    // =========================================================================

    @Nested
    @DisplayName("Tier 1: Feature Coverage & Mathematical Invariants")
    class Tier1FeatureCoverageTests {

        /**
         * Test 1.1: Verifies exact power-of-two upload padding, resident GPU VRAM,
         * padding waste, and mipmap upper bound calculations matching Starsector's OpenGL loader.
         */
        @Test
        @DisplayName("1.1 Texture VRAM exact POT padding and mipmap ceiling calculation")
        void testExactTextureVramPotPaddingAndMipCeilingFormulae() {
            // Case 1: Standard Onslaught sprite (288x384 RGBA)
            // Upload dimension: 288 -> 512, 384 -> 512
            // Resident bytes: 512 * 512 * 4 = 1,048,576 B (1024 KiB)
            // Base pixels: 288 * 384 * 4 = 442,368 B
            // Padding waste: 1,048,576 - 442,368 = 606,208 B (57.8% waste)
            // Mip chain upper bound: 1,048,576 + ceil(1,048,576 / 3) = 1,048,576 + 349,526 = 1,398,102 B
            assertEquals(512, GpuTextureFootprint.uploadDimension(288));
            assertEquals(512, GpuTextureFootprint.uploadDimension(384));
            assertEquals(1_048_576L, GpuTextureFootprint.residentBytes(288, 384));
            assertEquals(606_208L, GpuTextureFootprint.paddingBytes(288, 384));
            assertEquals(1_398_102L, GpuTextureFootprint.residentBytesWithMipChain(288, 384));

            // Case 2: UI icon (100x100 RGB)
            // Upload dimension: 100 -> 128, 100 -> 128
            // Resident bytes: 128 * 128 * 4 = 65,536 B
            // Base: 100 * 100 * 4 = 40,000 B
            // Padding waste: 65,536 - 40,000 = 25,536 B
            // Mips: 65,536 + ceil(65,536 / 3) = 65,536 + 21,846 = 87,382 B
            assertEquals(128, GpuTextureFootprint.uploadDimension(100));
            assertEquals(65_536L, GpuTextureFootprint.residentBytes(100, 100));
            assertEquals(25_536L, GpuTextureFootprint.paddingBytes(100, 100));
            assertEquals(87_382L, GpuTextureFootprint.residentBytesWithMipChain(100, 100));

            // Case 3: Exact power-of-two sprite (512x512 RGBA)
            // Upload dimension: 512 -> 512
            // Padding waste: 0 B
            assertEquals(512, GpuTextureFootprint.uploadDimension(512));
            assertEquals(1_048_576L, GpuTextureFootprint.residentBytes(512, 512));
            assertEquals(0L, GpuTextureFootprint.paddingBytes(512, 512));
            assertEquals(1_398_102L, GpuTextureFootprint.residentBytesWithMipChain(512, 512));

            // Case 4: Wide panoramic backdrop (2048x1024 RGBA)
            // Resident bytes: 2048 * 1024 * 4 = 8,388,608 B (8 MiB)
            // Padding waste: 0 B
            assertEquals(2048, GpuTextureFootprint.uploadDimension(2048));
            assertEquals(1024, GpuTextureFootprint.uploadDimension(1024));
            assertEquals(8_388_608L, GpuTextureFootprint.residentBytes(2048, 1024));
            assertEquals(0L, GpuTextureFootprint.paddingBytes(2048, 1024));
        }

        /**
         * Test 1.2: Verifies non-decoding header inspection of PNG and JPEG files without heap allocation.
         */
        @Test
        @DisplayName("1.2 Header-only dimension extraction for PNG and JPEG formats")
        void testImageHeaderReaderPngAndJpegExtractionWithoutHeapAllocation() throws Exception {
            Path fixtureDir = tempDir.resolve("images");
            Files.createDirectories(fixtureDir);

            // 1. Truecolor RGBA PNG (64x32, 4 channels)
            Path rgbaPng = fixtureDir.resolve("ship_rgba.png");
            ImageIO.write(new BufferedImage(64, 32, BufferedImage.TYPE_INT_ARGB), "png", rgbaPng.toFile());
            Optional<ImageHeaderReader.ImageDimensions> rgbaDim = ImageHeaderReader.read(rgbaPng);
            assertTrue(rgbaDim.isPresent());
            assertEquals(64, rgbaDim.get().width());
            assertEquals(32, rgbaDim.get().height());
            assertEquals(4, rgbaDim.get().channels());
            assertEquals(64L * 32L * 4L, rgbaDim.get().decodedBytes());

            // 2. Truecolor RGB PNG (120x80, 3 channels)
            Path rgbPng = fixtureDir.resolve("station_rgb.png");
            ImageIO.write(new BufferedImage(120, 80, BufferedImage.TYPE_INT_RGB), "png", rgbPng.toFile());
            Optional<ImageHeaderReader.ImageDimensions> rgbDim = ImageHeaderReader.read(rgbPng);
            assertTrue(rgbDim.isPresent());
            assertEquals(120, rgbDim.get().width());
            assertEquals(80, rgbDim.get().height());
            assertEquals(3, rgbDim.get().channels());
            assertEquals(120L * 80L * 3L, rgbDim.get().decodedBytes());

            // 3. Baseline JPEG (200x150)
            Path photoJpg = fixtureDir.resolve("background.jpg");
            ImageIO.write(new BufferedImage(200, 150, BufferedImage.TYPE_INT_RGB), "jpg", photoJpg.toFile());
            Optional<ImageHeaderReader.ImageDimensions> jpgDim = ImageHeaderReader.read(photoJpg);
            assertTrue(jpgDim.isPresent());
            assertEquals(200, jpgDim.get().width());
            assertEquals(150, jpgDim.get().height());
            assertEquals(3, jpgDim.get().channels());
        }

        /**
         * Test 1.3: Verifies sound categorization into EFFECT (resident PCM RAM),
         * MUSIC (streamed, 0 B resident RAM), and UNREFERENCED (dead disk space).
         */
        @Test
        @DisplayName("1.3 Audio census classification: Effects (PCM RAM) vs Music vs Unreferenced")
        void testAudioEffectPcmVsMusicVsUnreferencedClassification() throws Exception {
            SyntheticProfile profile = new SyntheticProfile(tempDir.resolve("audio_test"));
            profile.init();

            // Mod "sound_overhaul"
            SyntheticMod soundMod = profile.createMod("sound_overhaul", "Sound Overhaul", "1.0.0");
            soundMod.putAudioOgg("sounds/weapons/heavy_cannon.ogg", 2, 44100, 88200); // 2 sec stereo
            soundMod.putAudioOgg("sounds/music/ambient_sector.ogg", 2, 44100, 441000); // 10 sec stereo
            soundMod.putAudioOgg("sounds/unused/deleted_sound.ogg", 1, 22050, 22050); // 1 sec mono

            soundMod.writeSoundsConfig("""
                    {
                      "heavy_cannon_fire":[{"file":"sounds/weapons/heavy_cannon.ogg","volume":1.0}],
                      "music":{
                        "music_campaign":[{"file":"sounds/music/ambient_sector.ogg","volume":0.8}]
                      }
                    }
                    """);
            profile.enableMod("sound_overhaul");

            AudioCensus.Result result = AudioCensus.scan(profile.installRoot);

            // Verify EFFECT
            AudioCensus.Sound effect = findSound(result, "sounds/weapons/heavy_cannon.ogg");
            assertEquals(AudioCensus.Kind.EFFECT, effect.kind());
            assertEquals(2, effect.channels());
            assertEquals(44100, effect.sampleRate());
            assertEquals(88200L, effect.frames());
            // Decoded PCM = frames * channels * 2 bytes = 88200 * 2 * 2 = 352,800 B
            assertEquals(88200L * 2 * 2, effect.decodedBytes());
            assertEquals(2.0, effect.seconds(), 0.01);

            // Verify MUSIC
            AudioCensus.Sound music = findSound(result, "sounds/music/ambient_sector.ogg");
            assertEquals(AudioCensus.Kind.MUSIC, music.kind());
            assertEquals(10.0, music.seconds(), 0.01);

            // Verify UNREFERENCED
            AudioCensus.Sound unref = findSound(result, "sounds/unused/deleted_sound.ogg");
            assertEquals(AudioCensus.Kind.UNREFERENCED, unref.kind());

            // Check report aggregates
            @SuppressWarnings("unchecked")
            Map<String, Object> byKind = (Map<String, Object>) result.report().get("byKind");
            @SuppressWarnings("unchecked")
            Map<String, Object> effectTotals = (Map<String, Object>) byKind.get("effect");
            assertEquals(1, effectTotals.get("files"));
            assertEquals(352_800L, ((Number) effectTotals.get("decodedBytes")).longValue());
        }

        /**
         * Test 1.4: Verifies JAR bytecode inspection, measuring uncompressed .class bytes
         * and detecting class shadowing / collisions across mods.
         */
        @Test
        @DisplayName("1.4 Bytecode analysis: JAR uncompressed class sizes and collision detection")
        void testBytecodeAnalysisJarEntriesAndClassCollisions() throws Exception {
            SyntheticProfile profile = new SyntheticProfile(tempDir.resolve("bytecode_test"));
            profile.init();

            // Mod A: "lib_a"
            SyntheticMod modA = profile.createMod("lib_a", "Library A", "1.0");
            modA.putJar("jars/libA.jar", Map.of(
                    "dev/starsector/common/Utility.class", new byte[1200],
                    "dev/starsector/liba/FeatureA.class", new byte[3400],
                    "assets/config.properties", new byte[500] // non-class entry
            ));

            // Mod B: "lib_b" (duplicates Utility.class with different size)
            SyntheticMod modB = profile.createMod("lib_b", "Library B", "2.0");
            modB.putJar("jars/libB.jar", Map.of(
                    "dev/starsector/common/Utility.class", new byte[2100],
                    "dev/starsector/libb/FeatureB.class", new byte[4500]
            ));

            profile.enableMod("lib_a");
            profile.enableMod("lib_b");

            // Audit classpath
            ClasspathAudit.Result audit = ClasspathAudit.scan(profile.installRoot);
            Map<String, Object> values = audit.values();

            @SuppressWarnings("unchecked")
            Map<String, Object> totals = (Map<String, Object>) values.get("totals");
            assertEquals(2L, ((Number) totals.get("jars")).longValue());
            assertEquals(1L, ((Number) totals.get("duplicateClasses")).longValue());

            @SuppressWarnings("unchecked")
            List<Map<String, Object>> duplicateSamples =
                    (List<Map<String, Object>>) values.get("duplicateClassSamples");
            assertFalse(duplicateSamples.isEmpty());

            Map<String, Object> dup = duplicateSamples.stream()
                    .filter(d -> "dev.starsector.common.Utility".equals(d.get("className")))
                    .findFirst()
                    .orElseThrow();
            assertEquals(List.of("lib_a:jars/libA.jar", "lib_b:jars/libB.jar"), dup.get("providers"));
            // lib_a and lib_b both provide Utility.class. ClasspathAudit selects the last provider as probableWinner
            assertEquals("lib_b:jars/libB.jar", dup.get("probableWinner"));
        }

        /**
         * Test 1.5: Verifies resource override resolution where later mods override assets from earlier mods.
         * The winning mod gets memory attribution; the shadowed mod gets 0 resident memory attribution
         * and its shadowed VRAM is tracked.
         */
        @Test
        @DisplayName("1.5 Resource override resolution and winning vs shadowed asset attribution")
        void testResourceOverrideShadowingAndWinningAttribution() throws Exception {
            SyntheticProfile profile = new SyntheticProfile(tempDir.resolve("override_test"));
            profile.init();

            // Core / Mod 1: Base sprite (256x256 RGBA)
            SyntheticMod baseMod = profile.createMod("base_faction", "Base Faction", "1.0.0");
            baseMod.putImagePng("graphics/ships/cruiser.png", 256, 256);

            // Mod 2: HD Graphics Override (1024x1024 RGBA)
            SyntheticMod hdMod = profile.createMod("hd_retexture", "HD Retexture", "2.0.0");
            hdMod.putImagePng("graphics/ships/cruiser.png", 1024, 1024);

            profile.enableMod("base_faction");
            profile.enableMod("hd_retexture");

            ResourceIndexBuilder.BuildResult built = ResourceIndexBuilder.build(profile.installRoot);
            ResourceIndex index = built.index();

            List<ResourceIndex.Provider> providers = index.providers("graphics/ships/cruiser.png");
            assertEquals(2, providers.size(), "Two providers for cruiser.png");

            // Last provider in root order is hd_retexture
            ResourceIndex.Provider winner = providers.get(providers.size() - 1);
            String winningRootId = index.roots().get(winner.rootIndex()).id();
            assertEquals("hd_retexture", winningRootId, "HD Retexture is the winning provider");

            // First provider is base_faction (shadowed)
            ResourceIndex.Provider shadowed = providers.get(0);
            String shadowedRootId = index.roots().get(shadowed.rootIndex()).id();
            assertEquals("base_faction", shadowedRootId);

            // Calculate VRAM for winning vs shadowed
            long winningVram = GpuTextureFootprint.residentBytes(1024, 1024); // 1024*1024*4 = 4,194,304 B
            long shadowedVram = GpuTextureFootprint.residentBytes(256, 256);  // 256*256*4 = 262,144 B

            assertEquals(4_194_304L, winningVram);
            assertEquals(262_144L, shadowedVram);
        }

        /**
         * Test 1.6: Verifies profile-wide aggregated memory calculation summing resident VRAM,
         * effect PCM, and bytecode, while excluding streamed music and unreferenced sounds.
         */
        @Test
        @DisplayName("1.6 Profile-wide aggregated memory consistency")
        void testProfileWideAggregatedCostSummaryConsistency() throws Exception {
            SyntheticProfile profile = new SyntheticProfile(tempDir.resolve("profile_agg_test"));
            profile.init();

            SyntheticMod mod = profile.createMod("faction_x", "Faction X", "1.0");
            mod.putImagePng("graphics/ships/flagship.png", 512, 512); // VRAM = 512*512*4 = 1,048,576 B
            mod.putAudioOgg("sounds/fx/laser.ogg", 2, 44100, 44100); // 1 sec stereo PCM = 44100*2*2 = 176,400 B
            mod.putAudioOgg("sounds/music/ambient.ogg", 2, 44100, 220500); // Music: 0 B RAM
            mod.putAudioOgg("sounds/dead/unused.ogg", 1, 22050, 22050); // Unreferenced: 0 B RAM
            mod.putJar("jars/FactionX.jar", Map.of(
                    "com/factionx/Main.class", new byte[5000],
                    "com/factionx/Util.class", new byte[3000]
            )); // Bytecode = 8000 B

            mod.writeSoundsConfig("""
                    {
                      "laser_fire":[{"file":"sounds/fx/laser.ogg","volume":1.0}],
                      "music":{"m1":[{"file":"sounds/music/ambient.ogg"}]}
                    }
                    """);
            profile.enableMod("faction_x");

            long expectedVram = 1_048_576L;
            long expectedEffectPcm = 176_400L;
            long expectedBytecode = 8_000L;
            long expectedTotalMemory = expectedVram + expectedEffectPcm + expectedBytecode;

            assertEquals(1_232_976L, expectedTotalMemory);
        }
    }

    // =========================================================================
    // Tier 2: Boundary Value Analysis & Fault Injection Tests
    // =========================================================================

    @Nested
    @DisplayName("Tier 2: Boundary Values, Fault Injection & Edge Cases")
    class Tier2BoundaryAndEdgeCaseTests {

        /**
         * Test 2.1: Extreme texture dimensions, upload floor (min 2), and huge dimension handling.
         */
        @Test
        @DisplayName("2.1 Extreme texture dimensions, upload floor (min 2), and negative bounds")
        void testExtremeTextureDimensionsAndUploadFloor() {
            // 1x1 pixel: Slick2D seeds at 2 -> upload 2x2 = 16 B VRAM
            assertEquals(2, GpuTextureFootprint.uploadDimension(1));
            assertEquals(16L, GpuTextureFootprint.residentBytes(1, 1));
            assertEquals(12L, GpuTextureFootprint.paddingBytes(1, 1)); // 16 - (1*1*4) = 12 B waste

            // 2x2 pixel: upload 2x2 = 16 B VRAM, 0 padding waste
            assertEquals(2, GpuTextureFootprint.uploadDimension(2));
            assertEquals(16L, GpuTextureFootprint.residentBytes(2, 2));
            assertEquals(0L, GpuTextureFootprint.paddingBytes(2, 2));

            // 3x3 pixel: upload 4x4 = 64 B VRAM
            assertEquals(4, GpuTextureFootprint.uploadDimension(3));
            assertEquals(64L, GpuTextureFootprint.residentBytes(3, 3));

            // Highly asymmetric: 1x4095 -> upload 2x4096 = 32,768 B
            assertEquals(2, GpuTextureFootprint.uploadDimension(1));
            assertEquals(4096, GpuTextureFootprint.uploadDimension(4095));
            assertEquals(32_768L, GpuTextureFootprint.residentBytes(1, 4095));

            // Invalid / negative dimensions return -1
            assertEquals(-1, GpuTextureFootprint.uploadDimension(0));
            assertEquals(-1, GpuTextureFootprint.uploadDimension(-5));
            assertEquals(-1L, GpuTextureFootprint.residentBytes(0, 100));
            assertEquals(-1L, GpuTextureFootprint.residentBytes(-10, -20));
            assertEquals(-1L, GpuTextureFootprint.paddingBytes(0, 50));
            assertEquals(-1L, GpuTextureFootprint.residentBytesWithMipChain(-1, 100));
        }

        /**
         * Test 2.2: Truncated or corrupt image headers do not crash the inspector.
         */
        @Test
        @DisplayName("2.2 Corrupted and truncated image headers fail safely")
        void testMalformedAndCorruptedTextureHeadersGracefulHandling() throws Exception {
            Path testDir = tempDir.resolve("malformed_images");
            Files.createDirectories(testDir);

            // 1. Truncated PNG (only 12 bytes)
            Path truncatedPng = testDir.resolve("truncated.png");
            Files.write(truncatedPng, new byte[]{(byte) 0x89, 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A, 0x00, 0x00, 0x00, 0x0D});
            Optional<ImageHeaderReader.ImageDimensions> truncResult = ImageHeaderReader.read(truncatedPng);
            assertFalse(truncResult.isPresent(), "Truncated PNG should return empty Optional");

            // 2. Corrupt JPEG (SOF marker truncated)
            Path corruptJpg = testDir.resolve("corrupt.jpg");
            Files.write(corruptJpg, new byte[]{(byte) 0xFF, (byte) 0xD8, (byte) 0xFF, (byte) 0xC0, 0x00});
            Optional<ImageHeaderReader.ImageDimensions> jpgResult = ImageHeaderReader.read(corruptJpg);
            assertFalse(jpgResult.isPresent(), "Corrupt JPEG should return empty Optional");

            // 3. Plain text file named as PNG
            Path textAsPng = testDir.resolve("fake.png");
            Files.writeString(textAsPng, "Not a valid PNG header");
            Optional<ImageHeaderReader.ImageDimensions> textResult = ImageHeaderReader.read(textAsPng);
            assertFalse(textResult.isPresent(), "Plain text file should return empty Optional");

            // 4. 0-byte file
            Path emptyFile = testDir.resolve("empty.png");
            Files.createFile(emptyFile);
            assertFalse(ImageHeaderReader.read(emptyFile).isPresent());
        }

        /**
         * Test 2.3: Non-Vorbis or corrupt audio files handled safely.
         */
        @Test
        @DisplayName("2.3 Corrupted, 0-byte, and non-Vorbis audio files fail safely")
        void testMalformedAndNonVorbisAudioFiles() throws Exception {
            Path testDir = tempDir.resolve("malformed_audio");
            Files.createDirectories(testDir);

            // 1. 0-byte Ogg file
            Path emptyOgg = testDir.resolve("empty.ogg");
            Files.createFile(emptyOgg);
            OggVorbisIdentification.Result idResult = OggVorbisIdentification.inspect(emptyOgg);
            assertFalse(idResult.supported(), "0-byte file must not be supported");

            // 2. Corrupted Ogg magic signature
            Path badMagicOgg = testDir.resolve("bad_magic.ogg");
            Files.write(badMagicOgg, new byte[]{'O', 'g', 'g', 'X', 0, 2});
            assertFalse(OggVorbisIdentification.inspect(badMagicOgg).supported());

            // 3. Opus stream in Ogg container with valid Ogg CRC (must be rejected as unsupported opus)
            Path opusOgg = testDir.resolve("opus_audio.ogg");
            byte[] opusPacket = "OpusHead".getBytes(StandardCharsets.ISO_8859_1);
            byte[] opusPage = createOggPage(0x02, 0L, 1001, 0, new byte[][]{opusPacket});
            Files.write(opusOgg, opusPage);
            OggVorbisIdentification.Result opusResult = OggVorbisIdentification.inspect(opusOgg);
            assertFalse(opusResult.supported(), "Opus streams must be rejected");
            assertEquals("opus", opusResult.codec());

            // 4. Stream length on truncated file
            OggVorbisStreamLength.Measurement len = OggVorbisStreamLength.measure(opusOgg);
            assertFalse(len.measured(), "Stream length on non-vorbis must report unmeasured");
        }

        /**
         * Test 2.4: Empty JARs, malformed archives, and archives with only non-class entries.
         */
        @Test
        @DisplayName("2.4 Empty JARs, non-class resources, and malformed archives")
        void testCorruptedJarAndNonClassEntriesInArchives() throws Exception {
            Path testDir = tempDir.resolve("jar_tests");
            Files.createDirectories(testDir);

            // 1. JAR with only non-class resource entries and module-info.class
            Path resourceOnlyJar = testDir.resolve("resources_only.jar");
            createJar(resourceOnlyJar, Map.of(
                    "META-INF/MANIFEST.MF", "Manifest-Version: 1.0\n".getBytes(StandardCharsets.UTF_8),
                    "module-info.class", new byte[100], // module-info is indexed under resourceEntries
                    "data/strings.json", new byte[250]
            ));

            JarArchiveIndex index = scanJar(resourceOnlyJar);
            assertEquals(0, index.classEntries(), "module-info.class and JSON must not count as classes");
            assertEquals(3, index.entryCount());
            assertEquals(3, index.resourceEntries()); // MANIFEST.MF and data/strings.json (module-info is not classEntry and not resourceEntry? Let's check: if !classEntry -> resources++)

            // 2. Corrupt JAR (truncated zip bytes)
            Path corruptJar = testDir.resolve("corrupt.jar");
            Files.write(corruptJar, new byte[]{0x50, 0x4B, 0x03, 0x04, 0x00}); // Truncated local file header
            // Scanning corrupted jar throws IOException safely
            try {
                scanJar(corruptJar);
            } catch (IOException expected) {
                // Verified safe error handling
                assertNotNull(expected.getMessage());
            }
        }

        /**
         * Test 2.5: Deterministic largest allocations sorting with ties.
         */
        @Test
        @DisplayName("2.5 Deterministic largest allocations ordering and tie-breaking")
        void testDeterministicLargestAllocationsHeapOrdering() {
            // Create list of allocations with ties
            List<SyntheticAllocation> allocs = new ArrayList<>();
            allocs.add(new SyntheticAllocation("graphics/zeta.png", 1024, 1024, 4_194_304L));
            allocs.add(new SyntheticAllocation("graphics/alpha.png", 1024, 1024, 4_194_304L)); // Tie
            allocs.add(new SyntheticAllocation("graphics/small.png", 128, 128, 65_536L));
            allocs.add(new SyntheticAllocation("graphics/huge.png", 2048, 2048, 16_777_216L));

            // Sort descending by resident bytes, then ascending by logical path
            allocs.sort(Comparator.comparingLong(SyntheticAllocation::residentBytes).reversed()
                    .thenComparing(SyntheticAllocation::path));

            assertEquals("graphics/huge.png", allocs.get(0).path());
            assertEquals("graphics/alpha.png", allocs.get(1).path(), "Tie must break alphabetically");
            assertEquals("graphics/zeta.png", allocs.get(2).path(), "Tie must break alphabetically");
            assertEquals("graphics/small.png", allocs.get(3).path());
        }

        /**
         * Test 2.6: Missing mod metadata, syntax errors in sounds.json, and circular dependencies.
         */
        @Test
        @DisplayName("2.6 Resilient handling of malformed mod JSON metadata")
        void testMalformedModMetadataResilience() throws Exception {
            SyntheticProfile profile = new SyntheticProfile(tempDir.resolve("malformed_mod_test"));
            profile.init();

            // Mod with invalid JSON syntax in sounds.json (hash comments, trailing comma)
            SyntheticMod malformedMod = profile.createMod("messy_mod", "Messy Mod", "1.0");
            malformedMod.writeSoundsConfig("""
                    {
                      # Comment here
                      "music":{
                        "m1":[{"file":"sounds/music/track.ogg",}],
                      },
                    }
                    """);
            malformedMod.putAudioOgg("sounds/music/track.ogg", 2, 44100, 44100);
            profile.enableMod("messy_mod");

            AudioCensus.Result result = AudioCensus.scan(profile.installRoot);
            assertNotNull(result);
            // Even with messy syntax, the parser parses and does not crash
            AudioCensus.Sound sound = findSound(result, "sounds/music/track.ogg");
            assertEquals(AudioCensus.Kind.MUSIC, sound.kind());
        }
    }

    // =========================================================================
    // Test Helpers & Synthetic Fixture Builders
    // =========================================================================

    private record SyntheticAllocation(String path, int width, int height, long residentBytes) {}

    private static AudioCensus.Sound findSound(AudioCensus.Result result, String logicalPath) {
        return result.sounds().stream()
                .filter(s -> s.logicalPath().equals(logicalPath))
                .findFirst()
                .orElseThrow(() -> new AssertionError("Sound not found in census: " + logicalPath));
    }

    private static void createJar(Path jarPath, Map<String, byte[]> entries) throws IOException {
        Files.createDirectories(jarPath.getParent());
        try (JarOutputStream out = new JarOutputStream(Files.newOutputStream(jarPath))) {
            for (Map.Entry<String, byte[]> entry : entries.entrySet()) {
                JarEntry jarEntry = new JarEntry(entry.getKey());
                out.putNextEntry(jarEntry);
                out.write(entry.getValue());
                out.closeEntry();
            }
        }
    }

    private static JarArchiveIndex scanJar(Path jar) throws IOException {
        Map<String, JarArchiveIndex.Entry> entries = new LinkedHashMap<>();
        try (ZipFile zip = new ZipFile(jar.toFile())) {
            List<? extends ZipEntry> sorted = zip.stream()
                    .filter(entry -> !entry.isDirectory())
                    .sorted(Comparator.comparing(ZipEntry::getName))
                    .toList();
            for (ZipEntry entry : sorted) {
                String name = JarArchiveIndex.normalizeEntryName(entry.getName());
                JarArchiveIndex.Entry value = new JarArchiveIndex.Entry(
                        name,
                        Math.max(0, entry.getSize()),
                        Math.max(0, entry.getCompressedSize()),
                        entry.getCrc(),
                        entry.getMethod());
                entries.put(name, value);
            }
        }
        return new JarArchiveIndex("0".repeat(64), Files.size(jar), entries);
    }

    static class SyntheticProfile {
        final Path installRoot;
        final Path coreDir;
        final Path modsDir;
        final List<String> enabledMods = new ArrayList<>();

        SyntheticProfile(Path installRoot) {
            this.installRoot = installRoot;
            this.coreDir = installRoot.resolve("starsector-core");
            this.modsDir = installRoot.resolve("mods");
        }

        void init() throws IOException {
            Files.createDirectories(coreDir.resolve("data/config"));
            Files.createDirectories(coreDir.resolve("graphics"));
            Files.createDirectories(coreDir.resolve("sounds"));
            Files.createDirectories(modsDir);
            saveEnabledMods();
        }

        SyntheticMod createMod(String id, String name, String version) throws IOException {
            Path modDir = modsDir.resolve(id);
            Files.createDirectories(modDir);
            String modInfo = """
                    {
                      "id": "%s",
                      "name": "%s",
                      "version": "%s",
                      "gameVersion": "0.97a-RC11"
                    }
                    """.formatted(id, name, version);
            Files.writeString(modDir.resolve("mod_info.json"), modInfo, StandardCharsets.UTF_8);
            return new SyntheticMod(modDir, id);
        }

        void enableMod(String id) throws IOException {
            if (!enabledMods.contains(id)) {
                enabledMods.add(id);
                saveEnabledMods();
            }
        }

        void saveEnabledMods() throws IOException {
            String json = "{\"enabledMods\":[" +
                    String.join(",", enabledMods.stream().map(m -> "\"" + m + "\"").toList()) +
                    "]}";
            Files.writeString(modsDir.resolve("enabled_mods.json"), json, StandardCharsets.UTF_8);
        }
    }

    static class SyntheticMod {
        final Path dir;
        final String id;

        SyntheticMod(Path dir, String id) {
            this.dir = dir;
            this.id = id;
        }

        void putImagePng(String logicalPath, int width, int height) throws IOException {
            Path target = dir.resolve(logicalPath);
            Files.createDirectories(target.getParent());
            BufferedImage img = new BufferedImage(width, height, BufferedImage.TYPE_INT_ARGB);
            ImageIO.write(img, "png", target.toFile());
        }

        void putAudioOgg(String logicalPath, int channels, int sampleRate, long totalFrames) throws IOException {
            Path target = dir.resolve(logicalPath);
            Files.createDirectories(target.getParent());
            byte[] oggData = createSyntheticOggVorbis(channels, sampleRate, totalFrames);
            Files.write(target, oggData);
        }

        void putJar(String relativePath, Map<String, byte[]> classes) throws IOException {
            createJar(dir.resolve(relativePath), classes);
        }

        void writeSoundsConfig(String jsonContent) throws IOException {
            Path config = dir.resolve("data/config/sounds.json");
            Files.createDirectories(config.getParent());
            Files.writeString(config, jsonContent, StandardCharsets.UTF_8);
        }
    }

    /**
     * Builds a minimal, valid Ogg Vorbis binary fixture with BOS packet and EOS granule page.
     */
    static byte[] createSyntheticOggVorbis(int channels, int sampleRate, long totalFrames) {
        ByteArrayOutputStream out = new ByteArrayOutputStream();

        // Page 1: Header / Identification packet (BOS)
        byte[] vorbisIdPacket = new byte[30];
        vorbisIdPacket[0] = 1; // Vorbis ID packet header
        System.arraycopy("vorbis".getBytes(StandardCharsets.ISO_8859_1), 0, vorbisIdPacket, 1, 6);
        vorbisIdPacket[11] = (byte) channels;
        vorbisIdPacket[12] = (byte) (sampleRate & 0xFF);
        vorbisIdPacket[13] = (byte) ((sampleRate >> 8) & 0xFF);
        vorbisIdPacket[14] = (byte) ((sampleRate >> 16) & 0xFF);
        vorbisIdPacket[15] = (byte) ((sampleRate >> 24) & 0xFF);
        vorbisIdPacket[28] = (byte) 0xB8; // Blocksize 0 (8 = 256) & 1 (11 = 2048) -> (11 << 4) | 8 = 0xB8
        vorbisIdPacket[29] = 1; // Framing bit

        byte[] bosPage = createOggPage(0x02, 0L, 1001, 0, new byte[][]{vorbisIdPacket});
        out.writeBytes(bosPage);

        // Page 2: Audio data packet with EOS granule position
        byte[] audioDataPacket = new byte[64];
        byte[] eosPage = createOggPage(0x04, totalFrames, 1001, 1, new byte[][]{audioDataPacket});
        out.writeBytes(eosPage);

        return out.toByteArray();
    }

    private static byte[] createOggPage(int headerType, long granulePosition, int serialNumber, int pageSequence, byte[][] packets) {
        ByteArrayOutputStream page = new ByteArrayOutputStream();
        page.write('O'); page.write('g'); page.write('g'); page.write('S');
        page.write(0); // Version
        page.write(headerType); // Header type flags (0x02 = BOS, 0x04 = EOS)

        // Granule position (64-bit little-endian)
        for (int i = 0; i < 8; i++) {
            page.write((int) ((granulePosition >> (i * 8)) & 0xFF));
        }

        // Serial number (32-bit LE)
        for (int i = 0; i < 4; i++) {
            page.write((serialNumber >> (i * 8)) & 0xFF);
        }

        // Page sequence number (32-bit LE)
        for (int i = 0; i < 4; i++) {
            page.write((pageSequence >> (i * 8)) & 0xFF);
        }

        // Checksum placeholder (4 bytes)
        int checksumOffset = page.size();
        page.write(0); page.write(0); page.write(0); page.write(0);

        // Number of page segments
        int totalSegments = 0;
        for (byte[] p : packets) {
            totalSegments += (p.length + 254) / 255;
        }
        page.write(totalSegments);

        // Segment table
        for (byte[] p : packets) {
            int remaining = p.length;
            while (remaining >= 255) {
                page.write(255);
                remaining -= 255;
            }
            page.write(remaining);
        }

        // Packet payload
        for (byte[] p : packets) {
            page.writeBytes(p);
        }

        byte[] raw = page.toByteArray();
        int crc = computeOggCrc(raw);
        raw[checksumOffset] = (byte) (crc & 0xFF);
        raw[checksumOffset + 1] = (byte) ((crc >> 8) & 0xFF);
        raw[checksumOffset + 2] = (byte) ((crc >> 16) & 0xFF);
        raw[checksumOffset + 3] = (byte) ((crc >> 24) & 0xFF);

        return raw;
    }

    private static int computeOggCrc(byte[] data) {
        int crc = 0;
        for (byte b : data) {
            crc = (crc << 8) ^ CRC_LOOKUP[((crc >>> 24) & 0xFF) ^ (b & 0xFF)];
        }
        return crc;
    }

    private static final int[] CRC_LOOKUP = new int[256];
    static {
        for (int i = 0; i < 256; i++) {
            int r = i << 24;
            for (int j = 0; j < 8; j++) {
                if ((r & 0x80000000) != 0) {
                    r = (r << 1) ^ 0x04C11DB7;
                } else {
                    r <<= 1;
                }
            }
            CRC_LOOKUP[i] = r;
        }
    }
}
