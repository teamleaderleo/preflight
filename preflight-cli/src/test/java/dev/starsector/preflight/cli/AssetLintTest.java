package dev.starsector.preflight.cli;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Base64;
import java.util.List;
import java.util.Map;
import java.util.zip.CRC32;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class AssetLintTest {
    @TempDir
    Path temporaryDirectory;

    @Test
    void reportsAnUndecodableDeclaredEffectAsAnError() throws Exception {
        Path core = profile();
        Files.write(core.resolve("sounds/broken.ogg"), new byte[0]);
        declare("""
                {"a":[{"file":"sounds/broken.ogg","volume":1}]}
                """);

        AssetLint.Finding finding = only(AssetLint.scan(temporaryDirectory, null), "audio-undecodable");

        assertEquals(AssetLint.Severity.ERROR, finding.severity());
        assertEquals(AssetLint.Cost.NONE, finding.cost());
        assertEquals(0, finding.bytes());
    }

    /** An undeclared broken file is a leftover, not a bug the author has shipped into the game. */
    @Test
    void anUndecodableFileNothingDeclaresIsOnlyInformational() throws Exception {
        Path core = profile();
        Files.write(core.resolve("sounds/broken.ogg"), new byte[0]);
        declare("{}");

        AssetLint.Finding finding = only(AssetLint.scan(temporaryDirectory, null), "audio-undecodable");

        assertEquals(AssetLint.Severity.INFO, finding.severity());
    }

    @Test
    void reportsOversampledAudioWithTheCostOfTheSampleRate() throws Exception {
        Path core = profile();
        put(core, "sounds/quiet.ogg", "mono-22050.ogg");
        declare("""
                {"a":[{"file":"sounds/quiet.ogg","volume":1}]}
                """);

        // 22,050 Hz is ordinary and must not be flagged.
        assertTrue(AssetLint.scan(temporaryDirectory, null).findings().stream()
                .noneMatch(finding -> finding.rule().equals("audio-oversampled")));
    }

    @Test
    void reportsNonPowerOfTwoTexturePaddingAsVideoMemory() throws Exception {
        Path core = profile();
        Files.createDirectories(core.resolve("graphics"));
        Files.write(core.resolve("graphics/wide.png"), png(1_200, 1_200));
        declare("{}");

        AssetLint.Finding finding = only(AssetLint.scan(temporaryDirectory, null), "texture-npot-padding");

        assertEquals(AssetLint.Cost.VRAM, finding.cost());
        // 2048*2048 - 1200*1200 pixels at four bytes each.
        assertEquals((2_048L * 2_048L - 1_200L * 1_200L) * 4, finding.bytes());
        assertTrue(finding.detail().contains("1200x1200 uploaded as 2048x2048"), finding.detail());
    }

    /** A power-of-two texture pays nothing, and reporting one would be a false positive. */
    @Test
    void leavesPowerOfTwoTexturesAlone() throws Exception {
        Path core = profile();
        Files.createDirectories(core.resolve("graphics"));
        Files.write(core.resolve("graphics/exact.png"), png(1_024, 512));
        declare("{}");

        assertTrue(AssetLint.scan(temporaryDirectory, null).findings().isEmpty());
    }

    /**
     * Disk, decoded memory, and video memory are different resources. Adding them would produce a
     * headline number that overstates every finding it contains, so the report keeps them apart.
     */
    @Test
    void neverCombinesDifferentCostKindsIntoOneTotal() throws Exception {
        Path core = profile();
        Files.createDirectories(core.resolve("graphics"));
        Files.write(core.resolve("graphics/wide.png"), png(1_200, 1_200));
        put(core, "sounds/orphan.ogg", "stereo-44100.ogg");
        declare("{}");

        AssetLint.Result result = AssetLint.scan(temporaryDirectory, null);
        Map<AssetLint.Cost, Long> costs = AssetLint.byCost(result.findings());

        assertEquals(2, result.findings().size());
        assertTrue(costs.containsKey(AssetLint.Cost.VRAM));
        assertTrue(costs.containsKey(AssetLint.Cost.DISK));
        assertEquals(6_843L, costs.get(AssetLint.Cost.DISK));

        @SuppressWarnings("unchecked")
        Map<String, Object> reported = (Map<String, Object>) result.report().get("bytesByCost");
        assertEquals(2, reported.size(), "each cost kind is reported separately");
        assertFalse(result.report().containsKey("avoidableBytes"),
                "no single combined byte figure may exist");

        String rendered = AssetLintCommand.render(result, null);
        assertTrue(rendered.contains("on disk"), rendered);
        assertTrue(rendered.contains("in video memory"), rendered);
    }

    @Test
    void filtersToASingleMod() throws Exception {
        Path core = profile();
        Path alpha = temporaryDirectory.resolve("mods/Alpha");
        Files.createDirectories(alpha.resolve("graphics"));
        Files.createDirectories(core.resolve("graphics"));
        Files.write(core.resolve("graphics/core.png"), png(1_200, 1_200));
        Files.write(alpha.resolve("graphics/alpha.png"), png(1_200, 1_200));
        Files.writeString(alpha.resolve("mod_info.json"), "{\"id\":\"alpha\"}");
        Files.writeString(temporaryDirectory.resolve("mods/enabled_mods.json"),
                "{\"enabledMods\":[\"alpha\"]}");
        declare("{}");

        AssetLint.Result all = AssetLint.scan(temporaryDirectory, null);
        AssetLint.Result filtered = AssetLint.scan(temporaryDirectory, "alpha");

        assertEquals(2, all.findings().size());
        assertEquals(1, filtered.findings().size());
        assertEquals("alpha", filtered.findings().get(0).provider());
    }

    /** Every rule must explain itself; a finding with no reason is one an author cannot act on. */
    @Test
    void everyEmittedRuleCarriesAnExplanation() throws Exception {
        Path core = profile();
        Files.createDirectories(core.resolve("graphics"));
        Files.write(core.resolve("graphics/wide.png"), png(1_200, 1_200));
        Files.write(core.resolve("sounds/broken.ogg"), new byte[0]);
        put(core, "sounds/orphan.ogg", "stereo-44100.ogg");
        declare("{\"a\":[{\"file\":\"sounds/broken.ogg\",\"volume\":1}]}");

        AssetLint.Result result = AssetLint.scan(temporaryDirectory, null);

        assertFalse(result.findings().isEmpty());
        for (AssetLint.Finding finding : result.findings()) {
            assertFalse(AssetLint.explain(finding.rule()).isBlank(),
                    "rule without an explanation: " + finding.rule());
        }
    }

    @Test
    void aCleanProfileSaysSoRatherThanPrintingAnEmptyReport() throws Exception {
        profile();
        declare("{}");

        AssetLint.Result result = AssetLint.scan(temporaryDirectory, null);

        assertTrue(result.findings().isEmpty());
        assertEquals("No findings.\n", AssetLintCommand.render(result, null));
        assertEquals("No findings for alpha.\n", AssetLintCommand.render(result, "alpha"));
    }

    @Test
    void rendersTerminalControlsAsVisibleText() {
        AssetLint.Finding finding = new AssetLint.Finding(
                "audio-undecodable", AssetLint.Severity.ERROR, AssetLint.Cost.NONE,
                "evil\u001b[31m", "sounds/hidden\nfile.ogg", 0, "bad\u009b2Kdetail");
        AssetLint.Result result = new AssetLint.Result(Map.of(), List.of(finding));

        String rendered = AssetLintCommand.render(result, null);

        assertTrue(rendered.contains("evil\\u001b[31m"), rendered);
        assertTrue(rendered.contains("sounds/hidden\\u000afile.ogg"), rendered);
        assertTrue(rendered.contains("bad\\u009b2Kdetail"), rendered);
        assertFalse(rendered.contains("\u001b"), rendered);
        assertFalse(rendered.contains("\u009b"), rendered);
        assertFalse(rendered.contains("hidden\nfile"), rendered);
    }

    @Test
    void reportsASoundDeclaredButSuppliedByNobody() throws Exception {
        profile();
        declare("""
                {"a":[{"file":"sounds/absent.ogg","volume":1}]}
                """);

        AssetLint.Finding finding = only(AssetLint.scan(temporaryDirectory, null), "sound-declared-missing");

        assertEquals(AssetLint.Severity.ERROR, finding.severity());
        assertEquals("sounds/absent.ogg", finding.path());
    }

    /**
     * A .wav is not an editor intermediate — Starsector plays it, and twenty effects in the reviewed
     * profile are declared as .wav. Flagging it would tell authors to delete working sound.
     */
    @Test
    void doesNotTreatPlayableFormatsAsEditorSource() throws Exception {
        Path core = profile();
        Files.write(core.resolve("sounds/effect.wav"), new byte[70_000]);
        Files.write(core.resolve("sounds/artwork.pdn"), new byte[70_000]);
        declare("{}");

        AssetLint.Finding finding = only(AssetLint.scan(temporaryDirectory, null), "asset-editor-source");

        assertEquals("sounds/artwork.pdn", finding.path());
        assertEquals(AssetLint.Cost.DISK, finding.cost());
        assertEquals(70_000, finding.bytes());
    }

    @Test
    void reportsAShadowedCopyAgainstTheModThatShipsIt() throws Exception {
        Path core = profile();
        Path alpha = mod("alpha");
        Files.createDirectories(core.resolve("graphics"));
        Files.write(core.resolve("graphics/shared.png"), new byte[200_000]);
        Files.write(alpha.resolve("graphics/shared.png"), new byte[200_000]);
        declare("{}");

        AssetLint.Finding finding = only(AssetLint.scan(temporaryDirectory, null), "asset-shadowed");

        assertEquals("core", finding.provider(), "the shadowed copy is the one that never loads");
        assertTrue(finding.detail().contains("alpha provides this path"), finding.detail());
    }

    /** Most shadowing is deliberate and tiny; without a floor it buries every other rule. */
    @Test
    void ignoresShadowedFilesTooSmallToMatter() throws Exception {
        Path core = profile();
        Path alpha = mod("alpha");
        Files.createDirectories(core.resolve("data"));
        Files.createDirectories(alpha.resolve("data"));
        Files.write(core.resolve("data/tiny.csv"), new byte[1_024]);
        Files.write(alpha.resolve("data/tiny.csv"), new byte[1_024]);
        declare("{}");

        assertTrue(AssetLint.scan(temporaryDirectory, null).findings().stream()
                .noneMatch(finding -> finding.rule().equals("asset-shadowed")));
    }

    @Test
    void reportsIdenticalContentAtDifferentPaths() throws Exception {
        Path core = profile();
        Files.createDirectories(core.resolve("graphics"));
        byte[] content = new byte[200_000];
        content[7] = 42;
        Files.write(core.resolve("graphics/one.png"), content);
        Files.write(core.resolve("graphics/copy of one.png"), content);
        declare("{}");

        List<AssetLint.Finding> duplicates = AssetLint.scan(temporaryDirectory, null).findings().stream()
                .filter(finding -> finding.rule().equals("asset-duplicate-content"))
                .toList();

        assertEquals(2, duplicates.size(), "both copies are named, since either could be the one to drop");
        // Only the redundant copy is avoidable, so the pair must not each claim the full size.
        assertEquals(200_000, duplicates.stream().mapToLong(AssetLint.Finding::bytes).sum());
    }

    /** The same path in two mods is {@code asset-shadowed}; counting it twice would double the bytes. */
    @Test
    void doesNotAlsoReportShadowedPathsAsDuplicateContent() throws Exception {
        Path core = profile();
        Path alpha = mod("alpha");
        Files.createDirectories(core.resolve("graphics"));
        byte[] content = new byte[200_000];
        Files.write(core.resolve("graphics/shared.png"), content);
        Files.write(alpha.resolve("graphics/shared.png"), content);
        declare("{}");

        List<AssetLint.Finding> findings = AssetLint.scan(temporaryDirectory, null).findings();

        assertEquals(1, findings.size(), findings.toString());
        assertEquals("asset-shadowed", findings.get(0).rule());
    }

    @Test
    void reportsAnInterlacedPngAsProgressive() throws Exception {
        Path core = profile();
        Files.createDirectories(core.resolve("graphics"));
        Files.write(core.resolve("graphics/adam7.png"), png(64, 64, true));
        declare("{}");

        AssetLint.Finding finding = only(AssetLint.scan(temporaryDirectory, null), "texture-progressive");

        assertEquals(AssetLint.Severity.WARNING, finding.severity());
        assertEquals("Adam7 interlaced", finding.detail());
    }

    @Test
    void reportsAProgressiveJpegAndLeavesABaselineOneAlone() throws Exception {
        Path core = profile();
        Files.createDirectories(core.resolve("graphics"));
        Files.write(core.resolve("graphics/progressive.jpg"), jpeg(0xC2));
        Files.write(core.resolve("graphics/baseline.jpg"), jpeg(0xC0));
        declare("{}");

        AssetLint.Finding finding = only(AssetLint.scan(temporaryDirectory, null), "texture-progressive");

        assertEquals("graphics/progressive.jpg", finding.path());
        assertEquals("progressive JPEG", finding.detail());
    }

    /** A non-interlaced PNG must not be flagged; 26,251 of the reviewed profile's images are these. */
    @Test
    void leavesOrdinaryImagesAlone() throws Exception {
        Path core = profile();
        Files.createDirectories(core.resolve("graphics"));
        Files.write(core.resolve("graphics/plain.png"), png(64, 64, false));
        declare("{}");

        assertTrue(AssetLint.scan(temporaryDirectory, null).findings().isEmpty());
    }

    @Test
    void reportsAFileWhoseContentsAreNotTheFormatItsNameClaims() throws Exception {
        Path core = profile();
        Files.createDirectories(core.resolve("graphics"));
        Files.write(core.resolve("graphics/actually_a_jpeg.png"), jpeg(0xC0));
        declare("{}");

        AssetLint.Finding finding =
                only(AssetLint.scan(temporaryDirectory, null), "asset-extension-mismatch");

        assertEquals(AssetLint.Severity.INFO, finding.severity());
        assertTrue(finding.detail().contains("contents are JPEG, name says PNG"), finding.detail());
    }

    /** A minimal JPEG: SOI, then a start-of-frame carrying the dimensions. */
    private static byte[] jpeg(int startOfFrameMarker) {
        return new byte[] {
                (byte) 0xFF, (byte) 0xD8,
                (byte) 0xFF, (byte) startOfFrameMarker,
                0, 17,                     // segment length
                8,                         // sample precision
                0, 64, 0, 64,              // height, width
                3,                         // components
                1, 0x11, 0, 2, 0x11, 0, 3, 0x11, 0};
    }

    /**
     * A mod directory linted alone is missing the rest of the profile, and three rules need it.
     * Found by cross-checking: {@code knights_of_ludd} reports sixteen unreferenced sounds standalone
     * and none in the profile, because a companion mod declares them.
     */
    @Test
    void standaloneSuppressesTheRulesThatNeedAWholeProfile() throws Exception {
        Path directory = temporaryDirectory.resolve("MyMod");
        Files.createDirectories(directory.resolve("data/config"));
        Files.createDirectories(directory.resolve("graphics"));
        Files.writeString(directory.resolve("mod_info.json"), "{\"id\":\"mymod\"}");
        // Declared by nobody here, and declaring a file this directory does not ship.
        Files.createDirectories(directory.resolve("sounds"));
        Files.write(directory.resolve("sounds/orphan.ogg"), fixture("stereo-44100.ogg"));
        Files.writeString(directory.resolve("data/config/sounds.json"),
                "{\"a\":[{\"file\":\"sounds/sfx_ui/core_click.ogg\",\"volume\":1}]}");
        Files.write(directory.resolve("graphics/wide.png"), png(1_200, 1_200));

        AssetLint.Result result = AssetLint.scanStandalone(directory);

        assertEquals(List.of("texture-npot-padding"),
                result.findings().stream().map(AssetLint.Finding::rule).distinct().toList(),
                result.findings().toString());
        assertEquals("standalone", result.report().get("scope"));
        assertEquals(List.of("asset-shadowed", "sound-declared-missing", "audio-unreferenced"),
                result.report().get("rulesRequiringAProfile"));
    }

    @Test
    void standaloneNamesTheModByItsDeclaredIdRatherThanItsDirectory() throws Exception {
        Path directory = temporaryDirectory.resolve("SomeMod-1.2.3");
        Files.createDirectories(directory.resolve("graphics"));
        Files.writeString(directory.resolve("mod_info.json"), "{\"id\":\"somemod\"}");
        Files.write(directory.resolve("graphics/wide.png"), png(1_200, 1_200));

        AssetLint.Result result = AssetLint.scanStandalone(directory);

        assertEquals("somemod", result.findings().get(0).provider());
    }

    private Path mod(String id) throws IOException {
        Path directory = temporaryDirectory.resolve("mods").resolve(id);
        Files.createDirectories(directory.resolve("graphics"));
        Files.writeString(directory.resolve("mod_info.json"), "{\"id\":\"" + id + "\"}");
        Files.writeString(temporaryDirectory.resolve("mods/enabled_mods.json"),
                "{\"enabledMods\":[\"" + id + "\"]}");
        return directory;
    }

    // --- config syntax --------------------------------------------------------------------------

    @Test
    void reportsConfigurationStrandedOutsideTheTopLevelValue() throws Exception {
        Path core = profile();
        Files.createDirectories(core.resolve("data/weapons/proj"));
        Files.writeString(core.resolve("data/weapons/proj/msl.proj"), """
                {"id":"msl"}
                	"behaviorSpec":{"behavior":"PROXIMITY_FUSE"}
                """);

        AssetLint.Finding finding =
                only(AssetLint.scan(temporaryDirectory, null), "config-unread-content");

        assertEquals(AssetLint.Severity.ERROR, finding.severity());
        assertEquals(AssetLint.Cost.NONE, finding.cost());
        assertTrue(finding.detail().contains("behaviorSpec"), finding.detail());
    }

    @Test
    void reportsAConfigFileNoReaderCouldFinish() throws Exception {
        Path core = profile();
        Files.createDirectories(core.resolve("data/hulls"));
        Files.writeString(core.resolve("data/hulls/ship.ship"), "{\"id\":\"x\",\"slots\":[\n");

        AssetLint.Finding finding =
                only(AssetLint.scan(temporaryDirectory, null), "config-unparseable");

        assertEquals(AssetLint.Severity.ERROR, finding.severity());
    }

    /**
     * The dialect check that matters most in practice. These four shapes are all normal in shipping
     * mods, and a rule that fires on any of them would be reporting working files as broken.
     */
    @Test
    void staysSilentOnConfigTheGameAccepts() throws Exception {
        Path core = profile();
        Files.createDirectories(core.resolve("data/strings"));
        Files.writeString(core.resolve("data/strings/tips.json"), """
                {
                	tips:["a tip", # a comment
                	      "another",],
                }
                }
                """);

        List<AssetLint.Finding> findings = AssetLint.scan(temporaryDirectory, null).findings().stream()
                .filter(finding -> finding.rule().startsWith("config-"))
                .toList();

        assertTrue(findings.isEmpty(), "expected no config findings, got " + findings);
    }

    /**
     * A broken file belongs to the author who wrote it even when a later mod covers the path. Other
     * rules here are about bytes, where only the loaded copy costs anything; this one is about the
     * file being wrong.
     */
    @Test
    void reportsABrokenConfigAgainstEveryModThatShipsIt() throws Exception {
        Path core = profile();
        Path mod = temporaryDirectory.resolve("mods/alpha");
        Files.createDirectories(mod.resolve("data/hulls"));
        Files.writeString(mod.resolve("mod_info.json"), "{\"id\":\"alpha\",\"name\":\"Alpha\"}");
        Files.writeString(temporaryDirectory.resolve("mods/enabled_mods.json"),
                "{\"enabledMods\":[\"alpha\"]}");
        String broken = "{\"id\":\"x\"}\n\"stranded\":1\n";
        Files.createDirectories(core.resolve("data/hulls"));
        Files.writeString(core.resolve("data/hulls/ship.ship"), broken);
        Files.writeString(mod.resolve("data/hulls/ship.ship"), broken);

        List<String> providers = AssetLint.scan(temporaryDirectory, null).findings().stream()
                .filter(finding -> finding.rule().equals("config-unread-content"))
                .map(AssetLint.Finding::provider)
                .sorted()
                .toList();

        assertEquals(List.of("alpha", "core"), providers);
    }

    private AssetLint.Finding only(AssetLint.Result result, String rule) {
        List<AssetLint.Finding> matching = result.findings().stream()
                .filter(finding -> finding.rule().equals(rule))
                .toList();
        assertEquals(1, matching.size(), "expected one " + rule + ", got " + result.findings());
        return matching.get(0);
    }

    private Path profile() throws IOException {
        Path core = temporaryDirectory.resolve("starsector-core");
        Files.createDirectories(core.resolve("data/config"));
        Files.createDirectories(core.resolve("sounds"));
        Files.createDirectories(temporaryDirectory.resolve("mods"));
        Files.writeString(temporaryDirectory.resolve("mods/enabled_mods.json"), "{\"enabledMods\":[]}");
        return core;
    }

    private void declare(String soundsJson) throws IOException {
        Files.writeString(
                temporaryDirectory.resolve("starsector-core/data/config/sounds.json"), soundsJson);
    }

    private void put(Path root, String logicalPath, String fixtureName) throws IOException {
        Path file = root.resolve(logicalPath);
        Files.createDirectories(file.getParent());
        Files.write(file, fixture(fixtureName));
    }

    private static byte[] png(int width, int height) {
        return png(width, height, false);
    }

    /** A PNG with a valid header and no image data; the reader needs only IHDR. */
    private static byte[] png(int width, int height, boolean interlaced) {
        byte[] signature = {(byte) 0x89, 'P', 'N', 'G', '\r', '\n', 0x1a, '\n'};
        byte[] ihdr = new byte[25];
        write32(ihdr, 0, 13);
        ihdr[4] = 'I';
        ihdr[5] = 'H';
        ihdr[6] = 'D';
        ihdr[7] = 'R';
        write32(ihdr, 8, width);
        write32(ihdr, 12, height);
        ihdr[16] = 8;
        ihdr[17] = 6;
        ihdr[20] = (byte) (interlaced ? 1 : 0);
        CRC32 crc = new CRC32();
        crc.update(ihdr, 4, 17);
        write32(ihdr, 21, (int) crc.getValue());
        byte[] out = new byte[signature.length + ihdr.length];
        System.arraycopy(signature, 0, out, 0, signature.length);
        System.arraycopy(ihdr, 0, out, signature.length, ihdr.length);
        return out;
    }

    private static void write32(byte[] target, int offset, int value) {
        target[offset] = (byte) (value >>> 24);
        target[offset + 1] = (byte) (value >>> 16);
        target[offset + 2] = (byte) (value >>> 8);
        target[offset + 3] = (byte) value;
    }

    private static byte[] fixture(String name) throws IOException {
        String base = "/audio/ogg-v1/" + name + ".b64";
        InputStream single = AssetLintTest.class.getResourceAsStream(base);
        if (single != null) {
            try (single) {
                return Base64.getMimeDecoder().decode(single.readAllBytes());
            }
        }
        ByteArrayOutputStream encoded = new ByteArrayOutputStream();
        for (int i = 0; i < 100; i++) {
            InputStream part = AssetLintTest.class.getResourceAsStream(
                    base + ".part" + String.format("%02d", i));
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
