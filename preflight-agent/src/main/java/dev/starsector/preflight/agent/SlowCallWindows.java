package dev.starsector.preflight.agent;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Bounded exact wall-clock windows for the slowest opt-in diagnostic calls. */
final class SlowCallWindows {
    static final int LIMIT = 32;
    static final long MINIMUM_NANOS = 1_000_000L;

    private final long[] durations = new long[LIMIT];
    private final long[] endEpochMillis = new long[LIMIT];
    private int count;
    private int shortest;

    void reset() {
        count = 0;
        shortest = 0;
    }

    /** Returns the captured end epoch, or zero when this call was below the retained frontier. */
    long record(long durationNanos) {
        if (durationNanos < MINIMUM_NANOS) return 0L;
        int target;
        if (count < LIMIT) {
            target = count++;
        } else {
            if (durationNanos <= durations[shortest]) return 0L;
            target = shortest;
        }
        long endedAt = System.currentTimeMillis();
        durations[target] = durationNanos;
        endEpochMillis[target] = endedAt;
        recomputeShortest();
        return endedAt;
    }

    List<Map<String, Object>> report() {
        List<Call> calls = new ArrayList<>(count);
        for (int index = 0; index < count; index++) {
            calls.add(new Call(durations[index], endEpochMillis[index]));
        }
        calls.sort(Comparator.comparingLong(Call::durationNanos).reversed());
        List<Map<String, Object>> result = new ArrayList<>(calls.size());
        for (Call call : calls) {
            Map<String, Object> value = new LinkedHashMap<>();
            double durationMillis = call.durationNanos() / 1_000_000.0;
            value.put("durationMillis", durationMillis);
            value.put("startEpochMillis", call.endEpochMillis() - durationMillis);
            value.put("endEpochMillis", call.endEpochMillis());
            result.add(value);
        }
        return result;
    }

    private void recomputeShortest() {
        if (count == 0) return;
        int candidate = 0;
        for (int index = 1; index < count; index++) {
            if (durations[index] < durations[candidate]) candidate = index;
        }
        shortest = candidate;
    }

    private record Call(long durationNanos, long endEpochMillis) {
    }
}
