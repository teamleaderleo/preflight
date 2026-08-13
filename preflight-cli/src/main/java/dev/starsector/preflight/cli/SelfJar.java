package dev.starsector.preflight.cli;

import java.net.URISyntaxException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Locale;

final class SelfJar {
    static final String CLASSES_OVERRIDE_PROPERTY = "dev.starsector.preflight.test.selfJar";

    private SelfJar() {
    }

    static Path locate() {
        try {
            Path location = Path.of(PreflightCli.class.getProtectionDomain().getCodeSource().getLocation().toURI())
                    .toAbsolutePath()
                    .normalize();
            if (Files.isDirectory(location)) {
                String override = System.getProperty(CLASSES_OVERRIDE_PROPERTY);
                if (override != null && !override.isBlank()) {
                    Path candidate = Path.of(override).toAbsolutePath().normalize();
                    if (Files.isRegularFile(candidate)
                            && candidate.getFileName().toString().toLowerCase(Locale.ROOT).endsWith(".jar")) {
                        return candidate;
                    }
                    throw new IllegalStateException("Preflight test self-JAR is not a packaged JAR: " + candidate);
                }
            }
            if (!Files.isRegularFile(location)
                    || !location.getFileName().toString().toLowerCase(Locale.ROOT).endsWith(".jar")) {
                throw new IllegalStateException(
                        "Preflight is running from classes instead of its packaged JAR; run `mvn package` and use preflight-cli/target/preflight.jar");
            }
            return location;
        } catch (URISyntaxException error) {
            throw new IllegalStateException("Could not locate the Preflight JAR", error);
        }
    }
}
