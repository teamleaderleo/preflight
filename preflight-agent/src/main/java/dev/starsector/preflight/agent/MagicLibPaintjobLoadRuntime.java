package dev.starsector.preflight.agent;

import java.io.File;
import java.lang.invoke.MethodHandle;
import java.lang.invoke.MethodHandles;
import java.lang.invoke.MethodType;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.IdentityHashMap;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.atomic.AtomicLong;

/** Avoids exception-driven probes for absent optional MagicLib paintjob JSON files. */
public final class MagicLibPaintjobLoadRuntime {
    static final String PLAN_ID = "magiclib-paintjob-optional-json-v1";

    private static final Map<Object, Path> roots = new IdentityHashMap<>();
    private static final AtomicLong probes = new AtomicLong();
    private static final AtomicLong absent = new AtomicLong();
    private static final AtomicLong present = new AtomicLong();
    private static final AtomicLong delegated = new AtomicLong();
    private static final AtomicLong failures = new AtomicLong();
    private static volatile MethodHandle getPath;
    private static volatile Class<?> modSpecClass;
    private static volatile boolean installed;

    private MagicLibPaintjobLoadRuntime() {
    }

    static void installed() {
        installed = true;
    }

    /** Returns true only when the current mod's own directory proves the optional path absent. */
    public static boolean knownMissing(String path, Object modSpec) {
        probes.incrementAndGet();
        if (path == null || modSpec == null || path.indexOf('\\') >= 0) {
            delegated.incrementAndGet();
            return false;
        }
        try {
            Path root = root(modSpec);
            if (root == null || !Files.isDirectory(root)) {
                delegated.incrementAndGet();
                return false;
            }
            Path relative = Path.of(path);
            if (relative.isAbsolute()) {
                delegated.incrementAndGet();
                return false;
            }
            Path candidate = root.resolve(relative).normalize();
            if (!candidate.startsWith(root)) {
                delegated.incrementAndGet();
                return false;
            }

            // Reuse the resolver's conservative, case-aware directory listing. Listing failures
            // fall through to the filesystem, so an uncertain path keeps MagicLib's real load.
            boolean exists = ResourceProbeRuntime.exists(new File(candidate.toString()));
            if (exists) {
                present.incrementAndGet();
                return false;
            }
            absent.incrementAndGet();
            return true;
        } catch (ThreadDeath | VirtualMachineError fatal) {
            throw fatal;
        } catch (Throwable unexpected) {
            failures.incrementAndGet();
            delegated.incrementAndGet();
            return false;
        }
    }

    private static Path root(Object modSpec) throws Throwable {
        synchronized (roots) {
            Path remembered = roots.get(modSpec);
            if (remembered != null) return remembered;
        }
        MethodHandle accessor = getPath;
        Class<?> type = modSpec.getClass();
        if (accessor == null || modSpecClass != type) {
            accessor = MethodHandles.publicLookup().findVirtual(
                    type, "getPath", MethodType.methodType(String.class));
            getPath = accessor;
            modSpecClass = type;
        }
        String value = (String) accessor.invoke(modSpec);
        if (value == null || value.isBlank()) return null;
        Path resolved = Path.of(value).toAbsolutePath().normalize();
        synchronized (roots) {
            roots.put(modSpec, resolved);
        }
        return resolved;
    }

    static Map<String, Object> telemetry() {
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("planId", PLAN_ID);
        result.put("installed", installed);
        result.put("probes", probes.get());
        result.put("knownAbsent", absent.get());
        result.put("knownPresent", present.get());
        result.put("delegated", delegated.get());
        result.put("failures", failures.get());
        synchronized (roots) {
            result.put("modRoots", roots.size());
        }
        return result;
    }

    static void reset() {
        synchronized (roots) {
            roots.clear();
        }
        getPath = null;
        modSpecClass = null;
        installed = false;
        probes.set(0);
        absent.set(0);
        present.set(0);
        delegated.set(0);
        failures.set(0);
    }
}
