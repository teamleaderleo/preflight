package dev.starsector.preflight.cli;

import static org.junit.jupiter.api.Assertions.*;
import dev.starsector.preflight.core.Json;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class StopCommandIT {
    @TempDir Path home;

    @Test
    void packagedUserStopSignalsOnlyTheRecordedChildAndWritesIntentBeforeExit() throws Exception {
        Path run = PreflightHome.resolve(Platform.current(), home, Map.of()).runs().resolve("stop-target");
        Files.createDirectories(run);
        String java = Path.of(System.getProperty("java.home"), "bin",
                Platform.current() == Platform.WINDOWS ? "java.exe" : "java").toString();
        String classpath = System.getProperty("surefire.test.class.path", System.getProperty("java.class.path"));
        Process target = new ProcessBuilder(java, "-cp", classpath, StopTarget.class.getName(), run.toString())
                .redirectErrorStream(true).redirectOutput(home.resolve("target.log").toFile()).start();
        Process stop = null;
        try {
            long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(15);
            while (!Files.exists(run.resolve("ready")) && target.isAlive() && System.nanoTime() < deadline) {
                Thread.sleep(50);
            }
            assertTrue(Files.exists(run.resolve("ready")), Files.readString(home.resolve("target.log")));
            Path jar = Path.of("target", "preflight.jar").toAbsolutePath();
            assertTrue(Files.isRegularFile(jar));
            Path output = home.resolve("stop.json");
            stop = new ProcessBuilder(java, "-Duser.home=" + home, "-jar", jar.toString(),
                    "stop", "--user-requested", "--pid", Long.toString(target.pid()),
                    "--timeout-seconds", "2", "--json")
                    .redirectOutput(output.toFile()).redirectError(home.resolve("stop.err").toFile()).start();
            assertTrue(stop.waitFor(15, TimeUnit.SECONDS));
            assertEquals(0, stop.exitValue(), Files.readString(home.resolve("stop.err")));
            assertTrue(target.waitFor(5, TimeUnit.SECONDS));
            assertTrue(StarsectorRunLogEvidence.exactUserStopRequested(run));
            assertTrue(Files.readString(output).contains("\"result\":\"stopped\"")
                    || Files.readString(output).contains("\"result\": \"stopped\""), Files.readString(output));
        } finally {
            if (stop != null && stop.isAlive()) { stop.destroyForcibly(); stop.waitFor(5, TimeUnit.SECONDS); }
            if (target.isAlive()) { target.destroyForcibly(); target.waitFor(5, TimeUnit.SECONDS); }
        }
    }

    public static class StopTarget {
        public static void main(String[] args) throws Exception {
            Path run = Path.of(args[0]);
            ProcessHandle self = ProcessHandle.current();
            Map<String, Object> record = new LinkedHashMap<>();
            record.put("format", "starsector-preflight-runtime-process-v1");
            record.put("pid", self.pid());
            record.put("parentPid", self.parent().map(ProcessHandle::pid).orElse(null));
            record.put("startedAt", self.info().startInstant().orElseThrow().toString());
            record.put("observedAt", Instant.now().toString());
            record.put("state", "running");
            record.put("stoppedAt", null);
            Files.writeString(run.resolve("runtime-process.json"), Json.object(record));
            Files.writeString(run.resolve("ready"), "ready");
            Thread.sleep(60_000);
        }
    }
}
