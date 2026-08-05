package dev.starsector.preflight.agent;

/** Opt-in gate for removing reviewed, per-asset startup progress messages. */
final class AssetProgressLogRuntime {
    static final String PROPERTY = "preflight.assetProgressLogs";

    private AssetProgressLogRuntime() {
    }

    static boolean suppress() {
        return "off".equalsIgnoreCase(System.getProperty(PROPERTY, ""));
    }
}
