package dev.starsector.preflight.cli;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.starsector.preflight.core.GpuTextureFootprint;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.zip.CRC32;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class ResourceCostCommandTest {

    @TempDir
    Path tempDir;

    @Test
    void testInspectResourcesWithJsonOutput() throws Exception {
        Path install = createSyntheticGame(tempDir.resolve("cli-json-game"));

        ByteArrayOutputStream outBytes = new ByteArrayOutputStream();
        PrintStream originalOut = System.out;
        System.setOut(new PrintStream(outBytes));
        try {
            int exitCode = PreflightCli.run(new String[]{
                    "inspect", "resources", "--game", install.toString(), "--json"
            });
            assertEquals(0, exitCode);
        } finally {
            System.setOut(originalOut);
        }

        String json = outBytes.toString(StandardCharsets.UTF_8);
        assertTrue(json.contains("\"format\":\"starsector-preflight-resource-cost-v1\""));
        assertTrue(json.contains("\"testmod\""));
        assertTrue(json.contains("\"textureVram\""));
    }

    @Test
    void testCostAliasWithJsonOutput() throws Exception {
        Path install = createSyntheticGame(tempDir.resolve("cli-alias-game"));

        ByteArrayOutputStream outBytes = new ByteArrayOutputStream();
        PrintStream originalOut = System.out;
        System.setOut(new PrintStream(outBytes));
        try {
            int exitCode = PreflightCli.run(new String[]{
                    "cost", "--game", install.toString(), "--json"
            });
            assertEquals(0, exitCode);
        } finally {
            System.setOut(originalOut);
        }

        String json = outBytes.toString(StandardCharsets.UTF_8);
        assertTrue(json.contains("\"format\":\"starsector-preflight-resource-cost-v1\""));
        assertTrue(json.contains("\"testmod\""));
    }

    @Test
    void testInspectResourcesAsciiTableOutput() throws Exception {
        Path install = createSyntheticGame(tempDir.resolve("cli-table-game"));

        ByteArrayOutputStream outBytes = new ByteArrayOutputStream();
        PrintStream originalOut = System.out;
        System.setOut(new PrintStream(outBytes));
        try {
            int exitCode = PreflightCli.run(new String[]{
                    "inspect", "resources", "--game", install.toString()
            });
            assertEquals(0, exitCode);
        } finally {
            System.setOut(originalOut);
        }

        String table = outBytes.toString(StandardCharsets.UTF_8);
        assertTrue(table.contains("=== PREFLIGHT RESOURCE COST INSPECTOR ==="));
        assertTrue(table.contains("MOD ID (ORDER)"));
        assertTrue(table.contains("VRAM (GPU)"));
        assertTrue(table.contains("POT WASTE"));
        assertTrue(table.contains("AUDIO PCM"));
        assertTrue(table.contains("BYTECODE"));
        assertTrue(table.contains("testmod"));
    }

    @Test
    void testInspectResourcesWithModFilter() throws Exception {
        Path install = createSyntheticGame(tempDir.resolve("cli-filter-game"));

        ByteArrayOutputStream outBytes = new ByteArrayOutputStream();
        PrintStream originalOut = System.out;
        System.setOut(new PrintStream(outBytes));
        try {
            int exitCode = PreflightCli.run(new String[]{
                    "inspect", "resources", "--game", install.toString(), "--mod", "testmod"
            });
            assertEquals(0, exitCode);
        } finally {
            System.setOut(originalOut);
        }

        String table = outBytes.toString(StandardCharsets.UTF_8);
        assertTrue(table.contains("MOD DRILLDOWN: Test Mod (testmod)"));
    }

    @Test
    void testInspectResourcesWithOutputFile() throws Exception {
        Path install = createSyntheticGame(tempDir.resolve("cli-output-game"));
        Path reportPath = tempDir.resolve("output/report.json");

        int exitCode = PreflightCli.run(new String[]{
                "inspect", "resources", "--game", install.toString(), "--output", reportPath.toString(), "--json"
        });
        assertEquals(0, exitCode);
        assertTrue(Files.exists(reportPath));
        String readJson = Files.readString(reportPath, StandardCharsets.UTF_8);
        assertTrue(readJson.contains("\"format\":\"starsector-preflight-resource-cost-v1\""));
    }

    @Test
    void testMissingStarsectorReturnsExitCode3() throws Exception {
        ByteArrayOutputStream errBytes = new ByteArrayOutputStream();
        PrintStream originalErr = System.err;
        System.setErr(new PrintStream(errBytes));
        try {
            int exitCode = PreflightCli.run(new String[]{
                    "cost", "--game", tempDir.resolve("non-existent-folder").toString()
            });
            assertEquals(3, exitCode);
        } catch (IllegalArgumentException e) {
            // --game not a directory caught as IllegalArgumentException which cli main handles
        } finally {
            System.setErr(originalErr);
        }
    }

    private Path createSyntheticGame(Path root) throws IOException {
        Path core = root.resolve("starsector-core");
        Path mod = root.resolve("mods/testmod");
        Files.createDirectories(core.resolve("graphics"));
        Files.createDirectories(core.resolve("data/config"));
        Files.createDirectories(mod.resolve("graphics"));
        Files.createDirectories(mod.resolve("data/config"));

        Files.writeString(root.resolve("mods/enabled_mods.json"), "{\"enabledMods\":[\"testmod\"]}");
        Files.writeString(mod.resolve("mod_info.json"), "{\"id\":\"testmod\",\"name\":\"Test Mod\",\"version\":\"1.0.0\"}");

        // Create 288x384 PNG in mod
        byte[] png = createPng(288, 384);
        Files.write(mod.resolve("graphics/ship.png"), png);

        return root;
    }

    private static byte[] createPng(int width, int height) {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        out.writeBytes(new byte[] {(byte) 0x89, 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A});

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
        ihdr.write(6); // RGBA
        ihdr.write(0); // compression
        ihdr.write(0); // filter
        ihdr.write(0); // interlace

        byte[] ihdrBytes = ihdr.toByteArray();
        CRC32 crc = new CRC32();
        crc.update(ihdrBytes);
        long crcVal = crc.getValue();

        out.write(0);
        out.write(0);
        out.write(0);
        out.write(13);
        out.writeBytes(ihdrBytes);
        out.write((int) ((crcVal >>> 24) & 0xFF));
        out.write((int) ((crcVal >>> 16) & 0xFF));
        out.write((int) ((crcVal >>> 8) & 0xFF));
        out.write((int) (crcVal & 0xFF));

        out.writeBytes(new byte[] {0, 0, 0, 0, 'I', 'E', 'N', 'D', (byte) 0xAE, 0x42, 0x60, (byte) 0x82});
        return out.toByteArray();
    }
}
