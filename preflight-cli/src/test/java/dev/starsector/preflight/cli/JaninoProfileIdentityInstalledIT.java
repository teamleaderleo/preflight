package dev.starsector.preflight.cli;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.nio.file.Path;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;

/** Opt-in no-game proof that fast archive ordering equals the complete classpath index. */
class JaninoProfileIdentityInstalledIT {
    @Test
    void resourceIndexArchiveOrderEqualsTheInstalledClasspathIndex() throws Exception {
        String configured = System.getProperty("preflight.game");
        Assumptions.assumeTrue(configured != null && !configured.isBlank(),
                "set -Dpreflight.game=/path/to/Starsector");
        Path install = Path.of(configured).toAbsolutePath().normalize();
        Path cache = PrepareCommand.defaultCacheDirectory().toAbsolutePath().normalize();
        var resources = ResourceIndexBuilder.build(install).index();
        var classpath = ClasspathIndexBuilder.build(install, cache).profile();
        try (ProfileIdentityContext profile = ProfileIdentityContext.of(install, resources)) {
            long slowStarted = System.nanoTime();
            var indexed = JaninoProfileIdentityBuilder.build(profile, classpath);
            long fastStarted = System.nanoTime();
            var recovered = JaninoProfileIdentityBuilder.build(
                    profile, JaninoProfileIdentityBuilder.discoverOrderedArchives(profile));
            long finished = System.nanoTime();

            assertEquals(indexed.context(), recovered.context());
            assertEquals(indexed.archiveCount(), recovered.archiveCount());
            System.out.printf(java.util.Locale.ROOT,
                    "installed Janino identity: indexed %.1fms, recovered %.1fms, %d archives%n",
                    (fastStarted - slowStarted) / 1_000_000.0,
                    (finished - fastStarted) / 1_000_000.0,
                    recovered.archiveCount());
        }
    }
}
