package dev.starsector.preflight.cli;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.charset.StandardCharsets;
import org.junit.jupiter.api.Test;

class SupportEvidenceProjectionTest {
    @Test
    void keepsBoundedSupportFieldsAndDropsUnknownOrPathBearingFields() {
        String projected = text(SupportEvidenceProjection.project("benchmark-result.json", """
                {
                  "format":"starsector-preflight-desktop-benchmark-v1",
                  "installRoot":"/Users/example/Starsector",
                  "secret":"do-not-export",
                  "metrics":{
                    "processToMainMenuMs":{
                      "measurementOnly":80000,
                      "optimized":15880,
                      "improvementPercent":80.15,
                      "secret":"nested-secret"
                    }
                  },
                  "summary":{"status":"accepted","path":"C:\\\\private\\\\report"}
                }
                """));

        assertTrue(projected.contains("starsector-preflight-support-evidence-v1"), projected);
        assertTrue(projected.contains("metrics.processToMainMenuMs.optimized"), projected);
        assertTrue(projected.contains("15880"), projected);
        assertTrue(projected.contains("summary.status"), projected);
        assertFalse(projected.contains("installRoot"), projected);
        assertFalse(projected.contains("do-not-export"), projected);
        assertFalse(projected.contains("nested-secret"), projected);
        assertFalse(projected.contains("private"), projected);
    }

    @Test
    void dropsAllowedStringFieldWhenItsValueLooksLikeAnAbsolutePath() {
        String projected = text(SupportEvidenceProjection.project(
                "runtime-state.json",
                "{\"state\":\"/home/example/private\",\"status\":\"stopped\"}"));

        assertFalse(projected.contains("/home/example/private"), projected);
        assertTrue(projected.contains("stopped"), projected);
    }

    @Test
    void dropsEmbeddedUnixWindowsAndUncPathsFromAllowedProse() {
        String unix = text(SupportEvidenceProjection.project(
                "unix.json",
                "{\"reason\":\"Could not read /opt/Starsector/private-profile.json\",\"status\":\"ok\"}"));
        String windows = text(SupportEvidenceProjection.project(
                "windows.json",
                "{\"summary\":\"Failed at C:\\\\Users\\\\alice\\\\secret.txt\",\"status\":\"ok\"}"));
        String unc = text(SupportEvidenceProjection.project(
                "unc.json",
                "{\"summary\":\"Failed at \\\\\\\\server\\\\share\\\\secret.txt\",\"status\":\"ok\"}"));

        assertFalse(unix.contains("/opt/Starsector"), unix);
        assertFalse(windows.contains("Users"), windows);
        assertFalse(unc.contains("server"), unc);
        assertTrue(unix.contains("status"), unix);
        assertTrue(windows.contains("status"), windows);
        assertTrue(unc.contains("status"), unc);
    }

    @Test
    void dropsAbsolutePathsAfterClosingAndUnicodePunctuation() {
        String unix = text(SupportEvidenceProjection.project(
                "unix-punctuation.json",
                "{\"reason\":\"failed)/opt/Starsector/private.json\",\"status\":\"ok\"}"));
        String windows = text(SupportEvidenceProjection.project(
                "windows-punctuation.json",
                "{\"summary\":\"failed—C:\\\\Users\\\\alice\\\\private.txt\",\"status\":\"ok\"}"));
        String unc = text(SupportEvidenceProjection.project(
                "unc-punctuation.json",
                "{\"summary\":\"failed)\\\\\\\\server\\\\share\\\\private.txt\",\"status\":\"ok\"}"));

        assertFalse(unix.contains("/opt/Starsector"), unix);
        assertFalse(windows.contains("Users"), windows);
        assertFalse(unc.contains("server"), unc);
        assertTrue(unix.contains("status"), unix);
        assertTrue(windows.contains("status"), windows);
        assertTrue(unc.contains("status"), unc);
    }

    @Test
    void dropsEmbeddedUrisAndSecretAssignmentsFromAllowedProse() {
        String fileUri = text(SupportEvidenceProjection.project(
                "file-uri.json",
                "{\"reason\":\"Could not open file:///Users/alice/private.json\",\"status\":\"ok\"}"));
        String url = text(SupportEvidenceProjection.project(
                "url.json",
                "{\"summary\":\"Upload failed at https://support.example/case?token=secret\",\"status\":\"ok\"}"));
        String token = text(SupportEvidenceProjection.project(
                "token.json",
                "{\"reason\":\"authorization: Bearer top-secret\",\"status\":\"ok\"}"));

        assertFalse(fileUri.contains("file:"), fileUri);
        assertFalse(url.contains("support.example"), url);
        assertFalse(token.contains("top-secret"), token);
        assertTrue(fileUri.contains("status"), fileUri);
        assertTrue(url.contains("status"), url);
        assertTrue(token.contains("status"), token);
    }

    @Test
    void keepsBenignColonProseAndDropsGenericUriSchemes() {
        String benign = text(SupportEvidenceProjection.project(
                "benign-colon.json",
                "{\"reason\":\"Current profile: ready\",\"status\":\"ok\"}"));
        String smb = text(SupportEvidenceProjection.project(
                "smb.json",
                "{\"summary\":\"mounted at smb://server/share/private\",\"status\":\"ok\"}"));
        String ssh = text(SupportEvidenceProjection.project(
                "ssh.json",
                "{\"reason\":\"clone failed for ssh://alice:credential@example.invalid/repo\",\"status\":\"ok\"}"));
        String ftp = text(SupportEvidenceProjection.project(
                "ftp.json",
                "{\"summary\":\"mirror at ftp://example.invalid/private\",\"status\":\"ok\"}"));
        String mailto = text(SupportEvidenceProjection.project(
                "mailto.json",
                "{\"reason\":\"contact mailto:alice@example.invalid\",\"status\":\"ok\"}"));

        assertTrue(benign.contains("Current profile: ready"), benign);
        assertFalse(smb.contains("server/share"), smb);
        assertFalse(ssh.contains("example.invalid"), ssh);
        assertFalse(ftp.contains("example.invalid"), ftp);
        assertFalse(mailto.contains("alice@example.invalid"), mailto);
        assertTrue(smb.contains("status"), smb);
        assertTrue(ssh.contains("status"), ssh);
        assertTrue(ftp.contains("status"), ftp);
        assertTrue(mailto.contains("status"), mailto);
    }

    @Test
    void keepsRedactedHomeAndLogicalResourcePathsButRejectsASecondAbsolutePath() {
        String safe = text(SupportEvidenceProjection.project(
                "safe.json",
                "{\"reason\":\"cache miss for <home>/cache and graphics/foo.png\",\"status\":\"ok\"}"));
        String mixed = text(SupportEvidenceProjection.project(
                "mixed.json",
                "{\"reason\":\"home is <home>/cache; fallback is /Volumes/private/cache\",\"status\":\"ok\"}"));

        assertTrue(safe.contains("&lt;home&gt;/cache") || safe.contains("<home>/cache"), safe);
        assertTrue(safe.contains("graphics/foo.png"), safe);
        assertFalse(mixed.contains("Volumes"), mixed);
        assertTrue(mixed.contains("status"), mixed);
    }

    @Test
    void projectsJsonlIntoBoundedRecords() {
        String projected = text(SupportEvidenceProjection.project(
                "results.jsonl",
                "{\"seconds\":16.28,\"secret\":\"x\"}\n{\"seconds\":15.88}\n"));

        assertTrue(projected.contains("16.28"), projected);
        assertTrue(projected.contains("15.88"), projected);
        assertFalse(projected.contains("secret"), projected);
    }

    @Test
    void rejectsMalformedJsonlRatherThanCopyingItAsText() {
        assertThrows(IllegalArgumentException.class, () -> SupportEvidenceProjection.project(
                "results.jsonl",
                "{\"seconds\":16.28}\nnot-json\n"));
    }

    @Test
    void boundsFreeFormStrings() {
        String oversized = "x".repeat(SupportEvidenceProjection.MAX_STRING_CHARS + 1);
        String projected = text(SupportEvidenceProjection.project(
                "runtime-state.json",
                "{\"reason\":\"" + oversized + "\",\"status\":\"ok\"}"));

        assertFalse(projected.contains(oversized), projected);
        assertTrue(projected.contains("status"), projected);
    }

    private static String text(byte[] bytes) {
        return new String(bytes, StandardCharsets.UTF_8);
    }
}
