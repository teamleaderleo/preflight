package dev.starsector.preflight.agent;

import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.InputStream;
import java.util.Base64;
import org.junit.jupiter.api.Test;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.MethodInsnNode;

class StartupGraphicsTextureBreakdownPlanTest {
    @Test
    void instrumentsReviewedEmbeddedReplacementOnly() throws Exception {
        byte[] replacement;
        try (InputStream input = getClass().getResourceAsStream(
                "/dev/starsector/preflight/agent/graphicslib-texture-data-1.12.1.class.b64")) {
            assertNotNull(input);
            replacement = Base64.getMimeDecoder().decode(input.readAllBytes());
        }
        byte[] transformed = StartupGraphicsTextureBreakdownPlan.transform(replacement);
        assertNotNull(transformed);
        assertTrue(runtimeCalls(transformed) > 10);
        assertNull(StartupGraphicsTextureBreakdownPlan.transform(transformed));
    }

    private static int runtimeCalls(byte[] bytes) {
        ClassNode owner = new ClassNode(Opcodes.ASM9);
        new ClassReader(bytes).accept(owner, 0);
        int calls = 0;
        for (var method : owner.methods) {
            for (var instruction : method.instructions) {
                if (instruction instanceof MethodInsnNode invoked
                        && "dev/starsector/preflight/agent/StartupPhaseRuntime".equals(invoked.owner)) {
                    calls++;
                }
            }
        }
        return calls;
    }
}
