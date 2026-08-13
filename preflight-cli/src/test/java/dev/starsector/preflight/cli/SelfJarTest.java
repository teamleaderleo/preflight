package dev.starsector.preflight.cli;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class SelfJarTest {
    @TempDir
    Path temporaryDirectory;

    @Test
    void reactorClassesMayUseAnExplicitPackagedAgentForProcessTests() throws Exception {
        Path packaged = temporaryDirectory.resolve("current-agent.jar").toAbsolutePath().normalize();
        Files.createFile(packaged);
        String previous = System.getProperty(SelfJar.CLASSES_OVERRIDE_PROPERTY);
        try {
            System.setProperty(SelfJar.CLASSES_OVERRIDE_PROPERTY, packaged.toString());
            assertEquals(packaged, SelfJar.locate());
        } finally {
            if (previous == null) {
                System.clearProperty(SelfJar.CLASSES_OVERRIDE_PROPERTY);
            } else {
                System.setProperty(SelfJar.CLASSES_OVERRIDE_PROPERTY, previous);
            }
        }
    }
}
