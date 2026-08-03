package org.apache.log4j;

/** Minimal test double; production resolves the installed game class reflectively. */
public final class LogManager {
    public static int shutdownCalls;

    private LogManager() {
    }

    public static void shutdown() {
        shutdownCalls++;
    }
}
