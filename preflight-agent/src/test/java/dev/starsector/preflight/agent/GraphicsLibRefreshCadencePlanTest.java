package dev.starsector.preflight.agent;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;

import java.io.InputStream;
import java.util.Base64;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.tree.AbstractInsnNode;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.IntInsnNode;
import org.objectweb.asm.tree.MethodInsnNode;
import org.objectweb.asm.tree.MethodNode;

class GraphicsLibRefreshCadencePlanTest {
    @Test
    void exactReplacementGetsBoundedRefreshCadences() throws Exception {
        byte[] transformed = GraphicsLibRefreshCadencePlan.transform(optimizedReplacement());
        assertNotNull(transformed);
        assertEquals(GraphicsLibCompactReplayPlan.REPLACEMENT_SHA256,
                ClassSignature.parse(transformed).sha256());

        ClassNode owner = new ClassNode(Opcodes.ASM9);
        new ClassReader(transformed).accept(owner, ClassReader.EXPAND_FRAMES);
        Map<String, Integer> expected = Map.of(
                "readTextureDataCSVInner(Ljava/lang/String;Z)V", 500,
                "autoGenMissingNormalMapsInner(Z)V", 500,
                "autoGenMissingNormalMaps()V", 1000);
        for (Map.Entry<String, Integer> entry : expected.entrySet()) {
            MethodNode method = owner.methods.stream()
                    .filter(candidate -> (candidate.name + candidate.desc).equals(entry.getKey()))
                    .findFirst().orElseThrow();
            int sites = 0;
            for (AbstractInsnNode instruction : method.instructions.toArray()) {
                if (instruction instanceof MethodInsnNode call
                        && call.owner.equals("org/dark/shaders/ShaderModPlugin")
                        && call.name.equals("refresh")) {
                    AbstractInsnNode cursor = previousOpcode(call);
                    cursor = previousOpcode(cursor);
                    cursor = previousOpcode(cursor);
                    IntInsnNode divisor = (IntInsnNode) cursor;
                    assertEquals(entry.getValue().intValue(), divisor.operand, entry.getKey());
                    sites++;
                }
            }
            assertEquals(entry.getKey().startsWith("autoGenMissingNormalMapsInner") ? 3 : 1,
                    sites, entry.getKey());
        }
    }

    @Test
    void changedInputIsRejected() throws Exception {
        byte[] changed = optimizedReplacement();
        changed[changed.length - 1] ^= 1;
        assertNull(GraphicsLibRefreshCadencePlan.transform(changed));
    }

    private static byte[] optimizedReplacement() throws Exception {
        try (InputStream input = GraphicsLibRefreshCadencePlanTest.class.getResourceAsStream(
                "/dev/starsector/preflight/agent/graphicslib-texture-data-1.12.1.class.b64")) {
            assertNotNull(input);
            byte[] base = Base64.getMimeDecoder().decode(input.readAllBytes());
            return GraphicsLibLazyNormalPlan.transform(base);
        }
    }

    private static AbstractInsnNode previousOpcode(AbstractInsnNode instruction) {
        AbstractInsnNode previous = instruction.getPrevious();
        while (previous != null && previous.getOpcode() < 0) {
            previous = previous.getPrevious();
        }
        return previous;
    }
}
