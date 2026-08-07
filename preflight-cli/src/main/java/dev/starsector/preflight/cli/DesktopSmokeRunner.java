package dev.starsector.preflight.cli;

import dev.starsector.preflight.core.Json;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

/** Executes semantic smoke steps against one PID-addressed driver and seals the result. */
final class DesktopSmokeRunner {
    private static final Set<String> OBSERVED_ACTIONS = Set.of(
            "activate-window", "click", "press-key", "hold-key");

    private DesktopSmokeRunner() {
    }

    static Map<String, Object> run(
            DesktopSmokeScenario scenario,
            Path runtimeProcess,
            Path runDirectory,
            DesktopSmokeDriver driver,
            Clock clock) throws IOException {
        try (DriverCalls calls = new DriverCalls(scenario.timeoutSeconds())) {
            return run(scenario, runtimeProcess, runDirectory, driver, clock, calls);
        }
    }

    private static Map<String, Object> run(
            DesktopSmokeScenario scenario,
            Path runtimeProcess,
            Path runDirectory,
            DesktopSmokeDriver driver,
            Clock clock,
            DriverCalls calls) throws IOException {
        Path realRun = runDirectory.toRealPath();
        Instant startedAt = clock.instant();
        List<Map<String, Object>> completedSteps = new ArrayList<>();
        List<String> diagnostics = new ArrayList<>();
        DesktopSmokeDriver.Descriptor descriptor;
        try {
            descriptor = calls.call(driver::descriptor, 10);
            if (descriptor == null) {
                throw new IllegalStateException("Driver returned no descriptor");
            }
            descriptor = normalize(descriptor);
        } catch (DesktopSmokeDriver.UnavailableException unavailable) {
            descriptor = unavailableDescriptor();
            diagnostics.add(message(unavailable));
            return seal(scenario, realRun, descriptor, "skipped", startedAt, clock.instant(),
                    completedSteps, diagnostics);
        } catch (Exception failure) {
            descriptor = unavailableDescriptor();
            diagnostics.add("Driver probe failed: " + message(failure));
            return seal(scenario, realRun, descriptor, "skipped", startedAt, clock.instant(),
                    completedSteps, diagnostics);
        }
        if (descriptor.diagnostics() != null) {
            descriptor.diagnostics().stream().map(DesktopSmokeRunner::bounded).forEach(diagnostics::add);
        }

        Set<String> capabilities = descriptor.capabilities() == null
                ? Set.of()
                : new LinkedHashSet<>(descriptor.capabilities());
        Set<String> missing = new LinkedHashSet<>(scenario.requiredCapabilities());
        missing.removeAll(capabilities);
        if (!missing.isEmpty()) {
            diagnostics.add("Driver is missing required capabilities: " + String.join(", ", missing));
            return seal(scenario, realRun, descriptor, "skipped", startedAt, clock.instant(),
                    completedSteps, diagnostics);
        }

        TargetCheck initial = target(runtimeProcess);
        if (!initial.attachable()) {
            diagnostics.add("Runtime process isn't attachable: " + initial.reason());
            return seal(scenario, realRun, descriptor, "skipped", startedAt, clock.instant(),
                    completedSteps, diagnostics);
        }
        try {
            calls.call(() -> {
                driver.attach(initial.target());
                return null;
            }, 10);
        } catch (DesktopSmokeDriver.UnavailableException unavailable) {
            diagnostics.add("Driver attachment unavailable: " + message(unavailable));
            return seal(scenario, realRun, descriptor, "skipped", startedAt, clock.instant(),
                    completedSteps, diagnostics);
        } catch (Exception failure) {
            diagnostics.add("Driver attachment failed: " + message(failure));
            return failFirstStep(scenario, realRun, descriptor, startedAt, clock,
                    completedSteps, diagnostics, "Driver attachment failed");
        }

        Instant deadline = startedAt.plusSeconds(scenario.timeoutSeconds());
        List<Map<String, Object>> steps = scenario.stepViews();
        for (Map<String, Object> step : steps) {
            String id = step.get("id").toString();
            String kind = step.get("kind").toString();
            Instant stepStarted = clock.instant();
            if (stepStarted.isAfter(deadline) || calls.expired()) {
                diagnostics.add("Scenario timeout expired before step " + id);
                completedSteps.add(step(id, "failed", stepStarted, stepStarted,
                        "Scenario timeout expired", List.of()));
                return seal(scenario, realRun, descriptor, "failed", startedAt, clock.instant(),
                        completedSteps, diagnostics);
            }
            TargetCheck current = target(runtimeProcess);
            if (!current.attachable()) {
                diagnostics.add("Runtime process became unavailable at " + id + ": " + current.reason());
                completedSteps.add(step(id, "failed", stepStarted, clock.instant(),
                        "Runtime process became unavailable", List.of()));
                return seal(scenario, realRun, descriptor, "failed", startedAt, clock.instant(),
                        completedSteps, diagnostics);
            }
            try {
                DesktopSmokeDriver.ActionResult action = calls.call(
                        () -> driver.execute(step, realRun), stepTimeoutSeconds(step, scenario));
                if (action == null) {
                    throw new IllegalStateException("Driver returned no action result");
                }
                String detail = bounded(action.detail());
                if (OBSERVED_ACTIONS.contains(kind)) {
                    DesktopSmokeDriver.Observation observation = calls.call(driver::observe, 10);
                    if (observation == null) {
                        throw new IllegalStateException("Driver returned no fresh observation");
                    }
                    String observed = bounded(observation.detail());
                    detail = detail.isBlank() ? observed : detail + "; observed: " + observed;
                }
                List<Map<String, Object>> artifacts = artifacts(action.artifacts());
                if ("quit".equals(kind)) {
                    TargetCheck afterQuit = target(runtimeProcess);
                    if (afterQuit.attachable()) {
                        throw new IllegalStateException(
                                "Quit returned while the runtime process was still attachable");
                    }
                }
                Instant stepCompleted = clock.instant();
                if (stepCompleted.isAfter(deadline) || calls.expired()) {
                    throw new IllegalStateException("Scenario timeout expired during step " + id);
                }
                completedSteps.add(step(id, "passed", stepStarted, stepCompleted, detail, artifacts));
            } catch (DesktopSmokeDriver.UnavailableException unavailable) {
                diagnostics.add("Driver became unavailable at " + id + ": " + message(unavailable));
                completedSteps.add(step(id, "skipped", stepStarted, clock.instant(),
                        message(unavailable), List.of()));
                return seal(scenario, realRun, descriptor, "skipped", startedAt, clock.instant(),
                        completedSteps, diagnostics);
            } catch (Exception failure) {
                diagnostics.add("Driver failed at " + id + ": " + message(failure));
                completedSteps.add(step(id, "failed", stepStarted, clock.instant(),
                        message(failure), List.of()));
                return seal(scenario, realRun, descriptor, "failed", startedAt, clock.instant(),
                        completedSteps, diagnostics);
            }
        }
        return seal(scenario, realRun, descriptor, "passed", startedAt, clock.instant(),
                completedSteps, diagnostics);
    }

    private static int stepTimeoutSeconds(
            Map<String, Object> step, DesktopSmokeScenario scenario) {
        Object value = step.get("timeoutSeconds");
        return value instanceof Number number
                ? Math.min(number.intValue(), scenario.timeoutSeconds())
                : scenario.timeoutSeconds();
    }

    private static Map<String, Object> failFirstStep(
            DesktopSmokeScenario scenario,
            Path runDirectory,
            DesktopSmokeDriver.Descriptor descriptor,
            Instant startedAt,
            Clock clock,
            List<Map<String, Object>> steps,
            List<String> diagnostics,
            String detail) throws IOException {
        Instant now = clock.instant();
        steps.add(step(scenario.stepIds().get(0), "failed", now, now, detail, List.of()));
        return seal(scenario, runDirectory, descriptor, "failed", startedAt, now, steps, diagnostics);
    }

    private static TargetCheck target(Path runtimeProcess) {
        try {
            Map<String, Object> inspected = RuntimeProcessIdentity.read(runtimeProcess).inspect();
            boolean attachable = Boolean.TRUE.equals(inspected.get("attachable"));
            String reason = inspected.get("reason") == null ? "unknown" : inspected.get("reason").toString();
            if (!attachable) return new TargetCheck(null, false, reason);
            long pid = ((Number) inspected.get("pid")).longValue();
            Instant startedAt = (Instant) inspected.get("startedAt");
            return new TargetCheck(new DesktopSmokeDriver.ProcessTarget(pid, startedAt), true, null);
        } catch (Exception error) {
            return new TargetCheck(null, false, message(error));
        }
    }

    private static List<Map<String, Object>> artifacts(List<DesktopSmokeDriver.Artifact> artifacts) {
        if (artifacts == null) return List.of();
        return artifacts.stream().map(artifact -> {
            Map<String, Object> value = new LinkedHashMap<>();
            value.put("kind", artifact.kind());
            value.put("path", artifact.path());
            return value;
        }).toList();
    }

    private static Map<String, Object> step(
            String id,
            String status,
            Instant startedAt,
            Instant completedAt,
            String detail,
            List<Map<String, Object>> artifacts) {
        Map<String, Object> value = new LinkedHashMap<>();
        value.put("id", id);
        value.put("status", status);
        value.put("startedAt", startedAt);
        value.put("completedAt", completedAt);
        value.put("detail", bounded(detail));
        value.put("artifacts", artifacts);
        return value;
    }

    private static Map<String, Object> seal(
            DesktopSmokeScenario scenario,
            Path runDirectory,
            DesktopSmokeDriver.Descriptor descriptor,
            String status,
            Instant startedAt,
            Instant completedAt,
            List<Map<String, Object>> steps,
            List<String> diagnostics) throws IOException {
        Map<String, Object> driver = new LinkedHashMap<>();
        driver.put("id", descriptor.id());
        driver.put("version", descriptor.version());
        driver.put("platform", descriptor.platform());
        driver.put("capabilities", descriptor.capabilities() == null
                ? List.of()
                : List.copyOf(descriptor.capabilities()));
        Map<String, Object> request = new LinkedHashMap<>();
        request.put("format", DesktopSmokeEvidence.DRIVER_RESULT_FORMAT);
        request.put("status", status);
        request.put("startedAt", startedAt);
        request.put("completedAt", completedAt.isBefore(startedAt) ? startedAt : completedAt);
        request.put("driver", driver);
        request.put("runDirectory", runDirectory);
        request.put("steps", List.copyOf(steps));
        request.put("diagnostics", diagnostics.stream().map(DesktopSmokeRunner::bounded).limit(100).toList());
        Path driverResult = runDirectory.resolve("driver-result.json");
        atomicWrite(driverResult, Json.object(request) + System.lineSeparator());
        return DesktopSmokeEvidence.collect(
                scenario, driverResult, runDirectory.resolve("smoke-evidence.json"));
    }

    private static DesktopSmokeDriver.Descriptor unavailableDescriptor() {
        String platform = switch (Platform.current()) {
            case MAC -> "mac";
            case WINDOWS -> "windows";
            case LINUX -> "linux";
            case OTHER -> "other";
        };
        return new DesktopSmokeDriver.Descriptor(
                "unavailable-driver", "unknown", platform, Set.of(), List.of());
    }

    private static DesktopSmokeDriver.Descriptor normalize(
            DesktopSmokeDriver.Descriptor descriptor) {
        if (descriptor.id() == null
                || descriptor.version() == null
                || descriptor.platform() == null) {
            throw new IllegalArgumentException("Driver descriptor identity is incomplete");
        }
        Set<String> capabilities = descriptor.capabilities() == null
                ? Set.of()
                : Collections.unmodifiableSet(new LinkedHashSet<>(descriptor.capabilities()));
        List<String> diagnostics = descriptor.diagnostics() == null
                ? List.of()
                : descriptor.diagnostics().stream().map(DesktopSmokeRunner::bounded).limit(100).toList();
        return new DesktopSmokeDriver.Descriptor(
                descriptor.id(), descriptor.version(), descriptor.platform(), capabilities, diagnostics);
    }

    private static String bounded(String value) {
        String text = value == null ? "" : value;
        return text.length() <= 2_000 ? text : text.substring(0, 2_000);
    }

    private static String message(Throwable error) {
        String message = error.getMessage();
        return bounded(message == null || message.isBlank()
                ? error.getClass().getSimpleName()
                : message);
    }

    private static void atomicWrite(Path destination, String value) throws IOException {
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

    private record TargetCheck(
            DesktopSmokeDriver.ProcessTarget target, boolean attachable, String reason) {
    }

    private static final class DriverCalls implements AutoCloseable {
        private final ExecutorService executor = Executors.newSingleThreadExecutor(task -> {
            Thread thread = new Thread(task, "Preflight-Desktop-Smoke-Driver");
            thread.setDaemon(true);
            return thread;
        });
        private final long deadlineNanos;

        private DriverCalls(int timeoutSeconds) {
            deadlineNanos = System.nanoTime() + TimeUnit.SECONDS.toNanos(timeoutSeconds);
        }

        private <T> T call(Callable<T> operation, int maximumSeconds) throws Exception {
            long remaining = deadlineNanos - System.nanoTime();
            long allowed = Math.min(remaining, TimeUnit.SECONDS.toNanos(maximumSeconds));
            if (allowed <= 0) throw new TimeoutException("Desktop smoke deadline expired");
            Future<T> future = executor.submit(operation);
            try {
                return future.get(allowed, TimeUnit.NANOSECONDS);
            } catch (TimeoutException timeout) {
                future.cancel(true);
                throw new TimeoutException("Desktop smoke driver call exceeded its deadline");
            } catch (InterruptedException interrupted) {
                future.cancel(true);
                Thread.currentThread().interrupt();
                throw interrupted;
            } catch (ExecutionException wrapped) {
                Throwable cause = wrapped.getCause();
                if (cause instanceof ThreadDeath fatal) throw fatal;
                if (cause instanceof VirtualMachineError fatal) throw fatal;
                if (cause instanceof Exception exception) throw exception;
                throw new RuntimeException(cause);
            }
        }

        private boolean expired() {
            return System.nanoTime() >= deadlineNanos;
        }

        @Override
        public void close() {
            executor.shutdownNow();
        }
    }
}
