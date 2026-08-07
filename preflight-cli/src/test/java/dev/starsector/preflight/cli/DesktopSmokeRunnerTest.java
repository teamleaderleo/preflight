package dev.starsector.preflight.cli;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.starsector.preflight.core.Json;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

final class DesktopSmokeRunnerTest {
    @TempDir
    Path temporaryDirectory;

    @Test
    void executesTheScenarioInOrderAndObservesAfterInput() throws Exception {
        Path run = Files.createDirectory(temporaryDirectory.resolve("pass"));
        Path runtime = runtimeIdentity(run);
        FakeDriver driver = new FakeDriver(capabilities(), null);

        Map<String, Object> result = DesktopSmokeRunner.run(
                scenario(), runtime, run, driver, Clock.systemUTC());

        assertEquals("passed", result.get("status"));
        assertEquals(List.of("menu", "continue", "capture"), driver.executed);
        assertEquals(1, driver.observations);
        assertTrue(driver.attached);
        assertTrue(Files.isRegularFile(run.resolve("driver-result.json")));
        assertTrue(Files.isRegularFile(run.resolve("smoke-evidence.json")));
    }

    @Test
    void missingCapabilitiesProduceAnHonestSkipWithoutAttachment() throws Exception {
        Path run = Files.createDirectory(temporaryDirectory.resolve("skip"));
        Path runtime = runtimeIdentity(run);
        FakeDriver driver = new FakeDriver(Set.of("process-control"), null);

        Map<String, Object> result = DesktopSmokeRunner.run(
                scenario(), runtime, run, driver, Clock.systemUTC());

        assertEquals("skipped", result.get("status"));
        assertFalse(driver.attached);
        assertTrue(result.get("diagnostics").toString().contains("missing required capabilities"));
    }

    @Test
    void aDriverFailureStopsTheScenarioAndCannotBecomeAPass() throws Exception {
        Path run = Files.createDirectory(temporaryDirectory.resolve("failure"));
        Path runtime = runtimeIdentity(run);
        FakeDriver driver = new FakeDriver(capabilities(), "continue");

        Map<String, Object> result = DesktopSmokeRunner.run(
                scenario(), runtime, run, driver, Clock.systemUTC());

        assertEquals("failed", result.get("status"));
        assertEquals(List.of("menu", "continue"), driver.executed);
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> steps = (List<Map<String, Object>>) result.get("steps");
        assertEquals("passed", steps.get(0).get("status"));
        assertEquals("failed", steps.get(1).get("status"));
    }

    private Path runtimeIdentity(Path run) throws Exception {
        ProcessHandle process = ProcessHandle.current();
        Map<String, Object> identity = new LinkedHashMap<>();
        identity.put("format", "starsector-preflight-runtime-process-v1");
        identity.put("pid", process.pid());
        identity.put("parentPid", process.parent().map(ProcessHandle::pid).orElse(null));
        identity.put("startedAt", process.info().startInstant().orElseThrow());
        identity.put("observedAt", Instant.now());
        identity.put("state", "running");
        identity.put("stoppedAt", null);
        Path path = run.resolve("runtime-process.json");
        Files.writeString(path, Json.object(identity));
        return path;
    }

    private static Set<String> capabilities() {
        return Set.of("process-control", "semantic-state", "window-control", "screen-capture");
    }

    private static DesktopSmokeScenario scenario() {
        return DesktopSmokeScenario.parse("""
                {
                  "format":"starsector-preflight-smoke-v1",
                  "name":"runner",
                  "timeoutSeconds":60,
                  "launch":{"preset":"fast","textureStorage":"balanced","profile":null},
                  "steps":[
                    {"id":"menu","kind":"wait-state","state":"main-menu-ready","timeoutSeconds":30},
                    {"id":"continue","kind":"click","target":"main-menu.continue"},
                    {"id":"capture","kind":"capture","artifacts":["screenshot"]}
                  ]
                }
                """);
    }

    private static final class FakeDriver implements DesktopSmokeDriver {
        private final Set<String> capabilities;
        private final String failAt;
        private final List<String> executed = new ArrayList<>();
        private boolean attached;
        private int observations;

        private FakeDriver(Set<String> capabilities, String failAt) {
            this.capabilities = capabilities;
            this.failAt = failAt;
        }

        @Override
        public Descriptor descriptor() {
            return new Descriptor("fake-driver", "1.0", "mac", capabilities, List.of());
        }

        @Override
        public void attach(ProcessTarget target) {
            assertEquals(ProcessHandle.current().pid(), target.pid());
            attached = true;
        }

        @Override
        public ActionResult execute(Map<String, Object> step, Path runDirectory) throws Exception {
            String id = step.get("id").toString();
            executed.add(id);
            if (id.equals(failAt)) throw new IOExceptionForTest("synthetic failure");
            if ("capture".equals(id)) {
                Path screenshot = Files.writeString(runDirectory.resolve("campaign.png"), "pixels");
                return new ActionResult(
                        "captured", List.of(new Artifact("screenshot", screenshot)));
            }
            return ActionResult.completed("completed " + id);
        }

        @Override
        public Observation observe() {
            observations++;
            return new Observation("fresh frame");
        }
    }

    private static final class IOExceptionForTest extends Exception {
        private IOExceptionForTest(String message) {
            super(message);
        }
    }
}
