package dev.starsector.preflight.agent;

import dev.starsector.preflight.core.Json;
import java.io.IOException;
import java.lang.reflect.Field;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
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
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Closed, desktop-smoke-only game-thread actions addressed through the run directory. */
public final class InternalGameControlRuntime {
    static final String REQUEST_FORMAT = "starsector-preflight-runtime-action-request-v1";
    static final String RECEIPT_FORMAT = "starsector-preflight-runtime-action-receipt-v1";
    static final String CONTINUE_ACTION = "main-menu.continue";
    static final String INTERACTIVE_STATE = "main-menu-interactive";
    static final String CAMPAIGN_STATE = "campaign-ready";
    static final String REQUEST_FILE = "runtime-action-request.json";
    static final String RECEIPT_FILE = "runtime-action-receipt.json";
    private static final int MAX_REQUEST_BYTES = 4 * 1024;
    private static final long POLL_INTERVAL_NANOS = 20_000_000L;
    private static final Pattern REQUEST = Pattern.compile(
            "\\{\\\"format\\\":\\\"" + REQUEST_FORMAT
                    + "\\\",\\\"sequence\\\":([1-9][0-9]*),\\\"pid\\\":([1-9][0-9]*),"
                    + "\\\"processStartedAt\\\":\\\"([^\\\"]{1,80})\\\","
                    + "\\\"action\\\":\\\"" + CONTINUE_ACTION + "\\\","
                    + "\\\"expectedState\\\":\\\"" + INTERACTIVE_STATE + "\\\","
                    + "\\\"deadline\\\":\\\"([^\\\"]{1,80})\\\"\\}\\s*");

    private static volatile boolean enabled;
    private static Path requestPath;
    private static Path receiptPath;
    private static long nextPollNanos;
    private static long completedSequence;

    private InternalGameControlRuntime() {
    }

    static synchronized void beginSession(Path adapterReport) {
        enabled = Boolean.getBoolean("preflight.desktopSmoke");
        Path directory = adapterReport.toAbsolutePath().normalize().getParent();
        requestPath = directory == null ? null : directory.resolve(REQUEST_FILE);
        receiptPath = directory == null ? null : directory.resolve(RECEIPT_FILE);
        nextPollNanos = 0L;
        completedSequence = 0L;
        if (requestPath == null || receiptPath == null) enabled = false;
    }

    static boolean enabled() {
        return enabled;
    }

    /** Runs from the exact reviewed title {@code advanceImpl} boundary. */
    public static void titleAdvance(Object title) {
        if (!enabled || title == null || !RuntimeSemanticState.is(INTERACTIVE_STATE)) return;
        long now = System.nanoTime();
        if (now < nextPollNanos) return;
        nextPollNanos = now + POLL_INTERVAL_NANOS;
        Path request = requestPath;
        if (request == null || !Files.isRegularFile(request, LinkOption.NOFOLLOW_LINKS)) return;

        Request parsed;
        try {
            parsed = read(request);
        } catch (ThreadDeath | VirtualMachineError fatal) {
            throw fatal;
        } catch (Throwable failure) {
            publishRejected(0L, "request-invalid", failure);
            enabled = false;
            return;
        }
        if (parsed.sequence() <= completedSequence) return;
        completedSequence = parsed.sequence();
        String rejection = rejection(parsed);
        if (rejection != null) {
            publish(parsed, "rejected", rejection, null, null);
            return;
        }

        Instant accepted = Instant.now();
        try {
            invokeContinue(title);
            publish(parsed, "executed", "title callback accepted Continue", accepted, Instant.now());
        } catch (ThreadDeath | VirtualMachineError fatal) {
            throw fatal;
        } catch (Throwable failure) {
            publish(parsed, "failed", bounded(failure), accepted, Instant.now());
        }
    }

    private static Request read(Path source) throws IOException {
        long size = Files.size(source);
        if (size <= 0 || size > MAX_REQUEST_BYTES) {
            throw new IOException("request size is outside 1.." + MAX_REQUEST_BYTES + " bytes");
        }
        String raw = Files.readString(source, StandardCharsets.UTF_8);
        Matcher match = REQUEST.matcher(raw);
        if (!match.matches()) throw new IOException("request shape or field order is not canonical");
        try {
            return new Request(
                    Long.parseLong(match.group(1)),
                    Long.parseLong(match.group(2)),
                    Instant.parse(match.group(3)),
                    Instant.parse(match.group(4)));
        } catch (RuntimeException invalid) {
            throw new IOException("request identity or deadline is invalid", invalid);
        }
    }

    private static String rejection(Request request) {
        if (request.pid() != ProcessHandle.current().pid()) return "pid-mismatch";
        Instant actualStart = RuntimeSemanticState.processStartedAt();
        if (actualStart == null || !actualStart.equals(request.processStartedAt())) {
            return "process-start-mismatch";
        }
        if (!RuntimeSemanticState.is(INTERACTIVE_STATE)) return "before-state-mismatch";
        if (Instant.now().isAfter(request.deadline())) return "deadline-expired";
        if (Files.exists(receiptPath, LinkOption.NOFOLLOW_LINKS)) return "receipt-already-exists";
        return null;
    }

    private static void invokeContinue(Object title) throws ReflectiveOperationException {
        if (!MainMenuInteractivePlan.TARGET_CLASS.replace('/', '.').equals(title.getClass().getName())) {
            throw new IllegalStateException("title-class-mismatch");
        }
        Method getMainMenu = title.getClass().getMethod("getMainMenu");
        Object menu = invoke(getMainMenu, title);
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
        Class<?> actionType = selectionMethods[0].getParameterTypes()[0];
        @SuppressWarnings({"rawtypes", "unchecked"})
        Object continueAction = Enum.valueOf((Class<? extends Enum>) actionType.asSubclass(Enum.class), "CONTINUE");
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
                RuntimeSemanticState.processStartedAt(), Instant.now()),
                "rejected", detail + ": " + bounded(failure), null, Instant.now());
    }

    private static void publish(
            Request request,
            String status,
            String detail,
            Instant acceptedAt,
            Instant executedAt) {
        Path destination = receiptPath;
        if (destination == null || Files.exists(destination, LinkOption.NOFOLLOW_LINKS)) return;
        Map<String, Object> values = new LinkedHashMap<>();
        values.put("format", RECEIPT_FORMAT);
        values.put("sequence", request.sequence());
        values.put("pid", ProcessHandle.current().pid());
        values.put("processStartedAt", RuntimeSemanticState.processStartedAt());
        values.put("action", CONTINUE_ACTION);
        values.put("acceptedAt", acceptedAt);
        values.put("executedAt", executedAt);
        values.put("boundary", "title.advanceImpl");
        values.put("beforeState", INTERACTIVE_STATE);
        values.put("afterState", RuntimeSemanticState.currentState());
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
    }

    private record Request(long sequence, long pid, Instant processStartedAt, Instant deadline) {
    }
}
