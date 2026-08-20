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

class JaninoProfileIdentityBuilderTest {
    @TempDir
    Path temporaryDirectory;

    @Test
    void isStableForTheSameCompleteCompilationProfile() throws Exception {
        Fixture fixture = Fixture.create(temporaryDirectory.resolve("stable"));

        JaninoProfileIdentityBuilder.Result first = fixture.build();
        JaninoProfileIdentityBuilder.Result repeated = fixture.build();

        assertEquals(first.context(), repeated.context());
        assertEquals(1, first.archiveCount());
        assertEquals(2, first.looseProviderCount());
        assertEquals(3, first.coreJarCount());
        assertEquals(fixture.modules.toAbsolutePath().normalize(), first.runtimeModules());
    }

    @Test
    void everyCompilationInputMovesItsPartOfTheIdentity() throws Exception {
        Fixture fixture = Fixture.create(temporaryDirectory.resolve("mutations"));
        var before = fixture.build().context();

        Files.writeString(fixture.looseJava, "package scripts; class Loose { int changed; }");
        var looseChanged = fixture.withCurrentResourceMetadata().build().context();
        assertNotEquals(before.orderedSourceGraphSha256(), looseChanged.orderedSourceGraphSha256());

        Files.writeString(fixture.modJar, "mod-jar-v2");
        var archiveChanged = fixture.withCurrentArchiveMetadata().build().context();
        assertNotEquals(looseChanged.orderedClasspathSha256(), archiveChanged.orderedClasspathSha256());
        assertNotEquals(looseChanged.orderedSourceGraphSha256(), archiveChanged.orderedSourceGraphSha256());

        Files.writeString(fixture.coreJar, "core-api-v2");
        var coreChanged = fixture.build().context();
        assertNotEquals(archiveChanged.parentLoaderIdentitySha256(), coreChanged.parentLoaderIdentitySha256());

        Files.writeString(fixture.janinoJar, "janino-v2");
        var janinoChanged = fixture.build().context();
        assertNotEquals(coreChanged.janinoImplementationSha256(), janinoChanged.janinoImplementationSha256());

        Files.writeString(fixture.modules, "runtime-image-v2");
        var runtimeChanged = fixture.build().context();
        assertNotEquals(janinoChanged.compilerOptionsSha256(), runtimeChanged.compilerOptionsSha256());
        assertNotEquals(janinoChanged.parentLoaderIdentitySha256(), runtimeChanged.parentLoaderIdentitySha256());
    }

    @Test
    void archiveOrderAndDeclarationModeArePartOfTheIdentity() throws Exception {
        Fixture fixture = Fixture.create(temporaryDirectory.resolve("order"));
        ClasspathProfileIndex.Archive archive = fixture.classpath.archives().get(0);
        Path secondJar = fixture.root.resolve("mods/second/jars/second.jar");
        Files.createDirectories(secondJar.getParent());
        Files.writeString(secondJar, "second");
        ClasspathProfileIndex.Archive second = new ClasspathProfileIndex.Archive(
                "second", "jars/second.jar", secondJar, Hashes.sha256(secondJar),
                Files.size(secondJar), Files.getLastModifiedTime(secondJar).toMillis(),
                "archives/second.jar-index.json", false);

        var original = fixture.build().context();
        fixture.classpath = new ClasspathProfileIndex(
                "22".repeat(32), List.of(second, archive), Map.of());
        var reordered = fixture.build().context();
        assertNotEquals(original.orderedClasspathSha256(), reordered.orderedClasspathSha256());

        fixture.classpath = new ClasspathProfileIndex(
                "33".repeat(32), List.of(new ClasspathProfileIndex.Archive(
                        archive.modId(), archive.relativePath(), archive.physicalPath(),
                        archive.sourceSha256(), archive.sourceBytes(), archive.modifiedMillis(),
                        archive.archiveIndexRelativePath(), false)), Map.of());
        var implicit = fixture.build().context();
        assertNotEquals(original.orderedClasspathSha256(), implicit.orderedClasspathSha256());
    }

    @Test
    void launcherContractIsPartOfTheParentLoaderIdentity() throws Exception {
        Fixture fixture = Fixture.create(temporaryDirectory.resolve("launcher-contract"));
        try (ProfileIdentityContext profile =
                     ProfileIdentityContext.of(fixture.root, fixture.resources)) {
            List<JaninoProfileIdentityBuilder.OrderedArchive> archives =
                    fixture.classpath.archives().stream()
                            .map(archive -> new JaninoProfileIdentityBuilder.OrderedArchive(
                                    archive.modId(), archive.relativePath(), archive.physicalPath(),
                                    archive.sourceBytes(), archive.declared()))
                            .toList();
            var first = JaninoProfileIdentityBuilder.build(
                    profile, archives, "51".repeat(32)).context();
            var changed = JaninoProfileIdentityBuilder.build(
                    profile, archives, "52".repeat(32)).context();
            assertNotEquals(first.parentLoaderIdentitySha256(), changed.parentLoaderIdentitySha256());
        }
    }

    private static final class Fixture {
        private final Path root;
        private final Path gameJar;
        private final Path janinoJar;
        private final Path coreJar;
        private final Path modules;
        private final Path modJar;
        private final Path looseJava;
        private final Path looseClass;
        private ResourceIndex resources;
        private ClasspathProfileIndex classpath;

        private Fixture(
                Path root,
                Path gameJar,
                Path janinoJar,
                Path coreJar,
                Path modules,
                Path modJar,
                Path looseJava,
                Path looseClass,
                ResourceIndex resources,
                ClasspathProfileIndex classpath) {
            this.root = root;
            this.gameJar = gameJar;
            this.janinoJar = janinoJar;
            this.coreJar = coreJar;
            this.modules = modules;
            this.modJar = modJar;
            this.looseJava = looseJava;
            this.looseClass = looseClass;
            this.resources = resources;
            this.classpath = classpath;
        }

        static Fixture create(Path root) throws Exception {
            Path java = root.resolve("starsector-core");
            Path mod = root.resolve("mods/example");
            Path gameJar = java.resolve("starfarer_obf.jar");
            Path janinoJar = java.resolve("janino.jar");
            Path coreJar = java.resolve("api.jar");
            Path modules = java.resolve("jre/lib/modules");
            Path modJar = mod.resolve("jars/example.jar");
            Path looseJava = mod.resolve("data/scripts/Loose.java");
            Path looseClass = mod.resolve("data/scripts/Already.class");
            Files.createDirectories(modJar.getParent());
            Files.createDirectories(looseJava.getParent());
            Files.createDirectories(modules.getParent());
            Files.writeString(gameJar, "game-v1");
            Files.writeString(janinoJar, "janino-v1");
            Files.writeString(coreJar, "core-api-v1");
            Files.writeString(modules, "runtime-image-v1");
            Files.writeString(modJar, "mod-jar-v1");
            Files.writeString(looseJava, "package scripts; class Loose {}");
            Files.writeString(looseClass, "class-v1");

            ResourceIndex resources = resources(java, mod, looseJava, looseClass);
            ClasspathProfileIndex classpath = classpath(modJar, true);
            return new Fixture(
                    root, gameJar, janinoJar, coreJar, modules, modJar, looseJava, looseClass,
                    resources, classpath);
        }

        Fixture withCurrentResourceMetadata() throws Exception {
            resources = resources(gameJar.getParent(), looseJava.getParent().getParent().getParent(),
                    looseJava, looseClass);
            return this;
        }

        Fixture withCurrentArchiveMetadata() throws Exception {
            classpath = classpath(modJar, true);
            return this;
        }

        JaninoProfileIdentityBuilder.Result build() throws Exception {
            try (ProfileIdentityContext profile = ProfileIdentityContext.of(root, resources)) {
                return JaninoProfileIdentityBuilder.build(profile, classpath);
            }
        }

        private static ResourceIndex resources(
                Path core, Path mod, Path looseJava, Path looseClass) throws Exception {
            ResourceIndex.Provider javaProvider = provider(1, mod, looseJava);
            ResourceIndex.Provider classProvider = provider(1, mod, looseClass);
            return new ResourceIndex(
                    "11".repeat(32),
                    List.of(new ResourceIndex.Root("core", core, true),
                            new ResourceIndex.Root("example", mod, false)),
                    Map.of(
                            "data/scripts/Loose.java", List.of(javaProvider),
                            "data/scripts/Already.class", List.of(classProvider)));
        }

        private static ResourceIndex.Provider provider(int rootIndex, Path root, Path file)
                throws Exception {
            OpenedFileGenerationAuthority.Generation generation = OpenedFileGenerationAuthority.capture(file);
            return new ResourceIndex.Provider(
                    rootIndex,
                    root.relativize(file).toString(),
                    Files.size(file),
                    Files.getLastModifiedTime(file).toMillis(),
                    generation.provider(),
                    generation.token());
        }

        private static ClasspathProfileIndex classpath(Path modJar, boolean declared)
                throws Exception {
            return new ClasspathProfileIndex(
                    "44".repeat(32),
                    List.of(new ClasspathProfileIndex.Archive(
                            "example", "jars/example.jar", modJar, Hashes.sha256(modJar),
                            Files.size(modJar), Files.getLastModifiedTime(modJar).toMillis(),
                            "archives/example.jar-index.json", declared)),
                    Map.of());
        }
    }
}
