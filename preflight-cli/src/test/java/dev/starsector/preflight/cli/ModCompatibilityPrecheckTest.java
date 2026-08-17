package dev.starsector.preflight.cli;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.starsector.preflight.core.ModCompatibilityReadiness;
import dev.starsector.preflight.core.ModCompatibilityReadiness.ReasonCode;
import dev.starsector.preflight.core.ModCompatibilityReadiness.Severity;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class ModCompatibilityPrecheckTest {
    @TempDir Path temp;

    @Test
    void reportsMissingRequiredDependency() throws Exception {
        Path mods = mods();
        mod(mods, "consumer", """
                {"id":"consumer","name":"Consumer","version":"1.0.0","gameVersion":"0.98a-RC8",
                 "dependencies":[{"id":"library","name":"Library"}]}
                """);

        var result = ModCompatibilityPrecheck.inspect(mods, List.of("consumer"), "0.98a-RC8", 4096L);

        assertReason(result, ReasonCode.REQUIRED_DEPENDENCY_MISSING);
        assertTrue(result.hasErrors());
    }

    @Test
    void disabledDependencyProducesDeterministicReviewedPreviewWithoutMutation() throws Exception {
        Path mods = mods();
        mod(mods, "consumer", """
                {"id":"consumer","name":"Consumer","version":"1.0.0","gameVersion":"0.98a-RC8",
                 "dependencies":[{"id":"library"}]}
                """);
        mod(mods, "library", """
                {"id":"library","name":"Library","version":"2.0.0","gameVersion":"0.98a-RC8"}
                """);
        Path enabled = temp.resolve("enabled_mods.json");
        Files.writeString(enabled, "{\"enabledMods\":[\"consumer\"]}\n", StandardCharsets.UTF_8);
        String before = Files.readString(enabled);

        var result = ModCompatibilityPrecheck.inspect(mods, List.of("consumer"), "0.98a-RC8", 4096L);

        assertReason(result, ReasonCode.REQUIRED_DEPENDENCY_DISABLED);
        assertEquals(List.of("consumer"), result.suggestedProfileChange().before());
        assertEquals(List.of("consumer", "library"), result.suggestedProfileChange().after());
        assertEquals(List.of("library"), result.suggestedProfileChange().enable());
        assertEquals(before, Files.readString(enabled));
    }

    @Test
    void versionRulesMatchStarsectorMajorErrorAndMinorPatchWarnings() throws Exception {
        Path mods = mods();
        mod(mods, "consumer", """
                {"id":"consumer","name":"Consumer","version":"1.0.0","gameVersion":"0.98a-RC8",
                 "dependencies":[{"id":"major","version":{"major":2}},
                                 {"id":"minor","version":{"major":1,"minor":4}},
                                 {"id":"exact","version":"1.4.2"}]}
                """);
        mod(mods, "major", metadata("major", "1.9.9"));
        mod(mods, "minor", metadata("minor", "1.5.0"));
        mod(mods, "exact", metadata("exact", "1.4.3"));

        var result = ModCompatibilityPrecheck.inspect(
                mods, List.of("consumer", "major", "minor", "exact"), "0.98a-RC8", 4096L);

        assertReason(result, ReasonCode.DEPENDENCY_VERSION_MAJOR_MISMATCH);
        assertEquals(2, result.findings().stream()
                .filter(f -> f.reason() == ReasonCode.DEPENDENCY_VERSION_ADVISORY)
                .count());
    }

    @Test
    void unsupportedRangeSyntaxRemainsAdvisory() throws Exception {
        Path mods = mods();
        mod(mods, "consumer", """
                {"id":"consumer","name":"Consumer","version":"1.0.0","gameVersion":"0.98a-RC8",
                 "dependencies":[{"id":"library","version":">=2.0 <3.0"}]}
                """);
        mod(mods, "library", metadata("library", "2.4.0"));

        var result = ModCompatibilityPrecheck.inspect(
                mods, List.of("consumer", "library"), "0.98a-RC8", 4096L);

        assertReason(result, ReasonCode.DEPENDENCY_VERSION_ADVISORY);
        assertFalse(result.hasErrors());
    }

    @Test
    void duplicateIdsAreAmbiguousAndDeterministic() throws Exception {
        Path mods = mods();
        mod(mods, "a", metadata("same", "1.0.0"));
        mod(mods, "b", metadata("same", "1.0.0"));

        var first = ModCompatibilityPrecheck.inspect(mods, List.of("same"), "0.98a-RC8", 4096L);
        var second = ModCompatibilityPrecheck.inspect(mods, List.of("same"), "0.98a-RC8", 4096L);

        assertReason(first, ReasonCode.DUPLICATE_MOD_ID);
        assertEquals(first.toPublicMap(), second.toPublicMap());
    }

    @Test
    void malformedMetadataIsDistinctFromAbsentMetadata() throws Exception {
        Path mods = mods();
        mod(mods, "broken", "{\"id\":\"broken\",\"dependencies\":[}");

        var result = ModCompatibilityPrecheck.inspect(mods, List.of("broken", "absent"), "0.98a-RC8", 4096L);

        assertReason(result, ReasonCode.METADATA_INVALID);
        assertReason(result, ReasonCode.METADATA_ABSENT);
    }

    @Test
    void circularDeclarationsTerminateWithoutInventingTransitiveChanges() throws Exception {
        Path mods = mods();
        mod(mods, "a", """
                {"id":"a","name":"A","version":"1.0","gameVersion":"0.98a-RC8","dependencies":[{"id":"b"}]}
                """);
        mod(mods, "b", """
                {"id":"b","name":"B","version":"1.0","gameVersion":"0.98a-RC8","dependencies":[{"id":"a"}]}
                """);

        var result = ModCompatibilityPrecheck.inspect(mods, List.of("a"), "0.98a-RC8", 4096L);

        assertEquals(List.of("b"), result.suggestedProfileChange().enable());
        assertEquals(1, result.findings().stream()
                .filter(f -> f.reason() == ReasonCode.REQUIRED_DEPENDENCY_DISABLED)
                .count());
    }

    @Test
    void reviewedPreviewPreservesExistingModOrder() throws Exception {
        Path mods = mods();
        mod(mods, "consumer", """
                {"id":"consumer","name":"Consumer","version":"1","gameVersion":"0.98a-RC8",
                 "dependencies":[{"id":"library"}]}
                """);
        mod(mods, "library", metadata("library", "1"));
        mod(mods, "first", metadata("first", "1"));
        mod(mods, "last", metadata("last", "1"));

        var result = ModCompatibilityPrecheck.inspect(
                mods, List.of("last", "consumer", "first"), "0.98a-RC8", 4096L);

        assertEquals(List.of("last", "consumer", "first"), result.suggestedProfileChange().before());
        assertEquals(List.of("last", "consumer", "first", "library"), result.suggestedProfileChange().after());
        assertEquals(List.of("library"), result.suggestedProfileChange().enable());
    }

    @Test
    void unavailableEnabledProfileIsAdvisoryAndLaunchable() throws Exception {
        Path install = temp.resolve("partial-install");
        Files.createDirectories(install.resolve("mods"));

        var result = ModCompatibilityPrecheck.inspect(install);

        assertFalse(result.hasErrors());
        assertEquals(List.of(), result.enabledMods());
        assertTrue(result.findings().stream().anyMatch(finding ->
                finding.reason() == ReasonCode.ENABLED_MODS_UNAVAILABLE
                        && finding.severity() == Severity.WARNING));
        assertFalse(result.evidenceComplete());
        assertTrue(result.launchAllowed());
    }

    @Test
    void malformedEnabledProfileIsAdvisoryAndLaunchable() throws Exception {
        Path install = temp.resolve("malformed-profile");
        Path mods = install.resolve("mods");
        Files.createDirectories(mods);
        Files.writeString(mods.resolve("enabled_mods.json"), "{broken", StandardCharsets.UTF_8);

        var result = ModCompatibilityPrecheck.inspect(install);

        assertReason(result, ReasonCode.ENABLED_MODS_UNAVAILABLE);
        assertFalse(result.hasErrors());
        assertFalse(result.evidenceComplete());
        assertTrue(result.launchAllowed());
    }

    @Test
    void partialCompatibilityMetadataIsVisible() throws Exception {
        Path mods = mods();
        mod(mods, "partial", """
        {"id":"partial","name":"Partial","gameVersion":"0.98a-RC8"}
        """);

        var result = ModCompatibilityPrecheck.inspect(
                mods, List.of("partial"), "0.98a-RC8", 4096L);

        assertReason(result, ReasonCode.METADATA_PARTIAL);
        assertFalse(result.evidenceComplete());
        assertTrue(result.launchAllowed());
    }

    @Test
    void invalidMemoryDeclarationStaysAdvisoryAndIncomplete() throws Exception {
        Path mods = mods();
        mod(mods, "odd-memory", """
                {"id":"odd-memory","name":"Odd Memory","version":"1","gameVersion":"0.98a-RC8",
                 "requiredMemoryMB":0}
                """);

        var result = ModCompatibilityPrecheck.inspect(
                mods, List.of("odd-memory"), "0.98a-RC8", 4096L);

        assertReason(result, ReasonCode.MEMORY_REQUIREMENT_INVALID);
        assertFalse(result.hasErrors());
        assertFalse(result.evidenceComplete());
        assertTrue(result.launchAllowed());
    }

    @Test
    void optionalDependencyStaysInformational() throws Exception {
        Path mods = mods();
        mod(mods, "consumer", """
                {"id":"consumer","name":"Consumer","version":"1","gameVersion":"0.98a-RC8",
                 "dependencies":[{"id":"optional","optional":true}]}
                """);

        var result = ModCompatibilityPrecheck.inspect(mods, List.of("consumer"), "0.98a-RC8", 4096L);

        assertReason(result, ReasonCode.OPTIONAL_DEPENDENCY);
        assertFalse(result.hasErrors());
    }

    @Test
    void reportsGameVersionAndMemoryMismatch() throws Exception {
        Path mods = mods();
        mod(mods, "heavy", """
                {"id":"heavy","name":"Heavy","version":"1","gameVersion":"0.97a-RC11","requiredMemoryMB":6144}
                """);

        var result = ModCompatibilityPrecheck.inspect(mods, List.of("heavy"), "0.98a-RC8", 4096L);

        assertReason(result, ReasonCode.GAME_VERSION_MISMATCH);
        assertReason(result, ReasonCode.MEMORY_REQUIREMENT_EXCEEDS_HEAP);
        assertFalse(result.hasErrors());
    }

    @Test
    void looseJsonCommentsAndTrailingCommasAreAccepted() throws Exception {
        Path mods = mods();
        mod(mods, "commented", """
                {
                  # Starsector accepts hash comments
                  "id":"commented",
                  "name":"Commented",
                  "version":{"major":1,"minor":2,},
                  "gameVersion":"0.98a-RC8",
                  "dependencies":[],
                }
                """);

        var result = ModCompatibilityPrecheck.inspect(mods, List.of("commented"), "0.98a-RC8", 4096L);

        assertTrue(result.findings().isEmpty());
    }

    private Path mods() throws Exception {
        Path mods = temp.resolve("mods");
        Files.createDirectories(mods);
        return mods;
    }

    private static String metadata(String id, String version) {
        return "{\"id\":\"" + id + "\",\"name\":\"" + id + "\",\"version\":\"" + version
                + "\",\"gameVersion\":\"0.98a-RC8\"}";
    }

    private static void mod(Path mods, String directory, String json) throws Exception {
        Path root = mods.resolve(directory);
        Files.createDirectories(root);
        Files.writeString(root.resolve("mod_info.json"), json, StandardCharsets.UTF_8);
    }

    private static void assertReason(
            ModCompatibilityReadiness.Result result, ReasonCode reason) {
        assertTrue(result.findings().stream().anyMatch(finding -> finding.reason() == reason),
                () -> "Expected " + reason + " in " + result.findings());
    }
}
