package dev.starsector.preflight.cli;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.Map;

/** Controls whether HotSpot touches the entire configured heap before the game can start. */
enum HeapCommitPolicy {
    LAUNCHER_DEFAULT("launcher-default", null),
    ON_DEMAND("on-demand", "-XX:-AlwaysPreTouch");

    private final String reportValue;
    private final String javaOption;

    HeapCommitPolicy(String reportValue, String javaOption) {
        this.reportValue = reportValue;
        this.javaOption = javaOption;
    }

    String reportValue() {
        return reportValue;
    }

    Resolution resolve(LaunchTarget target) {
        if (javaOption == null) {
            return new Resolution(this, false, "the launcher policy is unchanged", null);
        }
        Path launcher = target.launcher().toAbsolutePath().normalize();
        try {
            if (Files.isSymbolicLink(launcher)
                    || !Files.isRegularFile(launcher, LinkOption.NOFOLLOW_LINKS)) {
                return new Resolution(this, false, "the launcher is not a regular file", launcher);
            }
            long bytes = Files.size(launcher);
            if (bytes > 1024L * 1024L) {
                return new Resolution(this, false, "the launcher exceeds the bounded read", launcher);
            }
            String text = Files.readString(launcher, StandardCharsets.UTF_8);
            if (!text.contains("-XX:+AlwaysPreTouch")) {
                return new Resolution(
                        this, false, "the launcher does not request eager heap commitment", launcher);
            }
            return new Resolution(
                    this, true, "the launcher explicitly requests -XX:+AlwaysPreTouch", launcher);
        } catch (IOException | RuntimeException problem) {
            return new Resolution(
                    this,
                    false,
                    "the launcher could not be checked: " + problem.getClass().getSimpleName(),
                    launcher);
        }
    }

    record Resolution(HeapCommitPolicy requested, boolean active, String reason, Path launcher) {
        String appendTo(String existing) {
            if (!active) return existing;
            return requested.appendOption(existing);
        }

        Map<String, Object> toReportValues() {
            Map<String, Object> values = new LinkedHashMap<>();
            values.put("requested", requested.reportValue());
            values.put("active", active);
            values.put("reason", reason);
            values.put("launcher", launcher);
            values.put("javaOption", active ? requested.javaOption : null);
            return values;
        }
    }

    private String appendOption(String existing) {
        String trimmed = existing == null ? "" : existing.trim();
        return trimmed.isEmpty() ? javaOption : trimmed + " " + javaOption;
    }
}
