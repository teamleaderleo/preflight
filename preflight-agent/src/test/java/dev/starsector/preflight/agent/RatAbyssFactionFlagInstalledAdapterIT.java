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

/** Opt-in transform check against the exact installed RAT abyss-faction script. */
class RatAbyssFactionFlagInstalledAdapterIT {
    @AfterEach
    void reset() {
        RatAbyssFactionFlagPlan.reset();
    }

    @Test
    void installedScriptUsesTheFalseDefaultLookupWithoutChangingItsShape() throws Exception {
        String configured = System.getProperty("preflight.rat.jar", "").trim();
        Assumptions.assumeTrue(!configured.isEmpty(), "set -Dpreflight.rat.jar=<RAT jar>");
        Path archive = Path.of(configured).toAbsolutePath().normalize();
        Assumptions.assumeTrue(Files.isRegularFile(archive), "configured RAT jar does not exist");

        byte[] original;
        try (JarFile jar = new JarFile(archive.toFile())) {
            var entry = jar.getJarEntry(RatAbyssFactionFlagPlan.TARGET_CLASS + ".class");
            assertNotNull(entry);
            try (var input = jar.getInputStream(entry)) {
                original = input.readAllBytes();
            }
        }
        ClassSignature signature = ClassSignature.parse(original);
        assertEquals(RatAbyssFactionFlagPlan.ORIGINAL_SHA256, signature.sha256());
        byte[] transformed = RatAbyssFactionFlagPlan.transform(signature, original);
        assertNotNull(transformed);
        assertNull(RatAbyssFactionFlagPlan.transform(ClassSignature.parse(transformed), transformed));

        ClassNode owner = new ClassNode(Opcodes.ASM9);
        new ClassReader(transformed).accept(owner, ClassReader.EXPAND_FRAMES);
        MethodNode advance = owner.methods.stream()
                .filter(method -> RatAbyssFactionFlagPlan.ADVANCE_METHOD.equals(method.name)
                        && RatAbyssFactionFlagPlan.ADVANCE_DESCRIPTOR.equals(method.desc))
                .findFirst().orElseThrow();
        assertEquals(0, calls(advance, "getBoolean"));
        assertEquals(1, calls(advance, "optBoolean"));
    }

    private static int calls(MethodNode method, String name) {
        int count = 0;
        for (AbstractInsnNode instruction : method.instructions) {
            if (instruction instanceof MethodInsnNode call
                    && "org/json/JSONObject".equals(call.owner)
                    && name.equals(call.name)
                    && "(Ljava/lang/String;)Z".equals(call.desc)) {
                count++;
            }
        }
        return count;
    }
}
