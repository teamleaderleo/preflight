package dev.starsector.preflight.cli;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class AdapterProbeAnalysisTest {
    private static final long MAX_INPUT_BYTES = 32L * 1024 * 1024;

    @TempDir
    Path temporaryDirectory;

    @Test
    void joinsExactClassNamesAcrossFullRetainedAndDetailedCandidateLists() throws Exception {
        Path adapter = temporaryDirectory.resolve("adapter.json");
        Path summary = temporaryDirectory.resolve("summary.json");
        Path output = temporaryDirectory.resolve("adapter-analysis.json");
        Files.writeString(adapter, """
                {"mode":"PROBE",
                 "candidates":[
                  {"className":"com/fs/starfarer/A","sha256":"aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa",
                   "relevanceScore":40,"sourceKind":"STARSECTOR_CORE","codeSource":"file:/game/core.jar"},
                  {"className":"com/fs/starfarer/B","sha256":"bbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbb",
                   "relevanceScore":100,"sourceKind":"STARSECTOR_CORE","codeSource":null}
                 ],
                 "rankedCandidates":[
                  {"className":"com/fs/starfarer/B","sha256":"bbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbb",
                   "relevanceScore":100,"sourceKind":"STARSECTOR_CORE","codeSource":null,
                   "evidence":["class name contains texture"]}
                 ]}
                """);
        Files.writeString(summary, """
                {"imageReadStackAttribution":{"topMethods":[
                  {"className":"com/fs/starfarer/A","methodName":"a","descriptor":"(Ljava/lang/String;)[B",
                   "behavioralScore":500,"events":10,"bytes":20480,"durationMs":4.5,"minimumDepth":1,
                   "depthWeight":200,"pathSamples":["graphics/a.png"],"pathsTruncated":false},
                  {"className":"com/fs/starfarer/Obf","methodName":"x","descriptor":"()V",
                   "behavioralScore":300,"events":6,"bytes":8192,"durationMs":2.0,"minimumDepth":2,
                   "depthWeight":80,"pathSamples":["graphics/b.png"],"pathsTruncated":false}
                ]}}
                """);

        AdapterProbeAnalysis.Result result = AdapterProbeAnalysis.analyze(adapter, summary, output);

        assertEquals(2, result.candidateClasses());
        assertEquals(2, result.behavioralClasses());
        assertEquals(1, result.matchedClasses());
        assertEquals(1, result.behaviorOnlyClasses());
        assertTrue(Files.isRegularFile(output));
        Map<String, Object> report = StrictJson.object(Files.readString(output));
        assertEquals(Boolean.FALSE, report.get("automaticAllowlistGenerated"));
        assertEquals(Boolean.FALSE, report.get("liveTransformationEligible"));
        assertEquals(Boolean.TRUE, report.get("requiresHumanReview"));
        assertEquals(Boolean.TRUE, report.get("sourceIdentityPreserved"));

        @SuppressWarnings("unchecked")
        List<Map<String, Object>> ranked = (List<Map<String, Object>>) report.get("rankedCandidates");
        assertEquals("com/fs/starfarer/A", ranked.get(0).get("className"));
        assertEquals("aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa",
                ranked.get(0).get("sha256"));
        assertEquals(540L, ranked.get(0).get("combinedScore"));
        assertEquals(Boolean.TRUE, ranked.get(0).get("hasBehavioralEvidence"));
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> methods = (List<Map<String, Object>>) ranked.get(0).get("behavioralMethods");
        assertEquals("(Ljava/lang/String;)[B", methods.get(0).get("descriptor"));

        @SuppressWarnings("unchecked")
        List<Map<String, Object>> behaviorOnly = (List<Map<String, Object>>) report.get("behaviorOnly");
        assertEquals("com/fs/starfarer/Obf", behaviorOnly.get(0).get("className"));
    }

    @Test
    void identicalClassBytesFromDifferentSourcesRemainSeparateAndAmbiguous() throws Exception {
        Path adapter = temporaryDirectory.resolve("source-variants.json");
        Path summary = temporaryDirectory.resolve("source-summary.json");
        Path output = temporaryDirectory.resolve("source-analysis.json");
        String hash = "c".repeat(64);
        Files.writeString(adapter, """
                {"mode":"PROBE","candidates":[
                  {"className":"com/fs/starfarer/A","sha256":"%s","relevanceScore":10,
                   "sourceKind":"STARSECTOR_CORE","codeSource":"file:/game/starsector-core/core.jar",
                   "normalizedSource":"/game/starsector-core/core.jar","loaderClass":"loader/Core","loaderName":"core"},
                  {"className":"com/fs/starfarer/A","sha256":"%s","relevanceScore":10,
                   "sourceKind":"MOD","codeSource":"file:/game/mods/copy.jar",
                   "normalizedSource":"/game/mods/copy.jar","loaderClass":"loader/Mod","loaderName":"mod"}
                ],"rankedCandidates":[]}
                """.formatted(hash, hash));
        Files.writeString(summary, """
                {"imageReadStackAttribution":{"topMethods":[
                  {"className":"com/fs/starfarer/A","methodName":"a","descriptor":"()V",
                   "behavioralScore":50,"events":2,"bytes":1000,"durationMs":1.0,"minimumDepth":1,
                   "depthWeight":20,"pathSamples":["graphics/a.png"],"pathsTruncated":false}
                ]}}
                """);

        AdapterProbeAnalysis.Result result = AdapterProbeAnalysis.analyze(adapter, summary, output);
        assertEquals(2, result.candidateClasses());
        assertEquals(2, result.matchedClasses());
        Map<String, Object> report = StrictJson.object(Files.readString(output));
        assertEquals(2L, report.get("candidateIdentities"));
        assertEquals(1L, report.get("distinctCandidateClassNames"));
        assertEquals(2L, report.get("matchedIdentities"));
        assertEquals(1L, report.get("matchedClassNames"));
        assertEquals(List.of("com/fs/starfarer/A"), report.get("ambiguousBehavioralClassNames"));

        @SuppressWarnings("unchecked")
        List<Map<String, Object>> ranked = (List<Map<String, Object>>) report.get("rankedCandidates");
        assertEquals(2, ranked.size());
        assertEquals("/game/mods/copy.jar", ranked.get(0).get("normalizedSource"));
        assertEquals("loader/Mod", ranked.get(0).get("loaderClass"));
        assertEquals("/game/starsector-core/core.jar", ranked.get(1).get("normalizedSource"));
        assertEquals("loader/Core", ranked.get(1).get("loaderClass"));
    }

    @Test
    void emptyEvidenceProducesDiagnosticsWithoutInventingMatches() throws Exception {
        Path adapter = temporaryDirectory.resolve("empty-adapter.json");
        Path summary = temporaryDirectory.resolve("empty-summary.json");
        Path output = temporaryDirectory.resolve("empty-analysis.json");
        Files.writeString(adapter, "{\"mode\":\"PROBE\",\"rankedCandidates\":[]}");
        Files.writeString(summary, "{\"imageReadStackAttribution\":{\"topMethods\":[]}}");

        AdapterProbeAnalysis.analyze(adapter, summary, output);
        Map<String, Object> report = StrictJson.object(Files.readString(output));
        assertEquals(0L, report.get("matchedClasses"));
        assertFalse(((List<?>) report.get("diagnostics")).isEmpty());
    }

    @Test
    void adapterReportRejectsAnInitiallyOversizedFileBeforeParsing() throws Exception {
        Path adapter = temporaryDirectory.resolve("oversized-adapter.json");
        Path summary = temporaryDirectory.resolve("summary.json");
        Path output = temporaryDirectory.resolve("analysis.json");
        writeSparseOversized(adapter);
        Files.writeString(summary, "{\"imageReadStackAttribution\":{\"topMethods\":[]}}");

        IOException error = assertThrows(
                IOException.class,
                () -> AdapterProbeAnalysis.analyze(adapter, summary, output));

        assertTrue(error.getMessage().contains("Adapter report exceeds"), error.getMessage());
    }

    @Test
    void startupSummaryRejectsAnInitiallyOversizedFileBeforeParsing() throws Exception {
        Path adapter = temporaryDirectory.resolve("adapter.json");
        Path summary = temporaryDirectory.resolve("oversized-summary.json");
        Path output = temporaryDirectory.resolve("analysis.json");
        Files.writeString(adapter, "{\"mode\":\"PROBE\",\"rankedCandidates\":[]}");
        writeSparseOversized(summary);

        IOException error = assertThrows(
                IOException.class,
                () -> AdapterProbeAnalysis.analyze(adapter, summary, output));

        assertTrue(error.getMessage().contains("Startup summary exceeds"), error.getMessage());
    }

    @Test
    void malformedStartupSummaryUtf8IsRejectedBeforeJsonParsing() throws Exception {
        Path adapter = temporaryDirectory.resolve("adapter.json");
        Path summary = temporaryDirectory.resolve("malformed-summary.json");
        Path output = temporaryDirectory.resolve("analysis.json");
        Files.writeString(adapter, "{\"mode\":\"PROBE\",\"rankedCandidates\":[]}");
        Files.write(summary, new byte[] {
                (byte) '{', (byte) '"', (byte) 'x', (byte) '"', (byte) ':',
                (byte) '"', (byte) 0x80, (byte) '"', (byte) '}'
        });

        IOException error = assertThrows(
                IOException.class,
                () -> AdapterProbeAnalysis.analyze(adapter, summary, output));

        assertTrue(error.getMessage().contains("UTF-8"), error.getMessage());
    }

    private static void writeSparseOversized(Path path) throws IOException {
        try (FileChannel channel = FileChannel.open(
                path, StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE)) {
            channel.position(MAX_INPUT_BYTES);
            channel.write(ByteBuffer.wrap(new byte[] {0}));
        }
        assertEquals(MAX_INPUT_BYTES + 1, Files.size(path));
    }
}
