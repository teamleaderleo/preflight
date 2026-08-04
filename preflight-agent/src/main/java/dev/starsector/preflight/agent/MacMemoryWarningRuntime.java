package dev.starsector.preflight.agent;

import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Uses macOS's pressure-aware available-memory estimate for Starsector's system-RAM warning. */
public final class MacMemoryWarningRuntime {
    static final String PLAN_ID = "macos-pressure-aware-system-ram-warning-v1";
    static final long WARNING_THRESHOLD_MIB = 1_000L;

    private static final Pattern AVAILABLE_PERCENT = Pattern.compile(
            "System-wide memory free percentage:\\s*(\\d{1,3})%");
    private static final AtomicLong checks = new AtomicLong();
    private static final AtomicLong macChecks = new AtomicLong();
    private static final AtomicLong corrections = new AtomicLong();
    private static final AtomicLong warningsPreserved = new AtomicLong();
    private static final AtomicLong probeFailures = new AtomicLong();
    private static volatile long lastReportedFreeMiB = -1L;
    private static volatile long lastTotalMiB = -1L;
    private static volatile int lastAvailablePercent = -1;
    private static volatile long lastEstimatedAvailableMiB = -1L;
    private static volatile String lastProbeFailure;
    private static volatile boolean installed;

    private MacMemoryWarningRuntime() {
    }

    static void installed() {
        installed = true;
    }

    /** Returns the exact boolean consumed by the retained vanilla warning branch. */
    public static boolean shouldWarn(long reportedFreeMiB, long totalMiB) {
        String os = System.getProperty("os.name", "").toLowerCase(Locale.ROOT);
        return shouldWarn(reportedFreeMiB, totalMiB, os.startsWith("mac"),
                MacMemoryWarningRuntime::readAvailablePercent);
    }

    static boolean shouldWarn(
            long reportedFreeMiB, long totalMiB, boolean macOs, PressureProbe probe) {
        checks.incrementAndGet();
        lastReportedFreeMiB = reportedFreeMiB;
        lastTotalMiB = totalMiB;
        lastAvailablePercent = -1;
        lastEstimatedAvailableMiB = -1L;
        lastProbeFailure = null;
        if (reportedFreeMiB >= WARNING_THRESHOLD_MIB) {
            return false;
        }
        if (!macOs) {
            warningsPreserved.incrementAndGet();
            return true;
        }

        macChecks.incrementAndGet();
        try {
            int availablePercent = probe.availablePercent();
            if (availablePercent < 0 || availablePercent > 100 || totalMiB <= 0L) {
                throw new IllegalStateException("invalid macOS memory-pressure result");
            }
            long estimatedAvailableMiB = totalMiB * availablePercent / 100L;
            lastAvailablePercent = availablePercent;
            lastEstimatedAvailableMiB = estimatedAvailableMiB;
            if (estimatedAvailableMiB < WARNING_THRESHOLD_MIB) {
                warningsPreserved.incrementAndGet();
                return true;
            }
            corrections.incrementAndGet();
            return false;
        } catch (ThreadDeath | VirtualMachineError fatal) {
            throw fatal;
        } catch (Throwable failure) {
            probeFailures.incrementAndGet();
            warningsPreserved.incrementAndGet();
            String message = failure.getMessage();
            lastProbeFailure = failure.getClass().getSimpleName()
                    + (message == null || message.isBlank() ? "" : ": " + message);
            return true;
        }
    }

    static int parseAvailablePercent(String output) {
        Matcher matcher = AVAILABLE_PERCENT.matcher(output);
        if (!matcher.find()) {
            throw new IllegalArgumentException("macOS memory-pressure percentage was absent");
        }
        int value = Integer.parseInt(matcher.group(1));
        if (value < 0 || value > 100) {
            throw new IllegalArgumentException("macOS memory-pressure percentage was invalid");
        }
        return value;
    }

    private static int readAvailablePercent() throws Exception {
        ProcessBuilder builder = new ProcessBuilder("/usr/bin/memory_pressure", "-Q")
                .redirectErrorStream(true);
        builder.environment().put("LC_ALL", "C");
        builder.environment().put("LANG", "C");
        Process process = builder.start();
        boolean interrupted = false;
        try {
            if (!process.waitFor(2L, TimeUnit.SECONDS)) {
                process.destroyForcibly();
                throw new IllegalStateException("macOS memory-pressure probe timed out");
            }
            String output = new String(process.getInputStream().readAllBytes(),
                    StandardCharsets.UTF_8);
            if (process.exitValue() != 0) {
                throw new IllegalStateException("macOS memory-pressure probe exited "
                        + process.exitValue());
            }
            return parseAvailablePercent(output);
        } catch (InterruptedException failure) {
            interrupted = true;
            process.destroyForcibly();
            throw failure;
        } finally {
            if (interrupted) {
                Thread.currentThread().interrupt();
            }
        }
    }

    static Map<String, Object> telemetry() {
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("planId", PLAN_ID);
        result.put("installed", installed);
        result.put("checks", checks.get());
        result.put("macChecks", macChecks.get());
        result.put("corrections", corrections.get());
        result.put("warningsPreserved", warningsPreserved.get());
        result.put("probeFailures", probeFailures.get());
        result.put("lastReportedFreeMiB", lastReportedFreeMiB);
        result.put("lastTotalMiB", lastTotalMiB);
        result.put("lastAvailablePercent", lastAvailablePercent);
        result.put("lastEstimatedAvailableMiB", lastEstimatedAvailableMiB);
        result.put("lastProbeFailure", lastProbeFailure);
        return result;
    }

    static void reset() {
        installed = false;
        checks.set(0L);
        macChecks.set(0L);
        corrections.set(0L);
        warningsPreserved.set(0L);
        probeFailures.set(0L);
        lastReportedFreeMiB = -1L;
        lastTotalMiB = -1L;
        lastAvailablePercent = -1;
        lastEstimatedAvailableMiB = -1L;
        lastProbeFailure = null;
    }

    @FunctionalInterface
    interface PressureProbe {
        int availablePercent() throws Exception;
    }
}
