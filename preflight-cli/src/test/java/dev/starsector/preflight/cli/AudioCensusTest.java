package dev.starsector.preflight.cli;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Base64;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class AudioCensusTest {
    @TempDir
    Path temporaryDirectory;

    @Test
    void classifiesEffectsMusicAndUnreferencedFilesFromTheConfigRatherThanThePath() throws Exception {
        Path core = temporaryDirectory.resolve("starsector-core");
        Path mods = temporaryDirectory.resolve("mods");
        Path alpha = mods.resolve("Alpha");
        Files.createDirectories(core.resolve("data/config"));
        Files.createDirectories(alpha.resolve("data/config"));

        // A theme that lives outside any music directory, and an effect that lives inside one.
        put(core, "sounds/theme_in_the_wrong_place.ogg", "stereo-44100.ogg");
        put(core, "sounds/music/short_effect.ogg", "mono-22050.ogg");
        put(core, "sounds/never_declared.ogg", "silence-mono-8000.ogg");
        put(alpha, "sounds/alpha/music/track.ogg", "clipping-stereo-48000.ogg");

        Files.writeString(core.resolve("data/config/sounds.json"), """
                {
                  "music":{
                    "music_title":[
                      {"file":"sounds/theme_in_the_wrong_place.ogg","volume":1}
                    ]
                  },
                  "ui_click":[
                    {"file":"sounds/music/short_effect.ogg","volume":1}
                  ]
                }
                """);
        Files.writeString(alpha.resolve("data/config/sounds.json"), """
                {
                  "music":{
                    "music_alpha":[
                      {"file":"track.ogg","source":"sounds/alpha/music","volume":1}
                    ]
                  }
                }
                """);
        Files.writeString(alpha.resolve("mod_info.json"), "{\"id\":\"alpha\"}");
        Files.writeString(mods.resolve("enabled_mods.json"), "{\"enabledMods\":[\"alpha\"]}");

        AudioCensus.Result result = AudioCensus.scan(temporaryDirectory);

        assertEquals(AudioCensus.Kind.MUSIC, kind(result, "sounds/theme_in_the_wrong_place.ogg"),
                "a declared theme is music wherever it lives");
        assertEquals(AudioCensus.Kind.EFFECT, kind(result, "sounds/music/short_effect.ogg"),
                "a declared effect is an effect even under a music directory");
        assertEquals(AudioCensus.Kind.MUSIC, kind(result, "sounds/alpha/music/track.ogg"),
                "a directory source sweeps in the files beneath it");
        assertEquals(AudioCensus.Kind.UNREFERENCED, kind(result, "sounds/never_declared.ogg"));
    }

    /**
     * The point of the census is that decoded size is not a function of file size. Asserting the
     * exact PCM byte counts keeps it honest: these are the lengths the installed decoder produces
     * for these fixtures, and they bear no fixed ratio to the encoded bytes.
     */
    @Test
    void measuresDecodedBytesRatherThanScalingTheEncodedSize() throws Exception {
        Path core = writeMinimalProfile();
        put(core, "sounds/one.ogg", "mono-22050.ogg");
        put(core, "sounds/two.ogg", "stereo-44100.ogg");
        Files.writeString(core.resolve("data/config/sounds.json"), """
                {
                  "a":[{"file":"sounds/one.ogg","volume":1}],
                  "b":[{"file":"sounds/two.ogg","volume":1}]
                }
                """);

        AudioCensus.Result result = AudioCensus.scan(temporaryDirectory);

        assertEquals(4_096, sound(result, "sounds/one.ogg").decodedBytes());
        assertEquals(16_384, sound(result, "sounds/two.ogg").decodedBytes());
        assertEquals(4_285, sound(result, "sounds/one.ogg").encodedBytes());
        Map<String, Object> effects = totals(result, "effect");
        assertEquals(2, effects.get("files"));
        assertEquals(20_480L, effects.get("decodedBytes"));
        assertEquals(4_285L + 6_843L, effects.get("encodedBytes"));
    }

    /**
     * The reviewed profile ships a declared effect that is FLAC in an Ogg container and another that
     * is zero bytes. Both must be reported and excluded from the decoded total rather than crashing
     * the census or being silently counted as free.
     */
    @Test
    void reportsUndecodableFilesWithoutCountingThemAsDecodedBytes() throws Exception {
        Path core = writeMinimalProfile();
        put(core, "sounds/good.ogg", "mono-22050.ogg");
        Files.write(core.resolve("sounds/empty.ogg"), new byte[0]);
        Files.write(core.resolve("sounds/not-vorbis.ogg"), flacInOgg());
        Files.writeString(core.resolve("data/config/sounds.json"), """
                {
                  "a":[
                    {"file":"sounds/good.ogg","volume":1},
                    {"file":"sounds/empty.ogg","volume":1},
                    {"file":"sounds/not-vorbis.ogg","volume":1}
                  ]
                }
                """);

        AudioCensus.Result result = AudioCensus.scan(temporaryDirectory);

        assertFalse(sound(result, "sounds/empty.ogg").decodable());
        assertFalse(sound(result, "sounds/not-vorbis.ogg").decodable());
        assertTrue(sound(result, "sounds/good.ogg").decodable());
        assertEquals(0, sound(result, "sounds/not-vorbis.ogg").decodedBytes());
        Map<String, Object> effects = totals(result, "effect");
        assertEquals(3, effects.get("files"));
        assertEquals(2L, effects.get("undecodable"));
        assertEquals(4_096L, effects.get("decodedBytes"), "only the decodable file contributes");

        @SuppressWarnings("unchecked")
        Map<String, Object> undecodable = (Map<String, Object>) result.report().get("undecodable");
        assertEquals(2, undecodable.get("count"));
    }

    /** Only the override winner is measured; counting every provider would inflate the footprint. */
    @Test
    void measuresOnlyTheWinningProviderForEachLogicalPath() throws Exception {
        Path core = temporaryDirectory.resolve("starsector-core");
        Path mods = temporaryDirectory.resolve("mods");
        Path alpha = mods.resolve("Alpha");
        Files.createDirectories(core.resolve("data/config"));
        Files.createDirectories(alpha.resolve("sounds"));
        put(core, "sounds/shared.ogg", "mono-22050.ogg");
        put(alpha, "sounds/shared.ogg", "stereo-44100.ogg");
        Files.writeString(core.resolve("data/config/sounds.json"),
                "{\"a\":[{\"file\":\"sounds/shared.ogg\",\"volume\":1}]}");
        Files.writeString(alpha.resolve("mod_info.json"), "{\"id\":\"alpha\"}");
        Files.writeString(mods.resolve("enabled_mods.json"), "{\"enabledMods\":[\"alpha\"]}");

        AudioCensus.Result result = AudioCensus.scan(temporaryDirectory);

        assertEquals(1, result.sounds().size());
        assertEquals("alpha", sound(result, "sounds/shared.ogg").rootId());
        assertEquals(16_384, sound(result, "sounds/shared.ogg").decodedBytes());
    }

    @Test
    void reportsMalformedDeclarationsAsMissingInsteadOfAbortingTheScan() throws Exception {
        Path core = writeMinimalProfile();
        Files.writeString(core.resolve("data/config/sounds.json"), """
                {
                  "bad_effect":[{"file":"../outside.ogg","volume":1}],
                  "music":{"bad_music":[{"file":"C:/outside.ogg","volume":1}]}
                }
                """);

        AudioCensus.Result result = AudioCensus.scan(temporaryDirectory);

        assertEquals(List.of("../outside.ogg", "c:/outside.ogg"), result.missingDeclarations());
    }

    private Path writeMinimalProfile() throws IOException {
        Path core = temporaryDirectory.resolve("starsector-core");
        Path mods = temporaryDirectory.resolve("mods");
        Files.createDirectories(core.resolve("data/config"));
        Files.createDirectories(core.resolve("sounds"));
        Files.createDirectories(mods);
        Files.writeString(mods.resolve("enabled_mods.json"), "{\"enabledMods\":[]}");
        return core;
    }

    private void put(Path root, String logicalPath, String fixtureName) throws IOException {
        Path file = root.resolve(logicalPath);
        Files.createDirectories(file.getParent());
        Files.write(file, fixture(fixtureName));
    }

    /** A minimal Ogg page carrying a FLAC identification packet, as one reviewed mod ships. */
    private static byte[] flacInOgg() {
        byte[] header = "FLAC".getBytes(StandardCharsets.ISO_8859_1);
        byte[] page = new byte[27 + 1 + header.length];
        page[0] = 'O';
        page[1] = 'g';
        page[2] = 'g';
        page[3] = 'S';
        page[5] = 0x02;
        page[26] = 1;
        page[27] = (byte) header.length;
        System.arraycopy(header, 0, page, 28, header.length);
        return page;
    }

    private static AudioCensus.Kind kind(AudioCensus.Result result, String logicalPath) {
        return sound(result, logicalPath).kind();
    }

    private static AudioCensus.Sound sound(AudioCensus.Result result, String logicalPath) {
        Optional<AudioCensus.Sound> found = result.sounds().stream()
                .filter(candidate -> candidate.logicalPath().equals(logicalPath))
                .findFirst();
        return found.orElseThrow(() -> new AssertionError("No census row for " + logicalPath
                + "; rows: " + result.sounds().stream().map(AudioCensus.Sound::logicalPath).toList()));
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> totals(AudioCensus.Result result, String kind) {
        Map<String, Object> byKind = (Map<String, Object>) result.report().get("byKind");
        return (Map<String, Object>) byKind.get(kind);
    }

    private static byte[] fixture(String name) throws IOException {
        String base = "/audio/ogg-v1/" + name + ".b64";
        InputStream single = AudioCensusTest.class.getResourceAsStream(base);
        if (single != null) {
            try (single) {
                return Base64.getMimeDecoder().decode(single.readAllBytes());
            }
        }
        ByteArrayOutputStream encoded = new ByteArrayOutputStream();
        for (int i = 0; i < 100; i++) {
            InputStream part = AudioCensusTest.class.getResourceAsStream(
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
