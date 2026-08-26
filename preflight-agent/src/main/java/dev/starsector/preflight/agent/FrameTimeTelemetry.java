package dev.starsector.preflight.agent;

/** Stable field names shared by the frame recorder and its bounded report consumers. */
public final class FrameTimeTelemetry {
    public static final String FRAME_REPORT_FORMAT = "starsector-preflight-runtime-frame-report-v1";
    public static final String REPORT = "frameTimes";
    public static final String ENABLED = "enabled";
    public static final String CAMPAIGN_ACTIVE = "campaignActive";
    public static final String CAMPAIGN_FIRST_30_SECONDS_ACTIVE = "campaignFirst30SecondsActive";
    public static final String CAMPAIGN_AFTER_30_SECONDS_ACTIVE = "campaignAfter30SecondsActive";
    public static final String CAMPAIGN_PAUSED_ACTIVE = "campaignPausedActive";
    public static final String CAMPAIGN_PAUSED_AFTER_30_SECONDS_ACTIVE =
            "campaignPausedAfter30SecondsActive";
    public static final String CAMPAIGN_UNPAUSED_ACTIVE = "campaignUnpausedActive";
    public static final String CAMPAIGN_UNPAUSED_AFTER_30_SECONDS_ACTIVE =
            "campaignUnpausedAfter30SecondsActive";
    public static final String COMBAT_AFTER_CAMPAIGN_ACTIVE = "combatAfterCampaignActive";
    public static final String TOTAL_ACTIVE_NANOS = "totalActiveNanos";
    public static final String MEASUREMENT_OVERHEAD = "measurementOverhead";
    public static final String AVERAGE_MICROS = "averageMicros";

    private FrameTimeTelemetry() {
    }
}
