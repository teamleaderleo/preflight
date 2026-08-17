package dev.starsector.preflight.agent;

import dev.starsector.preflight.core.Json;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;

/** Low-frequency live game state for semantic desktop smoke waits. */
public final class RuntimeSemanticState {
    static final String FORMAT = "starsector-preflight-runtime-state-v1";
    private static final int STARTING = 0;
    private static final int MAIN_MENU = 1;
    private static final int CAMPAIGN = 2;
    private static final int COMBAT = 3;
    private static final int STOPPED = 4;

    private static volatile boolean enabled;
    private static volatile int state = STARTING;
    private static Path destination;
    private static long sequence;
    private static String writeProblem;
    private static Instant processStartedAt;
    private static Instant mainMenuReadyAt;

    private RuntimeSemanticState() {
    }

    static synchronized void beginSession(Path reportDestination) throws IOException {
        destination = reportDestination.toAbsolutePath().normalize();
        state = STARTING;
        sequence = 0L;
        writeProblem = null;
        processStartedAt = ProcessHandle.current().info().startInstant().orElse(null);
        mainMenuReadyAt = null;
        enabled = true;
        try {
            write();
        } catch (IOException error) {
            enabled = false;
            writeProblem = error.getClass().getSimpleName() + ": " + error.getMessage();
            throw error;
        }
    }

    static boolean enabled() {
        return enabled;
    }

    public static void mainMenuReady() {
        transition(MAIN_MENU);
    }

    public static void campaignReady() {
        transition(CAMPAIGN);
    }

    public static void combatReady() {
        transition(COMBAT);
    }

    static synchronized void stopped() {
        transition(STOPPED);
        enabled = false;
    }

    private static void transition(int next) {
        if (!enabled || state == next) return;
        synchronized (RuntimeSemanticState.class) {
            if (!enabled || state == next) return;
            if (next == MAIN_MENU && mainMenuReadyAt == null) {
                mainMenuReadyAt = Instant.now();
            }
            state = next;
            sequence++;
            try {
                write();
            } catch (ThreadDeath | VirtualMachineError fatal) {
                throw fatal;
            } catch (Throwable error) {
                enabled = false;
                writeProblem = error.getClass().getSimpleName() + ": " + error.getMessage();
            }
        }
    }

    static synchronized Map<String, Object> telemetry() {
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("format", FORMAT);
        result.put("enabled", enabled);
        result.put("state", name(state));
        result.put("sequence", sequence);
        result.put("writeProblem", writeProblem);
        result.put("destination", destination);
        result.put("processStartedAt", processStartedAt);
        result.put("mainMenuReadyAt", mainMenuReadyAt);
        return result;
    }

    static synchronized void reset() {
        enabled = false;
        state = STARTING;
        destination = null;
        sequence = 0L;
        writeProblem = null;
        processStartedAt = null;
        mainMenuReadyAt = null;
    }

    private static void write() throws IOException {
        ProcessHandle process = ProcessHandle.current();
        Map<String, Object> values = new LinkedHashMap<>();
        values.put("format", FORMAT);
        values.put("pid", process.pid());
        values.put("processStartedAt", processStartedAt);
        values.put("mainMenuReadyAt", mainMenuReadyAt);
        values.put("state", name(state));
        values.put("sequence", sequence);
        values.put("observedAt", Instant.now());
        atomicWrite(destination, Json.object(values) + System.lineSeparator());
    }

    private static String name(int value) {
        return switch (value) {
            case MAIN_MENU -> "main-menu-ready";
            case CAMPAIGN -> "campaign-ready";
            case COMBAT -> "combat-ready";
            case STOPPED -> "stopped";
            default -> "starting";
        };
    }

    private static void atomicWrite(Path destination, String value) throws IOException {
        Path parent = destination.getParent();
        if (parent != null) Files.createDirectories(parent);
        Path temporary = destination.resolveSibling(destination.getFileName()
                + ".tmp-" + ProcessHandle.current().pid() + "-" + System.nanoTime());
        boolean moved = false;
        try {
            Files.writeString(temporary, value, StandardCharsets.UTF_8,
                    StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE);
            try {
                Files.move(temporary, destination,
                        StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
            } catch (AtomicMoveNotSupportedException ignored) {
                Files.move(temporary, destination, StandardCopyOption.REPLACE_EXISTING);
            }
            moved = true;
        } finally {
            if (!moved) Files.deleteIfExists(temporary);
        }
    }
}
