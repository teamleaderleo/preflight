package dev.starsector.preflight.cli;

import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.starsector.preflight.core.Json;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

final class RuntimeGameActionClientTest {
    @TempDir
    Path temporaryDirectory;

    @Test
    void requiresBothTheExactReceiptAndCampaignState() throws Exception {
        ProcessHandle process = ProcessHandle.current();
        Instant startedAt = process.info().startInstant().orElseThrow();
        DesktopSmokeDriver.ProcessTarget target =
                new DesktopSmokeDriver.ProcessTarget(process.pid(), startedAt);
        Path runtimeProcess = writeProcess(process, startedAt);
        writeState(startedAt, "main-menu-interactive", 2L);

        CompletableFuture<Void> game = CompletableFuture.runAsync(() -> {
            try {
                Path request = temporaryDirectory.resolve(RuntimeGameActionClient.REQUEST_FILE);
                for (int count = 0; count < 500 && !Files.isRegularFile(request); count++) {
                    TimeUnit.MILLISECONDS.sleep(5L);
                }
                assertTrue(Files.isRegularFile(request));
                Map<String, Object> receipt = new LinkedHashMap<>();
                receipt.put("format", RuntimeGameActionClient.RECEIPT_FORMAT);
                receipt.put("sequence", 1L);
                receipt.put("pid", process.pid());
                receipt.put("processStartedAt", startedAt);
                receipt.put("action", RuntimeGameActionClient.CONTINUE_ACTION);
                receipt.put("acceptedAt", Instant.now());
                receipt.put("executedAt", Instant.now());
                receipt.put("boundary", "title.advanceImpl");
                receipt.put("beforeState", "main-menu-interactive");
                receipt.put("afterState", "main-menu-interactive");
                receipt.put("status", "executed");
                receipt.put("detail", "synthetic exact callback");
                Files.writeString(
                        temporaryDirectory.resolve(RuntimeGameActionClient.RECEIPT_FILE),
                        Json.object(receipt));
                writeState(startedAt, "campaign-ready", 3L);
            } catch (Exception failure) {
                throw new RuntimeException(failure);
            }
        });

        String detail = RuntimeGameActionClient.continueCampaign(
                temporaryDirectory, runtimeProcess, target, 10);

        game.get(10, TimeUnit.SECONDS);
        assertTrue(detail.contains("receipt executed"), detail);
        assertTrue(detail.contains("campaign observed campaign-ready"), detail);
    }

    private Path writeProcess(ProcessHandle process, Instant startedAt) throws Exception {
        Map<String, Object> identity = new LinkedHashMap<>();
        identity.put("format", "starsector-preflight-runtime-process-v1");
        identity.put("pid", process.pid());
        identity.put("parentPid", process.parent().map(ProcessHandle::pid).orElse(null));
        identity.put("startedAt", startedAt);
        identity.put("observedAt", Instant.now());
        identity.put("state", "running");
        identity.put("stoppedAt", null);
        Path path = temporaryDirectory.resolve("runtime-process.json");
        Files.writeString(path, Json.object(identity));
        return path;
    }

    private void writeState(Instant startedAt, String state, long sequence) throws Exception {
        Map<String, Object> value = new LinkedHashMap<>();
        value.put("format", "starsector-preflight-runtime-state-v1");
        value.put("pid", ProcessHandle.current().pid());
        value.put("processStartedAt", startedAt);
        value.put("mainMenuReadyAt", startedAt.plusMillis(1));
        value.put("mainMenuInteractiveAt", startedAt.plusMillis(2));
        value.put("state", state);
        value.put("sequence", sequence);
        value.put("observedAt", Instant.now());
        Path destination = temporaryDirectory.resolve("runtime-state.json");
        Path temporary = temporaryDirectory.resolve("runtime-state.tmp-" + System.nanoTime());
        Files.writeString(temporary, Json.object(value));
        Files.move(temporary, destination, java.nio.file.StandardCopyOption.REPLACE_EXISTING);
    }
}
