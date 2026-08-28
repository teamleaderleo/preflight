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

final class GlMatrixOperationPlanTest {
    private static final String RUNTIME = GlMatrixOperationRuntime.class.getName().replace('.', '/');
    private static final List<MethodSpec> METHODS = List.of(
            new MethodSpec("glMatrixMode", "(I)V"),
            new MethodSpec("glLoadIdentity", "()V"),
            new MethodSpec("glPushMatrix", "()V"),
            new MethodSpec("glPopMatrix", "()V"),
            new MethodSpec("glLoadMatrix", "(Ljava/nio/FloatBuffer;)V"),
            new MethodSpec("glLoadMatrix", "(Ljava/nio/DoubleBuffer;)V"),
            new MethodSpec("glMultMatrix", "(Ljava/nio/FloatBuffer;)V"),
            new MethodSpec("glMultMatrix", "(Ljava/nio/DoubleBuffer;)V"),
            new MethodSpec("glTranslatef", "(FFF)V"),
            new MethodSpec("glTranslated", "(DDD)V"),
            new MethodSpec("glRotatef", "(FFFF)V"),
            new MethodSpec("glRotated", "(DDDD)V"),
            new MethodSpec("glScalef", "(FFF)V"),
            new MethodSpec("glScaled", "(DDD)V"),
            new MethodSpec("glOrtho", "(DDDDDD)V"),
            new MethodSpec("glFrustum", "(DDDDDD)V"));

    @BeforeEach
    void enable() {
        System.setProperty(GlMatrixOperationRuntime.ENABLE_PROPERTY, "true");
        GpuFrameTimeRuntime.beginSession(false);
        GlMatrixOperationRuntime.beginSession(true);
    }

    @AfterEach
    void reset() {
        System.clearProperty(GlMatrixOperationRuntime.ENABLE_PROPERTY);
        AdapterPlanControl.configure(java.util.Set.of());
        GpuFrameTimeRuntime.beginSession(false);
        GlMatrixOperationRuntime.reset();
    }

    @Test
    void instrumentsEveryReviewedMatrixWrapper() {
        ClassNode owner = fixture("org/lwjgl/opengl/GL11", -1, true);

        assertEquals(METHODS.size(), GlMatrixOperationPlan.instrument(owner));
        assertEquals(METHODS.size(), runtimeCalls(owner));
        assertEquals(METHODS.size(), GlMatrixOperationRuntime.telemetry().get("installedMethodCount"));
    }

    @Test
    void declinesDisabledWrongOwnerMissingMethodAndInvalidAccess() {
        GlMatrixOperationRuntime.beginSession(false);
        assertEquals(0, GlMatrixOperationPlan.instrument(fixture("org/lwjgl/opengl/GL11", -1, true)));

        GlMatrixOperationRuntime.beginSession(true);
        assertEquals(0, GlMatrixOperationPlan.instrument(fixture("example/Other", -1, true)));
        assertEquals(-1, GlMatrixOperationPlan.instrument(fixture("org/lwjgl/opengl/GL11", 0, true)));
        assertEquals(-1, GlMatrixOperationPlan.instrument(fixture("org/lwjgl/opengl/GL11", 0, false)));
    }

    private static ClassNode fixture(String name, int specialIndex, boolean omitSpecial) {
        ClassNode owner = new ClassNode(Opcodes.ASM9);
        owner.version = Opcodes.V1_5;
        owner.access = Opcodes.ACC_PUBLIC;
        owner.name = name;
        owner.superName = "java/lang/Object";
        for (int index = 0; index < METHODS.size(); index++) {
            if (index == specialIndex && omitSpecial) continue;
            MethodSpec spec = METHODS.get(index);
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
