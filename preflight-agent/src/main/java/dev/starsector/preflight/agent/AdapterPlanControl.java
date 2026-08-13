package dev.starsector.preflight.agent;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;

/** Session-wide kill switches for direct and composed adapter plans. */
final class AdapterPlanControl {
    private static volatile State state = new State(AdapterPlanScope.FULL, Set.of());

    private AdapterPlanControl() {
    }

    static void configure(Set<String> disabled) {
        configure(AdapterPlanScope.FULL, disabled);
    }

    static void configure(AdapterPlanScope scope, Set<String> disabled) {
        state = new State(
                scope == null ? AdapterPlanScope.FULL : scope,
                disabled == null ? Set.of() : Set.copyOf(disabled));
    }

    static boolean allows(String planId) {
        State current = state;
        return planId != null
                && current.scope().allows(planId)
                && !current.disabledPlans().contains(planId);
    }

    static Set<String> disabledPlans() {
        return state.disabledPlans();
    }

    static AdapterPlanScope scope() {
        return state.scope();
    }

    static Map<String, Object> telemetry() {
        State current = state;
        Map<String, Object> values = new LinkedHashMap<>();
        values.put("scope", current.scope().optionValue());
        values.put("disabledPlans", List.copyOf(new TreeSet<>(current.disabledPlans())));
        return Map.copyOf(values);
    }

    private record State(AdapterPlanScope scope, Set<String> disabledPlans) {
    }
}
