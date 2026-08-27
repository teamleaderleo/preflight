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
    static final String REQUEST_FORMAT = "starsector-preflight-runtime-action-request-v6";
    static final String RECEIPT_FORMAT = "starsector-preflight-runtime-action-receipt-v6";
    static final String CONTINUE_ACTION = "main-menu.continue";
    static final String CAMPAIGN_PAUSE_ACTION = "campaign.pause";
    static final String CAMPAIGN_UNPAUSE_ACTION = "campaign.unpause";
    static final String CAMPAIGN_BEGIN_FRAME_WINDOW_ACTION = "campaign.begin-frame-window";
    static final String COMBAT_PAUSE_ACTION = "combat.pause";
    static final String COMBAT_UNPAUSE_ACTION = "combat.unpause";
    static final String COMBAT_CAPTURE_VIEWPORT_ACTION = "combat.capture-viewport";
    static final String COMBAT_ZOOM_OUT_ACTION = "combat.zoom-out";
    static final String COMBAT_SET_STRESS_VIEWPORT_ACTION = "combat.set-stress-viewport";
    static final String COMBAT_VERIFY_ZOOM_OUT_ACTION = "combat.verify-zoom-out";
    static final String COMBAT_BEGIN_FRAME_WINDOW_ACTION = "combat.begin-frame-window";
    static final String COMBAT_END_FRAME_WINDOW_ACTION = "combat.end-frame-window";
    static final String COMBAT_STRESS_FIXTURE_ACTION =
            "combat.prepare-symmetric-1000dp-fixture";
    static final String COMBAT_FIXTURE_ACTION = "campaign.prepare-combat-fixture";
    static final String COMBAT_FIXTURE_VERIFY_ACTION = "campaign.verify-combat-fixture";
    static final String SIMULATION_OPPONENTS_ALL = "simulation.opponents.all";
    static final String SIMULATION_OPPONENTS_DEPLOY = "simulation.opponents.deploy";
    static final String SIMULATION_ALLIES_SELECT = "simulation.allies.select";
    static final String SIMULATION_ALLIES_ALL = "simulation.allies.all";
    static final String SIMULATION_ALLIES_DEPLOY = "simulation.allies.deploy";
    static final String SIMULATION_ENGAGE = "simulation.engage";
    static final String REQUEST_FILE = "runtime-action-request.json";
    static final String RECEIPT_FILE = "runtime-action-receipt.json";
    private static final Set<String> ACTIONS = Set.of(
            CONTINUE_ACTION, CAMPAIGN_PAUSE_ACTION, CAMPAIGN_UNPAUSE_ACTION,
            CAMPAIGN_BEGIN_FRAME_WINDOW_ACTION,
            COMBAT_PAUSE_ACTION, COMBAT_UNPAUSE_ACTION,
            COMBAT_CAPTURE_VIEWPORT_ACTION, COMBAT_ZOOM_OUT_ACTION,
            COMBAT_SET_STRESS_VIEWPORT_ACTION,
            COMBAT_VERIFY_ZOOM_OUT_ACTION,
            COMBAT_BEGIN_FRAME_WINDOW_ACTION, COMBAT_END_FRAME_WINDOW_ACTION,
            COMBAT_STRESS_FIXTURE_ACTION,
            COMBAT_FIXTURE_ACTION, COMBAT_FIXTURE_VERIFY_ACTION,
            SIMULATION_OPPONENTS_ALL, SIMULATION_OPPONENTS_DEPLOY,
            SIMULATION_ALLIES_SELECT, SIMULATION_ALLIES_ALL, SIMULATION_ALLIES_DEPLOY,
            SIMULATION_ENGAGE);
    private static final int MAX_RECEIPT_BYTES = 16 * 1024;
    private static final Set<String> RECEIPT_FIELDS = Set.of(
            "format", "sequence", "pid", "processStartedAt", "action", "acceptedAt",
            "executedAt", "boundary", "beforeState", "afterState", "beforePaused",
            "afterPaused", "status", "detail");

    private RuntimeGameActionClient() {
    }

    static boolean supports(String action) {
        return ACTIONS.contains(action);
    }

    static String continueCampaign(
            Path runDirectory,
            Path runtimeProcess,
            DesktopSmokeDriver.ProcessTarget target,
            int timeoutSeconds) throws Exception {
        return execute(runDirectory, runtimeProcess, target, 1L, CONTINUE_ACTION, timeoutSeconds);
    }

    static String execute(
            Path runDirectory,
            Path runtimeProcess,
            DesktopSmokeDriver.ProcessTarget target,
            long sequence,
            String action,
            int timeoutSeconds) throws Exception {
        if (sequence <= 0L) throw new IllegalArgumentException("Runtime action sequence must be positive");
        if (!supports(action)) throw new IllegalArgumentException("Unsupported runtime action: " + action);
        Path run = runDirectory.toRealPath();
        Path request = run.resolve(REQUEST_FILE);
        Path receipt = run.resolve(RECEIPT_FILE);
        if (Files.exists(request, LinkOption.NOFOLLOW_LINKS)
                || Files.exists(receipt, LinkOption.NOFOLLOW_LINKS)) {
            throw new IllegalStateException("Runtime action request or receipt already exists");
        }
        Path requestHistory = history(run, "runtime-action-request", sequence);
        Path receiptHistory = history(run, "runtime-action-receipt", sequence);
        if (Files.exists(requestHistory, LinkOption.NOFOLLOW_LINKS)
                || Files.exists(receiptHistory, LinkOption.NOFOLLOW_LINKS)) {
            throw new IllegalStateException("Runtime action sequence already exists: " + sequence);
        }

        Path runtimeState = runtimeProcess.toAbsolutePath().normalize()
                .resolveSibling("runtime-state.json");
        String expectedState = expectedState(action);
        RuntimeSemanticStateIdentity before = RuntimeSemanticStateIdentity.read(runtimeState, target);
        if (!expectedState.equals(before.state())) {
            throw new IllegalStateException(
                    action + " requires " + expectedState + "; observed " + before.state());
        }

        Instant deadline = Instant.now().plusSeconds(timeoutSeconds);
        Map<String, Object> values = new LinkedHashMap<>();
        values.put("format", REQUEST_FORMAT);
        values.put("sequence", sequence);
        values.put("pid", target.pid());
        values.put("processStartedAt", target.startedAt());
        values.put("action", action);
        values.put("expectedState", expectedState);
        values.put("deadline", deadline);
        createOnce(request, Json.object(values) + System.lineSeparator());

        Map<String, Object> accepted = waitForReceipt(
                receipt, runtimeProcess, target, sequence, action, expectedState, deadline);
        archive(request, requestHistory);
        archive(receipt, receiptHistory);
        Object status = accepted.get("status");
        if (!"executed".equals(status)) {
            throw new IllegalStateException(
                    "Runtime action was not executed: " + status + " (" + accepted.get("detail") + ")");
        }
        if (CONTINUE_ACTION.equals(action)) {
            waitForCampaign(runtimeState, runtimeProcess, target, deadline);
            return accepted.get("detail")
                    + "; receipt executed; campaign observed campaign-ready";
        }

        if (COMBAT_FIXTURE_ACTION.equals(action) || COMBAT_FIXTURE_VERIFY_ACTION.equals(action)) {
            if (!Boolean.TRUE.equals(accepted.get("beforePaused"))
                    || !Boolean.TRUE.equals(accepted.get("afterPaused"))) {
                throw new IOException("Runtime combat fixture did not preserve the paused state");
            }
            RuntimeSemanticStateIdentity after = RuntimeSemanticStateIdentity.read(runtimeState, target);
            if (!"campaign-ready".equals(after.state())) {
                throw new IOException("Runtime left campaign-ready during " + action);
            }
            return accepted.get("detail") + "; campaign remained paused";
        }

        if (CAMPAIGN_BEGIN_FRAME_WINDOW_ACTION.equals(action)) {
            if (!(accepted.get("beforePaused") instanceof Boolean)
                    || !accepted.get("beforePaused").equals(accepted.get("afterPaused"))) {
                throw new IOException("Runtime campaign frame window changed the pause state");
            }
            RuntimeSemanticStateIdentity after = RuntimeSemanticStateIdentity.read(runtimeState, target);
            if (!"campaign-ready".equals(after.state())) {
                throw new IOException("Runtime left campaign-ready during " + action);
            }
            return accepted.get("detail") + "; campaign pause state remained "
                    + accepted.get("afterPaused");
        }

        if (action.startsWith("simulation.")) {
            if (SIMULATION_ENGAGE.equals(action)) {
                waitForState(runtimeState, runtimeProcess, target, deadline,
                        "combat-ready", action);
                return accepted.get("detail") + "; combat observed combat-ready";
            }
            RuntimeSemanticStateIdentity after = RuntimeSemanticStateIdentity.read(runtimeState, target);
            if (!"simulation-ready".equals(after.state())) {
                throw new IOException("Runtime left simulation-ready during " + action);
            }
            return String.valueOf(accepted.get("detail"));
        }

        if (action.startsWith("combat.")) {
            RuntimeSemanticStateIdentity after = RuntimeSemanticStateIdentity.read(runtimeState, target);
            if (!"combat-ready".equals(after.state())) {
                throw new IOException("Runtime left combat-ready during " + action);
            }
            if (COMBAT_CAPTURE_VIEWPORT_ACTION.equals(action)
                    || COMBAT_ZOOM_OUT_ACTION.equals(action)
                    || COMBAT_SET_STRESS_VIEWPORT_ACTION.equals(action)
                    || COMBAT_VERIFY_ZOOM_OUT_ACTION.equals(action)
                    || COMBAT_BEGIN_FRAME_WINDOW_ACTION.equals(action)
                    || COMBAT_STRESS_FIXTURE_ACTION.equals(action)) {
                return String.valueOf(accepted.get("detail"));
            }
            boolean desiredPaused = COMBAT_PAUSE_ACTION.equals(action);
            if (!(accepted.get("beforePaused") instanceof Boolean)
                    || !Boolean.valueOf(desiredPaused).equals(accepted.get("afterPaused"))) {
                throw new IOException("Runtime combat action pause receipt is incomplete");
            }
            return accepted.get("detail") + "; combat pause state verified " + desiredPaused;
        }

        boolean desiredPaused = CAMPAIGN_PAUSE_ACTION.equals(action);
        if (!(accepted.get("beforePaused") instanceof Boolean)
                || !Boolean.valueOf(desiredPaused).equals(accepted.get("afterPaused"))) {
            throw new IOException("Runtime campaign action pause receipt is incomplete");
        }
        RuntimeSemanticStateIdentity after = RuntimeSemanticStateIdentity.read(runtimeState, target);
        if (!"campaign-ready".equals(after.state())) {
            throw new IOException("Runtime left campaign-ready during " + action);
        }
        return accepted.get("detail") + "; pause state verified " + desiredPaused;
    }

    private static Map<String, Object> waitForReceipt(
            Path receipt,
            Path runtimeProcess,
            DesktopSmokeDriver.ProcessTarget target,
            long sequence,
            String action,
            String expectedState,
            Instant deadline) throws Exception {
        while (Instant.now().isBefore(deadline)) {
            requireSameProcess(runtimeProcess, target, action);
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
                requireLong(value, "sequence", sequence);
                requireLong(value, "pid", target.pid());
                require(value, "processStartedAt", target.startedAt().toString());
                require(value, "action", action);
                require(value, "beforeState", expectedState);
                require(value, "boundary", CONTINUE_ACTION.equals(action)
                        ? "title.advanceImpl"
                        : COMBAT_ZOOM_OUT_ACTION.equals(action)
                                ? "combat-state.input"
                        : action.startsWith("combat.")
                                ? "combat-engine.advance"
                        : action.startsWith("simulation.")
                                ? "simulation-dialog.advance" : "campaign.processInput");
                if (!(value.get("acceptedAt") instanceof String)
                        || !(value.get("executedAt") instanceof String)) {
                    throw new IOException("Runtime action receipt timestamps are incomplete");
                }
                return value;
            }
            sleep();
        }
        throw new TimeoutException("Timed out waiting for the runtime " + action + " receipt");
    }

    private static void waitForCampaign(
            Path runtimeState,
            Path runtimeProcess,
            DesktopSmokeDriver.ProcessTarget target,
            Instant deadline) throws Exception {
        String last = "unavailable";
        while (Instant.now().isBefore(deadline)) {
            requireSameProcess(runtimeProcess, target, CONTINUE_ACTION);
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

    private static void waitForState(
            Path runtimeState,
            Path runtimeProcess,
            DesktopSmokeDriver.ProcessTarget target,
            Instant deadline,
            String expected,
            String action) throws Exception {
        String last = "unavailable";
        while (Instant.now().isBefore(deadline)) {
            requireSameProcess(runtimeProcess, target, action);
            RuntimeSemanticStateIdentity state = RuntimeSemanticStateIdentity.read(runtimeState, target);
            last = state.state();
            if (expected.equals(last)) return;
            if ("stopped".equals(last)) {
                throw new IllegalStateException("Runtime stopped during " + action);
            }
            sleep();
        }
        throw new TimeoutException(action + " did not reach " + expected + "; last state was " + last);
    }

    private static String expectedState(String action) {
        if (CONTINUE_ACTION.equals(action)) return "main-menu-interactive";
        if (action.startsWith("combat.")) return "combat-ready";
        return action.startsWith("simulation.") ? "simulation-ready" : "campaign-ready";
    }

    private static Path history(Path run, String stem, long sequence) {
        return run.resolve(stem + "-%06d.json".formatted(sequence));
    }

    private static void archive(Path active, Path history) throws IOException {
        try {
            Files.move(active, history, StandardCopyOption.ATOMIC_MOVE);
        } catch (AtomicMoveNotSupportedException ignored) {
            Files.move(active, history);
        }
    }

    private static void requireSameProcess(
            Path runtimeProcess,
            DesktopSmokeDriver.ProcessTarget target,
            String action) throws IOException {
        Map<String, Object> inspected = RuntimeProcessIdentity.read(runtimeProcess).inspect();
        if (!Boolean.TRUE.equals(inspected.get("attachable"))
                || ((Number) inspected.get("pid")).longValue() != target.pid()
                || !target.startedAt().equals(inspected.get("startedAt"))) {
            throw new IOException("Runtime process changed during " + action);
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
