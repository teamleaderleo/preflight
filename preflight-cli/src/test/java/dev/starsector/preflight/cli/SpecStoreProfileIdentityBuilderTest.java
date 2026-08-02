package dev.starsector.preflight.cli;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;

import dev.starsector.preflight.core.ClasspathProfileIndex;
import dev.starsector.preflight.core.Hashes;
import dev.starsector.preflight.core.ResourceIndex;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class SpecStoreProfileIdentityBuilderTest {
    @TempDir
    Path temporaryDirectory;

    @Test
    void identityChangesWithDataGameCodeAndOrderedModArchives() throws Exception {
        Path install = temporaryDirectory.resolve("game");
        Path core = install.resolve("starsector-core");
        Path data = core.resolve("data/config/settings.json");
        Path gameJar = core.resolve("starfarer_obf.jar");
        Path modJar = temporaryDirectory.resolve("mod.jar");
        Files.createDirectories(data.getParent());
        Files.writeString(data, "one");
        Files.write(gameJar, new byte[] {1});
        Files.write(modJar, new byte[] {2});

        ResourceIndex resources = resources(core, data);
        ClasspathProfileIndex classpath = classpath(modJar, "mod");
        String baseline = SpecStoreProfileIdentityBuilder.build(install, resources, classpath)
                .identity().identitySha256();
        assertEquals(baseline, SpecStoreProfileIdentityBuilder.build(install, resources, classpath)
                .identity().identitySha256());

        Files.writeString(data, "two");
        assertNotEquals(baseline, SpecStoreProfileIdentityBuilder.build(
                install, resources(core, data), classpath).identity().identitySha256());
        Files.writeString(data, "one");
        Files.write(gameJar, new byte[] {3});
        assertNotEquals(baseline, SpecStoreProfileIdentityBuilder.build(
                install, resources(core, data), classpath).identity().identitySha256());
        Files.write(gameJar, new byte[] {1});
        assertNotEquals(baseline, SpecStoreProfileIdentityBuilder.build(
                install, resources(core, data), classpath(modJar, "other-mod")).identity().identitySha256());
    }

    private static ResourceIndex resources(Path root, Path data) throws Exception {
        return new ResourceIndex(
                "a".repeat(64),
                List.of(new ResourceIndex.Root("core", root, true)),
                Map.of("data/config/settings.json", List.of(new ResourceIndex.Provider(
                        0,
                        "data/config/settings.json",
                        Files.size(data),
                        Files.getLastModifiedTime(data).toMillis()))));
    }

    private static ClasspathProfileIndex classpath(Path jar, String modId) throws Exception {
        long size = Files.size(jar);
        return new ClasspathProfileIndex(
                "b".repeat(64),
                List.of(new ClasspathProfileIndex.Archive(
                        modId,
                        "jars/mod.jar",
                        jar,
                        Hashes.sha256(jar),
                        size,
                        Files.getLastModifiedTime(jar).toMillis(),
                        "classpath/archives/mod.spca",
                        true)),
                Map.of("example/Plugin.class", List.of(0)));
    }
}
