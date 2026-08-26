package dev.starsector.preflight.agent;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.net.URLClassLoader;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;

/** Opt-in identity and API-shape check against the installed Console Commands archive. */
class ConsoleCombatFixtureRuntimeInstalledIT {
    @Test
    void exactInstalledConsoleArchiveExposesOnlyTheReviewedCommandBoundary() throws Exception {
        String configured = System.getProperty("preflight.console.jar", "").trim();
        Assumptions.assumeTrue(!configured.isEmpty(),
                "set -Dpreflight.console.jar=<lw_Console.jar>");
        Path archive = Path.of(configured).toAbsolutePath().normalize();
        Assumptions.assumeTrue(Files.isRegularFile(archive));
        Path install = archive.getParent().getParent().getParent().getParent();
        ArrayList<java.net.URL> classpath = new ArrayList<>();
        classpath.add(archive.toUri().toURL());
        try (var files = Files.list(install.resolve("Contents/Resources/Java"))) {
            files.filter(path -> path.getFileName().toString().endsWith(".jar"))
                    .sorted(Comparator.comparing(Path::toString))
                    .forEach(path -> add(classpath, path));
        }
        try (var files = Files.walk(install.resolve("mods/LazyLib-3.0.0/jars"), 2)) {
            files.filter(path -> path.getFileName().toString().endsWith(".jar"))
                    .sorted(Comparator.comparing(Path::toString))
                    .forEach(path -> add(classpath, path));
        }
        try (URLClassLoader loader = new URLClassLoader(
                classpath.toArray(java.net.URL[]::new),
                ClassLoader.getPlatformClassLoader())) {
            ConsoleCombatFixtureRuntime.Api api =
                    ConsoleCombatFixtureRuntime.verifyConsoleApi(loader, archive);
            assertEquals("org.lazywizard.console.Console", api.console().getName());
            assertEquals("org.lazywizard.console.BaseCommand$CommandContext", api.context().getName());
            assertEquals("org.lazywizard.console.BaseCommand$CommandResult", api.result().getName());
        }
    }

    private static void add(ArrayList<java.net.URL> classpath, Path path) {
        try {
            classpath.add(path.toUri().toURL());
        } catch (java.net.MalformedURLException failure) {
            throw new IllegalArgumentException(failure);
        }
    }
}
