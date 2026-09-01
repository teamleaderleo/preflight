package dev.starsector.preflight.agent;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.nio.ByteBuffer;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.tree.AbstractInsnNode;
import org.objectweb.asm.tree.InsnNode;
import org.objectweb.asm.tree.MethodInsnNode;
import org.objectweb.asm.tree.MethodNode;
import org.objectweb.asm.tree.analysis.Analyzer;
import org.objectweb.asm.tree.analysis.BasicInterpreter;

class TextureUploadProbePlanTest {
    private static final String RUNTIME =
            "dev/starsector/preflight/agent/TextureUploadProbeRuntime";
    private static final String GL11 = "org/lwjgl/opengl/GL11";

    @BeforeEach
    void enable() {
        System.setProperty(TextureUploadProbeRuntime.ENABLED_PROPERTY, "true");
        TextureUploadProbeRuntime.resetForTests();
    }

    @AfterEach
    void reset() {
        System.clearProperty(TextureUploadProbeRuntime.ENABLED_PROPERTY);
        TextureUploadProbeRuntime.resetForTests();
    }

    @Test
    void retainsTheOriginalCallAndAddsBoundedTimingHooks() throws Exception {
        MethodNode method = fixture();

        assertEquals(1, TextureUploadProbePlan.instrument(List.of(method)));
        assertEquals(1, calls(method, GL11, "glTexImage2D"));
        assertEquals(1, calls(method, RUNTIME, "begin"));
        assertEquals(1, calls(method, RUNTIME, "finish"));
        new Analyzer<>(new BasicInterpreter()).analyze("example/Owner", method);

        Map<String, Object> telemetry = TextureUploadProbeRuntime.telemetry();
        assertEquals(1, telemetry.get("installedCallSites"));
    }

    @Test
    void disabledProbeLeavesTheMethodUntouched() {
        System.clearProperty(TextureUploadProbeRuntime.ENABLED_PROPERTY);
        MethodNode method = fixture();

        assertEquals(0, TextureUploadProbePlan.instrument(List.of(method)));
        assertEquals(1, calls(method, GL11, "glTexImage2D"));
        assertEquals(0, calls(method, RUNTIME, "begin"));
        assertEquals(0, calls(method, RUNTIME, "finish"));
    }

    @Test
    @SuppressWarnings("unchecked")
    void telemetryRetainsDimensionsBytesAndPath() {
        ByteBuffer pixels = ByteBuffer.allocate(16);
        TextureUploadProbeRuntime.finish(
                System.nanoTime(), 2, 2, 6408, 5121, pixels, "graphics/test.png", false);

        Map<String, Object> telemetry = TextureUploadProbeRuntime.telemetry();
        assertEquals(1L, telemetry.get("calls"));
        assertEquals(16L, telemetry.get("totalBytes"));
        List<Map<String, Object>> slowest =
                (List<Map<String, Object>>) telemetry.get("slowest");
        assertEquals(1, slowest.size());
        assertEquals("graphics/test.png", slowest.get(0).get("logicalPath"));
        assertEquals(2, slowest.get(0).get("width"));
        assertEquals(2, slowest.get(0).get("height"));
    }

    private static MethodNode fixture() {
        MethodNode method = new MethodNode(
                Opcodes.ASM9,
                Opcodes.ACC_PUBLIC,
                "upload",
                "(Lcom/fs/graphics/Object;Ljava/lang/String;IIIIIZ)Lcom/fs/graphics/Object;",
                null,
                null);
        for (int index = 0; index < 8; index++) {
            method.instructions.add(new InsnNode(Opcodes.ICONST_0));
        }
        method.instructions.add(new InsnNode(Opcodes.ACONST_NULL));
        method.instructions.add(new MethodInsnNode(
                Opcodes.INVOKESTATIC,
                GL11,
                "glTexImage2D",
                "(IIIIIIIILjava/nio/ByteBuffer;)V",
                false));
        method.instructions.add(new InsnNode(Opcodes.ACONST_NULL));
        method.instructions.add(new InsnNode(Opcodes.ARETURN));
        method.maxLocals = 9;
        method.maxStack = 9;
        return method;
    }

    private static int calls(MethodNode method, String owner, String name) {
        int count = 0;
        for (AbstractInsnNode instruction : method.instructions) {
            if (instruction instanceof MethodInsnNode call
                    && owner.equals(call.owner)
                    && name.equals(call.name)) {
                count++;
            }
        }
        return count;
    }
}
