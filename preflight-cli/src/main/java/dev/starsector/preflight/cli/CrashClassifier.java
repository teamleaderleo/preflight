package dev.starsector.preflight.cli;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

/**
 * 10-class heuristic crash classifier with authentic mod attribution.
 */
final class CrashClassifier {

    private static final Map<String, String> KNOWN_PACKAGE_PREFIXES = Map.of(
            "org.lazywizard.lazylib", "lw_lazylib",
            "org.magiclib", "MagicLib",
            "org.dark.graphics", "GraphicsLib",
            "lunavalence.lunalib", "lunalib",
            "exerelin", "nexerelin",
            "armaa", "armaa",
            "data.scripts.world.corvus", "nexerelin"
    );

    private static final Pattern STACK_FRAME_PATTERN = Pattern.compile(
            "^\\s*at\\s+([a-zA-Z0-9_$.]+)\\.([a-zA-Z0-9_$<]+)\\(([^:)]+)(?::(\\d+))?\\)");
    private static final Pattern CAUTION_FRAME_PATTERN = Pattern.compile(
            "^\\s*Caused by:\\s+([a-zA-Z0-9_$.]+)(?::\\s*(.*))?");
    private static final Pattern EXCEPTION_HEADER_PATTERN = Pattern.compile(
            "(?:Exception in thread \"[^\"]+\"\\s+)?([a-zA-Z0-9_$.]+Exception|[a-zA-Z0-9_$.]+Error)(?::\\s*(.*))?");

    private static final Pattern MISSING_REQ_MOD_PATTERN = Pattern.compile(
            "Required mod \\[(.*?)\\] not found", Pattern.CASE_INSENSITIVE);
    private static final Pattern MOD_REQUIRES_PATTERN = Pattern.compile(
            "Mod \\[(.*?)\\] requires \\[(.*?)\\]", Pattern.CASE_INSENSITIVE);
    private static final Pattern MISSING_DEPENDENCY_PATTERN = Pattern.compile(
            "Missing dependency:?\\s*([a-zA-Z0-9_.-]+)", Pattern.CASE_INSENSITIVE);
    private static final Pattern CLASS_VERSION_PATTERN = Pattern.compile(
            "class file version (\\d+\\.\\d+).*recognizes.*up to (\\d+\\.\\d+)", Pattern.CASE_INSENSITIVE);

    private CrashClassifier() {
    }

    record ClassificationContext(
            Path installRoot,
            Path runDirectory,
            int exitCode,
            Integer launcherExitCode,
            List<String> logLines,
            Map<String, ModMetadata> installedMods,
            Map<String, String> classToModMap,
            JvmMemorySettings.Snapshot heapSnapshot
    ) {}

    record ModMetadata(
            String id,
            String name,
            String version,
            String author,
            Path directory
    ) {}

    /**
     * Classifies a failed run using bounded log lines and environment inspection.
     */
    static CrashDiagnosis classify(
            Path installRoot,
            Path runDirectory,
            int exitCode,
            Integer launcherExitCode,
            List<String> logLines) {

        List<String> problems = new ArrayList<>();
        Map<String, ModMetadata> installedMods = discoverInstalledMods(installRoot, problems);
        Map<String, String> classToModMap = buildClassToModMap(installRoot, installedMods, problems);
        JvmMemorySettings.Snapshot heapSnapshot = installRoot != null ? JvmMemorySettings.inspect(installRoot) : null;

        ClassificationContext context = new ClassificationContext(
                installRoot,
                runDirectory,
                exitCode,
                launcherExitCode,
                logLines == null ? List.of() : logLines,
                installedMods,
                classToModMap,
                heapSnapshot
        );

        return evaluateRules(context, problems);
    }

    private static CrashDiagnosis evaluateRules(ClassificationContext ctx, List<String> problems) {
        List<String> lines = ctx.logLines();
        String fullText = String.join("\n", lines);

        // 1. Check for JVM Fatal Native Crash (hs_err_pid*.log / SIGSEGV)
        if (fullText.contains("SIGSEGV")
                || fullText.contains("EXCEPTION_ACCESS_VIOLATION")
                || fullText.contains("# A fatal error has been detected by the Java Runtime Environment")
                || lines.stream().anyMatch(l -> l.contains("Problematic frame:") || l.contains("hs_err_pid"))) {
            return buildNativeCrashDiagnosis(ctx, problems);
        }

        // 2. Check for OutOfMemory (Heap / GC Overhead / Direct / Native / SIGKILL 137)
        if (ctx.exitCode() == 137
                || fullText.contains("java.lang.OutOfMemoryError: Java heap space")
                || fullText.contains("java.lang.OutOfMemoryError: GC overhead limit exceeded")
                || fullText.contains("OutOfMemoryError: Java heap space")
                || fullText.contains("GC overhead limit exceeded")) {
            return buildHeapOomDiagnosis(ctx, problems);
        }
        if (fullText.contains("java.lang.OutOfMemoryError: Direct buffer memory")
                || fullText.contains("OutOfMemoryError: Direct buffer memory")
                || fullText.contains("java.lang.OutOfMemoryError: Metaspace")
                || fullText.contains("OutOfMemoryError: unable to create new native thread")) {
            return buildDirectNativeOomDiagnosis(ctx, problems);
        }

        // 3. Check for VRAM Exhaustion / Texture Allocation Failure
        if (fullText.contains("OpenGLException: Out of memory")
                || fullText.contains("GL_OUT_OF_MEMORY")
                || fullText.contains("Texture allocation failed for")) {
            return buildVramExhaustionDiagnosis(ctx, problems);
        }

        // 4. Check for Missing Dependency
        Matcher reqMatch = MISSING_REQ_MOD_PATTERN.matcher(fullText);
        Matcher modReqMatch = MOD_REQUIRES_PATTERN.matcher(fullText);
        Matcher depMatch = MISSING_DEPENDENCY_PATTERN.matcher(fullText);
        if (reqMatch.find() || modReqMatch.find() || depMatch.find()
                || (fullText.contains("NoClassDefFoundError") && (fullText.contains("lazywizard") || fullText.contains("magiclib") || fullText.contains("lunalib")))) {
            return buildMissingDependencyDiagnosis(ctx, reqMatch, modReqMatch, depMatch, problems);
        }

        // 5. Check for Incompatible Mod Version / UnsupportedClassVersionError / Removed API
        if (fullText.contains("UnsupportedClassVersionError")
                || fullText.contains("has been compiled by a more recent version of the Java Runtime")
                || (fullText.contains("NoSuchMethodError") && fullText.contains("com.fs.starfarer.api"))
                || (fullText.contains("NoSuchFieldError") && fullText.contains("com.fs.starfarer.api"))) {
            return buildIncompatibleVersionDiagnosis(ctx, problems);
        }

        // 6. Check for Corrupt Save or Config (JSON / CSV / XML / XStream)
        if (fullText.contains("org.json.JSONException")
                || fullText.contains("JSONException: Expected a ',' or '}'")
                || fullText.contains("com.thoughtworks.xstream.converters.ConversionException")
                || fullText.contains("Fatal: CSV format error")
                || fullText.contains("XML document structures must start and end within the same entity")) {
            return buildCorruptConfigDiagnosis(ctx, problems);
        }

        // 7. Check for Graphics Driver / OpenGL / Shader Error
        if (fullText.contains("failed to create OpenGL context")
                || fullText.contains("Pixel format not accelerated")
                || fullText.contains("ShaderException:")
                || fullText.contains("Fragment shader failed to compile")
                || fullText.contains("Vertex shader failed to compile")
                || fullText.contains("GL_COMPILE_STATUS == GL_FALSE")
                || fullText.contains("nvoglv64.dll")
                || fullText.contains("amdvlk64.dll")
                || fullText.contains("ig9ic64.dll")) {
            return buildGraphicsDriverDiagnosis(ctx, problems);
        }

        // 8. Check for ClassNotFound / Missing JAR
        if (fullText.contains("ClassNotFoundException") || fullText.contains("NoClassDefFoundError")) {
            return buildClassNotFoundDiagnosis(ctx, problems);
        }

        // 9. Check for NullPointer or other RuntimeException in Mod Code
        if (fullText.contains("NullPointerException")
                || fullText.contains("ArrayIndexOutOfBoundsException")
                || fullText.contains("ClassCastException")
                || fullText.contains("IndexOutOfBoundsException")
                || fullText.contains("RuntimeException")
                || fullText.contains("Exception in thread")) {
            CrashDiagnosis modException = buildModExceptionDiagnosis(ctx, problems);
            if (modException != null) {
                return modException;
            }
        }

        // 10. Fallback: Generic Unclassified
        return buildGenericDiagnosis(ctx, problems);
    }

    private static CrashDiagnosis buildHeapOomDiagnosis(ClassificationContext ctx, List<String> problems) {
        Integer maxHeap = ctx.heapSnapshot() != null ? ctx.heapSnapshot().maxHeapMiB() : null;
        Integer initHeap = ctx.heapSnapshot() != null ? ctx.heapSnapshot().initialHeapMiB() : null;
        int targetHeap = maxHeap != null ? Math.min(8192, maxHeap + 2048) : 6144;

        List<CrashDiagnosis.RecoveryAction> actions = List.of(
                new CrashDiagnosis.RecoveryAction(
                        "INCREASE_HEAP_MEMORY",
                        "Increase Heap Memory to " + targetHeap + " MiB",
                        "Allocates +" + (targetHeap - (maxHeap != null ? maxHeap : 4096)) + " MiB RAM to the Starsector JVM in vmparams.",
                        true,
                        true,
                        "primary",
                        Map.of("targetHeapMiB", targetHeap)
                ),
                new CrashDiagnosis.RecoveryAction(
                        "CLEAR_PREPARED_CACHE",
                        "Clear Prepared Acceleration Cache",
                        "Purges cached texture and audio blobs to reduce startup memory pressure.",
                        false,
                        true,
                        "secondary",
                        Map.of()
                ),
                new CrashDiagnosis.RecoveryAction(
                        "EXPORT_DIAGNOSTICS",
                        "Export Support Diagnostics ZIP",
                        "Generates a sanitized ZIP bundle with crash logs for forum/Discord support.",
                        false,
                        true,
                        "quiet",
                        Map.of()
                )
        );

        int crashLine = findFirstMatchingLineIndex(ctx.logLines(), "OutOfMemoryError", "GC overhead", "exit code 137");
        String snippet = extractSnippet(ctx.logLines(), crashLine, 10);

        return new CrashDiagnosis(
                CrashDiagnosis.FORMAT,
                Instant.now(),
                ctx.runDirectory(),
                ctx.exitCode(),
                ctx.launcherExitCode(),
                CrashDiagnosis.CrashCategory.OUT_OF_MEMORY_HEAP,
                ctx.exitCode() == 137 && !String.join(" ", ctx.logLines()).contains("OutOfMemoryError")
                        ? CrashDiagnosis.Confidence.HIGH
                        : CrashDiagnosis.Confidence.EXACT,
                "Out of Memory: Java Heap Space Exhausted",
                "The Starsector JVM ran out of configured heap memory. This typically happens when running large mod setups with insufficient RAM allocated in vmparams.",
                null,
                null,
                new CrashDiagnosis.MemoryTelemetry(maxHeap, initHeap, "HEAP", true, false),
                snippet,
                extractStackTrace(ctx.logLines(), crashLine),
                extractLogWindow(ctx.logLines(), crashLine, 40),
                crashLine,
                actions,
                formatCopyableSnippet("OUT_OF_MEMORY_HEAP", "EXACT", null, "Java heap space exhausted", snippet),
                problems
        );
    }

    private static CrashDiagnosis buildDirectNativeOomDiagnosis(ClassificationContext ctx, List<String> problems) {
        Integer maxHeap = ctx.heapSnapshot() != null ? ctx.heapSnapshot().maxHeapMiB() : null;
        Integer initHeap = ctx.heapSnapshot() != null ? ctx.heapSnapshot().initialHeapMiB() : null;

        List<CrashDiagnosis.RecoveryAction> actions = List.of(
                new CrashDiagnosis.RecoveryAction(
                        "CLEAR_PREPARED_CACHE",
                        "Clear Prepared Texture Cache",
                        "Purges native direct buffer allocations and decompressed textures.",
                        true,
                        true,
                        "primary",
                        Map.of()
                ),
                new CrashDiagnosis.RecoveryAction(
                        "CLEAR_SHADER_CACHE",
                        "Clear Shader Cache",
                        "Clears cached GPU shaders from shaders/cache.",
                        false,
                        true,
                        "secondary",
                        Map.of()
                ),
                new CrashDiagnosis.RecoveryAction(
                        "EXPORT_DIAGNOSTICS",
                        "Export Support Diagnostics ZIP",
                        "Generates a sanitized ZIP bundle with crash logs for forum/Discord support.",
                        false,
                        true,
                        "quiet",
                        Map.of()
                )
        );

        int crashLine = findFirstMatchingLineIndex(ctx.logLines(), "Direct buffer memory", "Metaspace", "native thread");
        String snippet = extractSnippet(ctx.logLines(), crashLine, 10);

        return new CrashDiagnosis(
                CrashDiagnosis.FORMAT,
                Instant.now(),
                ctx.runDirectory(),
                ctx.exitCode(),
                ctx.launcherExitCode(),
                CrashDiagnosis.CrashCategory.OUT_OF_MEMORY_DIRECT_NATIVE,
                CrashDiagnosis.Confidence.EXACT,
                "Out of Memory: Native Direct Memory Exhausted",
                "Starsector exceeded the maximum native direct memory or Metaspace buffer limit. This is often caused by heavy graphics mods allocating large off-heap byte buffers.",
                null,
                null,
                new CrashDiagnosis.MemoryTelemetry(maxHeap, initHeap, "DIRECT_NATIVE", false, true),
                snippet,
                extractStackTrace(ctx.logLines(), crashLine),
                extractLogWindow(ctx.logLines(), crashLine, 40),
                crashLine,
                actions,
                formatCopyableSnippet("OUT_OF_MEMORY_DIRECT_NATIVE", "EXACT", null, "Direct native buffer memory exhausted", snippet),
                problems
        );
    }

    private static CrashDiagnosis buildVramExhaustionDiagnosis(ClassificationContext ctx, List<String> problems) {
        List<CrashDiagnosis.RecoveryAction> actions = List.of(
                new CrashDiagnosis.RecoveryAction(
                        "CLEAR_PREPARED_CACHE",
                        "Clear Prepared Cache",
                        "Purges high-resolution prepared texture caches.",
                        true,
                        true,
                        "primary",
                        Map.of()
                ),
                new CrashDiagnosis.RecoveryAction(
                        "EXPORT_DIAGNOSTICS",
                        "Export Support Diagnostics ZIP",
                        "Generates a sanitized ZIP bundle with crash logs.",
                        false,
                        true,
                        "quiet",
                        Map.of()
                )
        );

        int crashLine = findFirstMatchingLineIndex(ctx.logLines(), "GL_OUT_OF_MEMORY", "OpenGLException", "Texture allocation failed");
        String snippet = extractSnippet(ctx.logLines(), crashLine, 10);

        return new CrashDiagnosis(
                CrashDiagnosis.FORMAT,
                Instant.now(),
                ctx.runDirectory(),
                ctx.exitCode(),
                ctx.launcherExitCode(),
                CrashDiagnosis.CrashCategory.VRAM_EXHAUSTION_OR_TEXTURE_ALLOCATION,
                CrashDiagnosis.Confidence.HIGH,
                "GPU VRAM Exhaustion or Texture Allocation Failure",
                "Your graphics card ran out of video memory (VRAM) while allocating textures. Consider reducing texture resolution or disabling high-resolution portrait/ship mods.",
                null,
                null,
                new CrashDiagnosis.MemoryTelemetry(null, null, "VRAM", false, true),
                snippet,
                extractStackTrace(ctx.logLines(), crashLine),
                extractLogWindow(ctx.logLines(), crashLine, 40),
                crashLine,
                actions,
                formatCopyableSnippet("VRAM_EXHAUSTION_OR_TEXTURE_ALLOCATION", "HIGH", null, "GPU VRAM exhausted during texture allocation", snippet),
                problems
        );
    }

    private static CrashDiagnosis buildMissingDependencyDiagnosis(
            ClassificationContext ctx,
            Matcher reqMatch,
            Matcher modReqMatch,
            Matcher depMatch,
            List<String> problems) {

        String missingModId = null;
        String dependentModId = null;

        if (reqMatch.find(0)) {
            missingModId = reqMatch.group(1);
        } else if (modReqMatch.find(0)) {
            dependentModId = modReqMatch.group(1);
            missingModId = modReqMatch.group(2);
        } else if (depMatch.find(0)) {
            missingModId = depMatch.group(1);
        } else {
            String full = String.join(" ", ctx.logLines());
            if (full.contains("org/lazywizard/lazylib") || full.contains("LazyLib")) {
                missingModId = "lw_lazylib";
            } else if (full.contains("org/magiclib") || full.contains("MagicLib")) {
                missingModId = "MagicLib";
            } else if (full.contains("lunavalence/lunalib") || full.contains("lunalib")) {
                missingModId = "lunalib";
            } else if (full.contains("GraphicsLib")) {
                missingModId = "GraphicsLib";
            }
        }

        ModMetadata missingMod = missingModId != null ? ctx.installedMods().get(missingModId) : null;
        ModMetadata depMod = dependentModId != null ? ctx.installedMods().get(dependentModId) : null;

        CrashDiagnosis.MissingDependency missingDep = new CrashDiagnosis.MissingDependency(
                dependentModId != null ? dependentModId : "unknown",
                depMod != null ? depMod.name() : dependentModId,
                missingModId != null ? missingModId : "unknown",
                missingMod != null ? missingMod.name() : missingModId,
                null,
                null
        );

        List<CrashDiagnosis.RecoveryAction> actions = new ArrayList<>();
        if (dependentModId != null && ctx.installedMods().containsKey(dependentModId)) {
            actions.add(new CrashDiagnosis.RecoveryAction(
                    "DISABLE_OFFENDING_MOD",
                    "Disable '" + dependentModId + "' & Relaunch",
                    "Safely disables " + (depMod != null ? depMod.name() : dependentModId) + " in enabled_mods.json.",
                    true,
                    true,
                    "primary",
                    Map.of("modId", dependentModId)
            ));
        }
        actions.add(new CrashDiagnosis.RecoveryAction(
                "EXPORT_DIAGNOSTICS",
                "Export Support Diagnostics ZIP",
                "Generates a sanitized ZIP bundle with crash logs.",
                false,
                true,
                "quiet",
                Map.of()
        ));

        int crashLine = findFirstMatchingLineIndex(ctx.logLines(), "Required mod", "requires", "Missing dependency", "NoClassDefFoundError");
        String snippet = extractSnippet(ctx.logLines(), crashLine, 10);

        return new CrashDiagnosis(
                CrashDiagnosis.FORMAT,
                Instant.now(),
                ctx.runDirectory(),
                ctx.exitCode(),
                ctx.launcherExitCode(),
                CrashDiagnosis.CrashCategory.MISSING_DEPENDENCY,
                CrashDiagnosis.Confidence.EXACT,
                "Missing Required Mod Dependency: " + (missingMod != null ? missingMod.name() : (missingModId != null ? missingModId : "Unknown")),
                "A required prerequisite mod library is missing or disabled. Please enable or install the missing mod.",
                null,
                missingDep,
                null,
                snippet,
                extractStackTrace(ctx.logLines(), crashLine),
                extractLogWindow(ctx.logLines(), crashLine, 40),
                crashLine,
                actions,
                formatCopyableSnippet("MISSING_DEPENDENCY", "EXACT", null, "Missing mod: " + missingModId, snippet),
                problems
        );
    }

    private static CrashDiagnosis buildIncompatibleVersionDiagnosis(ClassificationContext ctx, List<String> problems) {
        FrameAttribution attribution = attributeStackFrame(ctx);
        CrashDiagnosis.OffendingMod offending = attribution != null ? attribution.offendingMod() : null;

        List<CrashDiagnosis.RecoveryAction> actions = new ArrayList<>();
        if (offending != null) {
            actions.add(new CrashDiagnosis.RecoveryAction(
                    "DISABLE_OFFENDING_MOD",
                    "Disable '" + offending.id() + "' & Relaunch",
                    "Safely disables " + offending.name() + " in enabled_mods.json after creating a backup.",
                    true,
                    true,
                    "primary",
                    Map.of("modId", offending.id())
            ));
        }
        actions.add(new CrashDiagnosis.RecoveryAction(
                "EXPORT_DIAGNOSTICS",
                "Export Support Diagnostics ZIP",
                "Generates a sanitized ZIP bundle with crash logs.",
                false,
                true,
                "quiet",
                Map.of()
        ));

        int crashLine = findFirstMatchingLineIndex(ctx.logLines(), "UnsupportedClassVersionError", "NoSuchMethodError", "NoSuchFieldError");
        String snippet = extractSnippet(ctx.logLines(), crashLine, 10);

        return new CrashDiagnosis(
                CrashDiagnosis.FORMAT,
                Instant.now(),
                ctx.runDirectory(),
                ctx.exitCode(),
                ctx.launcherExitCode(),
                CrashDiagnosis.CrashCategory.INCOMPATIBLE_MOD_VERSION,
                CrashDiagnosis.Confidence.EXACT,
                "Incompatible Mod Version or Java Runtime: " + (offending != null ? offending.name() : "Mod Code"),
                "A mod was compiled for a newer version of Java (e.g. Java 17/21) or references a Starsector API method that no longer exists.",
                offending,
                null,
                null,
                snippet,
                extractStackTrace(ctx.logLines(), crashLine),
                extractLogWindow(ctx.logLines(), crashLine, 40),
                crashLine,
                actions,
                formatCopyableSnippet("INCOMPATIBLE_MOD_VERSION", "EXACT", offending, "Unsupported class version or missing API method", snippet),
                problems
        );
    }

    private static CrashDiagnosis buildCorruptConfigDiagnosis(ClassificationContext ctx, List<String> problems) {
        FrameAttribution attribution = attributeStackFrame(ctx);
        CrashDiagnosis.OffendingMod offending = attribution != null ? attribution.offendingMod() : null;

        List<CrashDiagnosis.RecoveryAction> actions = new ArrayList<>();
        if (offending != null) {
            actions.add(new CrashDiagnosis.RecoveryAction(
                    "DISABLE_OFFENDING_MOD",
                    "Disable '" + offending.id() + "' & Relaunch",
                    "Safely disables " + offending.name() + " in enabled_mods.json.",
                    true,
                    true,
                    "primary",
                    Map.of("modId", offending.id())
            ));
        }
        actions.add(new CrashDiagnosis.RecoveryAction(
                "EXPORT_DIAGNOSTICS",
                "Export Support Diagnostics ZIP",
                "Generates a sanitized ZIP bundle with crash logs.",
                false,
                true,
                "quiet",
                Map.of()
        ));

        int crashLine = findFirstMatchingLineIndex(ctx.logLines(), "JSONException", "ConversionException", "CSV format error", "XML document");
        String snippet = extractSnippet(ctx.logLines(), crashLine, 10);

        return new CrashDiagnosis(
                CrashDiagnosis.FORMAT,
                Instant.now(),
                ctx.runDirectory(),
                ctx.exitCode(),
                ctx.launcherExitCode(),
                CrashDiagnosis.CrashCategory.CORRUPT_SAVE_OR_CONFIG,
                CrashDiagnosis.Confidence.HIGH,
                "Corrupted Mod Data or Savegame File",
                "Starsector failed while parsing a malformed JSON, CSV, or XML data file. Check recently edited mod data files or savegame files.",
                offending,
                null,
                null,
                snippet,
                extractStackTrace(ctx.logLines(), crashLine),
                extractLogWindow(ctx.logLines(), crashLine, 40),
                crashLine,
                actions,
                formatCopyableSnippet("CORRUPT_SAVE_OR_CONFIG", "HIGH", offending, "Data syntax / format parsing error", snippet),
                problems
        );
    }

    private static CrashDiagnosis buildGraphicsDriverDiagnosis(ClassificationContext ctx, List<String> problems) {
        List<CrashDiagnosis.RecoveryAction> actions = List.of(
                new CrashDiagnosis.RecoveryAction(
                        "CLEAR_SHADER_CACHE",
                        "Clear Shader Cache",
                        "Deletes cached shaders to force clean recompilation.",
                        true,
                        true,
                        "primary",
                        Map.of()
                ),
                new CrashDiagnosis.RecoveryAction(
                        "RESTORE_FALLBACK_ARGS",
                        "Restore Fallback Launch Parameters",
                        "Resets vmparams to default values without custom flags.",
                        false,
                        true,
                        "secondary",
                        Map.of()
                ),
                new CrashDiagnosis.RecoveryAction(
                        "EXPORT_DIAGNOSTICS",
                        "Export Support Diagnostics ZIP",
                        "Generates a sanitized ZIP bundle with crash logs.",
                        false,
                        true,
                        "quiet",
                        Map.of()
                )
        );

        int crashLine = findFirstMatchingLineIndex(ctx.logLines(), "OpenGL", "ShaderException", "Pixel format", "nvoglv64", "amdvlk64");
        String snippet = extractSnippet(ctx.logLines(), crashLine, 10);

        return new CrashDiagnosis(
                CrashDiagnosis.FORMAT,
                Instant.now(),
                ctx.runDirectory(),
                ctx.exitCode(),
                ctx.launcherExitCode(),
                CrashDiagnosis.CrashCategory.GRAPHICS_DRIVER_OR_OPENGL_ERROR,
                CrashDiagnosis.Confidence.HIGH,
                "Graphics Driver or Shader Compilation Error",
                "Starsector encountered an OpenGL driver failure or GLSL shader compilation error. Try updating GPU drivers or clearing the shader cache.",
                null,
                null,
                null,
                snippet,
                extractStackTrace(ctx.logLines(), crashLine),
                extractLogWindow(ctx.logLines(), crashLine, 40),
                crashLine,
                actions,
                formatCopyableSnippet("GRAPHICS_DRIVER_OR_OPENGL_ERROR", "HIGH", null, "Graphics driver or GLSL shader error", snippet),
                problems
        );
    }

    private static CrashDiagnosis buildNativeCrashDiagnosis(ClassificationContext ctx, List<String> problems) {
        List<CrashDiagnosis.RecoveryAction> actions = List.of(
                new CrashDiagnosis.RecoveryAction(
                        "RESTORE_FALLBACK_ARGS",
                        "Restore Safe Launch Arguments",
                        "Resets JVM launch options to vanilla safe defaults.",
                        true,
                        true,
                        "primary",
                        Map.of()
                ),
                new CrashDiagnosis.RecoveryAction(
                        "CLEAR_SHADER_CACHE",
                        "Clear Shader Cache",
                        "Clears shader cache to resolve corrupted driver binaries.",
                        false,
                        true,
                        "secondary",
                        Map.of()
                ),
                new CrashDiagnosis.RecoveryAction(
                        "EXPORT_DIAGNOSTICS",
                        "Export Support Diagnostics ZIP",
                        "Generates a sanitized ZIP bundle including hs_err crash logs.",
                        false,
                        true,
                        "quiet",
                        Map.of()
                )
        );

        int crashLine = findFirstMatchingLineIndex(ctx.logLines(), "SIGSEGV", "EXCEPTION_ACCESS_VIOLATION", "Problematic frame", "fatal error has been detected");
        String snippet = extractSnippet(ctx.logLines(), crashLine, 10);

        return new CrashDiagnosis(
                CrashDiagnosis.FORMAT,
                Instant.now(),
                ctx.runDirectory(),
                ctx.exitCode(),
                ctx.launcherExitCode(),
                CrashDiagnosis.CrashCategory.NATIVE_CRASH_SIGSEGV,
                CrashDiagnosis.Confidence.EXACT,
                "Fatal Native JVM Crash (SIGSEGV / Access Violation)",
                "The Java Virtual Machine crashed in native code (C/C++ runtime or graphics/audio driver). An hs_err log was generated with register and thread details.",
                null,
                null,
                null,
                snippet,
                extractStackTrace(ctx.logLines(), crashLine),
                extractLogWindow(ctx.logLines(), crashLine, 40),
                crashLine,
                actions,
                formatCopyableSnippet("NATIVE_CRASH_SIGSEGV", "EXACT", null, "Native crash in JVM/driver", snippet),
                problems
        );
    }

    private static CrashDiagnosis buildClassNotFoundDiagnosis(ClassificationContext ctx, List<String> problems) {
        FrameAttribution attribution = attributeStackFrame(ctx);
        CrashDiagnosis.OffendingMod offending = attribution != null ? attribution.offendingMod() : null;

        List<CrashDiagnosis.RecoveryAction> actions = new ArrayList<>();
        if (offending != null) {
            actions.add(new CrashDiagnosis.RecoveryAction(
                    "DISABLE_OFFENDING_MOD",
                    "Disable '" + offending.id() + "' & Relaunch",
                    "Safely disables " + offending.name() + " in enabled_mods.json.",
                    true,
                    true,
                    "primary",
                    Map.of("modId", offending.id())
            ));
        }
        actions.add(new CrashDiagnosis.RecoveryAction(
                "EXPORT_DIAGNOSTICS",
                "Export Support Diagnostics ZIP",
                "Generates a sanitized ZIP bundle with crash logs.",
                false,
                true,
                "quiet",
                Map.of()
        ));

        int crashLine = findFirstMatchingLineIndex(ctx.logLines(), "ClassNotFoundException", "NoClassDefFoundError");
        String snippet = extractSnippet(ctx.logLines(), crashLine, 10);

        return new CrashDiagnosis(
                CrashDiagnosis.FORMAT,
                Instant.now(),
                ctx.runDirectory(),
                ctx.exitCode(),
                ctx.launcherExitCode(),
                CrashDiagnosis.CrashCategory.CLASS_NOT_FOUND_MISSING_JAR,
                CrashDiagnosis.Confidence.EXACT,
                "Missing Class or Mod JAR File: " + (offending != null ? offending.name() : "Mod Code"),
                "A Java class was referenced that could not be found in any enabled mod JAR. The mod may be corrupted, incomplete, or missing a declared JAR file.",
                offending,
                null,
                null,
                snippet,
                extractStackTrace(ctx.logLines(), crashLine),
                extractLogWindow(ctx.logLines(), crashLine, 40),
                crashLine,
                actions,
                formatCopyableSnippet("CLASS_NOT_FOUND_MISSING_JAR", "EXACT", offending, "Missing class definition in classpath", snippet),
                problems
        );
    }

    private static CrashDiagnosis buildModExceptionDiagnosis(ClassificationContext ctx, List<String> problems) {
        FrameAttribution attribution = attributeStackFrame(ctx);
        if (attribution == null || attribution.offendingMod() == null) {
            return null;
        }

        CrashDiagnosis.OffendingMod offending = attribution.offendingMod();

        List<CrashDiagnosis.RecoveryAction> actions = new ArrayList<>();
        actions.add(new CrashDiagnosis.RecoveryAction(
                "DISABLE_OFFENDING_MOD",
                "Disable '" + offending.id() + "' & Relaunch",
                "Safely disables " + offending.name() + " in enabled_mods.json after creating an atomic backup.",
                true,
                true,
                "primary",
                Map.of("modId", offending.id())
        ));
        actions.add(new CrashDiagnosis.RecoveryAction(
                "EXPORT_DIAGNOSTICS",
                "Export Support Diagnostics ZIP",
                "Generates a sanitized ZIP bundle with crash logs.",
                false,
                true,
                "quiet",
                Map.of()
        ));

        int crashLine = attribution.crashLineIndex();
        String snippet = extractSnippet(ctx.logLines(), crashLine, 10);

        return new CrashDiagnosis(
                CrashDiagnosis.FORMAT,
                Instant.now(),
                ctx.runDirectory(),
                ctx.exitCode(),
                ctx.launcherExitCode(),
                CrashDiagnosis.CrashCategory.NULL_POINTER_IN_MOD_CODE,
                CrashDiagnosis.Confidence.EXACT,
                attribution.exceptionType() + " in " + offending.name() + " (" + offending.id() + ")",
                offending.name() + " encountered an unhandled " + attribution.exceptionType() + " at "
                        + offending.crashingClass() + "." + offending.crashingMethod()
                        + (offending.lineNumber() != null ? " (line " + offending.lineNumber() + ")" : "") + ".",
                offending,
                null,
                null,
                snippet,
                extractStackTrace(ctx.logLines(), crashLine),
                extractLogWindow(ctx.logLines(), crashLine, 40),
                crashLine,
                actions,
                formatCopyableSnippet("NULL_POINTER_IN_MOD_CODE", "EXACT", offending, attribution.exceptionType() + ": " + snippet, snippet),
                problems
        );
    }

    private static CrashDiagnosis buildGenericDiagnosis(ClassificationContext ctx, List<String> problems) {
        List<CrashDiagnosis.RecoveryAction> actions = List.of(
                new CrashDiagnosis.RecoveryAction(
                        "EXPORT_DIAGNOSTICS",
                        "Export Support Diagnostics ZIP",
                        "Bundles sanitized crash logs and environment state into a support package.",
                        true,
                        true,
                        "primary",
                        Map.of()
                ),
                new CrashDiagnosis.RecoveryAction(
                        "RESTORE_FALLBACK_ARGS",
                        "Restore Fallback Launch Parameters",
                        "Resets JVM launch flags to default values.",
                        false,
                        true,
                        "secondary",
                        Map.of()
                )
        );

        int crashLine = ctx.logLines().isEmpty() ? 0 : ctx.logLines().size() - 1;
        String snippet = extractSnippet(ctx.logLines(), crashLine, 10);

        return new CrashDiagnosis(
                CrashDiagnosis.FORMAT,
                Instant.now(),
                ctx.runDirectory(),
                ctx.exitCode(),
                ctx.launcherExitCode(),
                CrashDiagnosis.CrashCategory.GENERIC_UNCLASSIFIED,
                CrashDiagnosis.Confidence.LOW,
                "Starsector Launch Aborted (Exit Code " + ctx.exitCode() + ")",
                "The game process terminated unexpectedly without matching a known specific crash signature. Review the log snippet below or export a diagnostics bundle.",
                null,
                null,
                null,
                snippet,
                extractStackTrace(ctx.logLines(), crashLine),
                extractLogWindow(ctx.logLines(), crashLine, 40),
                crashLine,
                actions,
                formatCopyableSnippet("GENERIC_UNCLASSIFIED", "LOW", null, "Process exited with code " + ctx.exitCode(), snippet),
                problems
        );
    }

    private record FrameAttribution(
            CrashDiagnosis.OffendingMod offendingMod,
            String exceptionType,
            int crashLineIndex
    ) {}

    private static FrameAttribution attributeStackFrame(ClassificationContext ctx) {
        List<String> lines = ctx.logLines();
        String currentException = "RuntimeException";
        int bestCrashLine = -1;

        for (int i = 0; i < lines.size(); i++) {
            String line = lines.get(i);
            Matcher exMatch = EXCEPTION_HEADER_PATTERN.matcher(line);
            if (exMatch.find()) {
                currentException = exMatch.group(1);
                bestCrashLine = i;
            }
            Matcher causeMatch = CAUTION_FRAME_PATTERN.matcher(line);
            if (causeMatch.find()) {
                currentException = causeMatch.group(1);
                bestCrashLine = i;
            }

            Matcher frameMatch = STACK_FRAME_PATTERN.matcher(line);
            if (frameMatch.find()) {
                String className = frameMatch.group(1);
                String methodName = frameMatch.group(2);
                String sourceFile = frameMatch.group(3);
                String lineStr = frameMatch.group(4);
                Integer lineNum = lineStr != null ? Integer.parseInt(lineStr) : null;

                if (isIgnoredSystemClass(className)) {
                    continue;
                }

                // Check mod attribution for this frame
                String modId = matchClassToModId(className, ctx);
                if (modId != null) {
                    ModMetadata meta = ctx.installedMods().get(modId);
                    CrashDiagnosis.OffendingMod offending = new CrashDiagnosis.OffendingMod(
                            modId,
                            meta != null ? meta.name() : modId,
                            meta != null ? meta.version() : "unknown",
                            meta != null ? meta.author() : null,
                            meta != null ? meta.directory() : null,
                            className,
                            methodName,
                            lineNum,
                            sourceFile
                    );
                    return new FrameAttribution(offending, currentException, bestCrashLine >= 0 ? bestCrashLine : i);
                }
            }
        }

        Pattern missingClassPattern = Pattern.compile(
                "(?:ClassNotFoundException|NoClassDefFoundError|UnsupportedClassVersionError):\\s*([a-zA-Z0-9_.$/]+)");
        for (int i = 0; i < lines.size(); i++) {
            String line = lines.get(i);
            Matcher mc = missingClassPattern.matcher(line);
            if (mc.find()) {
                String className = mc.group(1).replace('/', '.');
                String modId = matchClassToModId(className, ctx);
                if (modId != null) {
                    ModMetadata meta = ctx.installedMods().get(modId);
                    CrashDiagnosis.OffendingMod offending = new CrashDiagnosis.OffendingMod(
                            modId,
                            meta != null ? meta.name() : modId,
                            meta != null ? meta.version() : "unknown",
                            meta != null ? meta.author() : null,
                            meta != null ? meta.directory() : null,
                            className,
                            null,
                            null,
                            null
                    );
                    return new FrameAttribution(offending, currentException, bestCrashLine >= 0 ? bestCrashLine : i);
                }
            }
        }

        return null;
    }

    private static boolean isIgnoredSystemClass(String className) {
        return className.startsWith("java.")
                || className.startsWith("javax.")
                || className.startsWith("sun.")
                || className.startsWith("jdk.")
                || className.startsWith("org.lwjgl.")
                || className.startsWith("com.jogamp.")
                || (className.startsWith("com.fs.starfarer.") && !className.contains("mod") && !className.contains("hullmods"));
    }

    private static String matchClassToModId(String className, ClassificationContext ctx) {
        // 1. Direct class map lookup
        if (ctx.classToModMap().containsKey(className)) {
            return ctx.classToModMap().get(className);
        }

        // 2. Known package prefixes
        for (Map.Entry<String, String> entry : KNOWN_PACKAGE_PREFIXES.entrySet()) {
            if (className.startsWith(entry.getKey())) {
                return entry.getValue();
            }
        }

        // 3. Match against installed mod IDs
        for (String modId : ctx.installedMods().keySet()) {
            if (className.toLowerCase(Locale.ROOT).startsWith(modId.toLowerCase(Locale.ROOT))) {
                return modId;
            }
        }

        return null;
    }

    private static Map<String, ModMetadata> discoverInstalledMods(Path installRoot, List<String> problems) {
        Map<String, ModMetadata> result = new LinkedHashMap<>();
        if (installRoot == null) return result;
        Path modsDir = installRoot.resolve("mods");
        if (!Files.isDirectory(modsDir)) return result;

        try (Stream<Path> stream = Files.list(modsDir)) {
            for (Path modDir : stream.filter(Files::isDirectory).sorted().toList()) {
                Path infoFile = modDir.resolve("mod_info.json");
                String id = modDir.getFileName().toString();
                String name = id;
                String version = "1.0";
                String author = null;

                if (Files.isRegularFile(infoFile, LinkOption.NOFOLLOW_LINKS)) {
                    try {
                        String json = Files.readString(infoFile, StandardCharsets.UTF_8);
                        String parsedId = JsonText.string(json, "id");
                        if (parsedId != null && !parsedId.isBlank()) id = parsedId;
                        String parsedName = JsonText.string(json, "name");
                        if (parsedName != null && !parsedName.isBlank()) name = parsedName;
                        String parsedVersion = JsonText.string(json, "version");
                        if (parsedVersion != null && !parsedVersion.isBlank()) version = parsedVersion;
                        String parsedAuthor = JsonText.string(json, "author");
                        if (parsedAuthor != null && !parsedAuthor.isBlank()) author = parsedAuthor;
                    } catch (Exception e) {
                        problems.add("Could not read mod_info.json in " + modDir.getFileName() + ": " + e.getMessage());
                    }
                }
                result.put(id, new ModMetadata(id, name, version, author, modDir.toAbsolutePath().normalize()));
            }
        } catch (IOException e) {
            problems.add("Could not list mods directory: " + e.getMessage());
        }

        return Collections.unmodifiableMap(result);
    }

    private static Map<String, String> buildClassToModMap(
            Path installRoot,
            Map<String, ModMetadata> installedMods,
            List<String> problems) {
        Map<String, String> map = new LinkedHashMap<>();
        if (installRoot == null) return map;

        try {
            ClasspathAudit.Result audit = ClasspathAudit.scan(installRoot);
            @SuppressWarnings("unchecked")
            List<Map<String, Object>> jars = (List<Map<String, Object>>) audit.values().get("jars");
            if (jars != null) {
                // ClasspathAudit records modId for each jar
                for (Map<String, Object> jar : jars) {
                    String modId = (String) jar.get("modId");
                    if (modId != null && !modId.isBlank()) {
                        // We map known package prefix or mod ID
                        map.put(modId, modId);
                    }
                }
            }
        } catch (Exception ignored) {
        }

        return map;
    }

    private static int findFirstMatchingLineIndex(List<String> lines, String... patterns) {
        for (int i = 0; i < lines.size(); i++) {
            String line = lines.get(i);
            for (String p : patterns) {
                if (line.contains(p)) {
                    return i;
                }
            }
        }
        return -1;
    }

    private static String extractSnippet(List<String> lines, int crashLine, int maxLines) {
        if (lines == null || lines.isEmpty() || crashLine < 0) {
            return "No log snippet available.";
        }
        int start = Math.max(0, crashLine - 2);
        int end = Math.min(lines.size(), start + maxLines);
        return String.join("\n", lines.subList(start, end));
    }

    private static List<String> extractStackTrace(List<String> lines, int crashLine) {
        if (lines == null || lines.isEmpty() || crashLine < 0 || crashLine >= lines.size()) {
            return List.of();
        }
        List<String> trace = new ArrayList<>();
        trace.add(lines.get(crashLine));
        for (int i = crashLine + 1; i < lines.size() && trace.size() < 30; i++) {
            String line = lines.get(i);
            if (line.startsWith("\tat ") || line.startsWith("Caused by:") || line.trim().startsWith("at ")) {
                trace.add(line);
            } else if (!line.isBlank() && Character.isDigit(line.charAt(0))) {
                break;
            }
        }
        return List.copyOf(trace);
    }

    private static List<String> extractLogWindow(List<String> lines, int crashLine, int windowSize) {
        if (lines == null || lines.isEmpty()) {
            return List.of();
        }
        if (crashLine < 0) {
            int start = Math.max(0, lines.size() - windowSize);
            return List.copyOf(lines.subList(start, lines.size()));
        }
        int half = windowSize / 2;
        int start = Math.max(0, crashLine - half);
        int end = Math.min(lines.size(), start + windowSize);
        return List.copyOf(lines.subList(start, end));
    }

    private static String formatCopyableSnippet(
            String category,
            String confidence,
            CrashDiagnosis.OffendingMod culprit,
            String errorSummary,
            String snippet) {
        StringBuilder sb = new StringBuilder();
        sb.append("```\n");
        sb.append("Starsector Crash Report (Preflight Diagnostics)\n");
        sb.append("Category: ").append(category).append(" (Confidence: ").append(confidence).append(")\n");
        if (culprit != null) {
            sb.append("Culprit: ").append(culprit.name()).append(" (").append(culprit.id()).append(" v").append(culprit.version()).append(")\n");
            sb.append("Location: ").append(culprit.crashingClass()).append(".").append(culprit.crashingMethod())
                    .append("(").append(culprit.jarPath()).append(":").append(culprit.lineNumber()).append(")\n");
        }
        sb.append("Error: ").append(errorSummary).append("\n\n");
        sb.append(snippet).append("\n");
        sb.append("```");
        return sb.toString();
    }
}
