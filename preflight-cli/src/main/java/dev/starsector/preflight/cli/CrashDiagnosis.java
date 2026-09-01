package dev.starsector.preflight.cli;

import dev.starsector.preflight.core.Json;
import java.nio.file.Path;
import java.time.Instant;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Typed immutable data model for Starsector launch crash diagnosis.
 * Format: starsector-preflight-crash-diagnosis-v1
 */
record CrashDiagnosis(
        String format,
        Instant diagnosedAt,
        Path runDirectory,
        int exitCode,
        Integer launcherExitCode,
        CrashCategory rootCauseCategory,
        Confidence confidence,
        String summaryTitle,
        String summaryDescription,
        OffendingMod offendingMod,
        MissingDependency missingDependency,
        MemoryTelemetry memoryTelemetry,
        String rootCauseSnippet,
        List<String> stackTrace,
        List<String> logSnippetLines,
        int crashLineIndex,
        List<RecoveryAction> recoveryActions,
        String copyableSnippet,
        List<String> problems
) {
    static final String FORMAT = "starsector-preflight-crash-diagnosis-v1";

    CrashDiagnosis {
        stackTrace = stackTrace == null ? List.of() : List.copyOf(stackTrace);
        logSnippetLines = logSnippetLines == null ? List.of() : List.copyOf(logSnippetLines);
        recoveryActions = recoveryActions == null ? List.of() : List.copyOf(recoveryActions);
        problems = problems == null ? List.of() : List.copyOf(problems);
    }

    enum CrashCategory {
        OUT_OF_MEMORY_HEAP,
        OUT_OF_MEMORY_DIRECT_NATIVE,
        MISSING_DEPENDENCY,
        INCOMPATIBLE_MOD_VERSION,
        CORRUPT_SAVE_OR_CONFIG,
        GRAPHICS_DRIVER_OR_OPENGL_ERROR,
        NATIVE_CRASH_SIGSEGV,
        CLASS_NOT_FOUND_MISSING_JAR,
        NULL_POINTER_IN_MOD_CODE,
        VRAM_EXHAUSTION_OR_TEXTURE_ALLOCATION,
        GENERIC_UNCLASSIFIED
    }

    enum Confidence {
        EXACT,
        HIGH,
        HEURISTIC,
        LOW
    }

    record OffendingMod(
            String id,
            String name,
            String version,
            String author,
            Path directory,
            String crashingClass,
            String crashingMethod,
            Integer lineNumber,
            String jarPath
    ) {
        Map<String, Object> toMap() {
            Map<String, Object> map = new LinkedHashMap<>();
            map.put("id", id);
            map.put("name", name);
            map.put("version", version);
            map.put("author", author);
            map.put("directory", directory == null ? null : directory.toString());
            map.put("crashingClass", crashingClass);
            map.put("crashingMethod", crashingMethod);
            map.put("lineNumber", lineNumber);
            map.put("jarPath", jarPath);
            return Collections.unmodifiableMap(map);
        }
    }

    record MissingDependency(
            String dependentModId,
            String dependentModName,
            String missingModId,
            String missingModName,
            String missingClassName,
            String minVersion
    ) {
        Map<String, Object> toMap() {
            Map<String, Object> map = new LinkedHashMap<>();
            map.put("dependentModId", dependentModId);
            map.put("dependentModName", dependentModName);
            map.put("missingModId", missingModId);
            map.put("missingModName", missingModName);
            map.put("missingClassName", missingClassName);
            map.put("minVersion", minVersion);
            return Collections.unmodifiableMap(map);
        }
    }

    record MemoryTelemetry(
            Integer configuredMaxHeapMiB,
            Integer configuredInitialHeapMiB,
            String memoryType,
            boolean heapExhausted,
            boolean vramExhausted
    ) {
        Map<String, Object> toMap() {
            Map<String, Object> map = new LinkedHashMap<>();
            map.put("configuredMaxHeapMiB", configuredMaxHeapMiB);
            map.put("configuredInitialHeapMiB", configuredInitialHeapMiB);
            map.put("memoryType", memoryType);
            map.put("heapExhausted", heapExhausted);
            map.put("vramExhausted", vramExhausted);
            return Collections.unmodifiableMap(map);
        }
    }

    record RecoveryAction(
            String id,
            String label,
            String description,
            boolean recommended,
            boolean safe,
            String tone,
            Map<String, Object> parameters
    ) {
        RecoveryAction {
            parameters = parameters == null ? Map.of() : Collections.unmodifiableMap(new LinkedHashMap<>(parameters));
        }

        Map<String, Object> toMap() {
            Map<String, Object> map = new LinkedHashMap<>();
            map.put("id", id);
            map.put("label", label);
            map.put("description", description);
            map.put("recommended", recommended);
            map.put("safe", safe);
            map.put("tone", tone == null ? "primary" : tone);
            map.put("parameters", parameters);
            return Collections.unmodifiableMap(map);
        }
    }

    Map<String, Object> toMap() {
        Map<String, Object> map = new LinkedHashMap<>();
        map.put("format", format);
        map.put("diagnosedAt", diagnosedAt == null ? null : diagnosedAt.toString());
        map.put("runDirectory", runDirectory == null ? null : runDirectory.toString());
        map.put("exitCode", exitCode);
        map.put("launcherExitCode", launcherExitCode);
        map.put("rootCauseCategory", rootCauseCategory == null ? null : rootCauseCategory.name());
        map.put("confidence", confidence == null ? null : confidence.name());
        map.put("summaryTitle", summaryTitle);
        map.put("summaryDescription", summaryDescription);
        map.put("offendingMod", offendingMod == null ? null : offendingMod.toMap());
        map.put("missingDependency", missingDependency == null ? null : missingDependency.toMap());
        map.put("memoryTelemetry", memoryTelemetry == null ? null : memoryTelemetry.toMap());
        map.put("rootCauseSnippet", rootCauseSnippet);
        map.put("stackTrace", stackTrace);
        map.put("logSnippetLines", logSnippetLines);
        map.put("crashLineIndex", crashLineIndex);
        map.put("recoveryActions", recoveryActions.stream().map(RecoveryAction::toMap).toList());
        map.put("copyableSnippet", copyableSnippet);
        map.put("problems", problems);
        return Collections.unmodifiableMap(map);
    }

    String toJson() {
        return Json.object(toMap());
    }
}
