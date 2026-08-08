package dev.starsector.preflight.cli;

import java.util.Locale;

/** Product-level acceleration domains whose dependency closure is owned by the CLI. */
enum OptimizationDomain {
    PREPARED_TEXTURES("prepared-textures"),
    PREPARED_AUDIO("prepared-audio");

    private final String optionValue;

    OptimizationDomain(String optionValue) {
        this.optionValue = optionValue;
    }

    String optionValue() {
        return optionValue;
    }

    static OptimizationDomain parse(String raw) {
        String normalized = raw.trim().toLowerCase(Locale.ROOT).replace('_', '-');
        for (OptimizationDomain domain : values()) {
            if (domain.optionValue.equals(normalized)) return domain;
        }
        throw new IllegalArgumentException(
                "Optimization domain must be prepared-textures or prepared-audio");
    }
}
