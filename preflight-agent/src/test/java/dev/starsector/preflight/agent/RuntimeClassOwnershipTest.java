package dev.starsector.preflight.agent;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class RuntimeClassOwnershipTest {
    @TempDir
    Path temporaryDirectory;

    @Test
    void resolvesModJarToTopLevelModIdWithoutTakingNestedDependencyId() throws Exception {
        Path mod = temporaryDirectory.resolve("game/mods/Fancy Folder");
        Path jar = mod.resolve("jars/runtime.jar");
        Files.createDirectories(jar.getParent());
        Files.write(jar, new byte[] {1});
        Files.writeString(mod.resolve("mod_info.json"), """
                {
                  "dependencies": [{"id": "dependency-id"}],
                  "name": "Fancy Mod",
                  "id": "fancy_mod"
                }
                """);
        AdapterSourceIdentity source = new AdapterSourceIdentity(
                jar.toUri().toString(),
                jar.toAbsolutePath().normalize().toString(),
                "MOD", "", "", "loader/Mod", "mod-loader");

        Map<String, Object> ownership = RuntimeClassOwnership.resolve(
                "example.Callback", source).report();

        assertEquals("mod:fancy_mod", ownership.get("ownerKey"));
        assertEquals("MOD", ownership.get("ownerKind"));
        assertEquals("fancy_mod", ownership.get("ownerName"));
        assertEquals("fancy_mod", ownership.get("modId"));
        assertEquals("Fancy Folder", ownership.get("modDirectory"));
        assertEquals("runtime.jar", ownership.get("sourceArtifact"));
        assertEquals("mod-info-id", ownership.get("resolution"));
        assertTrue(ownership.get("modRoot").toString().endsWith("Fancy Folder"));
    }

    @Test
    void retainsEvidenceBackedModDirectoryWhenMetadataIdIsUnavailable() throws Exception {
        Path mod = temporaryDirectory.resolve("game/mods/Directory Only");
        Path jar = mod.resolve("runtime.jar");
        Files.createDirectories(mod);
        Files.write(jar, new byte[] {1});
        AdapterSourceIdentity source = new AdapterSourceIdentity(
                jar.toUri().toString(), jar.toString(), "MOD", "", "",
                "loader/Mod", "mod-loader");

        Map<String, Object> ownership = RuntimeClassOwnership.resolve(
                "example.Callback", source).report();

        assertEquals("MOD", ownership.get("ownerKind"));
        assertEquals("Directory Only", ownership.get("ownerName"));
        assertNull(ownership.get("modId"));
        assertEquals("mod-directory-only", ownership.get("resolution"));
    }

    @Test
    void keepsJaninoGeneratedClassesExplicitlyDynamicWhenOriginCannotBeProved() {
        AdapterSourceIdentity source = new AdapterSourceIdentity(
                "", "", "UNKNOWN", "", "",
                "org/codehaus/janino/ByteArrayClassLoader", "janino");

        Map<String, Object> ownership = RuntimeClassOwnership.resolve(
                "SC", source).report();

        assertEquals("dynamic:janino", ownership.get("ownerKey"));
        assertEquals("DYNAMIC_JANINO", ownership.get("ownerKind"));
        assertNull(ownership.get("modId"));
        assertEquals("dynamic-janino-origin-unresolved", ownership.get("resolution"));
    }

    @Test
    void jsonReaderRejectsNestedIdAsTopLevelOwnership() {
        assertEquals("actual", RuntimeClassOwnership.topLevelString(
                "{\"nested\":{\"id\":\"wrong\"},\"id\":\"actual\"}", "id"));
        assertEquals("", RuntimeClassOwnership.topLevelString(
                "{\"nested\":{\"id\":\"wrong\"}}", "id"));
    }
}
