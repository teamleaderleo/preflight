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
import org.objectweb.asm.tree.AbstractInsnNode;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.MethodInsnNode;
import org.objectweb.asm.tree.MethodNode;
import org.objectweb.asm.tree.TypeInsnNode;

/** Opt-in structural transform of the exact installed API class; never starts Starsector. */
class CombatListenerRangeSnapshotInstalledAdapterIT {
    @AfterEach
    void clearProperty() {
        System.clearProperty(CombatListenerRangeSnapshotPlan.ENABLED_PROPERTY);
    }

    @Test
    void installedRangeQueriesRetainOneSnapshotWithoutListCopyOrIterator() throws Exception {
        String configured = System.getProperty("preflight.starsector.api.jar", "").trim();
        Assumptions.assumeTrue(!configured.isEmpty(),
                "set -Dpreflight.starsector.api.jar=<starfarer.api.jar>");
        Path archive = Path.of(configured).toAbsolutePath().normalize();
        Assumptions.assumeTrue(Files.isRegularFile(archive));

        byte[] original;
        try (JarFile jar = new JarFile(archive.toFile())) {
            var entry = jar.getJarEntry(CombatListenerRangeSnapshotPlan.TARGET_CLASS + ".class");
            assertNotNull(entry);
            try (var input = jar.getInputStream(entry)) {
                original = input.readAllBytes();
            }
        }
        ClassSignature signature = ClassSignature.parse(original);
        assertEquals(CombatListenerRangeSnapshotPlan.ORIGINAL_SHA256, signature.sha256());

        System.setProperty(CombatListenerRangeSnapshotPlan.ENABLED_PROPERTY, "true");
        byte[] transformed = CombatListenerRangeSnapshotPlan.transform(signature, original);
        assertNotNull(transformed);
        ClassNode owner = new ClassNode(Opcodes.ASM9);
        new ClassReader(transformed).accept(owner, ClassReader.EXPAND_FRAMES);

        for (var spec : CombatListenerRangeSnapshotPlan.METHODS) {
            MethodNode method = owner.methods.stream()
                    .filter(candidate -> spec.name().equals(candidate.name)
                            && CombatListenerRangeSnapshotPlan.DESCRIPTOR.equals(candidate.desc))
                    .findFirst().orElseThrow();
            assertEquals(0, allocations(method, "java/util/ArrayList"), spec.name());
            assertEquals(0, calls(method, "java/util/ArrayList", "iterator",
                    "()Ljava/util/Iterator;"), spec.name());
            assertEquals(1, calls(method, "java/util/List", "toArray",
                    "()[Ljava/lang/Object;"), spec.name());
            assertEquals(1, opcodes(method, Opcodes.AALOAD), spec.name());
        }
    }

    private static int calls(MethodNode method, String owner, String name, String descriptor) {
        int count = 0;
        for (AbstractInsnNode instruction : method.instructions) {
            if (instruction instanceof MethodInsnNode call
                    && owner.equals(call.owner)
                    && name.equals(call.name)
                    && descriptor.equals(call.desc)) count++;
        }
        return count;
    }

    private static int allocations(MethodNode method, String type) {
        int count = 0;
        for (AbstractInsnNode instruction : method.instructions) {
            if (instruction instanceof TypeInsnNode allocation
                    && instruction.getOpcode() == Opcodes.NEW
                    && type.equals(allocation.desc)) count++;
        }
        return count;
    }

    private static int opcodes(MethodNode method, int opcode) {
        int count = 0;
        for (AbstractInsnNode instruction : method.instructions) {
            if (instruction.getOpcode() == opcode) count++;
        }
        return count;
    }
}
