package dev.starsector.preflight.agent;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.starsector.preflight.core.PreparedFactionPriorityCache;
import dev.starsector.preflight.core.PreparedFactionPriorityCacheIO;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class FactionPriorityCacheRuntimeTest {
    @TempDir
    Path temporaryDirectory;

    @BeforeEach
    void begin() {
        FactionPriorityCacheRuntime.beginSession();
    }

    @AfterEach
    void reset() {
        FactionPriorityCacheRuntime.beginSession();
    }

    @Test
    void learnsOnlyOriginalAddsThenReplaysThemOnTheNextSession() throws Exception {
        String profile = "ab".repeat(32);
        Callback learning = new Callback();
        Object faction = new JsonIdentity("faction-a");
        assertTrue(FactionPriorityCacheRuntime.configure(temporaryDirectory, profile, true));
        org.junit.jupiter.api.Assertions.assertNull(FactionPriorityCacheRuntime.replayOrBegin(
                faction, learning, "knownShips", "hulls", true));
        FactionPriorityCacheRuntime.record("wolf");
        learning.o00000("wolf");
        FactionPriorityCacheRuntime.record("lasher");
        learning.o00000("lasher");
        FactionPriorityCacheRuntime.completeCall();
        org.junit.jupiter.api.Assertions.assertNull(FactionPriorityCacheRuntime.replayOrBegin(
                faction, new Callback(), "knownShips", "hulls", true),
                "a learning launch must never consume its own unvalidated results");
        FactionPriorityCacheRuntime.complete();

        PreparedFactionPriorityCache stored = PreparedFactionPriorityCacheIO.read(
                PreparedFactionPriorityCacheIO.path(temporaryDirectory, profile));
        assertEquals(1, stored.entries().size());

        FactionPriorityCacheRuntime.beginSession();
        assertTrue(FactionPriorityCacheRuntime.configure(temporaryDirectory, profile, true));
        Callback replay = new Callback();
        String[] replayIds = FactionPriorityCacheRuntime.replayOrBegin(
                new JsonIdentity("faction-a"), replay, "knownShips", "hulls", true);
        org.junit.jupiter.api.Assertions.assertNotNull(replayIds);
        for (String id : replayIds) replay.o00000(id);
        assertEquals(List.of("wolf", "lasher"), replay.ids);
        assertEquals(1L, FactionPriorityCacheRuntime.telemetry().get("hits"));
        assertEquals(2L, FactionPriorityCacheRuntime.telemetry().get("replayedIds"));
    }

    @Test
    void corruptArtifactDeclinesToFreshOriginalLearning() throws Exception {
        String profile = "cd".repeat(32);
        Path artifact = PreparedFactionPriorityCacheIO.path(temporaryDirectory, profile);
        java.nio.file.Files.createDirectories(artifact.getParent());
        java.nio.file.Files.writeString(artifact, "broken");

        assertTrue(FactionPriorityCacheRuntime.configure(temporaryDirectory, profile, true));
        org.junit.jupiter.api.Assertions.assertNull(FactionPriorityCacheRuntime.replayOrBegin(
                new JsonIdentity("faction-b"), new Callback(),
                "knownWeapons", "weapons", false));
        assertTrue(String.valueOf(FactionPriorityCacheRuntime.telemetry().get("status"))
                .startsWith("rejected:"));
    }

    public static final class Callback {
        private final List<String> ids = new ArrayList<>();

        public void o00000(String id) {
            ids.add(id);
        }
    }

    private record JsonIdentity(String value) {
        @Override
        public String toString() {
            return value;
        }
    }
}
