package dev.starsector.preflight.agent;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.starsector.preflight.core.Json;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

final class InternalGameControlRuntimeTest {
    @TempDir
    Path temporaryDirectory;

    @AfterEach
    void reset() {
        System.clearProperty("preflight.desktopSmoke");
        InternalGameControlRuntime.reset();
        RuntimeSemanticState.reset();
    }

    @Test
    void staysOffOutsideExplicitDesktopSmokeRuns() throws Exception {
        Path report = temporaryDirectory.resolve("adapter.json");
        RuntimeSemanticState.beginSession(temporaryDirectory.resolve("runtime-state.json"));
        InternalGameControlRuntime.beginSession(report);

        assertFalse(InternalGameControlRuntime.enabled());
        InternalGameControlRuntime.titleAdvance(new Object());
        assertFalse(Files.exists(temporaryDirectory.resolve("runtime-action-receipt.json")));
    }

    @Test
    void rejectsExpiredPidBoundRequestsWithoutInvokingTheTitle() throws Exception {
        System.setProperty("preflight.desktopSmoke", "true");
        RuntimeSemanticState.beginSession(temporaryDirectory.resolve("runtime-state.json"));
        RuntimeSemanticState.mainMenuReady();
        RuntimeSemanticState.mainMenuInteractive();
        InternalGameControlRuntime.beginSession(temporaryDirectory.resolve("adapter.json"));
        Files.writeString(temporaryDirectory.resolve("runtime-action-request.json"),
                request(Instant.EPOCH));

        InternalGameControlRuntime.titleAdvance(new Object());

        String receipt = Files.readString(temporaryDirectory.resolve("runtime-action-receipt.json"));
        assertTrue(receipt.contains("\"status\":\"rejected\""), receipt);
        assertTrue(receipt.contains("deadline-expired"), receipt);
    }

    @Test
    void aCanonicalLiveRequestReachesOnlyTheExactTitleShape() throws Exception {
        System.setProperty("preflight.desktopSmoke", "true");
        RuntimeSemanticState.beginSession(temporaryDirectory.resolve("runtime-state.json"));
        RuntimeSemanticState.mainMenuReady();
        RuntimeSemanticState.mainMenuInteractive();
        InternalGameControlRuntime.beginSession(temporaryDirectory.resolve("adapter.json"));
        Files.writeString(temporaryDirectory.resolve("runtime-action-request.json"),
                request(Instant.now().plusSeconds(30)));

        InternalGameControlRuntime.titleAdvance(new Object());

        String receipt = Files.readString(temporaryDirectory.resolve("runtime-action-receipt.json"));
        assertTrue(receipt.contains("\"status\":\"failed\""), receipt);
        assertTrue(receipt.contains("title-class-mismatch"), receipt);
    }

    private static String request(Instant deadline) {
        Map<String, Object> values = new LinkedHashMap<>();
        values.put("format", InternalGameControlRuntime.REQUEST_FORMAT);
        values.put("sequence", 1L);
        values.put("pid", ProcessHandle.current().pid());
        values.put("processStartedAt", RuntimeSemanticState.processStartedAt());
        values.put("action", InternalGameControlRuntime.CONTINUE_ACTION);
        values.put("expectedState", InternalGameControlRuntime.INTERACTIVE_STATE);
        values.put("deadline", deadline);
        return Json.object(values);
    }
}
