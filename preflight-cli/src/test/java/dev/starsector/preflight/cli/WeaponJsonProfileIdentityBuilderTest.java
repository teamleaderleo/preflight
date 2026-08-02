package dev.starsector.preflight.cli;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;

import dev.starsector.preflight.core.ResourceIndex;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class WeaponJsonProfileIdentityBuilderTest {
    @TempDir
    Path temporaryDirectory;

    @Test
    void hashesOnlyOrderedWeaponProvidersAndTheGameJar() throws Exception {
        Path install = temporaryDirectory.resolve("game");
        Path java = install.resolve("starsector-core");
        Path core = temporaryDirectory.resolve("core");
        Path mod = temporaryDirectory.resolve("mod");
        Files.createDirectories(java);
        Files.createDirectories(core.resolve("data/weapons"));
        Files.createDirectories(mod.resolve("data/weapons"));
        Files.createDirectories(mod.resolve("data/shipsystems/wpn"));
        Files.writeString(java.resolve("starfarer_obf.jar"), "game");
        Files.writeString(core.resolve("data/weapons/a.wpn"), "core");
        Files.writeString(mod.resolve("data/weapons/a.wpn"), "override");
        Files.writeString(mod.resolve("data/shipsystems/wpn/system.wpn"), "system");
        Files.writeString(mod.resolve("unrelated.txt"), "one");

        ResourceIndex resources = index(core, mod);
        WeaponJsonProfileIdentityBuilder.Result baseline =
                WeaponJsonProfileIdentityBuilder.build(install, resources);
        assertEquals(2, baseline.logicalPaths());
        assertEquals(3, baseline.providerCount());
        assertEquals(baseline.identitySha256(),
                WeaponJsonProfileIdentityBuilder.build(install, resources).identitySha256());

        Files.writeString(mod.resolve("unrelated.txt"), "two");
        assertEquals(baseline.identitySha256(),
                WeaponJsonProfileIdentityBuilder.build(install, resources).identitySha256());

        Files.writeString(mod.resolve("data/weapons/a.wpn"), "changed!");
        ResourceIndex changed = index(core, mod);
        assertNotEquals(baseline.identitySha256(),
                WeaponJsonProfileIdentityBuilder.build(install, changed).identitySha256());

        String beforeGameChange =
                WeaponJsonProfileIdentityBuilder.build(install, changed).identitySha256();
        Files.writeString(java.resolve("starfarer_obf.jar"), "new-game");
        assertNotEquals(beforeGameChange,
                WeaponJsonProfileIdentityBuilder.build(install, changed).identitySha256());
    }

    private static ResourceIndex index(Path core, Path mod) throws Exception {
        Path coreFile = core.resolve("data/weapons/a.wpn");
        Path override = mod.resolve("data/weapons/a.wpn");
        Path system = mod.resolve("data/shipsystems/wpn/system.wpn");
        return new ResourceIndex(
                "a".repeat(64),
                List.of(
                        new ResourceIndex.Root("core", core, true),
                        new ResourceIndex.Root("mod", mod, false)),
                Map.of(
                        "data/weapons/a.wpn", List.of(
                                provider(0, "data/weapons/a.wpn", coreFile),
                                provider(1, "data/weapons/a.wpn", override)),
                        "data/shipsystems/wpn/system.wpn", List.of(
                                provider(1, "data/shipsystems/wpn/system.wpn", system))));
    }

    private static ResourceIndex.Provider provider(int root, String relative, Path file) throws Exception {
        return new ResourceIndex.Provider(
                root, relative, Files.size(file), Files.getLastModifiedTime(file).toMillis());
    }
}
