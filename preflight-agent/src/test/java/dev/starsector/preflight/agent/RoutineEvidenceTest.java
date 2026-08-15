package dev.starsector.preflight.agent;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Path;
import java.util.Map;
import java.util.Properties;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * The per-seam evidence documents are skipped when they found nothing and kept when they did.
 *
 * <p>What this protects is the second half. Skipping the routine copies is only acceptable because
 * a report carrying a finding is still written on the launch that produced it -- a player who hits
 * a real problem cannot go back and re-run the launch that broke.
 */
class RoutineEvidenceTest {
    @TempDir
    Path temporaryDirectory;

    @Test
    void aReportThatObservedNothingIsRoutine() {
        assertTrue(new CodeLoaderSignatureReport(destination("code")).routine());
        assertTrue(new AudioDecoderSignatureReport(destination("audio")).routine());
        assertTrue(new SoundLoaderContractReport(destination("sound")).routine());
    }

    @Test
    void aContainedFailureIsNotRoutine() {
        CodeLoaderSignatureReport code = new CodeLoaderSignatureReport(destination("code"));
        code.contained("while reading the loader", new IllegalStateException("no"));
        assertFalse(code.routine());

        AudioDecoderSignatureReport audio = new AudioDecoderSignatureReport(destination("audio"));
        audio.contained("while reading the decoder", new IllegalStateException("no"));
        assertFalse(audio.routine());

        SoundLoaderContractReport sound = new SoundLoaderContractReport(destination("sound"));
        sound.contained("while reading the sound loader", new IllegalStateException("no"));
        assertFalse(sound.routine());
    }

    @Test
    void aSeamThatNeverMatchedIsStillRoutine() {
        // Tempting to call this a finding, since a seam the adapter stopped recognizing is exactly
        // what somebody wants to know about. But this report does not record what it saw instead --
        // the document would say `exactIdentityObserved: false` and nothing more -- and that a
        // target went untransformed is already in adapter.json, which every launch writes.
        assertTrue(new BytecodeShapeReport(destination("texture")).routine());
    }

    @Test
    void routineEvidenceIsKeptOnlyWhenAskedFor() {
        assertFalse(AdapterRuntime.fullEvidence(Map.of(), new Properties()));
        assertTrue(AdapterRuntime.fullEvidence(Map.of("PREFLIGHT_FULL_EVIDENCE", "1"), new Properties()));
        assertTrue(AdapterRuntime.fullEvidence(Map.of(), properties("preflight.evidence.full", "true")));
        assertFalse(AdapterRuntime.fullEvidence(Map.of(), properties("preflight.evidence.full", "false")));
    }

    private Path destination(String name) {
        return temporaryDirectory.resolve("adapter-" + name + ".json");
    }

    private static Properties properties(String key, String value) {
        Properties properties = new Properties();
        properties.setProperty(key, value);
        return properties;
    }
}
