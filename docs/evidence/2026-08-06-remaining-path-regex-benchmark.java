package dev.starsector.preflight.agent;

import dev.starsector.preflight.core.JarArchiveIndex;
import dev.starsector.preflight.core.JarArchiveIndexIO;
import dev.starsector.preflight.core.PreparedHullJsonCacheIO;
import dev.starsector.preflight.core.PreparedProjectileJsonCacheIO;
import dev.starsector.preflight.core.PreparedVariantJsonCacheIO;
import dev.starsector.preflight.core.PreparedWeaponJsonCacheIO;
import dev.starsector.preflight.core.ResourceIndex;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Deque;
import java.util.List;
import java.util.Locale;

/** Real-corpus operation benchmark for the last regex-backed path normalizers. */
final class RemainingPathRegexBenchmark {
    private static volatile int consumed;

    public static void main(String[] args) throws Exception {
        Path cache = Path.of(System.getProperty("user.home"), ".starsector-preflight", "cache");
        List<String> jarNames = jarNames(cache.resolve("classpath/archives"));
        List<SpecCase> specs = specCases(cache.resolve("spec-store"));
        for (String name : jarNames) {
            if (!oldJarNormalize(name).equals(JarArchiveIndex.normalizeEntryName(name))) {
                throw new AssertionError("JAR normalization differs for " + name);
            }
        }
        for (SpecCase item : specs) {
            if (!java.util.Objects.equals(oldSpecKey(item),
                    SpecCacheKey.of(item.path(), item.directories(), item.suffix()))) {
                throw new AssertionError("Spec normalization differs for " + item.path());
            }
        }

        for (int round = 0; round < 4; round++) {
            consumeJars(jarNames, true);
            consumeJars(jarNames, false);
            consumeSpecs(specs, true);
            consumeSpecs(specs, false);
        }
        report("JAR entry normalization", jarNames.size(),
                () -> consumeJars(jarNames, true), () -> consumeJars(jarNames, false));
        report("spec cache-key normalization", specs.size(),
                () -> consumeSpecs(specs, true), () -> consumeSpecs(specs, false));
        System.out.println("consume=" + consumed);
    }

    private static void report(String label, int operations, Work oldWork, Work newWork)
            throws Exception {
        long[] oldTimes = new long[10];
        long[] newTimes = new long[10];
        for (int round = 0; round < oldTimes.length; round++) {
            oldTimes[round] = time(oldWork);
            newTimes[round] = time(newWork);
        }
        Arrays.sort(oldTimes);
        Arrays.sort(newTimes);
        long oldMedian = midpoint(oldTimes);
        long newMedian = midpoint(newTimes);
        System.out.printf(Locale.ROOT,
                "%s: %,d ops; old %.2fns/op; scanner %.2fns/op; %.2fx; saves %.3fms/pass%n",
                label, operations, (double) oldMedian / operations, (double) newMedian / operations,
                (double) oldMedian / newMedian, (oldMedian - newMedian) / 1e6);
        System.out.println("  old ns=" + Arrays.toString(oldTimes));
        System.out.println("  new ns=" + Arrays.toString(newTimes));
    }

    private static List<String> jarNames(Path root) throws Exception {
        List<String> names = new ArrayList<>();
        try (var files = Files.walk(root)) {
            for (Path file : files.filter(path -> path.toString().endsWith(".spfj")).toList()) {
                JarArchiveIndex archive = JarArchiveIndexIO.read(file);
                names.addAll(archive.entries().keySet());
            }
        }
        return names;
    }

    private static List<SpecCase> specCases(Path root) throws Exception {
        List<SpecCase> cases = new ArrayList<>();
        Path variant = find(root.resolve("variant-json"), ".spvj");
        add(cases, PreparedVariantJsonCacheIO.read(variant).entries().keySet(),
                List.of("data/variants/"), ".variant");
        Path weapon = find(root.resolve("weapon-json"), ".spwj");
        add(cases, PreparedWeaponJsonCacheIO.read(weapon).entries().keySet(),
                List.of("data/weapons/", "data/shipsystems/wpn/"), ".wpn");
        Path projectile = find(root.resolve("projectile-json"), ".sppj");
        add(cases, PreparedProjectileJsonCacheIO.read(projectile).entries().keySet(),
                List.of("data/weapons/proj/", "data/shipsystems/proj/"), ".proj");
        Path hull = find(root.resolve("hull-json"), ".sphj");
        add(cases, PreparedHullJsonCacheIO.read(hull).entries().keySet(),
                List.of("data/hulls/"), ".ship");
        return cases;
    }

    private static void add(List<SpecCase> cases, Iterable<String> keys,
            List<String> directories, String suffix) {
        for (String key : keys) {
            String path = key.startsWith("abs:")
                    ? "/Applications/Starsector.app/Contents/Resources/Java/" + key.substring(4)
                    : key;
            cases.add(new SpecCase(path, directories, suffix));
        }
    }

    private static Path find(Path directory, String suffix) throws Exception {
        try (var files = Files.list(directory)) {
            return files.filter(path -> path.toString().endsWith(suffix)).findFirst().orElseThrow();
        }
    }

    private static void consumeJars(List<String> names, boolean old) {
        int value = 0;
        for (String name : names) {
            value += (old ? oldJarNormalize(name) : JarArchiveIndex.normalizeEntryName(name)).length();
        }
        consumed ^= value;
    }

    private static void consumeSpecs(List<SpecCase> cases, boolean old) {
        int value = 0;
        for (SpecCase item : cases) {
            String key = old ? oldSpecKey(item) : SpecCacheKey.of(
                    item.path(), item.directories(), item.suffix());
            value += key == null ? 0 : key.length();
        }
        consumed ^= value;
    }

    private static String oldJarNormalize(String raw) {
        if (raw == null || raw.isBlank()) {
            throw new IllegalArgumentException();
        }
        String value = raw.replace('\\', '/');
        if (value.startsWith("/") || value.matches("^[A-Za-z]:.*")) {
            throw new IllegalArgumentException();
        }
        Deque<String> segments = new ArrayDeque<>();
        for (String segment : value.split("/+")) {
            if (segment.isEmpty() || segment.equals(".")) continue;
            if (segment.equals("..")) throw new IllegalArgumentException();
            segments.addLast(segment);
        }
        if (segments.isEmpty()) throw new IllegalArgumentException();
        return String.join("/", new ArrayList<>(segments));
    }

    private static String oldSpecKey(SpecCase item) {
        String value = item.path().replace('\\', '/');
        boolean absolute = value.startsWith("/") || value.matches("^[A-Za-z]:.*");
        try {
            if (!absolute) {
                String path = ResourceIndex.normalizeLogicalPath(value);
                return matches(path, item) ? path : null;
            }
            String lower = value.toLowerCase(Locale.ROOT);
            int best = -1;
            for (String directory : item.directories()) {
                int at = lower.lastIndexOf('/' + directory);
                if (at > best) best = at;
            }
            if (best < 0) return null;
            String path = ResourceIndex.normalizeLogicalPath(value.substring(best + 1));
            return matches(path, item) ? "abs:" + path : null;
        } catch (RuntimeException ignored) {
            return null;
        }
    }

    private static boolean matches(String path, SpecCase item) {
        if (!path.endsWith(item.suffix())) return false;
        for (String directory : item.directories()) {
            if (path.startsWith(directory)) return true;
        }
        return false;
    }

    private static long time(Work work) throws Exception {
        long start = System.nanoTime();
        work.run();
        return System.nanoTime() - start;
    }

    private static long midpoint(long[] sorted) {
        return (sorted[sorted.length / 2 - 1] + sorted[sorted.length / 2]) / 2;
    }

    private record SpecCase(String path, List<String> directories, String suffix) {}
    private interface Work { void run() throws Exception; }
}
