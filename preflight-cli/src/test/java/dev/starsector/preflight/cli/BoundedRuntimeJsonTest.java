package dev.starsector.preflight.cli;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import java.io.FilterInputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.Arrays;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

final class BoundedRuntimeJsonTest {
    private static final int LIMIT = 64 * 1024;

    @TempDir
    Path root;

    @Test
    void exactEncodedByteLimitIsInclusive() throws Exception {
        byte[] prefix = "{\"value\":\"".getBytes(StandardCharsets.UTF_8);
        byte[] suffix = "\"}".getBytes(StandardCharsets.UTF_8);
        byte[] bytes = new byte[LIMIT];
        System.arraycopy(prefix, 0, bytes, 0, prefix.length);
        Arrays.fill(bytes, prefix.length, bytes.length - suffix.length, (byte) 'a');
        System.arraycopy(suffix, 0, bytes, bytes.length - suffix.length, suffix.length);
        Path file = Files.write(root.resolve("exact.json"), bytes);

        Map<String, Object> value = BoundedRuntimeJson.readObject(file, LIMIT, "runtime JSON");

        assertEquals(LIMIT, Files.size(file));
        assertEquals(LIMIT - prefix.length - suffix.length, ((String) value.get("value")).length());
    }

    @Test
    void initiallyOversizedInputIsRefusedBeforeOpen() throws Exception {
        Path file = Files.write(root.resolve("oversized.json"), new byte[] {'{', '}'});
        AtomicBoolean opened = new AtomicBoolean();

        assertThrows(IOException.class, () -> BoundedRuntimeJson.readObject(
                file, 1, "runtime JSON", ignored -> opened.set(true), input -> input));

        assertTrue(!opened.get());
    }

    @Test
    void growthDuringOpenedReadIsRejectedAtLimitPlusOne() throws Exception {
        byte[] bytes = "{\"value\":\"a\"}".getBytes(StandardCharsets.UTF_8);
        Path file = Files.write(root.resolve("growing.json"), bytes);
        AtomicBoolean appended = new AtomicBoolean();

        assertThrows(IOException.class, () -> BoundedRuntimeJson.readObject(
                file,
                bytes.length,
                "runtime JSON",
                ignored -> {},
                raw -> new FilterInputStream(raw) {
                    @Override
                    public int read(byte[] buffer, int offset, int length) throws IOException {
                        int requested = appended.get() ? length : Math.min(1, length);
                        int read = super.read(buffer, offset, requested);
                        if (!appended.get() && read > 0) {
                            Files.write(file, new byte[] {' '}, StandardOpenOption.APPEND);
                            appended.set(true);
                        }
                        return read;
                    }
                }));

        assertTrue(appended.get());
        assertEquals(bytes.length + 1L, Files.size(file));
    }

    @Test
    void finalComponentSymlinkReplacementIsRejectedAtActualOpen() throws Exception {
        Path file = Files.writeString(root.resolve("runtime.json"), "{}", StandardCharsets.UTF_8);
        Path replacement = Files.writeString(root.resolve("replacement.json"), "{}", StandardCharsets.UTF_8);
        Path probe = root.resolve("probe");
        try {
            Files.createSymbolicLink(probe, replacement.getFileName());
        } catch (UnsupportedOperationException | IOException unavailable) {
            assumeTrue(false, "Symbolic links are unavailable: " + unavailable.getMessage());
        }
        Files.delete(probe);

        assertThrows(IOException.class, () -> BoundedRuntimeJson.readObject(
                file,
                LIMIT,
                "runtime JSON",
                path -> {
                    Files.delete(path);
                    Files.createSymbolicLink(path, replacement.getFileName());
                },
                input -> input));

        assertTrue(Files.isSymbolicLink(file));
    }

    @Test
    void malformedUtf8KeepsCallerFailureSemantics() throws Exception {
        Path run = Files.createDirectories(root.resolve("heartbeat-run"));
        Files.write(run.resolve(LaunchHeartbeat.FILE_NAME), new byte[] {(byte) 0x80});
        assertNull(LaunchHeartbeat.read(run));

        Path runtime = Files.write(root.resolve("runtime-process.json"), new byte[] {(byte) 0x80});
        IOException error = assertThrows(
                IOException.class, () -> RuntimeProcessIdentity.read(runtime));
        assertTrue(error.getMessage().contains("UTF-8"));
    }
}
