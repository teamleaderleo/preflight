package dev.starsector.preflight.agent;

import dev.starsector.preflight.core.Json;
import java.io.IOException;
import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.time.Instant;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Closed, desktop-smoke-only game-thread actions addressed through the run directory. */
public final class InternalGameControlRuntime {
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
    static final String INTERACTIVE_STATE = "main-menu-interactive";
    static final String CAMPAIGN_STATE = "campaign-ready";
    static final String SIMULATION_STATE = "simulation-ready";
    static final String COMBAT_STATE = "combat-ready";
    static final String SIMULATION_OPPONENTS_ALL = "simulation.opponents.all";
    static final String SIMULATION_OPPONENTS_DEPLOY = "simulation.opponents.deploy";
    static final String SIMULATION_ALLIES_SELECT = "simulation.allies.select";
    static final String SIMULATION_ALLIES_ALL = "simulation.allies.all";
    static final String SIMULATION_ALLIES_DEPLOY = "simulation.allies.deploy";
    static final String SIMULATION_ENGAGE = "simulation.engage";
    static final String REQUEST_FILE = "runtime-action-request.json";
    static final String RECEIPT_FILE = "runtime-action-receipt.json";
    private static final String CAMPAIGN_CLASS = "com.fs.starfarer.campaign.CampaignState";
    private static final String CAMPAIGN_ENGINE = "com.fs.starfarer.campaign.CampaignEngine";
    private static final String COMBAT_ENGINE = "com.fs.starfarer.combat.CombatEngine";
    private static final String INPUT_EVENTS = "com.fs.starfarer.util.super.B";
    private static final String INPUT_EVENT = "com.fs.starfarer.util.super.Object";
    private static final String INPUT_EVENT_CLASS = "com.fs.starfarer.api.input.InputEventClass";
    private static final String INPUT_EVENT_TYPE = "com.fs.starfarer.api.input.InputEventType";
    private static final String CONTROL = "com.fs.starfarer.title.B.B";
    private static final String CONTROL_PHASE = "com.fs.starfarer.title.B.B$o";
    private static final String CONTROL_ACTION = "com.fs.starfarer.title.B.B$oo";
    private static final String CONTROL_BINDING = "com.fs.starfarer.title.B.B$Oo";
    private static final int MAX_REQUEST_BYTES = 4 * 1024;
    private static final long POLL_INTERVAL_NANOS = 20_000_000L;
    private static final Pattern REQUEST = Pattern.compile(
            "\\{\\\"format\\\":\\\"" + REQUEST_FORMAT
                    + "\\\",\\\"sequence\\\":([1-9][0-9]*),\\\"pid\\\":([1-9][0-9]*),"
                    + "\\\"processStartedAt\\\":\\\"([^\\\"]{1,80})\\\","
                    + "\\\"action\\\":\\\"(main-menu\\.continue|campaign\\.(?:pause|unpause|begin-frame-window|prepare-combat-fixture|verify-combat-fixture)"
                    + "|simulation\\.(?:opponents\\.(?:all|deploy)|allies\\.(?:select|all|deploy)|engage)"
                    + "|combat\\.(?:pause|unpause|capture-viewport|zoom-out|set-stress-viewport|verify-zoom-out|begin-frame-window|end-frame-window|prepare-symmetric-1000dp-fixture))\\\","
                    + "\\\"expectedState\\\":\\\"(main-menu-interactive|campaign-ready|simulation-ready|combat-ready)\\\","
                    + "\\\"deadline\\\":\\\"([^\\\"]{1,80})\\\"\\}\\s*");

    private static volatile boolean enabled;
    private static Path requestPath;
    private static Path receiptPath;
    private static long nextPollNanos;
    private static long completedSequence;
    private static Request pendingCampaignRequest;
    private static Instant pendingCampaignAcceptedAt;
    private static boolean pendingCampaignBeforePaused;
    private static boolean pendingCampaignDesiredPaused;
    private static float combatViewportBaselineMult;
    private static float combatViewportBaselineWidth;
    private static float combatViewportBaselineHeight;
    private static float combatViewportExpectedMult;
    private static int combatCursorExpectedX = -1;
    private static int combatCursorExpectedY = -1;
    private static float combatViewportExpectedCenterX = Float.NaN;
    private static float combatViewportExpectedCenterY = Float.NaN;
    private static Object combatStressViewport;
    private static Method combatStressSetViewport;
    private static Object[] combatStressViewportArguments;
    private static String combatStressViewportFailure;
    private static volatile boolean simulationEngaged;

    private InternalGameControlRuntime() {
    }

    static synchronized void beginSession(Path adapterReport) {
        enabled = Boolean.getBoolean("preflight.desktopSmoke");
        Path directory = adapterReport.toAbsolutePath().normalize().getParent();
        requestPath = directory == null ? null : directory.resolve(REQUEST_FILE);
        receiptPath = directory == null ? null : directory.resolve(RECEIPT_FILE);
        nextPollNanos = 0L;
        completedSequence = 0L;
        pendingCampaignRequest = null;
        pendingCampaignAcceptedAt = null;
        clearCombatViewportBaseline();
        simulationEngaged = false;
        ConsoleCombatFixtureRuntime.reset();
        CombatStressFixtureRuntime.reset();
        if (requestPath == null || receiptPath == null) enabled = false;
    }

    static boolean enabled() {
        return enabled;
    }

    static boolean simulationEngaged() {
        return simulationEngaged;
    }

    /** Runs from the exact reviewed title {@code advanceImpl} boundary. */
    public static void titleAdvance(Object title) {
        if (!enabled || title == null || !RuntimeSemanticState.is(INTERACTIVE_STATE)) return;
        Request parsed = poll();
        if (parsed == null) return;
        completedSequence = parsed.sequence();
        String rejection = rejection(parsed);
        if (rejection == null && !CONTINUE_ACTION.equals(parsed.action())) {
            rejection = "action-boundary-mismatch";
        }
        if (rejection != null) {
            publish(parsed, "rejected", rejection, null, Instant.now(),
                    "title.advanceImpl", INTERACTIVE_STATE, null, null);
            return;
        }

        Instant accepted = Instant.now();
        try {
            invokeContinue(title);
            publish(parsed, "executed", "title callback accepted Continue", accepted, Instant.now(),
                    "title.advanceImpl", INTERACTIVE_STATE, null, null);
        } catch (ThreadDeath | VirtualMachineError fatal) {
            throw fatal;
        } catch (Throwable failure) {
            publish(parsed, "failed", bounded(failure), accepted, Instant.now(),
                    "title.advanceImpl", INTERACTIVE_STATE, null, null);
        }
    }

    /** Adds one mapped pause-control down/up pair to the game's real campaign input batch. */
    public static void campaignInput(Object campaign, Object events) {
        if (!enabled || campaign == null || events == null || pendingCampaignRequest != null
                || !RuntimeSemanticState.is(CAMPAIGN_STATE)) return;
        Request parsed = poll();
        if (parsed == null) return;
        completedSequence = parsed.sequence();
        String rejection = rejection(parsed);
        if (rejection == null && !isCampaignAction(parsed.action())) {
            rejection = "action-boundary-mismatch";
        }
        if (rejection != null) {
            publish(parsed, "rejected", rejection, null, Instant.now(),
                    "campaign.processInput", CAMPAIGN_STATE, null, null);
            return;
        }

        Instant accepted = Instant.now();
        try {
            requireCampaignSafe(campaign, events);
            boolean before = campaignPaused(campaign);
            if (ConsoleCombatFixtureRuntime.ACTION.equals(parsed.action())) {
                if (!before) throw new IllegalStateException("combat-fixture-requires-paused-campaign");
                String detail = ConsoleCombatFixtureRuntime.prepare();
                boolean after = campaignPaused(campaign);
                if (!after) throw new IllegalStateException("combat-fixture-changed-pause-state");
                publish(parsed, "executed", detail, accepted, Instant.now(),
                        "campaign.processInput", CAMPAIGN_STATE, true, true);
                return;
            }
            if (ConsoleCombatFixtureRuntime.VERIFY_ACTION.equals(parsed.action())) {
                if (!before) throw new IllegalStateException("combat-fixture-verification-requires-paused-campaign");
                String detail = ConsoleCombatFixtureRuntime.verify();
                boolean after = campaignPaused(campaign);
                if (!after) throw new IllegalStateException("combat-fixture-verification-changed-pause-state");
                publish(parsed, "executed", detail, accepted, Instant.now(),
                        "campaign.processInput", CAMPAIGN_STATE, true, true);
                return;
            }
            if (CAMPAIGN_BEGIN_FRAME_WINDOW_ACTION.equals(parsed.action())) {
                FrameTimeRuntime.beginCampaignMeasurementWindow(before);
                publish(parsed, "executed",
                        "started a clean steady-state campaign frame window",
                        accepted, Instant.now(), "campaign.processInput", CAMPAIGN_STATE,
                        before, before);
                return;
            }
            boolean desired = CAMPAIGN_PAUSE_ACTION.equals(parsed.action());
            if (before == desired) {
                publish(parsed, "executed", "campaign pause state already matched request",
                        accepted, Instant.now(), "campaign.processInput", CAMPAIGN_STATE,
                        before, before);
                return;
            }
            List<Object> pauseEvents = mappedPauseEvents(campaign.getClass().getClassLoader());
            @SuppressWarnings("unchecked")
            List<Object> input = (List<Object>) events;
            input.addAll(pauseEvents);
            pendingCampaignRequest = parsed;
            pendingCampaignAcceptedAt = accepted;
            pendingCampaignBeforePaused = before;
            pendingCampaignDesiredPaused = desired;
        } catch (ThreadDeath | VirtualMachineError fatal) {
            throw fatal;
        } catch (Throwable failure) {
            publish(parsed, "failed", bounded(failure), accepted, Instant.now(),
                    "campaign.processInput", CAMPAIGN_STATE, null, null);
        }
    }

    /** Verifies the mapped control was consumed by the normal campaign input path. */
    public static void campaignInputComplete(Object campaign) {
        Request parsed = pendingCampaignRequest;
        if (parsed == null) return;
        boolean before = pendingCampaignBeforePaused;
        boolean desired = pendingCampaignDesiredPaused;
        Instant accepted = pendingCampaignAcceptedAt;
        pendingCampaignRequest = null;
        pendingCampaignAcceptedAt = null;
        try {
            boolean after = campaignPaused(campaign);
            if (after != desired) {
                publish(parsed, "failed", "mapped pause control did not reach requested state",
                        accepted, Instant.now(), "campaign.processInput", CAMPAIGN_STATE, before, after);
                return;
            }
            publish(parsed, "executed", "mapped pause control reached requested state",
                    accepted, Instant.now(), "campaign.processInput", CAMPAIGN_STATE, before, after);
        } catch (ThreadDeath | VirtualMachineError fatal) {
            throw fatal;
        } catch (Throwable failure) {
            publish(parsed, "failed", bounded(failure), accepted, Instant.now(),
                    "campaign.processInput", CAMPAIGN_STATE, before, null);
        }
    }

    /** Executes the closed simulator deployment catalog from the exact stock dialog advance seam. */
    public static void simulationDialog(Object dialog) {
        if (!enabled || dialog == null || !RuntimeSemanticState.is(SIMULATION_STATE)) return;
        Request parsed = poll();
        if (parsed == null) return;
        completedSequence = parsed.sequence();
        String rejection = rejection(parsed);
        if (rejection == null && !isSimulationAction(parsed.action())) {
            rejection = "action-boundary-mismatch";
        }
        if (rejection != null) {
            publish(parsed, "rejected", rejection, null, Instant.now(),
                    "simulation-dialog.advance", SIMULATION_STATE, null, null);
            return;
        }

        Instant accepted = Instant.now();
        try {
            String detail = executeSimulationAction(dialog, parsed.action());
            publish(parsed, "executed", detail, accepted, Instant.now(),
                    "simulation-dialog.advance", SIMULATION_STATE, null, null);
        } catch (ThreadDeath | VirtualMachineError fatal) {
            throw fatal;
        } catch (Throwable failure) {
            publish(parsed, "failed", bounded(failure), accepted, Instant.now(),
                    "simulation-dialog.advance", SIMULATION_STATE, null, null);
        }
    }

    /** Adds a bounded wheel gesture before {@code CombatState} consumes the real input batch. */
    public static void combatInput(Object state, Object events) {
        if (!enabled || state == null || events == null || !RuntimeSemanticState.is(COMBAT_STATE)) return;
        Request parsed = poll();
        if (parsed == null) return;
        if (!COMBAT_ZOOM_OUT_ACTION.equals(parsed.action())) {
            // The engine seam owns every other combat action later in this same frame.
            nextPollNanos = 0L;
            return;
        }
        completedSequence = parsed.sequence();
        String rejection = rejection(parsed);
        if (rejection != null) {
            publish(parsed, "rejected", rejection, null, Instant.now(),
                    "combat-state.input", COMBAT_STATE, null, null);
            return;
        }

        Instant accepted = Instant.now();
        try {
            if (!CombatRuntimeIntegrityPlan.COMBAT_STATE_CLASS.equals(
                    state.getClass().getName().replace('.', '/'))) {
                throw new IllegalStateException("combat-state-class-mismatch");
            }
            if (!INPUT_EVENTS.equals(events.getClass().getName())) {
                throw new IllegalStateException("combat-input-shape-mismatch");
            }
            @SuppressWarnings("unchecked")
            List<Object> input = (List<Object>) events;
            input.addAll(mouseScrollEvents(state.getClass().getClassLoader(), 12, -1));
            publish(parsed, "executed", "added 12 negative zoom-out wheel events to combat input",
                    accepted, Instant.now(), "combat-state.input", COMBAT_STATE, null, null);
        } catch (ThreadDeath | VirtualMachineError fatal) {
            throw fatal;
        } catch (Throwable failure) {
            publish(parsed, "failed", bounded(failure), accepted, Instant.now(),
                    "combat-state.input", COMBAT_STATE, null, null);
        }
    }

    /** Ensures a requested pause state from the exact reviewed combat-engine advance seam. */
    public static void combatAdvance(Object engine, Object events) {
        if (!enabled || engine == null || events == null || !RuntimeSemanticState.is(COMBAT_STATE)) return;
        Request parsed = poll();
        if (parsed == null) return;
        completedSequence = parsed.sequence();
        String rejection = rejection(parsed);
        if (rejection == null && !isCombatEngineAction(parsed.action())) {
            rejection = "action-boundary-mismatch";
        }
        if (rejection != null) {
            publish(parsed, "rejected", rejection, null, Instant.now(),
                    "combat-engine.advance", COMBAT_STATE, null, null);
            return;
        }

        Instant accepted = Instant.now();
        Boolean before = null;
        try {
            if (!COMBAT_ENGINE.equals(engine.getClass().getName())) {
                throw new IllegalStateException("combat-engine-class-mismatch");
            }
            if (CombatStressFixtureRuntime.ACTION.equals(parsed.action())) {
                CombatStressFixtureRuntime.Result result =
                        CombatStressFixtureRuntime.prepare(engine);
                before = result.beforePaused();
                publish(parsed, "executed", result.detail(), accepted, Instant.now(),
                        "combat-engine.advance", COMBAT_STATE,
                        result.beforePaused(), result.afterPaused());
                return;
            }
            if (COMBAT_BEGIN_FRAME_WINDOW_ACTION.equals(parsed.action())) {
                CombatStressFixtureRuntime.captureWorkloadBegin(engine);
                FrameTimeRuntime.beginCombatMeasurementWindow();
                CombatWorkloadRuntime.beginMeasurementWindow();
                publish(parsed, "executed", "started a clean steady-state combat frame window",
                        accepted, Instant.now(), "combat-engine.advance", COMBAT_STATE, null, null);
                return;
            }
            if (COMBAT_END_FRAME_WINDOW_ACTION.equals(parsed.action())) {
                FrameTimeRuntime.endCombatMeasurementWindow();
                CombatWorkloadRuntime.endMeasurementWindow();
                String detail = CombatStressFixtureRuntime.captureWorkloadEnd(engine);
                publish(parsed, "executed", detail, accepted, Instant.now(),
                        "combat-engine.advance", COMBAT_STATE, null, null);
                return;
            }
            if (COMBAT_CAPTURE_VIEWPORT_ACTION.equals(parsed.action())) {
                float[] viewport = viewportState(engine);
                combatViewportBaselineMult = viewport[0];
                combatViewportBaselineWidth = viewport[1];
                combatViewportBaselineHeight = viewport[2];
                publish(parsed, "executed", String.format(java.util.Locale.ROOT,
                                "captured combat viewport baseline: viewMult %.3f, visible %.1fx%.1f",
                                viewport[0], viewport[1], viewport[2]),
                        accepted, Instant.now(), "combat-engine.advance", COMBAT_STATE, null, null);
                return;
            }
            if (COMBAT_SET_STRESS_VIEWPORT_ACTION.equals(parsed.action())) {
                if (combatViewportBaselineMult <= 0f
                        || combatViewportBaselineWidth <= 0f
                        || combatViewportBaselineHeight <= 0f) {
                    throw new IllegalStateException("combat-viewport-baseline-missing");
                }
                int[] cursor = centerCombatCursor(engine);
                Object viewport = viewport(engine);
                Method setExternalControl = viewport.getClass().getMethod(
                        "setExternalControl", boolean.class);
                Method setViewport = viewport.getClass().getMethod(
                        "set", float.class, float.class, float.class, float.class);
                if (setExternalControl.getReturnType() != void.class
                        || setViewport.getReturnType() != void.class) {
                    throw new IllegalStateException("combat-viewport-setter-shape-mismatch");
                }
                combatViewportExpectedCenterX = 0f;
                combatViewportExpectedCenterY = 0f;
                combatViewportExpectedMult = 4.0f;
                float factor = combatViewportExpectedMult / combatViewportBaselineMult;
                float expectedWidth = combatViewportBaselineWidth * factor;
                float expectedHeight = combatViewportBaselineHeight * factor;
                Object[] rectangle = new Object[] {
                    -expectedWidth / 2f, -expectedHeight / 2f, expectedWidth, expectedHeight
                };
                invoke(setExternalControl, viewport, true);
                setViewport.invoke(viewport, rectangle);
                combatStressViewport = viewport;
                combatStressSetViewport = setViewport;
                combatStressViewportArguments = rectangle;
                combatStressViewportFailure = null;
                float[] after = viewportState(engine);
                publish(parsed, "executed", String.format(java.util.Locale.ROOT,
                                "set exact externally controlled combat stress viewport: viewMult %.3f, visible %.1fx%.1f, center (0,0), cursor (%d,%d)",
                                after[0], after[1], after[2], cursor[0], cursor[1]),
                        accepted, Instant.now(), "combat-engine.advance", COMBAT_STATE, null, null);
                return;
            }
            if (COMBAT_VERIFY_ZOOM_OUT_ACTION.equals(parsed.action())) {
                if (combatViewportBaselineMult <= 0f || combatViewportBaselineWidth <= 0f) {
                    throw new IllegalStateException("combat-viewport-baseline-missing");
                }
                float[] viewport = viewportState(engine);
                boolean wider = viewport[0] >= combatViewportBaselineMult * 1.05f
                        && viewport[1] >= combatViewportBaselineWidth * 1.05f
                        && viewport[2] >= combatViewportBaselineHeight * 1.05f;
                boolean exactExpected = combatViewportExpectedMult <= 0f
                        || Math.abs(viewport[0] - combatViewportExpectedMult) <= 0.001f;
                Object viewportObject = viewport(engine);
                boolean externalControl = viewportExternalControl(viewportObject);
                float[] center = viewportCenter(viewportObject);
                boolean exactCenter = Float.isNaN(combatViewportExpectedCenterX)
                        || (Math.abs(center[0] - combatViewportExpectedCenterX) <= 0.01f
                        && Math.abs(center[1] - combatViewportExpectedCenterY) <= 0.01f);
                if (combatStressViewportFailure != null) {
                    throw new IllegalStateException(
                            "combat-stress-viewport-lock-failed: " + combatStressViewportFailure);
                }
                if (!wider || !exactExpected || !externalControl || !exactCenter) {
                    throw new IllegalStateException(String.format(java.util.Locale.ROOT,
                            "combat-stress-viewport-mismatch: viewMult %.3f -> %.3f, visible %.1fx%.1f -> %.1fx%.1f, center (%.1f,%.1f), externalControl %s",
                            combatViewportBaselineMult, viewport[0],
                            combatViewportBaselineWidth, combatViewportBaselineHeight,
                            viewport[1], viewport[2], center[0], center[1], externalControl));
                }
                publish(parsed, "executed", String.format(java.util.Locale.ROOT,
                                "verified externally controlled combat stress viewport: viewMult %.3f -> %.3f, visible %.1fx%.1f -> %.1fx%.1f, center (%.1f,%.1f)",
                                combatViewportBaselineMult, viewport[0],
                                combatViewportBaselineWidth, combatViewportBaselineHeight,
                                viewport[1], viewport[2], center[0], center[1]),
                        accepted, Instant.now(), "combat-engine.advance", COMBAT_STATE, null, null);
                return;
            }
            Method isPaused = engine.getClass().getMethod("isPaused");
            Method setPaused = engine.getClass().getMethod("setPaused", boolean.class);
            if (isPaused.getReturnType() != boolean.class
                    || setPaused.getReturnType() != void.class) {
                throw new IllegalStateException("combat-pause-method-shape-mismatch");
            }
            before = (Boolean) invoke(isPaused, engine);
            boolean desired = COMBAT_PAUSE_ACTION.equals(parsed.action());
            if (before != desired) invoke(setPaused, engine, desired);
            boolean after = (Boolean) invoke(isPaused, engine);
            if (after != desired) {
                throw new IllegalStateException("combat-pause-state-did-not-match-request");
            }
            String detail = before == desired
                    ? "combat pause state already matched request"
                    : "combat pause state reached requested state";
            publish(parsed, "executed", detail, accepted, Instant.now(),
                    "combat-engine.advance", COMBAT_STATE, before, after);
        } catch (ThreadDeath | VirtualMachineError fatal) {
            throw fatal;
        } catch (Throwable failure) {
            publish(parsed, "failed", bounded(failure), accepted, Instant.now(),
                    "combat-engine.advance", COMBAT_STATE, before, null);
        }
    }

    /** Reasserts the smoke-only stress zoom after Starsector updates its own zoom target. */
    public static void combatAdvanceEnd(Object engine) {
        if (!enabled || engine == null || combatStressSetViewport == null
                || combatStressViewport == null || combatStressViewportArguments == null
                || combatStressViewportFailure != null
                || !RuntimeSemanticState.is(COMBAT_STATE)) return;
        try {
            combatStressSetViewport.invoke(combatStressViewport, combatStressViewportArguments);
        } catch (ThreadDeath | VirtualMachineError fatal) {
            throw fatal;
        } catch (Throwable failure) {
            combatStressViewportFailure = bounded(
                    failure instanceof InvocationTargetException target && target.getCause() != null
                            ? target.getCause() : failure);
        }
    }

    private static Request poll() {
        long now = System.nanoTime();
        if (now < nextPollNanos) return null;
        nextPollNanos = now + POLL_INTERVAL_NANOS;
        Path request = requestPath;
        if (request == null || !Files.isRegularFile(request, LinkOption.NOFOLLOW_LINKS)) return null;
        try {
            Request parsed = read(request);
            return parsed.sequence() <= completedSequence ? null : parsed;
        } catch (ThreadDeath | VirtualMachineError fatal) {
            throw fatal;
        } catch (Throwable failure) {
            publishRejected(0L, "request-invalid", failure);
            enabled = false;
            return null;
        }
    }

    private static Request read(Path source) throws IOException {
        long size = Files.size(source);
        if (size <= 0 || size > MAX_REQUEST_BYTES) {
            throw new IOException("request size is outside 1.." + MAX_REQUEST_BYTES + " bytes");
        }
        Matcher match = REQUEST.matcher(Files.readString(source, StandardCharsets.UTF_8));
        if (!match.matches()) throw new IOException("request shape or field order is not canonical");
        try {
            return new Request(
                    Long.parseLong(match.group(1)),
                    Long.parseLong(match.group(2)),
                    Instant.parse(match.group(3)),
                    match.group(4),
                    match.group(5),
                    Instant.parse(match.group(6)));
        } catch (RuntimeException invalid) {
            throw new IOException("request identity or deadline is invalid", invalid);
        }
    }

    private static String rejection(Request request) {
        if (!expectedState(request.action()).equals(request.expectedState())) {
            return "action-state-shape-mismatch";
        }
        if (request.pid() != ProcessHandle.current().pid()) return "pid-mismatch";
        Instant actualStart = RuntimeSemanticState.processStartedAt();
        if (actualStart == null || !actualStart.equals(request.processStartedAt())) {
            return "process-start-mismatch";
        }
        if (!RuntimeSemanticState.is(request.expectedState())) return "before-state-mismatch";
        if (Instant.now().isAfter(request.deadline())) return "deadline-expired";
        if (Files.exists(receiptPath, LinkOption.NOFOLLOW_LINKS)) return "receipt-already-exists";
        return null;
    }

    private static String expectedState(String action) {
        if (CONTINUE_ACTION.equals(action)) return INTERACTIVE_STATE;
        if (isCombatAction(action)) return COMBAT_STATE;
        return isSimulationAction(action) ? SIMULATION_STATE : CAMPAIGN_STATE;
    }

    private static boolean isCampaignAction(String action) {
        return CAMPAIGN_PAUSE_ACTION.equals(action)
                || CAMPAIGN_UNPAUSE_ACTION.equals(action)
                || CAMPAIGN_BEGIN_FRAME_WINDOW_ACTION.equals(action)
                || ConsoleCombatFixtureRuntime.ACTION.equals(action)
                || ConsoleCombatFixtureRuntime.VERIFY_ACTION.equals(action);
    }

    private static boolean isSimulationAction(String action) {
        return SIMULATION_OPPONENTS_ALL.equals(action)
                || SIMULATION_OPPONENTS_DEPLOY.equals(action)
                || SIMULATION_ALLIES_SELECT.equals(action)
                || SIMULATION_ALLIES_ALL.equals(action)
                || SIMULATION_ALLIES_DEPLOY.equals(action)
                || SIMULATION_ENGAGE.equals(action);
    }

    private static boolean isCombatAction(String action) {
        return COMBAT_PAUSE_ACTION.equals(action)
                || COMBAT_UNPAUSE_ACTION.equals(action)
                || COMBAT_CAPTURE_VIEWPORT_ACTION.equals(action)
                || COMBAT_ZOOM_OUT_ACTION.equals(action)
                || COMBAT_SET_STRESS_VIEWPORT_ACTION.equals(action)
                || COMBAT_VERIFY_ZOOM_OUT_ACTION.equals(action)
                || COMBAT_BEGIN_FRAME_WINDOW_ACTION.equals(action)
                || COMBAT_END_FRAME_WINDOW_ACTION.equals(action)
                || CombatStressFixtureRuntime.ACTION.equals(action);
    }

    private static boolean isCombatEngineAction(String action) {
        return isCombatAction(action) && !COMBAT_ZOOM_OUT_ACTION.equals(action);
    }

    private static List<Object> mouseScrollEvents(ClassLoader loader, int count, int direction)
            throws ReflectiveOperationException {
        if (count <= 0 || count > 20 || Math.abs(direction) != 1) {
            throw new IllegalArgumentException("combat-scroll-shape-invalid");
        }
        Class<?> eventClass = Class.forName(INPUT_EVENT, true, loader);
        Class<?> eventCategory = Class.forName(INPUT_EVENT_CLASS, true, loader);
        Class<?> eventType = Class.forName(INPUT_EVENT_TYPE, true, loader);
        Constructor<?> constructor = eventClass.getConstructor(
                eventCategory, eventType, int.class, int.class, int.class, char.class);
        Object mouse = enumValue(eventCategory, "MOUSE_EVENT");
        Object scroll = enumValue(eventType, "MOUSE_SCROLL");
        java.util.ArrayList<Object> result = new java.util.ArrayList<>(count);
        for (int index = 0; index < count; index++) {
            result.add(constructor.newInstance(mouse, scroll, 0, 0, direction, '\0'));
        }
        return result;
    }

    private static float[] viewportState(Object engine) throws ReflectiveOperationException {
        Object viewport = viewport(engine);
        if (viewport == null) throw new IllegalStateException("combat-viewport-missing");
        Method getViewMult = viewport.getClass().getMethod("getViewMult");
        Method getVisibleWidth = viewport.getClass().getMethod("getVisibleWidth");
        Method getVisibleHeight = viewport.getClass().getMethod("getVisibleHeight");
        if (getViewMult.getReturnType() != float.class
                || getVisibleWidth.getReturnType() != float.class
                || getVisibleHeight.getReturnType() != float.class) {
            throw new IllegalStateException("combat-viewport-method-shape-mismatch");
        }
        float mult = (Float) invoke(getViewMult, viewport);
        float width = (Float) invoke(getVisibleWidth, viewport);
        float height = (Float) invoke(getVisibleHeight, viewport);
        if (!Float.isFinite(mult) || mult <= 0f
                || !Float.isFinite(width) || width <= 0f
                || !Float.isFinite(height) || height <= 0f) {
            throw new IllegalStateException("combat-viewport-state-invalid");
        }
        return new float[] {mult, width, height};
    }

    private static Object viewport(Object engine) throws ReflectiveOperationException {
        Object viewport = invoke(engine.getClass().getMethod("getViewport"), engine);
        if (viewport == null) throw new IllegalStateException("combat-viewport-missing");
        return viewport;
    }

    private static int[] centerCombatCursor(Object engine) throws ReflectiveOperationException {
        ClassLoader loader = engine.getClass().getClassLoader();
        Class<?> display = Class.forName("org.lwjgl.opengl.Display", false, loader);
        Method getWidth = display.getMethod("getWidth");
        Method getHeight = display.getMethod("getHeight");
        if (getWidth.getReturnType() != int.class || getHeight.getReturnType() != int.class) {
            throw new IllegalStateException("display-dimensions-method-shape-mismatch");
        }
        int width = (Integer) invoke(getWidth, null);
        int height = (Integer) invoke(getHeight, null);
        if (width <= 0 || height <= 0) {
            throw new IllegalStateException("display-dimensions-invalid");
        }

        Class<?> mouse = Class.forName("org.lwjgl.input.Mouse", false, loader);
        Method setCursorPosition = mouse.getMethod("setCursorPosition", int.class, int.class);
        if (setCursorPosition.getReturnType() != void.class) {
            throw new IllegalStateException("mouse-cursor-setter-shape-mismatch");
        }
        combatCursorExpectedX = width / 2;
        combatCursorExpectedY = height / 2;
        invoke(setCursorPosition, null, combatCursorExpectedX, combatCursorExpectedY);
        int[] cursor = combatCursorState(engine);
        if (Math.abs(cursor[0] - combatCursorExpectedX) > 2
                || Math.abs(cursor[1] - combatCursorExpectedY) > 2) {
            throw new IllegalStateException(String.format(java.util.Locale.ROOT,
                    "combat-cursor-did-not-center: cursor (%d,%d) expected (%d,%d)",
                    cursor[0], cursor[1], combatCursorExpectedX, combatCursorExpectedY));
        }
        return cursor;
    }

    private static int[] combatCursorState(Object engine) throws ReflectiveOperationException {
        Class<?> mouse = Class.forName(
                "org.lwjgl.input.Mouse", false, engine.getClass().getClassLoader());
        Method getX = mouse.getMethod("getX");
        Method getY = mouse.getMethod("getY");
        if (getX.getReturnType() != int.class || getY.getReturnType() != int.class) {
            throw new IllegalStateException("mouse-cursor-getter-shape-mismatch");
        }
        return new int[] {(Integer) invoke(getX, null), (Integer) invoke(getY, null)};
    }

    private static boolean viewportExternalControl(Object viewport)
            throws ReflectiveOperationException {
        Method method = viewport.getClass().getMethod("isExternalControl");
        if (method.getReturnType() != boolean.class) {
            throw new IllegalStateException("combat-viewport-external-control-shape-mismatch");
        }
        return (Boolean) invoke(method, viewport);
    }

    private static float[] viewportCenter(Object viewport) throws ReflectiveOperationException {
        Object center = invoke(viewport.getClass().getMethod("getCenter"), viewport);
        if (center == null) throw new IllegalStateException("combat-viewport-center-missing");
        Field x = center.getClass().getField("x");
        Field y = center.getClass().getField("y");
        if (x.getType() != float.class || y.getType() != float.class) {
            throw new IllegalStateException("combat-viewport-center-shape-mismatch");
        }
        float centerX = x.getFloat(center);
        float centerY = y.getFloat(center);
        if (!Float.isFinite(centerX) || !Float.isFinite(centerY)) {
            throw new IllegalStateException("combat-viewport-center-invalid");
        }
        return new float[] {centerX, centerY};
    }

    private static void clearCombatViewportBaseline() {
        combatViewportBaselineMult = 0f;
        combatViewportBaselineWidth = 0f;
        combatViewportBaselineHeight = 0f;
        combatViewportExpectedMult = 0f;
        combatCursorExpectedX = -1;
        combatCursorExpectedY = -1;
        combatViewportExpectedCenterX = Float.NaN;
        combatViewportExpectedCenterY = Float.NaN;
        combatStressViewport = null;
        combatStressSetViewport = null;
        combatStressViewportArguments = null;
        combatStressViewportFailure = null;
    }

    private static String executeSimulationAction(Object dialog, String action)
            throws ReflectiveOperationException {
        if (!"com.fs.starfarer.ui.impl.M".equals(dialog.getClass().getName())) {
            throw new IllegalStateException("simulation-dialog-class-mismatch");
        }
        Method ownerMethod = dialog.getClass().getMethod("getOwnerId");
        Method selectedMethod = dialog.getClass().getMethod("getSelected");
        Method actionMethod = dialog.getClass().getMethod(
                "actionPerformed", Object.class, Object.class);
        if (ownerMethod.getReturnType() != int.class
                || !List.class.isAssignableFrom(selectedMethod.getReturnType())
                || actionMethod.getReturnType() != void.class) {
            throw new IllegalStateException("simulation-dialog-method-shape-mismatch");
        }
        int owner = (Integer) invoke(ownerMethod, dialog);
        if (SIMULATION_ALLIES_SELECT.equals(action)) {
            if (owner != 1) throw new IllegalStateException("simulation-opponents-tab-not-active");
            invoke(actionMethod, dialog, null, dialogField(dialog, "String.null$Object"));
            if ((Integer) invoke(ownerMethod, dialog) != 0) {
                throw new IllegalStateException("simulation-allies-tab-did-not-activate");
            }
            return "activated the simulation allies deployment tab";
        }
        if (SIMULATION_ENGAGE.equals(action)) {
            if (owner != 0) throw new IllegalStateException("simulation-allies-tab-not-active");
            int allied = deployedCount(dialog, 0);
            int opposing = deployedCount(dialog, 1);
            if (allied <= 0 || opposing <= 0) {
                throw new IllegalStateException("simulation-both-sides-not-deployed");
            }
            Method dismiss = dialog.getClass().getMethod("dismiss", int.class);
            if (dismiss.getReturnType() != void.class) {
                throw new IllegalStateException("simulation-dismiss-shape-mismatch");
            }
            invoke(dismiss, dialog, 0);
            simulationEngaged = true;
            RuntimeSemanticState.combatReady();
            return "dismissed deployment dialog with " + allied
                    + " allied and " + opposing + " opposing ships deployed";
        }

        int expectedOwner = action.startsWith("simulation.opponents.") ? 1 : 0;
        if (owner != expectedOwner) {
            throw new IllegalStateException("simulation-deployment-side-mismatch");
        }
        if (action.endsWith(".all")) {
            int before = selectedCount(selectedMethod, dialog);
            if (before != 0) throw new IllegalStateException("simulation-selection-not-empty");
            invoke(actionMethod, dialog, null, dialogField(dialog, "private.null$Object"));
            int after = selectedCount(selectedMethod, dialog);
            if (after <= 0) throw new IllegalStateException("simulation-all-selected-no-ships");
            return "selected " + after + " ships for simulation side " + owner;
        }
        int selected = selectedCount(selectedMethod, dialog);
        if (selected <= 0) throw new IllegalStateException("simulation-no-ships-selected");
        int before = deployedCount(dialog, owner);
        invoke(actionMethod, dialog, null, dialogField(dialog, "ÓOÖ000"));
        int after = deployedCount(dialog, owner);
        if (after <= before) throw new IllegalStateException("simulation-deploy-did-not-add-ships");
        return "deployed " + (after - before) + " ships for simulation side " + owner;
    }

    private static int selectedCount(Method selectedMethod, Object dialog)
            throws ReflectiveOperationException {
        Object value = invoke(selectedMethod, dialog);
        if (!(value instanceof List<?> selected)) {
            throw new IllegalStateException("simulation-selection-shape-mismatch");
        }
        return selected.size();
    }

    private static int deployedCount(Object dialog, int owner) throws ReflectiveOperationException {
        ClassLoader loader = dialog.getClass().getClassLoader();
        Class<?> engineClass = Class.forName("com.fs.starfarer.combat.CombatEngine", false, loader);
        Object engine = invoke(engineClass.getMethod("getInstance"), null);
        Object manager = invoke(engineClass.getMethod("getFleetManager", int.class), engine, owner);
        Object deployed = invoke(manager.getClass().getMethod("getDeployed"), manager);
        if (!(deployed instanceof java.util.Collection<?> ships)) {
            throw new IllegalStateException("simulation-deployed-shape-mismatch");
        }
        return ships.size();
    }

    private static Object dialogField(Object dialog, String name) throws ReflectiveOperationException {
        Field field = dialog.getClass().getDeclaredField(name);
        field.setAccessible(true);
        Object value = field.get(dialog);
        if (value == null || !"com.fs.starfarer.ui.n".equals(value.getClass().getName())) {
            throw new IllegalStateException("simulation-button-shape-mismatch:" + name);
        }
        return value;
    }

    private static void requireCampaignSafe(Object campaign, Object events)
            throws ReflectiveOperationException {
        if (!CAMPAIGN_CLASS.equals(campaign.getClass().getName())) {
            throw new IllegalStateException("campaign-class-mismatch");
        }
        if (!INPUT_EVENTS.equals(events.getClass().getName())) {
            throw new IllegalStateException("campaign-input-shape-mismatch");
        }
        if (booleanMethod(campaign, "isShowingDialog") || booleanMethod(campaign, "isShowingMenu")) {
            throw new IllegalStateException("campaign-interaction-active");
        }
    }

    private static boolean campaignPaused(Object campaign) throws ReflectiveOperationException {
        Field[] engines = Arrays.stream(campaign.getClass().getDeclaredFields())
                .filter(field -> CAMPAIGN_ENGINE.equals(field.getType().getName()))
                .toArray(Field[]::new);
        if (engines.length != 1) throw new IllegalStateException("campaign-engine-shape-mismatch");
        engines[0].setAccessible(true);
        Object engine = engines[0].get(campaign);
        if (engine == null) throw new IllegalStateException("campaign-engine-unavailable");
        return (Boolean) invoke(engine.getClass().getMethod("isPaused"), engine);
    }

    private static boolean booleanMethod(Object receiver, String name)
            throws ReflectiveOperationException {
        Method method = receiver.getClass().getMethod(name);
        if (method.getReturnType() != boolean.class || method.getParameterCount() != 0) {
            throw new IllegalStateException("campaign-" + name + "-shape-mismatch");
        }
        return (Boolean) invoke(method, receiver);
    }

    private static List<Object> mappedPauseEvents(ClassLoader loader)
            throws ReflectiveOperationException {
        Class<?> eventClass = Class.forName(INPUT_EVENT, true, loader);
        Class<?> eventCategory = Class.forName(INPUT_EVENT_CLASS, true, loader);
        Class<?> eventType = Class.forName(INPUT_EVENT_TYPE, true, loader);
        Class<?> control = Class.forName(CONTROL, true, loader);
        Class<?> phase = Class.forName(CONTROL_PHASE, true, loader);
        Class<?> action = Class.forName(CONTROL_ACTION, true, loader);
        Class<?> binding = Class.forName(CONTROL_BINDING, true, loader);

        Object pause = enumValue(action, "GENERAL_PAUSE");
        Object buttonDown = enumValue(phase, "BUTTON_DOWN");
        Object pair = invoke(control.getMethod("o00000", action), null, pause);
        if (pair == null) throw new IllegalStateException("pause-binding-unavailable");
        Method[] keyCodeMethods = Arrays.stream(binding.getMethods())
                .filter(method -> method.getDeclaringClass() == binding)
                .filter(method -> method.getParameterCount() == 0)
                .filter(method -> method.getReturnType() == int.class)
                .filter(method -> !"hashCode".equals(method.getName()))
                .toArray(Method[]::new);
        if (keyCodeMethods.length != 1) {
            throw new IllegalStateException("pause-binding-shape-mismatch");
        }
        Method keyCode = keyCodeMethods[0];
        Method[] matchers = Arrays.stream(control.getMethods())
                .filter(method -> Modifier.isStatic(method.getModifiers()))
                .filter(method -> method.getReturnType() == boolean.class)
                .filter(method -> Arrays.equals(method.getParameterTypes(),
                        new Class<?>[] {eventClass, phase, action}))
                .toArray(Method[]::new);
        if (matchers.length != 1) throw new IllegalStateException("pause-matcher-shape-mismatch");
        Method matcher = matchers[0];
        Constructor<?> constructor = eventClass.getConstructor(
                eventCategory, eventType, int.class, int.class, int.class, char.class);
        Object keyboard = enumValue(eventCategory, "KEYBOARD_EVENT");
        Object keyDown = enumValue(eventType, "KEY_DOWN");
        Object keyUp = enumValue(eventType, "KEY_UP");

        for (String fieldName : List.of("one", "two")) {
            Field field = pair.getClass().getField(fieldName);
            Object candidate = field.get(pair);
            if (candidate == null || !binding.equals(candidate.getClass())) continue;
            int code = (Integer) invoke(keyCode, candidate);
            if (code <= 0 || code >= 2_000) continue;
            for (int modifiers = 0; modifiers < 8; modifiers++) {
                Object event = constructor.newInstance(keyboard, keyDown, 0, 0, code, '\0');
                setModifiers(eventClass, event, modifiers);
                if ((Boolean) invoke(matcher, null, event, buttonDown, pause)) {
                    Object release = constructor.newInstance(keyboard, keyUp, 0, 0, code, '\0');
                    setModifiers(eventClass, release, modifiers);
                    return List.of(event, release);
                }
            }
        }
        throw new IllegalStateException("no-keyboard-pause-binding");
    }

    private static void setModifiers(Class<?> eventClass, Object event, int modifiers)
            throws ReflectiveOperationException {
        eventClass.getMethod("setCtrlDown", boolean.class).invoke(event, (modifiers & 1) != 0);
        eventClass.getMethod("setAltDown", boolean.class).invoke(event, (modifiers & 2) != 0);
        eventClass.getMethod("setShiftDown", boolean.class).invoke(event, (modifiers & 4) != 0);
    }

    @SuppressWarnings({"rawtypes", "unchecked"})
    private static Object enumValue(Class<?> type, String name) {
        return Enum.valueOf((Class<? extends Enum>) type.asSubclass(Enum.class), name);
    }

    private static void invokeContinue(Object title) throws ReflectiveOperationException {
        if (!supportedTitleClassName(title.getClass().getName())) {
            throw new IllegalStateException("title-class-mismatch");
        }
        Object menu = invoke(title.getClass().getMethod("getMainMenu"), title);
        if (menu == null || !"com.fs.starfarer.title.C".equals(menu.getClass().getName())) {
            throw new IllegalStateException("main-menu-shape-mismatch");
        }

        Field[] callbackFields = Arrays.stream(menu.getClass().getDeclaredFields())
                .filter(field -> "com.fs.starfarer.title.C$o".equals(field.getType().getName()))
                .toArray(Field[]::new);
        if (callbackFields.length != 1) throw new IllegalStateException("title-callback-shape-mismatch");
        callbackFields[0].setAccessible(true);
        Object callback = callbackFields[0].get(menu);
        if (callback == null) throw new IllegalStateException("title-callback-unavailable");

        Method[] selectionMethods = Arrays.stream(callbackFields[0].getType().getMethods())
                .filter(method -> "menuItemSelected".equals(method.getName()))
                .filter(method -> method.getParameterCount() == 1)
                .filter(method -> "com.fs.starfarer.title.C$o$o"
                        .equals(method.getParameterTypes()[0].getName()))
                .toArray(Method[]::new);
        if (selectionMethods.length != 1) throw new IllegalStateException("title-action-shape-mismatch");
        Object continueAction = enumValue(selectionMethods[0].getParameterTypes()[0], "CONTINUE");
        invoke(selectionMethods[0], callback, continueAction);
    }

    static boolean supportedTitleClassName(String className) {
        return MainMenuInteractivePlan.TARGET_CLASS.replace('/', '.').equals(className)
                || MainMenuInteractivePlan.WINDOWS_TARGET_CLASS.replace('/', '.').equals(className);
    }

    private static Object invoke(Method method, Object receiver, Object... arguments)
            throws ReflectiveOperationException {
        try {
            return method.invoke(receiver, arguments);
        } catch (InvocationTargetException failed) {
            Throwable cause = failed.getCause();
            if (cause instanceof RuntimeException runtime) throw runtime;
            if (cause instanceof Error error) throw error;
            throw failed;
        }
    }

    private static void publishRejected(long sequence, String detail, Throwable failure) {
        publish(new Request(sequence, ProcessHandle.current().pid(),
                        RuntimeSemanticState.processStartedAt(), CONTINUE_ACTION,
                        INTERACTIVE_STATE, Instant.now()),
                "rejected", detail + ": " + bounded(failure), null, Instant.now(),
                "request.read", RuntimeSemanticState.currentState(), null, null);
    }

    private static void publish(
            Request request,
            String status,
            String detail,
            Instant acceptedAt,
            Instant executedAt,
            String boundary,
            String beforeState,
            Boolean beforePaused,
            Boolean afterPaused) {
        Path destination = receiptPath;
        if (destination == null || Files.exists(destination, LinkOption.NOFOLLOW_LINKS)) return;
        Map<String, Object> values = new LinkedHashMap<>();
        values.put("format", RECEIPT_FORMAT);
        values.put("sequence", request.sequence());
        values.put("pid", ProcessHandle.current().pid());
        values.put("processStartedAt", RuntimeSemanticState.processStartedAt());
        values.put("action", request.action());
        values.put("acceptedAt", acceptedAt);
        values.put("executedAt", executedAt);
        values.put("boundary", boundary);
        values.put("beforeState", beforeState);
        values.put("afterState", RuntimeSemanticState.currentState());
        values.put("beforePaused", beforePaused);
        values.put("afterPaused", afterPaused);
        values.put("status", status);
        values.put("detail", detail == null ? "" : detail);
        try {
            createOnce(destination, Json.object(values) + System.lineSeparator());
        } catch (IOException ignored) {
            // The runner treats a missing receipt as failure; gameplay must not crash for evidence I/O.
        }
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

    private static String bounded(Throwable failure) {
        String message = failure.getMessage();
        String value = failure.getClass().getSimpleName()
                + (message == null || message.isBlank() ? "" : ": " + message);
        return value.length() <= 500 ? value : value.substring(0, 500);
    }

    static synchronized void reset() {
        enabled = false;
        requestPath = null;
        receiptPath = null;
        nextPollNanos = 0L;
        completedSequence = 0L;
        pendingCampaignRequest = null;
        pendingCampaignAcceptedAt = null;
        clearCombatViewportBaseline();
        simulationEngaged = false;
        CombatStressFixtureRuntime.reset();
    }

    private record Request(
            long sequence,
            long pid,
            Instant processStartedAt,
            String action,
            String expectedState,
            Instant deadline) {
    }
}
