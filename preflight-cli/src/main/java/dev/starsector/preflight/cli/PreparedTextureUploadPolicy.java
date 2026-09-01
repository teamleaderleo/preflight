package dev.starsector.preflight.cli;

import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;

/** Selects a correctness-equivalent prepared upload layout for pathological software renderers. */
final class PreparedTextureUploadPolicy {
    static final String GALLIUM_DRIVER = "GALLIUM_DRIVER";

    private PreparedTextureUploadPolicy() {
    }

    static Resolution resolve(
            Platform platform,
            OptimizationPreset preset,
            boolean npotDirect,
            boolean unpadded,
            Map<String, String> environment) {
        String gallium = environment.getOrDefault(GALLIUM_DRIVER, "").trim()
                .toLowerCase(Locale.ROOT);
        if (platform == Platform.WINDOWS
                && preset == OptimizationPreset.RECOMMENDED
                && unpadded
                && "llvmpipe".equals(gallium)) {
            return new Resolution(
                    true,
                    false,
                    "llvmpipe uses padded coherent-direct uploads; Recommended's other optimizations remain enabled");
        }
        return new Resolution(npotDirect, unpadded, "requested upload layout retained");
    }

    record Resolution(boolean npotDirect, boolean unpadded, String reason) {
        Map<String, Object> toReportValues() {
            Map<String, Object> values = new LinkedHashMap<>();
            values.put("npotDirect", npotDirect);
            values.put("unpadded", unpadded);
            values.put("reason", reason);
            return values;
        }
    }
}
