package dev.starsector.preflight.agent;

import java.lang.instrument.Instrumentation;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import jdk.jfr.Category;
import jdk.jfr.Configuration;
import jdk.jfr.Event;
import jdk.jfr.Label;
import jdk.jfr.Name;
import jdk.jfr.Recording;
import jdk.jfr.RecordingState;

/** Launch-time JFR and optional fail-closed adapter agent for Starsector Preflight. */
public final class PreflightAgent {
    private PreflightAgent() {
    }

    public static void premain(String agentArgs, Instrumentation instrumentation) {
        start(agentArgs, instrumentation);
    }

    public static void agentmain(String agentArgs, Instrumentation instrumentation) {
        start(agentArgs, instrumentation);
    }

    private static void start(String agentArgs, Instrumentation instrumentation) {
        AgentOptions options = contain("Agent options", () -> AgentOptions.parse(agentArgs));
        if (options == null) {
            return;
        }

        AdapterRuntime.Session adapterSession = contain(
                "Adapter initialization",
                () -> AdapterRuntime.start(options, instrumentation));
        Recording recording = startRecording(options);
        try {
            Runtime.getRuntime().addShutdownHook(new Thread(
                    () -> {
                        markStopping(recording);
                        closeAdapter(adapterSession);
                    },
                    "Preflight-Shutdown"));
        } catch (ThreadDeath | VirtualMachineError fatal) {
            throw fatal;
        } catch (Throwable error) {
            log("Could not register shutdown hook: " + message(error));
            stopRecording(recording, options.destination());
            closeAdapter(adapterSession);
        }
    }

    static <T> T contain(String component, Startup<T> startup) {
        try {
            return startup.start();
        } catch (ThreadDeath | VirtualMachineError fatal) {
            throw fatal;
        } catch (Throwable error) {
            log(component + " disabled: " + message(error));
            return null;
        }
    }

    private static void closeAdapter(AdapterRuntime.Session adapterSession) {
        if (adapterSession == null) {
            return;
        }
        contain("Adapter report finalization", () -> {
            adapterSession.close();
            return null;
        });
    }

    private static Recording startRecording(AgentOptions options) {
        try {
            Path destination = options.destination().toAbsolutePath().normalize();
            Path parent = destination.getParent();
            if (parent != null) {
                Files.createDirectories(parent);
            }

            Configuration configuration = Configuration.getConfiguration(options.settings());
            Recording recording = new Recording(configuration);
            recording.setName("Starsector Preflight startup");
            recording.setToDisk(true);
            // The JVM must dump this recording from its own shutdown hook. That hook writes the
            // destination before it wipes the chunk repository; a competing hook of ours that
            // stopped the recording could be caught between those two steps and dump a recording
            // whose chunks had already been deleted, leaving an empty file behind.
            recording.setDumpOnExit(true);
            recording.setDestination(destination);
            configureStartupEvents(recording, options);
            recording.start();

            AgentStarted started = new AgentStarted();
            started.destination = destination.toString();
            started.adapterMode = options.adapterMode().name();
            // Recorded so a reader can tell "no file read event" from "file reads were filtered".
            started.exhaustiveFileReads = options.exhaustiveFileReads();
            // Flight Recorder stores the path the JVM passed to the OS, so a game that opens its own
            // resources by relative path -- which Starsector does, its launcher having changed into
            // the core resource directory first -- leaves paths that mean nothing without this. An
            // analyser that guesses the base gets it wrong on any layout that does not match the
            // guess, and wrong quietly: every resource simply looks unopened. The process that holds
            // the answer is this one, so it records it rather than leaving it to be inferred.
            started.workingDirectory = System.getProperty("user.dir", "");
            started.commit();
            log("Recording startup to " + destination);
            if (options.adapterMode() != AdapterMode.OFF) {
                log("Adapter mode " + options.adapterMode() + "; report "
                        + options.adapterReport().toAbsolutePath().normalize());
            }
            return recording;
        } catch (ThreadDeath | VirtualMachineError fatal) {
            throw fatal;
        } catch (Throwable error) {
            log("Profiler disabled: " + message(error));
            return null;
        }
    }

    private static void configureStartupEvents(Recording recording, AgentOptions options) {
        recording.enable("jdk.InitialSystemProperty");
        recording.enable("jdk.JVMInformation");
        recording.enable("jdk.OSInformation");
        recording.enable("jdk.CPUInformation");
        if (options.exhaustiveFileReads()) {
            // Every read, however fast. The millisecond threshold below is right for "what cost time"
            // and wrong for "what was opened": a warm page-cache read of a small file finishes far
            // inside it, so a file the game genuinely loads can leave no event at all. Costs a larger
            // recording and some overhead, neither of which changes which files get opened.
            recording.enable("jdk.FileRead").withThreshold(Duration.ZERO).withStackTrace();
        } else {
            recording.enable("jdk.FileRead").withThreshold(Duration.ofMillis(1)).withStackTrace();
        }
        recording.enable("jdk.FileWrite").withThreshold(Duration.ofMillis(1)).withStackTrace();
        recording.enable("jdk.ClassLoad").withStackTrace();
        recording.enable("jdk.ClassDefine").withStackTrace();
        recording.enable("jdk.Compilation").withThreshold(Duration.ofMillis(1)).withStackTrace();
        recording.enable("jdk.GCPhasePause").withStackTrace();
        recording.enable("jdk.ThreadPark").withThreshold(Duration.ofMillis(1)).withStackTrace();
        recording.enable("jdk.ThreadSleep").withThreshold(Duration.ofMillis(1)).withStackTrace();
        recording.enable("jdk.ExecutionSample").withPeriod(Duration.ofMillis(10)).withStackTrace();
    }

    /**
     * Marks the end of startup while the JVM shuts down. The recording itself is dumped by the
     * JVM's own shutdown hook, which runs alongside this one, so the event is best effort: it lands
     * in the recording only when this hook reaches it before that dump.
     */
    private static void markStopping(Recording recording) {
        if (recording == null || recording.getState() != RecordingState.RUNNING) {
            return;
        }
        try {
            AgentStopping stopping = new AgentStopping();
            stopping.commit();
        } catch (Throwable error) {
            log("Could not record shutdown: " + message(error));
        }
    }

    private static void stopRecording(Recording recording, Path destination) {
        if (recording == null) {
            return;
        }
        try {
            if (recording.getState() == RecordingState.RUNNING) {
                AgentStopping stopping = new AgentStopping();
                stopping.commit();
                try {
                    recording.stop();
                } catch (IllegalStateException ignored) {
                    // The JVM may stop the destination-backed recording concurrently during shutdown.
                }
            }
            if (recording.getState() != RecordingState.CLOSED) {
                recording.close();
            }
            if (!Files.isRegularFile(destination)) {
                log("Recording ended without creating " + destination);
            } else if (Files.size(destination) == 0L) {
                log("Recording ended with an empty " + destination);
            } else {
                log("Wrote startup recording to " + destination);
            }
        } catch (Throwable error) {
            log("Failed to finish recording: " + message(error));
        }
    }

    private static String message(Throwable error) {
        String value = error.getMessage();
        return value == null || value.isBlank() ? error.getClass().getSimpleName() : value;
    }

    private static void log(String message) {
        System.err.println("[Preflight] " + message);
    }

    @FunctionalInterface
    interface Startup<T> {
        T start() throws Throwable;
    }

    @Name("preflight.AgentStarted")
    @Label("Preflight Agent Started")
    @Category("Starsector Preflight")
    static final class AgentStarted extends Event {
        @Label("Destination")
        String destination;

        @Label("Adapter Mode")
        String adapterMode;

        @Label("Exhaustive File Reads")
        boolean exhaustiveFileReads;

        @Label("Working Directory")
        String workingDirectory;
    }

    @Name("preflight.AgentStopping")
    @Label("Preflight Agent Stopping")
    @Category("Starsector Preflight")
    static final class AgentStopping extends Event {
    }
}
