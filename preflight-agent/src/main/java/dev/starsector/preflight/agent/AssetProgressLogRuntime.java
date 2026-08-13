package dev.starsector.preflight.agent;

/** Opt-in gate for removing reviewed, per-asset startup progress messages. */
final class AssetProgressLogRuntime {
    static final String PLAN_ID = "vanilla-asset-progress-log-suppression-v1";
    static final String PROPERTY = "preflight.assetProgressLogs";

    private AssetProgressLogRuntime() {
    }

    static boolean suppress() {
        return AdapterPlanControl.allows(PLAN_ID)
                && "off".equalsIgnoreCase(System.getProperty(PROPERTY, ""));
    }
}
