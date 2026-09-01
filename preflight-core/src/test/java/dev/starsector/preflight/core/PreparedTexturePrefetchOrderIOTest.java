package dev.starsector.preflight.core;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;

import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class PreparedTexturePrefetchOrderIOTest {
    @TempDir
    Path temporaryDirectory;

    @Test
    void keepsPrefetchOrderSeparateFromGeneralAccessOrder() throws Exception {
        String profile = "ab".repeat(32);
        Path prefetch = PreparedTexturePrefetchOrderIO.path(temporaryDirectory, profile);
        Path access = PreparedTextureAccessOrderIO.path(temporaryDirectory, profile);

        assertNotEquals(access, prefetch);
        PreparedTexturePrefetchOrderIO.write(
                prefetch, profile, List.of("graphics/ships/first.png", "graphics/second.jpg"));

        assertEquals(
                List.of("graphics/ships/first.png", "graphics/second.jpg"),
                PreparedTexturePrefetchOrderIO.read(prefetch, profile));
    }
}
