package dev.starsector.preflight.cli;

import dev.starsector.preflight.agent.AdapterPlanScope;
import java.util.Locale;

/** Stable product-level launch choices; raw adapter plan ids remain an engine detail. */
enum OptimizationPreset {
    CUSTOM("custom", AdapterPlanScope.FULL),
    RECOMMENDED("recommended", AdapterPlanScope.FULL),
    CONSERVATIVE("conservative", AdapterPlanScope.PORTABLE_STARTUP),
    OFF("off", AdapterPlanScope.FULL);

    private final String optionValue;
    private final AdapterPlanScope planScope;

    OptimizationPreset(String optionValue, AdapterPlanScope planScope) {
        this.optionValue = optionValue;
        this.planScope = planScope;
    }

    String optionValue() {
        return optionValue;
    }

    AdapterPlanScope planScope() {
        return planScope;
    }

    static OptimizationPreset parse(String raw) {
        String normalized = raw.trim().toLowerCase(Locale.ROOT).replace('_', '-');
        for (OptimizationPreset preset : values()) {
            if (preset != CUSTOM && preset.optionValue.equals(normalized)) return preset;
        }
        throw new IllegalArgumentException(
                "Optimization preset must be recommended, conservative, or off");
    }
}
