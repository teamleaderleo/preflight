package dev.starsector.preflight.agent;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;

import java.io.InputStream;
import java.util.Base64;
import org.junit.jupiter.api.Test;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.MethodInsnNode;

class GraphicsLibLazyNormalPlanTest {
    @Test
    void exactCompactReplacementGetsBothLazyCacheGuards() throws Exception {
        byte[] base = baseReplacement();
        byte[] transformed = GraphicsLibLazyNormalPlan.transform(base);
        assertNotNull(transformed);
        assertEquals(GraphicsLibLazyNormalPlan.OPTIMIZED_SHA256,
                ClassSignature.parse(transformed).sha256());

        ClassNode owner = new ClassNode(Opcodes.ASM9);
        new ClassReader(transformed).accept(owner, ClassReader.EXPAND_FRAMES);
        long lazySprites = owner.methods.stream()
                .flatMap(method -> java.util.Arrays.stream(method.instructions.toArray()))
                .filter(MethodInsnNode.class::isInstance)
                .map(MethodInsnNode.class::cast)
                .filter(call -> call.owner.equals(
                        "dev/starsector/preflight/agent/GraphicsLibNormalCacheRuntime"))
                .filter(call -> call.name.equals("lazySprite"))
                .count();
        long lazyChecks = owner.methods.stream()
                .flatMap(method -> java.util.Arrays.stream(method.instructions.toArray()))
                .filter(MethodInsnNode.class::isInstance)
                .map(MethodInsnNode.class::cast)
                .filter(call -> call.owner.equals(
                        "dev/starsector/preflight/agent/GraphicsLibNormalCacheRuntime"))
                .filter(call -> call.name.equals("isLazySprite"))
                .count();
        assertEquals(1, lazySprites);
        assertEquals(1, lazyChecks);
    }

    @Test
    void changedInputIsRejected() throws Exception {
        byte[] changed = baseReplacement();
        changed[changed.length - 1] ^= 1;
        assertNull(GraphicsLibLazyNormalPlan.transform(changed));
    }

    private static byte[] baseReplacement() throws Exception {
        try (InputStream input = GraphicsLibLazyNormalPlanTest.class.getResourceAsStream(
                "/dev/starsector/preflight/agent/graphicslib-texture-data-1.12.1.class.b64")) {
            assertNotNull(input);
            return Base64.getMimeDecoder().decode(input.readAllBytes());
        }
    }
}
