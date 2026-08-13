package dev.starsector.preflight.agent;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class MagicLibPaintjobLoadRuntimeTest {
    @TempDir
    Path temporary;

    @AfterEach
    void reset() {
        MagicLibPaintjobLoadRuntime.reset();
        ResourceProbeRuntime.reset();
    }

    @Test
    void provesOnlyChildrenAbsentFromAReadableModRoot() throws Exception {
        Path config = temporary.resolve("data/config/paintjobs");
        Files.createDirectories(config);
        Files.writeString(config.resolve("present.paintjob"), "{}");
        FakeModSpec mod = new FakeModSpec(temporary.toString());

        assertFalse(MagicLibPaintjobLoadRuntime.knownMissing(
                "data/config/paintjobs/present.paintjob", mod));
        assertTrue(MagicLibPaintjobLoadRuntime.knownMissing(
                "data/config/paintjobs/absent.paintjob", mod));
        assertFalse(MagicLibPaintjobLoadRuntime.knownMissing("../outside.paintjob", mod));
    }

    @Test
    void unknownModApiShapeDelegates() {
        assertFalse(MagicLibPaintjobLoadRuntime.knownMissing(
                "data/config/paintjobs/absent.paintjob", new Object()));
    }

    public static final class FakeModSpec {
        private final String path;

        FakeModSpec(String path) {
            this.path = path;
        }

        public String getPath() {
            return path;
        }
    }
}
