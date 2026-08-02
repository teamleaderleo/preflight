package dev.starsector.preflight.cli;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;

import dev.starsector.preflight.core.ResourceIndex;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class VariantJsonProfileIdentityBuilderTest {
    @TempDir
    Path temporaryDirectory;

    @Test
    void hashesGameAndOrderedVariantProvidersButIgnoresUnrelatedData() throws Exception {
        Path install = temporaryDirectory.resolve("game");
        Path core = install.resolve("starsector-core");
        Path mod = temporaryDirectory.resolve("mod");
        Path gameJar = core.resolve("starfarer_obf.jar");
        Path coreVariant = core.resolve("data/variants/example.variant");
        Path modVariant = mod.resolve("data/variants/example.variant");
        Path settings = mod.resolve("data/config/settings.json");
        Files.createDirectories(coreVariant.getParent());
        Files.createDirectories(modVariant.getParent());
        Files.createDirectories(settings.getParent());
        Files.write(gameJar, new byte[] {1});
        Files.writeString(coreVariant, "core");
        Files.writeString(modVariant, "mod");
        Files.writeString(settings, "one");

        ResourceIndex resources = resources(core, mod, coreVariant, modVariant, settings, false);
        VariantJsonProfileIdentityBuilder.Result baseline =
                VariantJsonProfileIdentityBuilder.build(install, resources);
        assertEquals(1, baseline.logicalPaths());
        assertEquals(2, baseline.providerCount());
        assertEquals(7, baseline.providerBytes());
        assertEquals(baseline.identitySha256(),
                VariantJsonProfileIdentityBuilder.build(install, resources).identitySha256());

        Files.writeString(settings, "unrelated-change");
        assertEquals(baseline.identitySha256(), VariantJsonProfileIdentityBuilder.build(
                install, resources(core, mod, coreVariant, modVariant, settings, false)).identitySha256());

        Files.writeString(modVariant, "changed");
        assertNotEquals(baseline.identitySha256(), VariantJsonProfileIdentityBuilder.build(
                install, resources(core, mod, coreVariant, modVariant, settings, false)).identitySha256());
        Files.writeString(modVariant, "mod");

        assertNotEquals(baseline.identitySha256(), VariantJsonProfileIdentityBuilder.build(
                install, resources(core, mod, coreVariant, modVariant, settings, true)).identitySha256());
        Files.write(gameJar, new byte[] {2});
        assertNotEquals(baseline.identitySha256(), VariantJsonProfileIdentityBuilder.build(
                install, resources(core, mod, coreVariant, modVariant, settings, false)).identitySha256());
    }

    private static ResourceIndex resources(
            Path core,
            Path mod,
            Path coreVariant,
            Path modVariant,
            Path settings,
            boolean reverseRoots) throws Exception {
        List<ResourceIndex.Root> roots = reverseRoots
                ? List.of(new ResourceIndex.Root("mod", mod, false), new ResourceIndex.Root("core", core, true))
                : List.of(new ResourceIndex.Root("core", core, true), new ResourceIndex.Root("mod", mod, false));
        int coreIndex = reverseRoots ? 1 : 0;
        int modIndex = reverseRoots ? 0 : 1;
        List<ResourceIndex.Provider> variants = reverseRoots
                ? List.of(provider(modIndex, mod, modVariant), provider(coreIndex, core, coreVariant))
                : List.of(provider(coreIndex, core, coreVariant), provider(modIndex, mod, modVariant));
        Map<String, List<ResourceIndex.Provider>> entries = new LinkedHashMap<>();
        entries.put("data/variants/example.variant", variants);
        entries.put("data/config/settings.json", List.of(provider(modIndex, mod, settings)));
        return new ResourceIndex("a".repeat(64), roots, entries);
    }

    private static ResourceIndex.Provider provider(int root, Path rootPath, Path file) throws Exception {
        return new ResourceIndex.Provider(
                root,
                rootPath.relativize(file).toString(),
                Files.size(file),
                Files.getLastModifiedTime(file).toMillis());
    }
}
