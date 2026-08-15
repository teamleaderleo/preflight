package dev.starsector.preflight.cli;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.Locale;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class ChildProcessOutputTest {
    @TempDir
    Path temporaryDirectory;

    @Test
    void streamsBothChildChannelsAndRetainsBoundedFatalTail() throws Exception {
        boolean windows = System.getProperty("os.name", "").toLowerCase(Locale.ROOT).contains("win");
        Path java = Path.of(System.getProperty("java.home"), "bin", windows ? "java.exe" : "java");
        ProcessBuilder builder = new ProcessBuilder(
                java.toString(),
                "-cp",
                System.getProperty("java.class.path"),
                Emitter.class.getName());
        Path console = temporaryDirectory.resolve("run/console.txt");
        ByteArrayOutputStream streamed = new ByteArrayOutputStream();

        ChildProcessOutput.Result result;
        try (PrintStream operator = new PrintStream(streamed, true, StandardCharsets.UTF_8)) {
            result = ChildProcessOutput.run(builder, console, operator);
        }

        assertEquals(0, result.exitCode());
        assertEquals(console.toAbsolutePath().normalize(), result.file());
        assertTrue(Files.isRegularFile(console));
        assertTrue(result.truncated());
        // The retained window now spends part of itself on the head and on the line naming what was
        // dropped, so the assertion that matters is that the bound holds, not that it is hit exactly.
        assertTrue(
                result.capturedBytes() <= ChildProcessOutput.MAX_CAPTURE_BYTES,
                "captured " + result.capturedBytes());
        assertTrue(
                result.capturedBytes() > ChildProcessOutput.MAX_CAPTURE_BYTES - 512,
                "the window should still be nearly full: " + result.capturedBytes());
        assertTrue(result.totalBytes() > result.capturedBytes());
        assertEquals(result.capturedBytes(), Files.size(console));
        assertEquals(result.totalBytes(), streamed.size());
        String retained = Files.readString(console);
        assertTrue(retained.contains("FATAL com.fs.starfarer.launcher.opengl.GLLauncher"), retained);

        StarsectorRunLogEvidence.Evidence evidence = StarsectorRunLogEvidence.inspect(
                StarsectorRunLogEvidence.snapshot(temporaryDirectory), result);
        assertTrue(evidence.fatalDetected());
        assertTrue(evidence.truncated());
        assertEquals("launcher-fatal", evidence.matches().get(0).get("category"));
    }

    public static final class Emitter {
        private Emitter() {
        }

        public static void main(String[] args) throws Exception {
            byte[] block = new byte[64 * 1024];
            Arrays.fill(block, (byte) 'x');
            block[block.length - 1] = '\n';
            for (int i = 0; i < 12; i++) {
                System.out.write(block);
                System.err.write(block);
            }
            System.err.println(
                    "FATAL com.fs.starfarer.launcher.opengl.GLLauncher - java.lang.IllegalArgumentException: synthetic");
        }
    }
}
