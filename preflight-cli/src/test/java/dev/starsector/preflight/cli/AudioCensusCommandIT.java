package dev.starsector.preflight.cli;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Base64;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class AudioCensusCommandIT {
    @TempDir
    Path temporaryDirectory;

    @Test
    void censusReportsBothOutputsAndMakesNoEligibilityDecision() throws Exception {
        Path game = buildProfile();
        Path report = temporaryDirectory.resolve("out").resolve("audio-census.json");
        Path csv = temporaryDirectory.resolve("out").resolve("sounds.csv");

        int exit = AudioCensusCommand.execute(
                new String[] {"--game", game.toString(), "--output", report.toString(), "--csv", csv.toString()},
                0);

        assertEquals(0, exit);
        String json = Files.readString(report);
        assertTrue(json.contains("\"reportFormat\":\"starsector-preflight-audio-census-v1\""), json);
        assertTrue(json.contains("\"measurementBasis\":\"final-ogg-page-granule-position\""), json);
        assertTrue(json.contains("\"soundsConfigsRead\":1"), json);

        // The eligible set is the two declared effects; the music entry and the undeclared file are not.
        assertTrue(json.contains("\"effect\":{\"files\":2,\"encodedBytes\":11128,\"decodedBytes\":20480"), json);
        assertTrue(json.contains("\"music\":{\"files\":1"), json);
        assertTrue(json.contains("\"unreferenced\":{\"files\":1"), json);

        // A census observes; it does not prepare, cache, or approve anything.
        assertFalse(json.contains("preparedAudioWritesEnabled"), json);
        assertFalse(json.contains("eligibilityEstablished"), json);

        List<String> rows = Files.readAllLines(csv);
        assertEquals(5, rows.size(), rows.toString());
        assertEquals("path,provider,kind,codec,channels,sampleRate,frames,seconds,encodedBytes,"
                + "decodedBytes,decodable", rows.get(0));
        assertTrue(rows.stream().anyMatch(row -> row.startsWith("\"sounds/effect-one.ogg\",\"core\",effect,")), rows.toString());
        assertTrue(rows.stream().anyMatch(row -> row.contains(",music,")), rows.toString());
        assertTrue(rows.stream().anyMatch(row -> row.contains(",unreferenced,")), rows.toString());
    }

    @Test
    void writesNothingWhenNoOutputIsRequested() throws Exception {
        Path game = buildProfile();

        assertEquals(0, AudioCensusCommand.execute(new String[] {"--game", game.toString()}, 0));

        try (var entries = Files.list(temporaryDirectory)) {
            assertTrue(entries.allMatch(path -> path.getFileName().toString().equals("game")),
                    "the census must not leave files behind unless asked");
        }
    }

    @Test
    void rejectsAnUnknownOptionRatherThanIgnoringIt() {
        assertThrows(IllegalArgumentException.class,
                () -> AudioCensusCommand.execute(new String[] {"--prepare"}, 0));
        assertThrows(IllegalArgumentException.class,
                () -> AudioCensusCommand.execute(new String[] {"--output"}, 0));
    }

    private Path buildProfile() throws IOException {
        Path game = temporaryDirectory.resolve("game");
        Path core = game.resolve("starsector-core");
        Path mods = game.resolve("mods");
        Files.createDirectories(core.resolve("data/config"));
        Files.createDirectories(mods);
        Files.writeString(mods.resolve("enabled_mods.json"), "{\"enabledMods\":[]}");

        write(core, "sounds/effect-one.ogg", "mono-22050.ogg");
        write(core, "sounds/effect-two.ogg", "stereo-44100.ogg");
        write(core, "sounds/theme.ogg", "clipping-stereo-48000.ogg");
        write(core, "sounds/orphan.ogg", "silence-mono-8000.ogg");
        Files.writeString(core.resolve("data/config/sounds.json"), """
                {
                  "music":{
                    "music_title":[
                      {"file":"sounds/theme.ogg","volume":1}
                    ]
                  },
                  "ui_click":[
                    {"file":"sounds/effect-one.ogg","volume":1},
                    {"file":"sounds/effect-two.ogg","volume":1}
                  ]
                }
                """);
        return game;
    }

    private void write(Path root, String logicalPath, String fixtureName) throws IOException {
        Path file = root.resolve(logicalPath);
        Files.createDirectories(file.getParent());
        Files.write(file, fixture(fixtureName));
    }

    private static byte[] fixture(String name) throws IOException {
        String base = "/audio/ogg-v1/" + name + ".b64";
        InputStream single = AudioCensusCommandIT.class.getResourceAsStream(base);
        if (single != null) {
            try (single) {
                return Base64.getMimeDecoder().decode(single.readAllBytes());
            }
        }
        ByteArrayOutputStream encoded = new ByteArrayOutputStream();
        for (int i = 0; i < 100; i++) {
            InputStream part = AudioCensusCommandIT.class.getResourceAsStream(
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
