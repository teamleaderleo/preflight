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
import org.objectweb.asm.tree.MethodNode;

class GraphicsLibTextureKeyPlanTest {
    private static final String DESCRIPTOR =
            "(Ljava/lang/String;Lorg/dark/shaders/util/TextureData$ObjectType;I)"
                    + "Ljava/lang/String;";

    @Test
    void exactReplacementWrapsOriginalPureKeyBuilder() throws Exception {
        byte[] transformed = GraphicsLibTextureKeyPlan.transform(refreshedReplacement());
        assertNotNull(transformed);
        assertEquals(GraphicsLibCompactReplayPlan.REPLACEMENT_SHA256,
                ClassSignature.parse(transformed).sha256());

        ClassNode owner = new ClassNode(Opcodes.ASM9);
        new ClassReader(transformed).accept(owner, ClassReader.EXPAND_FRAMES);
        MethodNode wrapper = exactMethod(owner, "getTextureDataKey");
        MethodNode original = exactMethod(owner, "preflight$original$getTextureDataKey");
        assertNotNull(wrapper);
        assertNotNull(original);
        assertEquals(Opcodes.ACC_SYNTHETIC,
                original.access & Opcodes.ACC_SYNTHETIC);
        assertEquals(1, calls(wrapper, "GraphicsLibTextureKeyRuntime", "lookup"));
        assertEquals(1, calls(wrapper, "GraphicsLibTextureKeyRuntime", "record"));
        assertEquals(1, calls(wrapper, "TextureData", "preflight$original$getTextureDataKey"));
    }

    @Test
    void changedInputIsRejected() throws Exception {
        byte[] changed = refreshedReplacement();
        changed[changed.length - 1] ^= 1;
        assertNull(GraphicsLibTextureKeyPlan.transform(changed));
    }

    private static byte[] refreshedReplacement() throws Exception {
        try (InputStream input = GraphicsLibTextureKeyPlanTest.class.getResourceAsStream(
                "/dev/starsector/preflight/agent/graphicslib-texture-data-1.12.1.class.b64")) {
            assertNotNull(input);
            byte[] base = Base64.getMimeDecoder().decode(input.readAllBytes());
            byte[] lazy = GraphicsLibLazyNormalPlan.transform(base);
            return GraphicsLibRefreshCadencePlan.transform(lazy);
        }
    }

    private static MethodNode exactMethod(ClassNode owner, String name) {
        return owner.methods.stream()
                .filter(method -> name.equals(method.name) && DESCRIPTOR.equals(method.desc))
                .findFirst().orElse(null);
    }

    private static int calls(MethodNode method, String ownerSuffix, String name) {
        int count = 0;
        for (var instruction : method.instructions.toArray()) {
            if (instruction instanceof MethodInsnNode call
                    && call.owner.endsWith(ownerSuffix)
                    && name.equals(call.name)) {
                count++;
            }
        }
        return count;
    }
}
