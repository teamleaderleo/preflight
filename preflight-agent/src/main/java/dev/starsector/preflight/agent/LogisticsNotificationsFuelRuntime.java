package dev.starsector.preflight.agent;

import java.util.LinkedHashMap;
import java.util.Map;

/** Reports installation of the exact Logistics Notifications load-time fuel snapshot repair. */
final class LogisticsNotificationsFuelRuntime {
    static final String PLAN_ID = "logistics-notifications-initial-fuel-snapshot-v1";

    private static volatile boolean installed;

    private LogisticsNotificationsFuelRuntime() {
    }

    static void installed() {
        installed = true;
    }

    static void reset() {
        installed = false;
    }

    static Map<String, Object> telemetry() {
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("planId", PLAN_ID);
        result.put("installed", installed);
        return result;
    }
}
