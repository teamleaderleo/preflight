package dev.starsector.preflight.agent;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.jar.JarFile;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.MethodInsnNode;

/** Opt-in exact installed-class transform check; it never starts or initializes the game. */
class SimOpponentSafetyInstalledAdapterIT {
    @AfterEach
    void reset() {
        SimOpponentSafetyRuntime.beginSession();
    }

    @Test
    void installedRefitSimulatorHasExactlyTheReviewedConsumptionSites() throws Exception {
        String configured = System.getProperty("preflight.starsector.core.jar", "").trim();
        Assumptions.assumeTrue(!configured.isEmpty(),
                "set -Dpreflight.starsector.core.jar=<starfarer_obf.jar>");
        Path archive = Path.of(configured).toAbsolutePath().normalize();
        Assumptions.assumeTrue(Files.isRegularFile(archive));

        byte[] original;
        try (JarFile jar = new JarFile(archive.toFile())) {
            var entry = jar.getJarEntry(SimOpponentSafetyPlan.TARGET_CLASS + ".class");
            assertNotNull(entry);
            try (var input = jar.getInputStream(entry)) {
                original = input.readAllBytes();
            }
        }
        ClassSignature signature = ClassSignature.parse(original);
        assertEquals(SimOpponentSafetyPlan.ORIGINAL_SHA256, signature.sha256());
        byte[] transformed = SimOpponentSafetyPlan.transform(signature, original);
        assertNotNull(transformed);

        ClassNode owner = new ClassNode(Opcodes.ASM9);
        new ClassReader(transformed).accept(owner, ClassReader.EXPAND_FRAMES);
        var simulation = owner.methods.stream()
                .filter(method -> SimOpponentSafetyPlan.SIMULATION_METHOD.equals(method.name)
                        && SimOpponentSafetyPlan.SIMULATION_DESCRIPTOR.equals(method.desc))
                .findFirst().orElseThrow();
        int filters = 0;
        for (var instruction : simulation.instructions) {
            if (instruction instanceof MethodInsnNode call
                    && SimOpponentSafetyRuntime.class.getName().replace('.', '/').equals(call.owner)
                    && "filter".equals(call.name)) {
                filters++;
            }
        }
        assertEquals(2, filters);
    }

    @Test
    void installedOpponentDialogHasTheReviewedGridBoundary() throws Exception {
        String configured = System.getProperty("preflight.starsector.core.jar", "").trim();
        Assumptions.assumeTrue(!configured.isEmpty(),
                "set -Dpreflight.starsector.core.jar=<starfarer_obf.jar>");
        Path archive = Path.of(configured).toAbsolutePath().normalize();
        Assumptions.assumeTrue(Files.isRegularFile(archive));

        byte[] original;
        try (JarFile jar = new JarFile(archive.toFile())) {
            var entry = jar.getJarEntry(SimOpponentDialogProbePlan.TARGET_CLASS + ".class");
            assertNotNull(entry);
            try (var input = jar.getInputStream(entry)) {
                original = input.readAllBytes();
            }
        }
        ClassSignature signature = ClassSignature.parse(original);
        assertEquals(SimOpponentDialogProbePlan.ORIGINAL_SHA256, signature.sha256());
        byte[] transformed = SimOpponentDialogProbePlan.transform(signature, original);
        assertNotNull(transformed);

        ClassNode owner = new ClassNode(Opcodes.ASM9);
        new ClassReader(transformed).accept(owner, ClassReader.EXPAND_FRAMES);
        var grid = owner.methods.stream()
                .filter(method -> SimOpponentDialogProbePlan.GRID_METHOD.equals(method.name)
                        && SimOpponentDialogProbePlan.GRID_DESCRIPTOR.equals(method.desc))
                .findFirst().orElseThrow();
        var layout = owner.methods.stream()
                .filter(method -> SimOpponentDialogProbePlan.LAYOUT_METHOD.equals(method.name)
                        && SimOpponentDialogProbePlan.LAYOUT_DESCRIPTOR.equals(method.desc))
                .findFirst().orElseThrow();
        var advance = owner.methods.stream()
                .filter(method -> SimOpponentDialogProbePlan.ADVANCE_METHOD.equals(method.name)
                        && SimOpponentDialogProbePlan.ADVANCE_DESCRIPTOR.equals(method.desc))
                .findFirst().orElseThrow();
        var update = owner.methods.stream()
                .filter(method -> SimOpponentDialogProbePlan.UPDATE_METHOD.equals(method.name)
                        && SimOpponentDialogProbePlan.UPDATE_DESCRIPTOR.equals(method.desc))
                .findFirst().orElseThrow();
        assertEquals(1, dialogObservations(grid));
        assertEquals(1, dialogObservations(layout));
        assertEquals(1, dialogObservations(advance));
        int updateObservations = 0;
        for (var instruction : update.instructions) {
            if (instruction instanceof MethodInsnNode call
                    && SimOpponentSafetyRuntime.class.getName().replace('.', '/').equals(call.owner)
                    && "recordCategoryUpdate".equals(call.name)) {
                updateObservations++;
            }
        }
        assertEquals(1, updateObservations);
    }

    private static int dialogObservations(org.objectweb.asm.tree.MethodNode method) {
        int observations = 0;
        for (var instruction : method.instructions) {
            if (instruction instanceof MethodInsnNode call
                    && SimOpponentSafetyRuntime.class.getName().replace('.', '/').equals(call.owner)
                    && "recordDialog".equals(call.name)) {
                observations++;
            }
        }
        return observations;
    }
}
