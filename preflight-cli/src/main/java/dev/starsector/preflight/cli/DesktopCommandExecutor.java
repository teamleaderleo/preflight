package dev.starsector.preflight.cli;

import java.time.Duration;
import java.util.List;

/** Bounded, interruptible subprocess boundary shared by desktop automation adapters. */
interface DesktopCommandExecutor {
    Result run(List<String> command, Duration timeout) throws Exception;

    record Result(int exitCode, String output) {
    }
}
