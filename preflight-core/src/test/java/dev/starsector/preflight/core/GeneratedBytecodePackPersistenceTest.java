package dev.starsector.preflight.core;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.ByteArrayOutputStream;
import java.io.DataOutputStream;
import java.io.FilterInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class GeneratedBytecodePackPersistenceTest {
    @TempDir
    Path temporaryDirectory;

    @Test
    void refusesNamesThatCannotBeEncodedAsUtf8() throws Exception {
        String className = "x." + (char) 0xd800;
        GeneratedBytecodePack pack = pack(className, "x/" + (char) 0xd800);

        IOException error = assertThrows(IOException.class, () -> GeneratedBytecodePack.toBytes(pack));
        assertTrue(error.getMessage().contains("UTF-8"), error.getMessage());
    }

    @Test
    void rejectsGrowthDuringTheActualStreamRead() throws Exception {
        byte[] bytes = GeneratedBytecodePack.toBytes(pack("x.Y", "x/Y"));
        Path file = temporaryDirectory.resolve("growing.spjp");
        Files.write(file, bytes);
        boolean[] appended = {false};

        try (InputStream raw = Files.newInputStream(file);
             InputStream growing = new FilterInputStream(raw) {
                 @Override
                 public int read(byte[] buffer, int offset, int length) throws IOException {
                     int requested = appended[0] ? length : Math.min(1, length);
                     int read = super.read(buffer, offset, requested);
                     if (!appended[0] && read > 0) {
                         Files.write(file, new byte[] {0x55}, StandardOpenOption.APPEND);
                         appended[0] = true;
                     }
                     return read;
                 }
             }) {
            IOException error = assertThrows(
                    IOException.class,
                    () -> GeneratedBytecodePack.read(growing, bytes.length, file.toString()));
            assertTrue(appended[0]);
            assertTrue(error.getMessage().contains("byte safety limit"), error.getMessage());
        }
        assertEquals(bytes.length + 1L, Files.size(file));
    }

    private static GeneratedBytecodePack pack(String className, String internalName) throws IOException {
        GeneratedBytecodePack.Builder builder = new GeneratedBytecodePack.Builder("a".repeat(64));
        assertTrue(builder.record(className, Map.of(className, minimalClassfile(internalName))));
        GeneratedBytecodePack pack = builder.build();
        assertNotNull(pack);
        return pack;
    }

    private static byte[] minimalClassfile(String internalName) throws IOException {
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        try (DataOutputStream output = new DataOutputStream(bytes)) {
            output.writeInt(0xcafebabe);
            output.writeShort(0);
            output.writeShort(52);
            output.writeShort(3);
            output.writeByte(1);
            output.writeUTF(internalName);
            output.writeByte(7);
            output.writeShort(1);
            output.writeShort(0);
            output.writeShort(2);
        }
        return bytes.toByteArray();
    }
}
