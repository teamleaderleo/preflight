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

            Object hitchValue = value.get("hitchTax");
            if (hitchValue instanceof Map<?, ?> hitch) {
                owner.hitchAvailable |= Boolean.TRUE.equals(hitch.get("available"));
                owner.retainedSlowCalls += integer(hitch.get("retainedSlowCalls"));
                owner.callsOver50 += integer(hitch.get("callsOverlapping50msFrames"));
                owner.callsOver100 += integer(hitch.get("callsOverlapping100msFrames"));
                owner.frameAssociationsOver50 += integer(hitch.get("frameAssociationsOver50ms"));
                owner.frameAssociationsOver100 += integer(hitch.get("frameAssociationsOver100ms"));
                owner.callbackOverlapMillis += decimal(hitch.get("callbackOverlapMillis"));
                owner.maximumAssociatedFrameMillis = Math.max(
                        owner.maximumAssociatedFrameMillis,
                        decimal(hitch.get("maximumAssociatedFrameMillis")));
            }
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
        result.put("hitchTaxBasis", "bounded retained >=1ms callback windows joined by System.nanoTime overlap to retained >50ms/>100ms hitch frames");
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
