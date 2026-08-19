package dev.starsector.preflight.cli;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.stream.IntStream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class ModMetadataCheckTest {
    @TempDir
    Path temp;

    @Test
    void cleanRequiredDependencyProducesNoFinding() throws Exception {
        Path game = game(List.of("alpha", "lib"));
        mod(game, "alpha", """
                {
                  "id":"alpha",
                  "dependencies":[{"id":"lib","name":"Library",}],
                }
                """);
        mod(game, "lib", "{\"id\":\"lib\"}");

        ModMetadataCheck.Result result = ModMetadataCheck.check(game);

        assertEquals(List.of(), result.findings());
        assertEquals(ModMetadataCheck.ConversionMode.NORMAL, result.conversionMode());
        assertEquals(2, result.modDirectories());
        assertTrue(result.metadataBytes() > 0);
        assertTrue(result.elapsedNanos() > 0);
    }

    @Test
    void missingRequiredDependencyIsBlocking() throws Exception {
        Path game = game(List.of("alpha"));
        mod(game, "alpha", """
                {"id":"alpha","dependencies":[{"id":"lib"}]}
                """);

        SetupAnalysis.Finding finding = only(ModMetadataCheck.check(game));
        assertEquals("mod-metadata.required-dependency-missing", finding.code());
        assertEquals(SetupAnalysis.Severity.BLOCKING, finding.severity());
        assertEquals("alpha", finding.parameters().get("modId"));
        assertEquals("lib", finding.parameters().get("dependencyId"));
    }

    @Test
    void installedButDisabledRequiredDependencyIsBlocking() throws Exception {
        Path game = game(List.of("alpha"));
        mod(game, "alpha", "{\"id\":\"alpha\",\"dependencies\":[{\"id\":\"lib\"}]}");
        mod(game, "lib", "{\"id\":\"lib\"}");

        SetupAnalysis.Finding finding = only(ModMetadataCheck.check(game));
        assertEquals("mod-metadata.required-dependency-disabled", finding.code());
        assertEquals(SetupAnalysis.Severity.BLOCKING, finding.severity());
    }

    @Test
    void duplicateRelevantModIdIsBlockingAndConversionContextIsUnknown() throws Exception {
        Path game = game(List.of("dup"));
        mod(game, "first", "{\"id\":\"dup\"}");
        mod(game, "second", "{\"id\":\"dup\"}");

        ModMetadataCheck.Result result = ModMetadataCheck.check(game);
        SetupAnalysis.Finding finding = only(result);
        assertEquals("mod-metadata.duplicate-id", finding.code());
        assertEquals(2L, finding.parameters().get("candidates"));
        assertEquals(ModMetadataCheck.ConversionMode.UNKNOWN, result.conversionMode());
    }

    @Test
    void enabledModWithMissingMetadataIsDistinctFromAbsentInstallation() throws Exception {
        Path game = game(List.of("alpha"));
        Files.createDirectories(game.resolve("mods").resolve("alpha"));

        ModMetadataCheck.Result result = ModMetadataCheck.check(game);
        SetupAnalysis.Finding finding = only(result);
        assertEquals("mod-metadata.enabled-metadata-invalid", finding.code());
        assertEquals("missing", finding.parameters().get("problem"));
        assertEquals(ModMetadataCheck.ConversionMode.UNKNOWN, result.conversionMode());
    }

    @Test
    void malformedDependencyMetadataIsWarningRatherThanInventedDependency() throws Exception {
        Path game = game(List.of("alpha"));
        mod(game, "alpha", "{\"id\":\"alpha\",\"dependencies\":[42]}");

        SetupAnalysis.Finding finding = only(ModMetadataCheck.check(game));
        assertEquals("mod-metadata.dependencies-malformed", finding.code());
        assertEquals(SetupAnalysis.Severity.WARNING, finding.severity());
    }

    @Test
    void mixedMalformedDependencyKeepsKnownRequiredDependency() throws Exception {
        Path game = game(List.of("alpha"));
        mod(game, "alpha", """
                {"id":"alpha","dependencies":[{"id":"lib"},"junk"]}
                """);

        List<String> codes = ModMetadataCheck.check(game).findings().stream()
                .map(SetupAnalysis.Finding::code)
                .toList();

        assertEquals(
                List.of(
                        "mod-metadata.dependencies-malformed",
                        "mod-metadata.required-dependency-missing"),
                codes);
    }

    @Test
    void wrongTypedEnabledModsFailsClosedInsteadOfLookingEmpty() throws Exception {
        Path game = game(List.of());
        Files.writeString(game.resolve("mods/enabled_mods.json"), "{\"enabledMods\":{\"id\":\"alpha\"}}");

        IOException error = assertThrows(IOException.class, () -> ModMetadataCheck.check(game));
        assertTrue(error.getMessage().contains("enabledMods"));
    }

    @Test
    void aggregateBudgetCountsOversizedReadsThatBecomeUnreadableFindings() throws Exception {
        Path game = game(List.of());
        String oversized = "{\"id\":\"ignored\",\"padding\":\""
                + "x".repeat(1024 * 1024)
                + "\"}";
        Files.writeString(Files.createDirectories(game.resolve("mods/first")).resolve("mod_info.json"), oversized);
        Files.writeString(Files.createDirectories(game.resolve("mods/second")).resolve("mod_info.json"), oversized);

        IOException error = assertThrows(
                IOException.class,
                () -> ModMetadataCheck.check(game, 2L * 1024 * 1024));
        assertTrue(error.getMessage().contains("Aggregate mod metadata"));
    }

    @Test
    void findingFanoutFailsBeforeExceedingSharedResultLimit() throws Exception {
        List<String> enabled = IntStream.range(0, 257)
                .mapToObj(index -> "missing-" + index)
                .toList();
        Path game = game(enabled);

        IOException error = assertThrows(IOException.class, () -> ModMetadataCheck.check(game));
        assertTrue(error.getMessage().contains("shared finding limit"));
    }

    @Test
    void optionalDependencyIsNotPromotedToRequired() throws Exception {
        Path game = game(List.of("alpha"));
        mod(game, "alpha", """
                {
                  "id":"alpha",
                  "optionalDependencies":[{"id":"optional-lib"}]
                }
                """);

        assertEquals(List.of(), ModMetadataCheck.check(game).findings());
    }

    @Test
    void stringTotalConversionFlagIsAuthoritative() throws Exception {
        Path game = game(List.of("conversion"));
        mod(game, "conversion", "{\"id\":\"conversion\",\"totalConversion\":\"true\"}");

        ModMetadataCheck.Result result = ModMetadataCheck.check(game);

        assertEquals(ModMetadataCheck.ConversionMode.TOTAL_CONVERSION, result.conversionMode());
        assertEquals(List.of(), result.findings());
    }

    @Test
    void jsonBooleanTotalConversionFlagIsAlsoAccepted() throws Exception {
        Path game = game(List.of("conversion"));
        mod(game, "conversion", "{\"id\":\"conversion\",\"totalConversion\":true}");

        ModMetadataCheck.Result result = ModMetadataCheck.check(game);

        assertEquals(ModMetadataCheck.ConversionMode.TOTAL_CONVERSION, result.conversionMode());
        assertEquals(List.of(), result.findings());
    }

    @Test
    void absentOrFalseTotalConversionFlagMeansNormalProfile() throws Exception {
        Path game = game(List.of("alpha", "beta"));
        mod(game, "alpha", "{\"id\":\"alpha\"}");
        mod(game, "beta", "{\"id\":\"beta\",\"totalConversion\":\"false\"}");

        assertEquals(
                ModMetadataCheck.ConversionMode.NORMAL,
                ModMetadataCheck.check(game).conversionMode());
    }

    @Test
    void malformedTotalConversionFlagStaysUnknownAndWarns() throws Exception {
        Path game = game(List.of("alpha"));
        mod(game, "alpha", "{\"id\":\"alpha\",\"totalConversion\":\"maybe\"}");

        ModMetadataCheck.Result result = ModMetadataCheck.check(game);

        assertEquals(ModMetadataCheck.ConversionMode.UNKNOWN, result.conversionMode());
        SetupAnalysis.Finding finding = only(result);
        assertEquals("mod-metadata.total-conversion-flag-malformed", finding.code());
        assertEquals(SetupAnalysis.Severity.WARNING, finding.severity());
    }

    @Test
    void provenTotalConversionWinsOverOtherUnknownEnabledContext() throws Exception {
        Path game = game(List.of("conversion", "missing"));
        mod(game, "conversion", "{\"id\":\"conversion\",\"totalConversion\":true}");

        ModMetadataCheck.Result result = ModMetadataCheck.check(game);

        assertEquals(ModMetadataCheck.ConversionMode.TOTAL_CONVERSION, result.conversionMode());
        assertTrue(result.findings().stream().anyMatch(finding ->
                finding.code().equals("mod-metadata.enabled-mod-missing")));
    }

    @Test
    void oversizedEnabledMetadataFailsClosedWithoutUnboundedRead() throws Exception {
        Path game = game(List.of("alpha"));
        Path directory = Files.createDirectories(game.resolve("mods").resolve("alpha"));
        String huge = "{\"id\":\"alpha\",\"padding\":\"" + "x".repeat(1024 * 1024) + "\"}";
        Files.writeString(directory.resolve("mod_info.json"), huge);

        ModMetadataCheck.Result result = ModMetadataCheck.check(game);
        SetupAnalysis.Finding finding = only(result);
        assertEquals("mod-metadata.enabled-metadata-invalid", finding.code());
        assertEquals("unreadable", finding.parameters().get("problem"));
        assertEquals(ModMetadataCheck.ConversionMode.UNKNOWN, result.conversionMode());
    }

    @Test
    void setupJsonContainsNoFilesystemPaths() throws Exception {
        Path game = game(List.of("alpha"));
        mod(game, "alpha", "{\"id\":\"alpha\",\"dependencies\":[{\"id\":\"missing\"}]}");
        ModMetadataCheck.Result provider = ModMetadataCheck.check(game);
        String json = new SetupAnalysis.Result(
                        "game-identity",
                        "profile-identity",
                        provider.findings(),
                        List.of())
                .toJson();

        assertFalse(json.contains(temp.toString()));
        assertFalse(json.contains("mod_info.json"));
        assertTrue(json.contains("required-dependency-missing"));
    }

    private Path game(List<String> enabled) throws Exception {
        Path game = temp.resolve("game-" + System.nanoTime());
        Path mods = Files.createDirectories(game.resolve("mods"));
        String entries = enabled.stream().map(id -> "\"" + id + "\"").collect(java.util.stream.Collectors.joining(","));
        Files.writeString(mods.resolve("enabled_mods.json"), "{\"enabledMods\":[" + entries + "]}");
        return game;
    }

    private static void mod(Path game, String folder, String metadata) throws Exception {
        Path directory = Files.createDirectories(game.resolve("mods").resolve(folder));
        Files.writeString(directory.resolve("mod_info.json"), metadata);
    }

    private static SetupAnalysis.Finding only(ModMetadataCheck.Result result) {
        assertEquals(1, result.findings().size(), "expected one focused finding");
        return result.findings().get(0);
    }
}
