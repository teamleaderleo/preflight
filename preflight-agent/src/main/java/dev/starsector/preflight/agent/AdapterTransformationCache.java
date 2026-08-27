package dev.starsector.preflight.agent;

import dev.starsector.preflight.core.GeneratedBytecodePack;
import dev.starsector.preflight.core.Hashes;
import java.io.IOException;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.CodeSource;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Properties;
import java.util.TreeMap;
import java.util.TreeSet;
import org.objectweb.asm.ClassReader;

/** Persistent exact-output cache for the adapter's reviewed class transformations. */
final class AdapterTransformationCache {
    private static final String SCHEMA = "preflight-adapter-transformations-v1";
    private static final String CACHE_DIRECTORY = "adapter-transformations";
    private static State state = State.disabled("not configured");
    private static long lookups;
    private static long hits;
    private static long misses;
    private static long records;
    private static long bytesServed;
    private static long readFailures;
    private static long writeFailures;

    private AdapterTransformationCache() {
    }

    static synchronized void beginSession() {
        state = State.disabled("not configured");
        lookups = 0L;
        hits = 0L;
        misses = 0L;
        records = 0L;
        bytesServed = 0L;
        readFailures = 0L;
        writeFailures = 0L;
    }

    static synchronized void configure(
            Path cacheDirectory,
            AgentOptions options,
            AdapterTargetRegistry registry,
            Properties properties) {
        if (cacheDirectory == null) {
            state = State.disabled("texture cache directory unavailable");
            return;
        }
        if (FrameTimeRuntime.enabled()) {
            state = State.disabled("frame-time diagnostics are active");
            return;
        }
        try {
            String implementation = implementationIdentity();
            if (implementation.isBlank()) {
                state = State.disabled("implementation archives could not be identified");
                return;
            }
            configure(cacheDirectory, options, registry, properties, implementation);
        } catch (IOException | RuntimeException error) {
            state = State.disabled("configuration failed: " + message(error));
        }
    }

    static synchronized void configure(
            Path cacheDirectory,
            AgentOptions options,
            AdapterTargetRegistry registry,
            Properties properties,
            String implementation) {
        try {
            String context = contextKey(implementation, options, registry, properties);
            Path root = cacheDirectory.toAbsolutePath().normalize().resolve(CACHE_DIRECTORY);
            Path packPath = GeneratedBytecodePack.path(root, context);
            GeneratedBytecodePack loaded = null;
            if (Files.exists(packPath)) {
                try {
                    loaded = GeneratedBytecodePack.read(packPath);
                    if (!context.equals(loaded.contextKeySha256())) {
                        throw new IOException("transformation pack context differs");
                    }
                } catch (IOException | RuntimeException malformed) {
                    readFailures++;
                    loaded = null;
                }
            }
            state = new State(
                    true,
                    loaded == null ? "cold" : "ready",
                    context,
                    packPath,
                    loaded,
                    loaded == null
                            ? new GeneratedBytecodePack.Builder(context)
                            : new GeneratedBytecodePack.Builder(loaded));
        } catch (RuntimeException error) {
            state = State.disabled("configuration failed: " + message(error));
        }
    }

    static synchronized byte[] lookup(AdapterTarget target, ClassSignature signature) {
        if (!state.enabled || !cacheable(target)) return null;
        lookups++;
        String binaryName = binaryName(signature.internalName());
        Map<String, byte[]> classes = state.loaded == null ? null : state.loaded.classesFor(binaryName);
        byte[] transformed = classes == null ? null : classes.get(binaryName);
        if (transformed == null || !validIdentity(signature.internalName(), transformed)) {
            misses++;
            return null;
        }
        hits++;
        bytesServed += transformed.length;
        return transformed;
    }

    static synchronized void record(
            AdapterTarget target, ClassSignature signature, byte[] transformed) {
        if (!state.enabled || !cacheable(target) || transformed == null
                || !validIdentity(signature.internalName(), transformed)) {
            return;
        }
        String binaryName = binaryName(signature.internalName());
        if (state.builder.record(binaryName, Map.of(binaryName, transformed))) {
            records++;
        }
    }

    static synchronized void complete() {
        if (!state.enabled) return;
        try {
            GeneratedBytecodePack completed = state.builder.build();
            if (completed != null) {
                GeneratedBytecodePack.write(state.packPath, completed);
                state = new State(true, "written", state.context, state.packPath,
                        completed, new GeneratedBytecodePack.Builder(completed));
            }
        } catch (IOException | RuntimeException error) {
            writeFailures++;
            state = new State(true, "write failed: " + message(error), state.context,
                    state.packPath, state.loaded, state.builder);
        }
    }

    static synchronized Map<String, Object> telemetry() {
        Map<String, Object> values = new LinkedHashMap<>();
        values.put("enabled", state.enabled);
        values.put("status", state.status);
        values.put("context", state.context);
        values.put("pack", state.packPath == null ? "" : state.packPath.toString());
        values.put("loadedClasses", state.loaded == null ? 0 : state.loaded.classCount());
        values.put("lookups", lookups);
        values.put("hits", hits);
        values.put("misses", misses);
        values.put("records", records);
        values.put("bytesServed", bytesServed);
        values.put("readFailures", readFailures);
        values.put("writeFailures", writeFailures);
        return values;
    }

    static String contextKey(
            String implementation,
            AgentOptions options,
            AdapterTargetRegistry registry,
            Properties properties) {
        StringBuilder canonical = new StringBuilder(16_384)
                .append(SCHEMA).append('\n')
                .append("implementation=").append(implementation).append('\n')
                .append("javaRuntime=").append(System.getProperty("java.runtime.version", "")).append('\n')
                .append("javaVendor=").append(System.getProperty("java.vendor", "")).append('\n');
        canonical.append("textureMode=").append(options.textureAdapterMode()).append('\n');
        canonical.append("startupPhases=").append(options.startupPhaseProbe()).append('\n');
        canonical.append("ruleTokens=").append(options.ruleTokenCache()).append('\n');
        canonical.append("resourceProbes=").append(options.resourceProbeCache()).append('\n');
        canonical.append("loadJsonMemo=").append(options.loadJsonMemo()).append('\n');
        canonical.append("graphicsCompact=").append(options.graphicsLibCompactReplay()).append('\n');
        canonical.append("graphicsInsignia=").append(options.graphicsLibInsigniaManagerCache()).append('\n');
        canonical.append("preparedAudioPathLookup=").append(PreparedAudioRuntime.pathLookupReady()).append('\n');
        canonical.append("effectivePlanScope=")
                .append(AdapterPlanControl.scope().optionValue()).append('\n');
        for (String planId : new TreeSet<>(AdapterPlanControl.disabledPlans())) {
            canonical.append("disabledPlan=").append(planId).append('\n');
        }
        for (AdapterTarget target : registry.targets()) {
            canonical.append("target=").append(target.id()).append('|')
                    .append(target.internalClassName()).append('|')
                    .append(target.sha256()).append('|')
                    .append(target.planId()).append('|')
                    .append(target.sourceKind()).append('|')
                    .append(target.sourceSuffix()).append('|')
                    .append(target.sourceSha256()).append('|')
                    .append(target.loaderClass()).append('|')
                    .append(target.loaderName()).append('\n');
            for (AdapterTarget.RequiredMethod method : target.requiredMethods()) {
                canonical.append("method=").append(method.name()).append(method.descriptor()).append('\n');
            }
        }
        TreeMap<String, String> preflightProperties = new TreeMap<>();
        properties.stringPropertyNames().stream()
                .filter(name -> name.startsWith("preflight."))
                .forEach(name -> preflightProperties.put(name, properties.getProperty(name, "")));
        preflightProperties.forEach((name, value) -> canonical
                .append("property=").append(name).append('=').append(value).append('\n'));
        return Hashes.sha256(canonical.toString().getBytes(StandardCharsets.UTF_8));
    }

    private static String implementationIdentity() throws IOException {
        TreeMap<String, String> identities = new TreeMap<>();
        addImplementationIdentity(identities, AdapterTransformationRegistry.class);
        addImplementationIdentity(identities, GeneratedBytecodePack.class);
        addImplementationIdentity(identities, ClassReader.class);
        if (identities.isEmpty()) return "";
        StringBuilder canonical = new StringBuilder();
        identities.forEach((path, sha) -> canonical.append(path).append('=').append(sha).append('\n'));
        return Hashes.sha256(canonical.toString().getBytes(StandardCharsets.UTF_8));
    }

    private static void addImplementationIdentity(Map<String, String> identities, Class<?> type)
            throws IOException {
        CodeSource source = type.getProtectionDomain().getCodeSource();
        if (source == null || source.getLocation() == null) {
            throw new IOException("missing code source for " + type.getName());
        }
        URI location;
        try {
            location = source.getLocation().toURI();
        } catch (Exception error) {
            throw new IOException("invalid code source for " + type.getName(), error);
        }
        Path path;
        try {
            path = Path.of(location).toAbsolutePath().normalize();
        } catch (RuntimeException error) {
            throw new IOException("non-local code source for " + type.getName(), error);
        }
        SourceArchiveHashes.Result result = SourceArchiveHashes.sha256(path);
        if (!result.successful()) {
            throw new IOException(type.getName() + ": " + result.problem());
        }
        identities.put(path.toString(), result.sha256());
    }

    private static boolean cacheable(AdapterTarget target) {
        String plan = target.planId();
        return !FrameTimeRuntime.PLAN_ID.equals(plan)
                && !GlCommandCountRuntime.PLAN_ID.equals(plan)
                && !FrameLimiterTimePlan.PLAN_ID.equals(plan)
                && !CampaignCallTimeRuntime.PLAN_ID.equals(plan)
                && !CampaignEngineTimeRuntime.PLAN_ID.equals(plan)
                && !CampaignLocationEconomyTimeRuntime.PLAN_ID.equals(plan)
                && !CampaignMarketFleetTimeRuntime.PLAN_ID.equals(plan)
                && !FrameTimeStatePlan.PLAN_ID.equals(plan)
                && !FrameTimeStartupCompletionPlan.PLAN_ID.equals(plan)
                && !MainMenuInteractivePlan.PLAN_ID.equals(plan);
    }

    private static boolean validIdentity(String internalName, byte[] transformed) {
        try {
            return internalName.equals(new ClassReader(transformed).getClassName());
        } catch (RuntimeException malformed) {
            return false;
        }
    }

    private static String binaryName(String internalName) {
        return internalName.replace('/', '.');
    }

    private static String message(Throwable error) {
        String detail = error.getMessage();
        return detail == null || detail.isBlank() ? error.getClass().getSimpleName() : detail;
    }

    private record State(
            boolean enabled,
            String status,
            String context,
            Path packPath,
            GeneratedBytecodePack loaded,
            GeneratedBytecodePack.Builder builder) {
        static State disabled(String status) {
            return new State(false, status, "", null, null, null);
        }
    }
}
