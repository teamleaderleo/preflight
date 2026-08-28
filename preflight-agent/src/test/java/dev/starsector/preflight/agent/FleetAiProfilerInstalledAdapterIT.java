package dev.starsector.preflight.agent;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.jar.JarFile;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.tree.AbstractInsnNode;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.MethodInsnNode;
import org.objectweb.asm.tree.MethodNode;

/** Exact installed checks for the disabled-profiler fleet-AI label shortcut; never starts the game. */
class FleetAiProfilerInstalledAdapterIT {
    @BeforeEach
    void enable() {
        FleetAiProfilerRuntime.beginSession();
        FleetAiModuleTimeRuntime.beginSession(true);
    }

    @Test
    void installedOwnersMatchReviewedShapes() throws Exception {
        String coreConfigured = System.getProperty("preflight.starsector.core.jar", "").trim();
        String commonConfigured = System.getProperty("preflight.starsector.common.jar", "").trim();
        Assumptions.assumeTrue(!coreConfigured.isEmpty() && !commonConfigured.isEmpty(),
                "set installed starfarer_obf.jar and fs.common_obf.jar properties");
        Path core = Path.of(coreConfigured).toAbsolutePath().normalize();
        Path common = Path.of(commonConfigured).toAbsolutePath().normalize();
        Assumptions.assumeTrue(Files.isRegularFile(core) && Files.isRegularFile(common));

        byte[] fleet = entry(core, FleetAiProfilerPlan.FLEET_AI_CLASS);
        ClassSignature fleetSignature = ClassSignature.parse(fleet);
        assertEquals(FleetAiProfilerPlan.FLEET_AI_SHA256, fleetSignature.sha256());
        byte[] transformedFleet = FleetAiProfilerPlan.transform(fleetSignature, fleet);
        assertNotNull(transformedFleet);
        assertEquals(1, calls(method(transformedFleet, FleetAiProfilerPlan.ADVANCE,
                FleetAiProfilerPlan.ADVANCE_DESCRIPTOR), "abilityLabelNeeded"));
        byte[] timedFleet = FleetAiModuleTimePlan.transform(fleetSignature, transformedFleet);
        assertNotNull(timedFleet);
        assertEquals(5, calls(method(timedFleet, FleetAiProfilerPlan.ADVANCE,
                FleetAiProfilerPlan.ADVANCE_DESCRIPTOR),
                FleetAiModuleTimeRuntime.class, "enter"));
        assertEquals(5, calls(method(timedFleet, FleetAiProfilerPlan.ADVANCE,
                FleetAiProfilerPlan.ADVANCE_DESCRIPTOR),
                FleetAiModuleTimeRuntime.class, "exit"));

        byte[] profiler = entry(common, FleetAiProfilerPlan.PROFILER_CLASS);
        ClassSignature profilerSignature = ClassSignature.parse(profiler);
        assertEquals(FleetAiProfilerPlan.PROFILER_SHA256, profilerSignature.sha256());
        byte[] transformedProfiler = FleetAiProfilerPlan.transform(profilerSignature, profiler);
        assertNotNull(transformedProfiler);
        assertEquals(1, calls(method(transformedProfiler, FleetAiProfilerPlan.PROFILER_TOGGLE,
                FleetAiProfilerPlan.PROFILER_TOGGLE_DESCRIPTOR), "profilerState"));
    }

    private static byte[] entry(Path archive, String name) throws Exception {
        try (JarFile jar = new JarFile(archive.toFile())) {
            var entry = jar.getJarEntry(name + ".class");
            assertNotNull(entry);
            try (var input = jar.getInputStream(entry)) {
                return input.readAllBytes();
            }
        }
    }

    private static MethodNode method(byte[] bytes, String name, String descriptor) {
        ClassNode owner = new ClassNode(Opcodes.ASM9);
        new ClassReader(bytes).accept(owner, ClassReader.EXPAND_FRAMES);
        return owner.methods.stream()
                .filter(candidate -> name.equals(candidate.name) && descriptor.equals(candidate.desc))
                .findFirst().orElseThrow();
    }

    private static int calls(MethodNode method, String name) {
        return calls(method, FleetAiProfilerRuntime.class, name);
    }

    private static int calls(MethodNode method, Class<?> runtime, String name) {
        int result = 0;
        for (AbstractInsnNode instruction : method.instructions) {
            if (instruction instanceof MethodInsnNode call
                    && runtime.getName().replace('.', '/').equals(call.owner)
                    && name.equals(call.name)) result++;
        }
        return result;
    }
}
