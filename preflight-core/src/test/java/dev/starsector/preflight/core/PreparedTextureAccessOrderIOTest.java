package dev.starsector.preflight.core;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;

import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class PreparedTextureAccessOrderIOTest {
    @TempDir
    Path temporaryDirectory;

    @Test
    void keepsLogicalAccessSeparateFromPhysicalPackOrder() throws Exception {
        String profile = "ab".repeat(32);
        Path access = PreparedTextureAccessOrderIO.path(temporaryDirectory, profile);
        Path pack = PreparedTexturePackOrderIO.path(temporaryDirectory, profile);

        assertNotEquals(pack, access);
        PreparedTextureAccessOrderIO.write(
                access, profile, List.of("graphics/ships/first.png", "graphics/second.jpg"));

        assertEquals(
                List.of("graphics/ships/first.png", "graphics/second.jpg"),
                PreparedTextureAccessOrderIO.read(access, profile));
    }
}
