package dev.starsector.preflight.agent;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.util.List;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.tree.AbstractInsnNode;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.InsnNode;
import org.objectweb.asm.tree.MethodInsnNode;
import org.objectweb.asm.tree.MethodNode;

final class GlStateReissuePlanTest {
    private static final String RUNTIME = GlStateReissueRuntime.class.getName().replace('.', '/');
    private static final List<MethodSpec> GL11_METHODS = List.of(
            new MethodSpec("glBindTexture", "(II)V"),
            new MethodSpec("glEnable", "(I)V"),
            new MethodSpec("glDisable", "(I)V"),
            new MethodSpec("glBlendFunc", "(II)V"),
            new MethodSpec("glAlphaFunc", "(IF)V"),
            new MethodSpec("glDepthFunc", "(I)V"),
            new MethodSpec("glDepthMask", "(Z)V"),
            new MethodSpec("glCullFace", "(I)V"),
            new MethodSpec("glScissor", "(IIII)V"),
            new MethodSpec("glViewport", "(IIII)V"),
            new MethodSpec("glMatrixMode", "(I)V"),
            new MethodSpec("glCallList", "(I)V"),
            new MethodSpec("glCallLists", "(Ljava/nio/ByteBuffer;)V"),
            new MethodSpec("glCallLists", "(Ljava/nio/IntBuffer;)V"),
            new MethodSpec("glCallLists", "(Ljava/nio/ShortBuffer;)V"),
            new MethodSpec("glPopAttrib", "()V"),
            new MethodSpec("glPopClientAttrib", "()V"));
    private static final List<MethodSpec> GL13_METHODS = List.of(
            new MethodSpec("glActiveTexture", "(I)V"),
            new MethodSpec("glClientActiveTexture", "(I)V"));

    @BeforeEach
    void enable() {
        System.setProperty(GlStateReissueRuntime.ENABLE_PROPERTY, "true");
        GpuFrameTimeRuntime.beginSession(false);
        GlStateReissueRuntime.beginSession(true);
    }

    @AfterEach
    void reset() {
        System.clearProperty(GlStateReissueRuntime.ENABLE_PROPERTY);
        AdapterPlanControl.configure(java.util.Set.of());
        GpuFrameTimeRuntime.beginSession(false);
        GlStateReissueRuntime.reset();
    }

    @Test
    void instrumentsEveryReviewedGl11AndGl13Wrapper() {
        ClassNode gl11 = fixture("org/lwjgl/opengl/GL11", GL11_METHODS, -1, true);
        ClassNode gl13 = fixture("org/lwjgl/opengl/GL13", GL13_METHODS, -1, true);

        assertEquals(GL11_METHODS.size(), GlStateReissuePlan.instrument(gl11));
        assertEquals(GL11_METHODS.size(), runtimeCalls(gl11));
        assertEquals(GL13_METHODS.size(), GlStateReissuePlan.instrument(gl13));
        assertEquals(GL13_METHODS.size(), runtimeCalls(gl13));
        assertEquals(GL11_METHODS.size() + GL13_METHODS.size(),
                GlStateReissueRuntime.telemetry().get("installedMethodCount"));
    }

    @Test
    void declinesDisabledUnknownOwnerMissingMethodAndInvalidAccess() {
        GlStateReissueRuntime.beginSession(false);
        assertEquals(0, GlStateReissuePlan.instrument(
                fixture("org/lwjgl/opengl/GL11", GL11_METHODS, -1, true)));

        GlStateReissueRuntime.beginSession(true);
        assertEquals(0, GlStateReissuePlan.instrument(
                fixture("example/Other", GL11_METHODS, -1, true)));
        assertEquals(-1, GlStateReissuePlan.instrument(
                fixture("org/lwjgl/opengl/GL11", GL11_METHODS, 0, true)));
        assertEquals(-1, GlStateReissuePlan.instrument(
                fixture("org/lwjgl/opengl/GL13", GL13_METHODS, 0, false)));
    }

    private static ClassNode fixture(
            String name, List<MethodSpec> methods, int specialIndex, boolean omitSpecial) {
        ClassNode owner = new ClassNode(Opcodes.ASM9);
        owner.version = Opcodes.V1_5;
        owner.access = Opcodes.ACC_PUBLIC;
        owner.name = name;
        owner.superName = "java/lang/Object";
        for (int index = 0; index < methods.size(); index++) {
            if (index == specialIndex && omitSpecial) continue;
            MethodSpec spec = methods.get(index);
            int access = Opcodes.ACC_PUBLIC | Opcodes.ACC_STATIC;
            if (index == specialIndex && !omitSpecial) access = Opcodes.ACC_PUBLIC;
            MethodNode method = new MethodNode(Opcodes.ASM9, access, spec.name(), spec.descriptor(), null, null);
            method.instructions.add(new InsnNode(Opcodes.RETURN));
            owner.methods.add(method);
        }
        return owner;
    }

    private static int runtimeCalls(ClassNode owner) {
        int calls = 0;
        for (MethodNode method : owner.methods) {
            for (AbstractInsnNode instruction : method.instructions) {
                if (instruction instanceof MethodInsnNode call && RUNTIME.equals(call.owner)) calls++;
            }
        }
        return calls;
    }

    private record MethodSpec(String name, String descriptor) {
    }
}
