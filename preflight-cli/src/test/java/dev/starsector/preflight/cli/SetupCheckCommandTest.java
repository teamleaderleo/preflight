package dev.starsector.preflight.cli;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class SetupCheckCommandTest {
    @TempDir
    Path temp;

    @Test
    void analyzeSetupJsonComposesMetadataAndStaticProvidersWithoutPaths() throws Exception {
        Path game = gameWithDependencyAndStaticProblem();
        ByteArrayOutputStream captured = new ByteArrayOutputStream();
        PrintStream previous = System.out;
        int status;
        try (PrintStream output = new PrintStream(captured, true, StandardCharsets.UTF_8)) {
            System.setOut(output);
            status = AnalysisCommand.execute(
                    new String[] {"setup", "--game", game.toString(), "--json"},
                    0);
        } finally {
            System.setOut(previous);
        }

        assertEquals(0, status);
        String jsonText = captured.toString(StandardCharsets.UTF_8).trim();
        Map<String, Object> json = StrictJson.object(jsonText);
        assertEquals(SetupAnalysis.FORMAT, json.get("format"));
        assertEquals(false, json.get("ready"));
        assertTrue(((String) json.get("installationIdentity")).matches("install-v1:[0-9a-f]{64}"));
        assertTrue(((String) json.get("profileFingerprint")).matches("[0-9a-f]{64}"));
        List<?> findings = (List<?>) json.get("findings");
        List<String> codes = findings.stream()
                .map(value -> (String) ((Map<?, ?>) value).get("code"))
                .toList();
        assertTrue(codes.contains("mod-metadata.required-dependency-missing"));
        assertTrue(codes.contains("static-reference.variant-missing-hull"));
        assertEquals(List.of(), json.get("unavailableProviders"));
        assertFalse(jsonText.contains(game.toString()));
        assertFalse(jsonText.contains("mod_info.json"));
    }

    @Test
    void modOnlyResourceGraphMakesStaticProviderUnknownInsteadOfFalseBlocking() throws Exception {
        Path game = temp.resolve("mod-only");
        Files.createDirectories(game);
        Files.writeString(game.resolve("starfarer_obf.jar"), "fixture-game-build", StandardCharsets.UTF_8);
        Path mods = Files.createDirectories(game.resolve("mods"));
        Files.writeString(
                mods.resolve("enabled_mods.json"),
                "{\"enabledMods\":[\"alpha\"]}",
                StandardCharsets.UTF_8);
        Path alpha = Files.createDirectories(mods.resolve("alpha/data/variants"));
        Files.writeString(
                mods.resolve("alpha/mod_info.json"),
                "{\"id\":\"alpha\"}",
                StandardCharsets.UTF_8);
        Files.writeString(
                alpha.resolve("broken.variant"),
                "{\"variantId\":\"broken_Variant\",\"hullId\":\"vanilla_hull\"}",
                StandardCharsets.UTF_8);

        SetupAnalysis.Result result = SetupCheckCommand.analyze(game);

        assertEquals(List.of("static-links"), result.unavailableProviders());
        assertEquals(SetupCheckCommand.Outcome.UNKNOWN, SetupCheckCommand.outcome(result));
        assertFalse(result.findings().stream()
                .anyMatch(finding -> finding.code().equals("static-reference.variant-missing-hull")));
    }

    @Test
    void intentionalRuntimeLogExclusionDoesNotDisableStaticCoverage() throws Exception {
        Path game = gameWithDependencyAndStaticProblem();
        Files.writeString(
                game.resolve("starsector-core/starsector.log"),
                "runtime log",
                StandardCharsets.UTF_8);

        SetupAnalysis.Result result = SetupCheckCommand.analyze(game);

        assertFalse(result.unavailableProviders().contains("static-links"));
        assertTrue(result.findings().stream()
                .anyMatch(finding -> finding.code().equals("static-reference.variant-missing-hull")));
    }

    @Test
    void humanOutcomeDistinguishesProblemUnknownWarningAndReady() {
        SetupAnalysis.Finding problem = finding(
                "problem", SetupAnalysis.Severity.BLOCKING, "problem");
        String unknownSummary = "unknown" + (char) 27 + "[31m";
        SetupAnalysis.Finding unknown = finding(
                "unknown", SetupAnalysis.Severity.UNKNOWN, unknownSummary);
        SetupAnalysis.Finding warning = finding(
                "warning", SetupAnalysis.Severity.WARNING, "warning");

        assertEquals(
                SetupCheckCommand.Outcome.PROBLEM,
                SetupCheckCommand.outcome(result(List.of(problem, unknown, warning), List.of())));
        assertEquals(
                SetupCheckCommand.Outcome.UNKNOWN,
                SetupCheckCommand.outcome(result(List.of(unknown, warning), List.of())));
        assertEquals(
                SetupCheckCommand.Outcome.UNKNOWN,
                SetupCheckCommand.outcome(result(List.of(), List.of("static-links"))));
        assertEquals(
                SetupCheckCommand.Outcome.WARNING,
                SetupCheckCommand.outcome(result(List.of(warning), List.of())));
        assertEquals(
                SetupCheckCommand.Outcome.READY,
                SetupCheckCommand.outcome(result(List.of(), List.of())));

        String rendered = SetupCheckCommand.render(result(List.of(unknown), List.of()));
        assertFalse(rendered.indexOf((char) 27) >= 0);
        assertTrue(rendered.contains("\\u001b"));
        assertTrue(rendered.endsWith("Nothing was changed.\n"));
    }

    @Test
    void helpIsAvailableAtTheNestedConsumerWithoutRunningAnalysis() throws Exception {
        ByteArrayOutputStream captured = new ByteArrayOutputStream();
        PrintStream previous = System.out;
        int status;
        try (PrintStream output = new PrintStream(captured, true, StandardCharsets.UTF_8)) {
            System.setOut(output);
            status = AnalysisCommand.execute(new String[] {"setup", "--help"}, 0);
        } finally {
            System.setOut(previous);
        }

        assertEquals(0, status);
        assertTrue(captured.toString(StandardCharsets.UTF_8).contains("preflight analyze setup"));
    }

    private Path gameWithDependencyAndStaticProblem() throws Exception {
        Path game = temp.resolve("game-" + System.nanoTime());
        Path core = Files.createDirectories(game.resolve("starsector-core"));
        Files.writeString(core.resolve("starfarer_obf.jar"), "fixture-game-build", StandardCharsets.UTF_8);

        Path mods = Files.createDirectories(game.resolve("mods"));
        Files.writeString(
                mods.resolve("enabled_mods.json"),
                "{\"enabledMods\":[\"alpha\"]}",
                StandardCharsets.UTF_8);
        Path alpha = Files.createDirectories(mods.resolve("alpha"));
        Files.writeString(
                alpha.resolve("mod_info.json"),
                "{\"id\":\"alpha\",\"dependencies\":[{\"id\":\"lib\"}]}",
                StandardCharsets.UTF_8);
        Path variants = Files.createDirectories(alpha.resolve("data/variants"));
        Files.writeString(
                variants.resolve("broken.variant"),
                "{\"variantId\":\"broken_Variant\",\"hullId\":\"missing_hull\"}",
                StandardCharsets.UTF_8);
        return game;
    }

    private static SetupAnalysis.Result result(
            List<SetupAnalysis.Finding> findings,
            List<String> unavailableProviders) {
        return new SetupAnalysis.Result(
                "install-v1:" + "a".repeat(64),
                "b".repeat(64),
                findings,
                unavailableProviders);
    }

    private static SetupAnalysis.Finding finding(
            String code,
            SetupAnalysis.Severity severity,
            String summary) {
        return new SetupAnalysis.Finding(
                code,
                "provider",
                severity,
                summary,
                Map.of(),
                List.of(),
                List.of());
    }
}
