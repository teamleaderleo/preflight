package dev.starsector.preflight.cli;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class TexturePreparationReceiptTest {
    @TempDir
    Path directory;

    @Test
    void roundTripsTheExactProfileStorageAndScope() throws Exception {
        String profile = "a".repeat(64);
        TexturePreparationReceipt.write(
                directory,
                profile,
                TextureStoragePolicy.BALANCED,
                TexturePreparationScope.LEARNED);

        TexturePreparationReceipt.Receipt receipt = TexturePreparationReceipt.read(
                TexturePreparationReceipt.path(directory, profile), profile);

        assertEquals(TextureStoragePolicy.BALANCED, receipt.storage());
        assertEquals(TexturePreparationScope.LEARNED, receipt.scope());
    }

    @Test
    void rejectsAReceiptFromAnotherProfile() throws Exception {
        String profile = "a".repeat(64);
        Path receipt = TexturePreparationReceipt.path(directory, profile);
        Files.createDirectories(receipt.getParent());
        Files.writeString(receipt, """
                {"format":"starsector-preflight-texture-preparation-v1",\
                "profileFingerprint":"%s","textureStorage":"balanced","textureScope":"full"}
                """.formatted("b".repeat(64)));

        assertThrows(IOException.class, () -> TexturePreparationReceipt.read(receipt, profile));
    }
}
