package dev.starsector.preflight.agent;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.jar.JarFile;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.tree.AbstractInsnNode;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.MethodInsnNode;
import org.objectweb.asm.tree.MethodNode;
import org.objectweb.asm.tree.TypeInsnNode;
import org.objectweb.asm.tree.analysis.Analyzer;
import org.objectweb.asm.tree.analysis.BasicInterpreter;

/** Exact installed-mod bytecode check; reads the DCR archive in memory and never starts the game. */
class DetailedCombatResultsStateReuseInstalledAdapterTest {
    @AfterEach
    void clearProperty() {
        System.clearProperty(DetailedCombatResultsStateReusePlan.ENABLED_PROPERTY);
        DetailedCombatResultsStateReuseRuntime.beginSession();
    }

    @Test
    void installedDetectorReusesStateMapsWithoutChangingItsPerFrameBoundary() throws Exception {
        String configured = System.getProperty("preflight.dcr.jar", "").trim();
        Assumptions.assumeTrue(!configured.isEmpty(),
                "set -Dpreflight.dcr.jar=<StarSectorDetailedCombatResults.jar>");
        Path archive = Path.of(configured).toAbsolutePath().normalize();
        Assumptions.assumeTrue(Files.isRegularFile(archive));

        byte[] original;
        try (JarFile jar = new JarFile(archive.toFile())) {
            var entry = jar.getJarEntry(
                    DetailedCombatResultsStateReusePlan.TARGET_CLASS + ".class");
            assertNotNull(entry);
            try (var input = jar.getInputStream(entry)) {
                original = input.readAllBytes();
            }
        }
        ClassSignature signature = ClassSignature.parse(original);
        assertEquals(DetailedCombatResultsStateReusePlan.ORIGINAL_SHA256, signature.sha256());

        System.setProperty(DetailedCombatResultsStateReusePlan.ENABLED_PROPERTY, "true");
        byte[] transformed = DetailedCombatResultsStateReusePlan.transform(signature, original);
        assertNotNull(transformed);
        assertNull(DetailedCombatResultsStateReusePlan.transform(
                ClassSignature.parse(transformed), transformed));

        ClassNode owner = new ClassNode(Opcodes.ASM9);
        new ClassReader(transformed).accept(owner, ClassReader.EXPAND_FRAMES);
        for (MethodNode method : owner.methods) {
            new Analyzer<>(new BasicInterpreter()).analyze(owner.name, method);
        }

        MethodNode update = method(
                owner,
                DetailedCombatResultsStateReusePlan.METHOD,
                DetailedCombatResultsStateReusePlan.DESCRIPTOR);
        assertEquals(0, allocations(update, "java/util/HashMap"));
        assertEquals(1, calls(update, owner.name, "$preflight$refreshProjectileHistory"));
        assertEquals(1, calls(update, owner.name, "$preflight$rotateShipMaps"));
    }

    private static MethodNode method(ClassNode owner, String name, String descriptor) {
        return owner.methods.stream()
                .filter(candidate -> name.equals(candidate.name) && descriptor.equals(candidate.desc))
                .findFirst().orElseThrow();
    }

    private static int allocations(MethodNode method, String type) {
        int count = 0;
        for (AbstractInsnNode instruction : method.instructions) {
            if (instruction instanceof TypeInsnNode allocation
                    && allocation.getOpcode() == Opcodes.NEW
                    && type.equals(allocation.desc)) count++;
        }
        return count;
    }

    private static int calls(MethodNode method, String owner, String name) {
        int count = 0;
        for (AbstractInsnNode instruction : method.instructions) {
            if (instruction instanceof MethodInsnNode call
                    && owner.equals(call.owner) && name.equals(call.name)) count++;
        }
        return count;
    }
}
