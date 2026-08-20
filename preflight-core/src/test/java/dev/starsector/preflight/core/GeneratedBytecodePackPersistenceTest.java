package dev.starsector.preflight.core;

import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.ByteArrayOutputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.util.Map;
import org.junit.jupiter.api.Test;

class GeneratedBytecodePackPersistenceTest {
    @Test
    void refusesNamesThatCannotBeEncodedAsUtf8() throws Exception {
        String className = "x." + (char) 0xd800;
        GeneratedBytecodePack.Builder builder = new GeneratedBytecodePack.Builder("a".repeat(64));
        assertTrue(builder.record(className, Map.of(className, minimalClassfile("x/" + (char) 0xd800))));
        GeneratedBytecodePack pack = builder.build();
        assertNotNull(pack);

        IOException error = assertThrows(IOException.class, () -> GeneratedBytecodePack.toBytes(pack));
        assertTrue(error.getMessage().contains("UTF-8"), error.getMessage());
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
