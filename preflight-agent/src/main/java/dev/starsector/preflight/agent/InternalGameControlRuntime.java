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
    static final String REQUEST_FORMAT = "starsector-preflight-runtime-action-request-v2";
    static final String RECEIPT_FORMAT = "starsector-preflight-runtime-action-receipt-v2";
    static final String CONTINUE_ACTION = "main-menu.continue";
    static final String CAMPAIGN_PAUSE_ACTION = "campaign.pause";
    static final String CAMPAIGN_UNPAUSE_ACTION = "campaign.unpause";
    static final String INTERACTIVE_STATE = "main-menu-interactive";
    static final String CAMPAIGN_STATE = "campaign-ready";
    static final String REQUEST_FILE = "runtime-action-request.json";
    static final String RECEIPT_FILE = "runtime-action-receipt.json";
    private static final String CAMPAIGN_CLASS = "com.fs.starfarer.campaign.CampaignState";
    private static final String CAMPAIGN_ENGINE = "com.fs.starfarer.campaign.CampaignEngine";
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
                    + "\\\"action\\\":\\\"(main-menu\\.continue|campaign\\.(?:pause|unpause))\\\","
                    + "\\\"expectedState\\\":\\\"(main-menu-interactive|campaign-ready)\\\","
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
        if (requestPath == null || receiptPath == null) enabled = false;
    }

    static boolean enabled() {
        return enabled;
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
        return CONTINUE_ACTION.equals(action) ? INTERACTIVE_STATE : CAMPAIGN_STATE;
    }

    private static boolean isCampaignAction(String action) {
        return CAMPAIGN_PAUSE_ACTION.equals(action) || CAMPAIGN_UNPAUSE_ACTION.equals(action);
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
        if (!MainMenuInteractivePlan.TARGET_CLASS.replace('/', '.').equals(title.getClass().getName())) {
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
