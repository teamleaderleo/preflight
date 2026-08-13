package dev.starsector.preflight.agent;

/** Child entry point used to exercise ProcessBuilder on Starsector's exact bundled JVM. */
public final class MacMemoryWarningProcessProbeChild {
    private MacMemoryWarningProcessProbeChild() {
    }

    public static void main(String[] args) {
        MacMemoryWarningRuntime.beginSession();
        boolean warning = MacMemoryWarningRuntime.shouldWarn(128L, 24_576L);
        System.out.println(dev.starsector.preflight.core.Json.object(
                MacMemoryWarningRuntime.telemetry()));
        if (warning) System.exit(1);
    }
}
