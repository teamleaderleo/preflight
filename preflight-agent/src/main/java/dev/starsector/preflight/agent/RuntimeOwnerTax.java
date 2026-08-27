package dev.starsector.preflight.agent;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Report-time aggregation of concrete callback timing by evidence-backed runtime owner. */
final class RuntimeOwnerTax {
    private RuntimeOwnerTax() {
    }

    static Map<String, Object> report(
            List<Map<String, Object>> classes,
            String totalMillisField,
            String maximumMillisField) {
        HitchFrames hitchFrames = HitchFrames.capture();
        Map<String, Owner> owners = new LinkedHashMap<>();
        for (Map<String, Object> value : classes) {
            Object ownershipValue = value.get("ownership");
            if (!(ownershipValue instanceof Map<?, ?> ownership)) continue;
            String key = string(ownership.get("ownerKey"));
            if (key.isBlank()) key = "unresolved";
            Owner owner = owners.computeIfAbsent(key, ignored -> new Owner(
                    key,
                    string(ownership.get("ownerKind")),
                    string(ownership.get("ownerName")),
                    string(ownership.get("modId"))));
            owner.classes++;
            owner.calls += integer(value.get("calls"));
            owner.sampledCalls += integer(value.get("sampledCalls"));
            owner.totalMillis += decimal(value.get(totalMillisField));
            owner.maximumMillis = Math.max(owner.maximumMillis, decimal(value.get(maximumMillisField)));

            Map<String, Object> classHitch = hitchFrames.associate(value.get("slowestCalls"));
            value.put("hitchTax", classHitch);
            owner.hitchAvailable |= Boolean.TRUE.equals(classHitch.get("available"));
            owner.retainedSlowCalls += integer(classHitch.get("retainedSlowCalls"));
            owner.callsOver50 += integer(classHitch.get("callsOverlapping50msFrames"));
            owner.callsOver100 += integer(classHitch.get("callsOverlapping100msFrames"));
            owner.frameAssociationsOver50 += integer(classHitch.get("frameAssociationsOver50ms"));
            owner.frameAssociationsOver100 += integer(classHitch.get("frameAssociationsOver100ms"));
            owner.callbackOverlapMillis += decimal(classHitch.get("callbackOverlapMillis"));
            owner.maximumAssociatedFrameMillis = Math.max(
                    owner.maximumAssociatedFrameMillis,
                    decimal(classHitch.get("maximumAssociatedFrameMillis")));
        }

        List<Owner> steady = new ArrayList<>(owners.values());
        steady.sort(Comparator.comparingDouble((Owner value) -> value.totalMillis).reversed()
                .thenComparing(value -> value.ownerKey));
        List<Map<String, Object>> frameTax = new ArrayList<>(steady.size());
        for (Owner owner : steady) frameTax.add(owner.frameReport());

        List<Owner> hitch = new ArrayList<>(owners.values());
        hitch.sort(Comparator.comparingLong((Owner value) -> value.callsOver100).reversed()
                .thenComparing(Comparator.comparingLong(
                        (Owner value) -> value.callsOver50).reversed())
                .thenComparing(Comparator.comparingDouble(
                        (Owner value) -> value.callbackOverlapMillis).reversed())
                .thenComparing(value -> value.ownerKey));
        List<Map<String, Object>> hitchTax = new ArrayList<>(hitch.size());
        for (Owner owner : hitch) hitchTax.add(owner.hitchReport());

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("frameTaxBasis", "aggregate measured/estimated callback CPU time; sampled groups retain their class-level sample metadata");
        result.put("hitchTaxBasis", "bounded retained >=1ms callback wall-clock windows joined to retained >50ms/>100ms hitch frames through the hitch packet's epoch/offset mapping");
        result.put("hitchRecorderEnabled", hitchFrames.recorderEnabled);
        result.put("joinableRetainedHitchFrames", hitchFrames.frames.size());
        result.put("unjoinableRetainedHitchFrames", hitchFrames.unjoinableFrames);
        result.put("frameTax", frameTax);
        result.put("hitchTax", hitchTax);
        return result;
    }

    private static long integer(Object value) {
        return value instanceof Number number ? number.longValue() : 0L;
    }

    private static double decimal(Object value) {
        return value instanceof Number number ? number.doubleValue() : 0.0;
    }

    private static String string(Object value) {
        return value == null ? "" : value.toString();
    }

    private static final class HitchFrames {
        final boolean recorderEnabled;
        final List<HitchFrame> frames;
        final long unjoinableFrames;

        HitchFrames(boolean recorderEnabled, List<HitchFrame> frames, long unjoinableFrames) {
            this.recorderEnabled = recorderEnabled;
            this.frames = frames;
            this.unjoinableFrames = unjoinableFrames;
        }

        static HitchFrames capture() {
            try {
                Map<String, Object> telemetry = HitchPacketRuntime.telemetry();
                boolean enabled = Boolean.TRUE.equals(telemetry.get("enabled"));
                List<HitchFrame> frames = new ArrayList<>();
                long skipped = 0L;
                Object packetsValue = telemetry.get("packets");
                if (packetsValue instanceof Iterable<?> packets) {
                    for (Object packetValue : packets) {
                        if (!(packetValue instanceof Map<?, ?> packet)) continue;
                        Double packetEpoch = number(packet.get("startEpochMillis"));
                        Double packetOffset = number(packet.get("startOffsetMillis"));
                        Object historyValue = packet.get("frameHistory");
                        if (!(historyValue instanceof Iterable<?> history)) continue;
                        for (Object frameValue : history) {
                            if (!(frameValue instanceof Map<?, ?> frame)
                                    || !Boolean.TRUE.equals(frame.get("trigger"))) {
                                continue;
                            }
                            Double startOffset = number(frame.get("startOffsetMillis"));
                            Double durationMicros = number(frame.get("durationMicros"));
                            if (packetEpoch == null || packetOffset == null
                                    || startOffset == null || durationMicros == null) {
                                skipped++;
                                continue;
                            }
                            double startEpoch = packetEpoch + startOffset - packetOffset;
                            double endEpoch = startEpoch + durationMicros / 1_000.0;
                            frames.add(new HitchFrame(
                                    startEpoch,
                                    endEpoch,
                                    Boolean.TRUE.equals(frame.get("severe")),
                                    durationMicros / 1_000.0));
                        }
                    }
                }
                return new HitchFrames(enabled, List.copyOf(frames), skipped);
            } catch (ThreadDeath | VirtualMachineError fatal) {
                throw fatal;
            } catch (Throwable ignored) {
                return new HitchFrames(false, List.of(), 0L);
            }
        }

        Map<String, Object> associate(Object slowCallsValue) {
            long retained = 0L;
            long calls50 = 0L;
            long calls100 = 0L;
            long frame50 = 0L;
            long frame100 = 0L;
            double overlapMillis = 0.0;
            double maximumFrameMillis = 0.0;
            if (slowCallsValue instanceof Iterable<?> slowCalls) {
                for (Object callValue : slowCalls) {
                    if (!(callValue instanceof Map<?, ?> call)) continue;
                    Double start = number(call.get("startEpochMillis"));
                    Double end = number(call.get("endEpochMillis"));
                    if (start == null || end == null || end <= start) continue;
                    retained++;
                    boolean call50 = false;
                    boolean call100 = false;
                    for (HitchFrame frame : frames) {
                        double intersection = Math.min(end, frame.endEpochMillis)
                                - Math.max(start, frame.startEpochMillis);
                        if (intersection <= 0.0) continue;
                        call50 = true;
                        frame50++;
                        if (frame.severe) {
                            call100 = true;
                            frame100++;
                        }
                        overlapMillis += intersection;
                        maximumFrameMillis = Math.max(maximumFrameMillis, frame.durationMillis);
                    }
                    if (call50) calls50++;
                    if (call100) calls100++;
                }
            }
            Map<String, Object> result = new LinkedHashMap<>();
            result.put("available", recorderEnabled && unjoinableFrames == 0L);
            result.put("retainedSlowCalls", retained);
            result.put("callsOverlapping50msFrames", calls50);
            result.put("callsOverlapping100msFrames", calls100);
            result.put("frameAssociationsOver50ms", frame50);
            result.put("frameAssociationsOver100ms", frame100);
            result.put("callbackOverlapMillis", overlapMillis);
            result.put("maximumAssociatedFrameMillis", maximumFrameMillis);
            return result;
        }

        private static Double number(Object value) {
            return value instanceof Number number ? number.doubleValue() : null;
        }
    }

    private record HitchFrame(
            double startEpochMillis,
            double endEpochMillis,
            boolean severe,
            double durationMillis) {
    }

    private static final class Owner {
        final String ownerKey;
        final String ownerKind;
        final String ownerName;
        final String modId;
        long classes;
        long calls;
        long sampledCalls;
        double totalMillis;
        double maximumMillis;
        boolean hitchAvailable;
        long retainedSlowCalls;
        long callsOver50;
        long callsOver100;
        long frameAssociationsOver50;
        long frameAssociationsOver100;
        double callbackOverlapMillis;
        double maximumAssociatedFrameMillis;

        Owner(String ownerKey, String ownerKind, String ownerName, String modId) {
            this.ownerKey = ownerKey;
            this.ownerKind = ownerKind;
            this.ownerName = ownerName;
            this.modId = modId;
        }

        Map<String, Object> frameReport() {
            Map<String, Object> value = identity();
            value.put("classes", classes);
            value.put("calls", calls);
            value.put("sampledCalls", sampledCalls == 0L ? null : sampledCalls);
            value.put("totalMillis", totalMillis);
            value.put("maximumCallbackMillis", maximumMillis);
            return value;
        }

        Map<String, Object> hitchReport() {
            Map<String, Object> value = identity();
            value.put("available", hitchAvailable);
            value.put("retainedSlowCalls", retainedSlowCalls);
            value.put("callsOverlapping50msFrames", callsOver50);
            value.put("callsOverlapping100msFrames", callsOver100);
            value.put("frameAssociationsOver50ms", frameAssociationsOver50);
            value.put("frameAssociationsOver100ms", frameAssociationsOver100);
            value.put("callbackOverlapMillis", callbackOverlapMillis);
            value.put("maximumAssociatedFrameMillis", maximumAssociatedFrameMillis);
            return value;
        }

        private Map<String, Object> identity() {
            Map<String, Object> value = new LinkedHashMap<>();
            value.put("ownerKey", ownerKey);
            value.put("ownerKind", ownerKind);
            value.put("ownerName", ownerName);
            value.put("modId", modId.isBlank() ? null : modId);
            return value;
        }
    }
}
