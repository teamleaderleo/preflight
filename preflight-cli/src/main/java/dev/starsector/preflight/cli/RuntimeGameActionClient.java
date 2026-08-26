package dev.starsector.preflight.cli;

import dev.starsector.preflight.core.Json;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

/** PID-bound request/receipt client for the closed in-game desktop-smoke action catalog. */
final class RuntimeGameActionClient {
    static final String REQUEST_FORMAT = "starsector-preflight-runtime-action-request-v1";
    static final String RECEIPT_FORMAT = "starsector-preflight-runtime-action-receipt-v1";
    static final String CONTINUE_ACTION = "main-menu.continue";
    static final String REQUEST_FILE = "runtime-action-request.json";
    static final String RECEIPT_FILE = "runtime-action-receipt.json";
    private static final int MAX_RECEIPT_BYTES = 16 * 1024;
    private static final Set<String> RECEIPT_FIELDS = Set.of(
            "format", "sequence", "pid", "processStartedAt", "action", "acceptedAt",
            "executedAt", "boundary", "beforeState", "afterState", "status", "detail");

    private RuntimeGameActionClient() {
    }

    static String continueCampaign(
            Path runDirectory,
            Path runtimeProcess,
            DesktopSmokeDriver.ProcessTarget target,
            int timeoutSeconds) throws Exception {
        Path run = runDirectory.toRealPath();
        Path request = run.resolve(REQUEST_FILE);
        Path receipt = run.resolve(RECEIPT_FILE);
        if (Files.exists(request, LinkOption.NOFOLLOW_LINKS)
                || Files.exists(receipt, LinkOption.NOFOLLOW_LINKS)) {
            throw new IllegalStateException("Runtime action request or receipt already exists");
        }
        Path runtimeState = runtimeProcess.toAbsolutePath().normalize()
                .resolveSibling("runtime-state.json");
        RuntimeSemanticStateIdentity before = RuntimeSemanticStateIdentity.read(runtimeState, target);
        if (!"main-menu-interactive".equals(before.state())) {
            throw new IllegalStateException(
                    "Continue requires main-menu-interactive; observed " + before.state());
        }

        Instant deadline = Instant.now().plusSeconds(timeoutSeconds);
        Map<String, Object> values = new LinkedHashMap<>();
        values.put("format", REQUEST_FORMAT);
        values.put("sequence", 1L);
        values.put("pid", target.pid());
        values.put("processStartedAt", target.startedAt());
        values.put("action", CONTINUE_ACTION);
        values.put("expectedState", "main-menu-interactive");
        values.put("deadline", deadline);
        createOnce(request, Json.object(values) + System.lineSeparator());

        Map<String, Object> accepted = waitForReceipt(
                receipt, runtimeProcess, target, deadline);
        waitForCampaign(runtimeState, runtimeProcess, target, deadline);
        return accepted.get("detail") + "; receipt executed; campaign observed campaign-ready";
    }

    private static Map<String, Object> waitForReceipt(
            Path receipt,
            Path runtimeProcess,
            DesktopSmokeDriver.ProcessTarget target,
            Instant deadline) throws Exception {
        while (Instant.now().isBefore(deadline)) {
            requireSameProcess(runtimeProcess, target);
            if (Files.isRegularFile(receipt, LinkOption.NOFOLLOW_LINKS)) {
                long size = Files.size(receipt);
                if (size <= 0 || size > MAX_RECEIPT_BYTES) {
                    throw new IOException("Runtime action receipt has an invalid size");
                }
                Map<String, Object> value = StrictJson.object(
                        Files.readString(receipt, StandardCharsets.UTF_8));
                if (!value.keySet().equals(RECEIPT_FIELDS)) {
                    throw new IOException("Runtime action receipt fields are not exact");
                }
                require(value, "format", RECEIPT_FORMAT);
                requireLong(value, "sequence", 1L);
                requireLong(value, "pid", target.pid());
                require(value, "processStartedAt", target.startedAt().toString());
                require(value, "action", CONTINUE_ACTION);
                require(value, "boundary", "title.advanceImpl");
                require(value, "beforeState", "main-menu-interactive");
                Object status = value.get("status");
                if (!"executed".equals(status)) {
                    throw new IllegalStateException(
                            "Runtime Continue was not executed: " + status + " (" + value.get("detail") + ")");
                }
                if (!(value.get("acceptedAt") instanceof String)
                        || !(value.get("executedAt") instanceof String)) {
                    throw new IOException("Runtime action receipt timestamps are incomplete");
                }
                return value;
            }
            sleep();
        }
        throw new TimeoutException("Timed out waiting for the runtime Continue receipt");
    }

    private static void waitForCampaign(
            Path runtimeState,
            Path runtimeProcess,
            DesktopSmokeDriver.ProcessTarget target,
            Instant deadline) throws Exception {
        String last = "unavailable";
        while (Instant.now().isBefore(deadline)) {
            requireSameProcess(runtimeProcess, target);
            RuntimeSemanticStateIdentity state = RuntimeSemanticStateIdentity.read(runtimeState, target);
            last = state.state();
            if (state.reached("campaign-ready")) return;
            if ("stopped".equals(last)) {
                throw new IllegalStateException("Runtime stopped after Continue");
            }
            sleep();
        }
        throw new TimeoutException(
                "Continue receipt arrived but campaign-ready did not; last state was " + last);
    }

    private static void requireSameProcess(
            Path runtimeProcess, DesktopSmokeDriver.ProcessTarget target) throws IOException {
        Map<String, Object> inspected = RuntimeProcessIdentity.read(runtimeProcess).inspect();
        if (!Boolean.TRUE.equals(inspected.get("attachable"))
                || ((Number) inspected.get("pid")).longValue() != target.pid()
                || !target.startedAt().equals(inspected.get("startedAt"))) {
            throw new IOException("Runtime process changed during the Continue action");
        }
    }

    private static void require(Map<String, Object> value, String field, String expected)
            throws IOException {
        if (!expected.equals(value.get(field))) {
            throw new IOException("Runtime action receipt " + field + " differs");
        }
    }

    private static void requireLong(Map<String, Object> value, String field, long expected)
            throws IOException {
        if (!(value.get(field) instanceof Number number) || number.longValue() != expected) {
            throw new IOException("Runtime action receipt " + field + " differs");
        }
    }

    private static void sleep() throws InterruptedException {
        TimeUnit.MILLISECONDS.sleep(20L);
    }

    private static void createOnce(Path destination, String value) throws IOException {
        Path temporary = destination.resolveSibling(destination.getFileName()
                + ".tmp-" + ProcessHandle.current().pid() + "-" + System.nanoTime());
        boolean moved = false;
        try {
            Files.writeString(temporary, value, StandardCharsets.UTF_8,
                    StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE);
            try {
                Files.move(temporary, destination, StandardCopyOption.ATOMIC_MOVE);
            } catch (AtomicMoveNotSupportedException ignored) {
                Files.move(temporary, destination);
            }
            moved = true;
        } finally {
            if (!moved) Files.deleteIfExists(temporary);
        }
    }
}
