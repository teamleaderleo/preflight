package dev.starsector.preflight.cli;

import dev.starsector.preflight.core.Json;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.IdentityHashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.tree.AbstractInsnNode;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.JumpInsnNode;
import org.objectweb.asm.tree.LookupSwitchInsnNode;
import org.objectweb.asm.tree.MethodInsnNode;
import org.objectweb.asm.tree.MethodNode;
import org.objectweb.asm.tree.TableSwitchInsnNode;
import org.objectweb.asm.tree.TypeInsnNode;

/** Offline bytecode lead generator for suspicious work in mod gameplay callbacks and loops. */
final class ModHotPatternAudit {
    private static final long MAX_CLASS_BYTES = 4L * 1024L * 1024L;
    private static final Set<String> HOT_INTERFACE_FRAGMENTS = Set.of(
            "EveryFrameScript",
            "CombatEveryFramePlugin",
            "LayeredRenderingPlugin",
            "WeaponEffectPlugin",
            "BeamEffectPlugin",
            "MissileAIPlugin",
            "ShipAIPlugin");
    private static final Set<String> HOT_METHOD_NAMES = Set.of(
            "advance",
            "render",
            "advanceInCombat",
            "renderInWorldCoords",
            "renderInUICoords",
            "renderAbove",
            "renderBelow",
            "processInputPreCoreControls");
    private static final Set<String> TEMP_COLLECTION_TYPES = Set.of(
            "java/util/ArrayList",
            "java/util/LinkedList",
            "java/util/HashMap",
            "java/util/LinkedHashMap",
            "java/util/HashSet",
            "java/util/LinkedHashSet",
            "java/util/TreeMap",
            "java/util/TreeSet",
            "java/util/Vector");
    private static final Set<String> BOX_TYPES = Set.of(
            "java/lang/Boolean",
            "java/lang/Byte",
            "java/lang/Character",
            "java/lang/Short",
            "java/lang/Integer",
            "java/lang/Long",
            "java/lang/Float",
            "java/lang/Double");

    private ModHotPatternAudit() {
    }

    static Result scan(Path installRoot, int limit) throws IOException {
        ClasspathAudit.Result classpath = ClasspathAudit.scan(installRoot);
        Map<String, Object> classpathValues = classpath.values();
        Map<String, Path> modDirectories = modDirectories(classpathValues.get("mods"));
        List<Finding> findings = new ArrayList<>();
        long jarsScanned = 0L;
        long classesScanned = 0L;
        long classesSkipped = 0L;
        long malformedClasses = 0L;

        Object jarsValue = classpathValues.get("jars");
        if (jarsValue instanceof Iterable<?> jars) {
            for (Object jarValue : jars) {
                if (!(jarValue instanceof Map<?, ?> jar) || !Boolean.TRUE.equals(jar.get("valid"))) continue;
                String modId = text(jar.get("modId"));
                Path modDirectory = modDirectories.get(modId);
                String relativePath = text(jar.get("relativePath"));
                if (modDirectory == null || relativePath.isBlank()) continue;
                Path archive = modDirectory.resolve(relativePath).normalize();
                jarsScanned++;
                try (ZipFile zip = new ZipFile(archive.toFile())) {
                    var entries = zip.entries();
                    while (entries.hasMoreElements()) {
                        ZipEntry entry = entries.nextElement();
                        if (entry.isDirectory() || !entry.getName().endsWith(".class")) continue;
                        long size = entry.getSize();
                        if (size > MAX_CLASS_BYTES) {
                            classesSkipped++;
                            continue;
                        }
                        try (InputStream input = zip.getInputStream(entry)) {
                            byte[] bytes = input.readNBytes((int) MAX_CLASS_BYTES + 1);
                            if (bytes.length > MAX_CLASS_BYTES) {
                                classesSkipped++;
                                continue;
                            }
                            classesScanned++;
                            scanClass(modId, relativePath, bytes, findings);
                        } catch (RuntimeException malformed) {
                            malformedClasses++;
                        }
                    }
                }
            }
        }

        findings.sort(Comparator.comparingInt(Finding::score).reversed()
                .thenComparing(Finding::modId)
                .thenComparing(Finding::className)
                .thenComparing(Finding::methodName)
                .thenComparing(Finding::pattern));
        List<Finding> retained = findings.size() <= limit
                ? List.copyOf(findings)
                : List.copyOf(findings.subList(0, Math.max(0, limit)));

        Map<String, ModScore> scores = new HashMap<>();
        for (Finding finding : findings) {
            ModScore score = scores.computeIfAbsent(finding.modId(), ModScore::new);
            score.findings++;
            score.score += finding.score();
            score.loopFindings += finding.inLoopCount() > 0 ? 1 : 0;
            score.hotSurfaceFindings += finding.hotSurface() ? 1 : 0;
        }
        List<ModScore> orderedScores = new ArrayList<>(scores.values());
        orderedScores.sort(Comparator.comparingInt((ModScore value) -> value.score).reversed()
                .thenComparing(value -> value.modId));

        Map<String, Object> totals = new LinkedHashMap<>();
        totals.put("jarsScanned", jarsScanned);
        totals.put("classesScanned", classesScanned);
        totals.put("classesSkippedForSize", classesSkipped);
        totals.put("malformedClasses", malformedClasses);
        totals.put("findings", findings.size());
        totals.put("retainedFindings", retained.size());

        List<Map<String, Object>> findingValues = new ArrayList<>(retained.size());
        for (Finding finding : retained) findingValues.add(finding.report());
        List<Map<String, Object>> modValues = new ArrayList<>(orderedScores.size());
        for (ModScore score : orderedScores) modValues.add(score.report());

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("format", "starsector-preflight-mod-hot-pattern-audit-v1");
        result.put("classification", "offline static lead generator; findings require runtime attribution before optimization claims");
        result.put("archiveFingerprint", classpathValues.get("archiveFingerprint"));
        result.put("classpathFingerprint", classpathValues.get("classpathFingerprint"));
        result.put("totals", totals);
        result.put("modScores", modValues);
        result.put("findings", findingValues);
        return new Result(result);
    }

    private static void scanClass(
            String modId, String jar, byte[] bytes, List<Finding> findings) {
        ClassNode owner = new ClassNode();
        new ClassReader(bytes).accept(owner, ClassReader.SKIP_DEBUG | ClassReader.SKIP_FRAMES);
        boolean hotClass = owner.interfaces.stream().anyMatch(ModHotPatternAudit::hotInterface);
        String className = owner.name.replace('/', '.');
        for (MethodNode method : owner.methods) {
            if ((method.access & (Opcodes.ACC_ABSTRACT | Opcodes.ACC_NATIVE)) != 0) continue;
            boolean hotSurface = hotClass || HOT_METHOD_NAMES.contains(method.name);
            List<AbstractInsnNode> instructions = instructions(method);
            boolean[] inLoop = loopMembership(instructions);
            Map<String, PatternCount> patterns = new LinkedHashMap<>();
            for (int index = 0; index < instructions.size(); index++) {
                AbstractInsnNode instruction = instructions.get(index);
                Pattern pattern = pattern(instruction);
                if (pattern == null) continue;
                boolean loop = inLoop[index];
                if (!hotSurface && !loop) continue;
                PatternCount count = patterns.computeIfAbsent(
                        pattern.id, ignored -> new PatternCount(pattern));
                count.count++;
                if (loop) count.inLoop++;
            }
            for (PatternCount count : patterns.values()) {
                int multiplier = count.inLoop > 0 ? 3 : 1;
                int score = count.pattern.weight * multiplier
                        * Math.max(1, count.inLoop > 0 ? count.inLoop : count.count);
                findings.add(new Finding(
                        modId,
                        jar,
                        className,
                        method.name,
                        method.desc,
                        hotSurface,
                        count.pattern.id,
                        count.pattern.description,
                        count.count,
                        count.inLoop,
                        score));
            }
        }
    }

    private static Pattern pattern(AbstractInsnNode instruction) {
        if (instruction instanceof TypeInsnNode type && type.getOpcode() == Opcodes.NEW) {
            if (TEMP_COLLECTION_TYPES.contains(type.desc)) {
                return Pattern.TEMP_COLLECTION;
            }
            if ("java/lang/StringBuilder".equals(type.desc)
                    || "java/lang/StringBuffer".equals(type.desc)) {
                return Pattern.TEMP_STRING_BUILDER;
            }
        }
        if (!(instruction instanceof MethodInsnNode call)) return null;
        String owner = call.owner;
        String name = call.name;
        if (("java/util/Collections".equals(owner) && ("sort".equals(name) || "shuffle".equals(name)))
                || ("java/util/List".equals(owner) && "sort".equals(name))) {
            return Pattern.SORT_OR_SHUFFLE;
        }
        if ("java/util/Arrays".equals(owner) && name.startsWith("copyOf")) {
            return Pattern.COPY;
        }
        if (TEMP_COLLECTION_TYPES.contains(owner) && "<init>".equals(name)
                && call.desc.contains("Ljava/util/Collection;")) {
            return Pattern.COPY;
        }
        if (BOX_TYPES.contains(owner) && "valueOf".equals(name)) {
            return Pattern.BOXING;
        }
        if (("java/lang/String".equals(owner) && "format".equals(name))
                || ("java/util/Formatter".equals(owner) && "format".equals(name))
                || (owner.startsWith("java/text/") && "format".equals(name))) {
            return Pattern.FORMATTING;
        }
        if (name.equals("toArray")) {
            return Pattern.TO_ARRAY;
        }
        if (name.equals("stream") || name.equals("parallelStream")) {
            return Pattern.STREAM_PIPELINE;
        }
        if ("java/util/regex/Pattern".equals(owner) && "compile".equals(name)) {
            return Pattern.REGEX_COMPILE;
        }
        if ("java/lang/System".equals(owner)
                && ("nanoTime".equals(name) || "currentTimeMillis".equals(name))) {
            return Pattern.CLOCK_POLL;
        }
        if (("java/lang/Thread".equals(owner) && "sleep".equals(name))
                || ("java/util/concurrent/locks/LockSupport".equals(owner)
                        && name.startsWith("park"))
                || ("org/lwjgl/opengl/Display".equals(owner) && "sync".equals(name))) {
            return Pattern.SCHEDULER_WAIT;
        }
        if (owner.startsWith("org/lwjgl/opengl/GL")
                && (name.startsWith("glGet") || "glReadPixels".equals(name)
                        || "glFinish".equals(name) || "glFlush".equals(name)
                        || name.startsWith("glMapBuffer"))) {
            return Pattern.GL_QUERY_OR_SYNC;
        }
        if (owner.startsWith("org/lwjgl/opengl/GL")
                && (name.startsWith("glGen") || name.startsWith("glCreate")
                        || name.startsWith("glDelete"))) {
            return Pattern.GL_RESOURCE_CHURN;
        }
        String lowerOwner = owner.toLowerCase(Locale.ROOT);
        if ((lowerOwner.contains("log4j") || lowerOwner.contains("slf4j"))
                && Set.of("trace", "debug", "info", "warn", "error", "fatal").contains(name)) {
            return Pattern.LOGGING;
        }
        if (("java/lang/reflect/Method".equals(owner) && "invoke".equals(name))
                || ("java/lang/Class".equals(owner) && "forName".equals(name))) {
            return Pattern.REFLECTION;
        }
        return null;
    }

    private static List<AbstractInsnNode> instructions(MethodNode method) {
        List<AbstractInsnNode> result = new ArrayList<>(method.instructions.size());
        for (AbstractInsnNode instruction = method.instructions.getFirst();
                instruction != null; instruction = instruction.getNext()) {
            result.add(instruction);
        }
        return result;
    }

    private static boolean[] loopMembership(List<AbstractInsnNode> instructions) {
        boolean[] result = new boolean[instructions.size()];
        if (instructions.isEmpty()) return result;
        IdentityHashMap<AbstractInsnNode, Integer> indexes = new IdentityHashMap<>();
        for (int index = 0; index < instructions.size(); index++) indexes.put(instructions.get(index), index);
        int[] delta = new int[instructions.size() + 1];
        for (int index = 0; index < instructions.size(); index++) {
            AbstractInsnNode instruction = instructions.get(index);
            if (instruction instanceof JumpInsnNode jump) {
                markLoop(index, indexes.get(jump.label), delta);
            } else if (instruction instanceof TableSwitchInsnNode table) {
                markLoop(index, indexes.get(table.dflt), delta);
                for (var label : table.labels) markLoop(index, indexes.get(label), delta);
            } else if (instruction instanceof LookupSwitchInsnNode lookup) {
                markLoop(index, indexes.get(lookup.dflt), delta);
                for (var label : lookup.labels) markLoop(index, indexes.get(label), delta);
            }
        }
        int depth = 0;
        for (int index = 0; index < result.length; index++) {
            depth += delta[index];
            result[index] = depth > 0;
        }
        return result;
    }

    private static void markLoop(int source, Integer target, int[] delta) {
        if (target == null || target > source || target < 0) return;
        delta[target]++;
        if (source + 1 < delta.length) delta[source + 1]--;
    }

    private static boolean hotInterface(String internalName) {
        for (String fragment : HOT_INTERFACE_FRAGMENTS) {
            if (internalName.contains(fragment)) return true;
        }
        return false;
    }

    private static Map<String, Path> modDirectories(Object modsValue) {
        Map<String, Path> result = new HashMap<>();
        if (!(modsValue instanceof Iterable<?> mods)) return result;
        for (Object value : mods) {
            if (!(value instanceof Map<?, ?> mod)) continue;
            String id = text(mod.get("id"));
            Object directory = mod.get("directory");
            if (!id.isBlank() && directory instanceof Path path) result.put(id, path);
        }
        return result;
    }

    private static String text(Object value) {
        return value == null ? "" : value.toString();
    }

    record Result(Map<String, Object> values) {
        String toJson() {
            return Json.object(values);
        }
    }

    private enum Pattern {
        TEMP_COLLECTION("TEMP_COLLECTION", 2, "temporary collection allocation"),
        TEMP_STRING_BUILDER("TEMP_STRING_BUILDER", 1, "temporary string builder allocation"),
        SORT_OR_SHUFFLE("SORT_OR_SHUFFLE", 4, "collection sort/shuffle"),
        COPY("COPY", 3, "array/collection copy"),
        BOXING("BOXING", 1, "primitive boxing"),
        FORMATTING("FORMATTING", 4, "string/number formatting"),
        TO_ARRAY("TO_ARRAY", 2, "collection/stream toArray"),
        STREAM_PIPELINE("STREAM_PIPELINE", 2, "stream pipeline creation"),
        REGEX_COMPILE("REGEX_COMPILE", 4, "regular-expression compilation"),
        CLOCK_POLL("CLOCK_POLL", 1, "clock read"),
        SCHEDULER_WAIT("SCHEDULER_WAIT", 5, "sleep/park/display sync"),
        GL_QUERY_OR_SYNC("GL_QUERY_OR_SYNC", 5, "OpenGL query/readback/synchronization call"),
        GL_RESOURCE_CHURN("GL_RESOURCE_CHURN", 5, "OpenGL resource create/delete call"),
        LOGGING("LOGGING", 4, "logging call"),
        REFLECTION("REFLECTION", 3, "reflection/dynamic class lookup");

        final String id;
        final int weight;
        final String description;

        Pattern(String id, int weight, String description) {
            this.id = id;
            this.weight = weight;
            this.description = description;
        }
    }

    private static final class PatternCount {
        final Pattern pattern;
        int count;
        int inLoop;

        PatternCount(Pattern pattern) {
            this.pattern = pattern;
        }
    }

    private record Finding(
            String modId,
            String jar,
            String className,
            String methodName,
            String descriptor,
            boolean hotSurface,
            String pattern,
            String description,
            int count,
            int inLoopCount,
            int score) {
        Map<String, Object> report() {
            Map<String, Object> value = new LinkedHashMap<>();
            value.put("modId", modId);
            value.put("jar", jar);
            value.put("className", className);
            value.put("methodName", methodName);
            value.put("descriptor", descriptor);
            value.put("hotSurface", hotSurface);
            value.put("pattern", pattern);
            value.put("description", description);
            value.put("count", count);
            value.put("inLoopCount", inLoopCount);
            value.put("score", score);
            return value;
        }
    }

    private static final class ModScore {
        final String modId;
        int score;
        int findings;
        int loopFindings;
        int hotSurfaceFindings;

        ModScore(String modId) {
            this.modId = modId;
        }

        Map<String, Object> report() {
            Map<String, Object> value = new LinkedHashMap<>();
            value.put("modId", modId);
            value.put("score", score);
            value.put("findings", findings);
            value.put("loopFindings", loopFindings);
            value.put("hotSurfaceFindings", hotSurfaceFindings);
            return value;
        }
    }
}
