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
import org.objectweb.asm.tree.JumpInsnNode;
import org.objectweb.asm.tree.MethodInsnNode;
import org.objectweb.asm.tree.MethodNode;

/** Opt-in transform check against the exact installed MnemonicUtils sensor script. */
class MnemonicSensorsEntityFilterInstalledAdapterIT {
    @AfterEach
    void reset() {
        MnemonicSensorsEntityFilterPlan.reset();
    }

    @Test
    void installedScriptOmitsTheNonNullCopyAndRetainsTheMatchSnapshot() throws Exception {
        String configured = System.getProperty("preflight.mnemonicutils.jar", "").trim();
        Assumptions.assumeTrue(!configured.isEmpty(),
                "set -Dpreflight.mnemonicutils.jar=<MnemonicUtils jar>");
        Path archive = Path.of(configured).toAbsolutePath().normalize();
        Assumptions.assumeTrue(Files.isRegularFile(archive),
                "configured MnemonicUtils jar does not exist");

        byte[] original;
        try (JarFile jar = new JarFile(archive.toFile())) {
            var entry = jar.getJarEntry(MnemonicSensorsEntityFilterPlan.TARGET_CLASS + ".class");
            assertNotNull(entry);
            try (var input = jar.getInputStream(entry)) {
                original = input.readAllBytes();
            }
        }
        ClassSignature signature = ClassSignature.parse(original);
        assertEquals(MnemonicSensorsEntityFilterPlan.ORIGINAL_SHA256, signature.sha256());
        byte[] transformed = MnemonicSensorsEntityFilterPlan.transform(signature, original);
        assertNotNull(transformed);
        assertNull(MnemonicSensorsEntityFilterPlan.transform(
                ClassSignature.parse(transformed), transformed));

        ClassNode owner = new ClassNode(Opcodes.ASM9);
        new ClassReader(transformed).accept(owner, ClassReader.EXPAND_FRAMES);
        MethodNode method = owner.methods.stream()
                .filter(candidate -> MnemonicSensorsEntityFilterPlan.METHOD.equals(candidate.name)
                        && MnemonicSensorsEntityFilterPlan.DESCRIPTOR.equals(candidate.desc))
                .findFirst().orElseThrow();
        assertEquals(0, calls(method, "kotlin/collections/CollectionsKt", "filterNotNull"));
        assertEquals(1, calls(method, "java/util/Collection", "add"));
        assertEquals(1, jumps(method, Opcodes.IFNULL));
    }

    private static int calls(MethodNode method, String owner, String name) {
        int count = 0;
        for (AbstractInsnNode instruction : method.instructions) {
            if (instruction instanceof MethodInsnNode call
                    && owner.equals(call.owner) && name.equals(call.name)) count++;
        }
        return count;
    }

    private static int jumps(MethodNode method, int opcode) {
        int count = 0;
        for (AbstractInsnNode instruction : method.instructions) {
            if (instruction instanceof JumpInsnNode && instruction.getOpcode() == opcode) count++;
        }
        return count;
    }
}
