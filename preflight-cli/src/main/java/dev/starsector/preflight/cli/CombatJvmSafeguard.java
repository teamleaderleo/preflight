package dev.starsector.preflight.cli;

import dev.starsector.preflight.core.Hashes;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

/**
 * Pre-launch guard for one demonstrated HotSpot/Rosetta combat miscompile.
 *
 * <p>The failing cast is valid in the exact shipped bytecode and at runtime: the source and target
 * classes come from the same archive and loader, and the source directly implements the target.
 * Interpreting {@code Ship.advance} survived the same destruction/full-retreat overlap, but a later
 * long combat produced the identical impossible cast from {@code Ship.render}. This
 * guard deliberately matches the entire known-risk contract before changing compilation policy:
 * macOS, the reviewed x86-64 Zulu runtime, the aggressively customized launcher/directives, and the
 * exact combat class. Any drift keeps the launcher's policy and leaves evidence in {@code run.json}.
 */
final class CombatJvmSafeguard {
    static final String DISABLE_ENVIRONMENT = "PREFLIGHT_DISABLE_COMBAT_JVM_SAFEGUARD";
    static final String COMPILE_EXCLUSION =
            "-XX:CompileCommand=exclude,com/fs/starfarer/combat/entities/Ship.advance";
    static final String RENDER_COMPILE_EXCLUSION =
            "-XX:CompileCommand=exclude,com/fs/starfarer/combat/entities/Ship.render";
    static final List<String> COMPILE_EXCLUSIONS =
            List.of(COMPILE_EXCLUSION, RENDER_COMPILE_EXCLUSION);
    static final String MODE_PROPERTY =
            "-Dpreflight.combatIntegrity.jvmMode=auto-ship-cast-sites-interpreted";
    static final String SHIP_CLASS = "com/fs/starfarer/combat/entities/Ship.class";
    static final int MAX_SHIP_CLASS_BYTES = 1024 * 1024;
    static final String REVIEWED_SHIP_SHA256 =
            "71997384a879ba6b0897b9f9e8cbf6d91449f2e767b81576dffed7fdd5b29926";

    private CombatJvmSafeguard() {
    }

    static Resolution resolve(Platform platform, LaunchTarget target, Map<String, String> environment) {
        if (disabled(environment.get(DISABLE_ENVIRONMENT))) {
            return Resolution.inactive("disabled by " + DISABLE_ENVIRONMENT);
        }
        if (platform != Platform.MAC) {
            return Resolution.inactive("not macOS");
        }
        try {
            String launcher = boundedText(target.launcher());
            if (!hasRiskyCompilerPolicy(launcher)) {
                return Resolution.inactive("launcher does not match the reviewed aggressive compiler policy");
            }

            Path release = firstRegular(List.of(
                    target.installRoot().resolve("Contents/Home/release"),
                    target.launcher().getParent().resolve("../Home/release").normalize(),
                    target.launcher().getParent().resolve("../../Home/release").normalize()));
            if (release == null || !reviewedRuntime(boundedText(release))) {
                return Resolution.inactive("bundled runtime is not the reviewed x86-64 Zulu 17.0.10 build");
            }

            Path directives = firstRegular(List.of(
                    target.installRoot().resolve("Contents/MacOS/compiler_directives.txt"),
                    target.launcher().resolveSibling("compiler_directives.txt")));
            if (directives == null || !reviewedCombatDirectives(boundedText(directives))) {
                return Resolution.inactive("combat compiler directives do not match the reviewed C1-only policy");
            }

            Path gameJar = firstRegular(List.of(
                    target.installRoot().resolve("Contents/Resources/Java/starfarer_obf.jar"),
                    target.installRoot().resolve("starfarer_obf.jar"),
                    target.workingDirectory().resolve("starfarer_obf.jar")));
            if (gameJar == null) {
                return Resolution.inactive("starfarer_obf.jar was not found");
            }
            String shipSha256 = entrySha256(gameJar, SHIP_CLASS);
            if (!REVIEWED_SHIP_SHA256.equals(shipSha256)) {
                return new Resolution(false, "combat class differs from the reviewed build",
                        gameJar, shipSha256);
            }
            return new Resolution(true,
                    "exact risky macOS x86-JVM/compiler/combat fingerprint matched",
                    gameJar, shipSha256);
        } catch (IOException | RuntimeException problem) {
            return Resolution.inactive("fingerprint probe failed: " + message(problem));
        }
    }

    static String appendOptions(String existing, Resolution resolution) {
        if (!resolution.active()) return existing;
        String result = existing == null ? "" : existing.trim();
        for (String exclusion : COMPILE_EXCLUSIONS) {
            if (!containsOption(result, exclusion)) {
                result = append(result, exclusion);
            }
        }
        if (!result.contains("-Dpreflight.combatIntegrity.jvmMode=")) {
            result = append(result, MODE_PROPERTY);
        }
        return result;
    }

    private static boolean hasRiskyCompilerPolicy(String text) {
        return containsAll(text,
                "-XX:+UnlockExperimentalVMOptions",
                "-XX:+AlwaysCompileLoopMethods",
                "-XX:TieredStopAtLevel=4",
                "-XX:UseAVX=3",
                "-XX:CompilerDirectivesFile=");
    }

    private static boolean reviewedRuntime(String text) {
        return containsAll(text,
                "IMPLEMENTOR=\"Azul Systems, Inc.\"",
                "JAVA_RUNTIME_VERSION=\"17.0.10+7-LTS\"",
                "OS_ARCH=\"x86_64\"",
                "OS_NAME=\"Darwin\"");
    }

    private static boolean reviewedCombatDirectives(String text) {
        String compact = text.replaceAll("\\s+", "").toLowerCase(Locale.ROOT);
        return compact.contains("com/fs/starfarer/combat/*.*")
                && compact.contains("c1:{enable:true,exclude:false")
                && compact.contains("c2:{enable:true,exclude:true");
    }

    private static String entrySha256(Path archive, String name) throws IOException {
        try (ZipFile zip = new ZipFile(archive.toFile())) {
            ZipEntry entry = zip.getEntry(name);
            if (entry == null || entry.isDirectory()) return null;
            if (entry.getSize() > MAX_SHIP_CLASS_BYTES) {
                throw new IOException("refusing oversized combat class " + name
                        + " (" + entry.getSize() + " bytes)");
            }
            try (var input = zip.getInputStream(entry)) {
                byte[] bytes = input.readNBytes(MAX_SHIP_CLASS_BYTES + 1);
                if (bytes.length > MAX_SHIP_CLASS_BYTES) {
                    throw new IOException("refusing oversized combat class " + name);
                }
                return Hashes.sha256(bytes);
            }
        }
    }

    private static String boundedText(Path path) throws IOException {
        long size = Files.size(path);
        if (size > 1024L * 1024L) {
            throw new IOException("refusing oversized fingerprint input " + path + " (" + size + " bytes)");
        }
        return Files.readString(path, StandardCharsets.UTF_8);
    }

    private static Path firstRegular(List<Path> candidates) {
        return candidates.stream()
                .map(path -> path.toAbsolutePath().normalize())
                .distinct()
                .filter(Files::isRegularFile)
                .findFirst()
                .orElse(null);
    }

    private static boolean containsAll(String text, String... needles) {
        for (String needle : needles) {
            if (!text.contains(needle)) return false;
        }
        return true;
    }

    private static boolean disabled(String value) {
        if (value == null) return false;
        return switch (value.trim().toLowerCase(Locale.ROOT)) {
            case "1", "true", "yes", "on" -> true;
            default -> false;
        };
    }

    private static boolean containsOption(String existing, String option) {
        return (" " + existing + " ").contains(" " + option + " ");
    }

    private static String append(String existing, String option) {
        return existing.isBlank() ? option : existing + " " + option;
    }

    private static String message(Throwable problem) {
        String detail = problem.getMessage();
        return problem.getClass().getSimpleName()
                + (detail == null || detail.isBlank() ? "" : ": " + detail);
    }

    record Resolution(boolean active, String reason, Path gameJar, String shipClassSha256) {
        static Resolution inactive(String reason) {
            return new Resolution(false, reason, null, null);
        }

        Map<String, Object> toReportValues() {
            Map<String, Object> result = new LinkedHashMap<>();
            result.put("active", active);
            result.put("reason", reason);
            result.put("gameJar", gameJar);
            result.put("shipClassSha256", shipClassSha256);
            result.put("compileExclusion", active ? COMPILE_EXCLUSION : null);
            result.put("compileExclusions", active ? COMPILE_EXCLUSIONS : List.of());
            result.put("disableEnvironment", DISABLE_ENVIRONMENT);
            return result;
        }
    }
}
