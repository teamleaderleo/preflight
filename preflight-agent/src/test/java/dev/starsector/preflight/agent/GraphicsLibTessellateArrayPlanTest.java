package dev.starsector.preflight.agent;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.MethodVisitor;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.tree.AbstractInsnNode;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.FieldNode;
import org.objectweb.asm.tree.MethodInsnNode;
import org.objectweb.asm.tree.MethodNode;

class GraphicsLibTessellateArrayPlanTest {
    private static final String TARGET = GraphicsLibTessellateArrayPlan.TARGET_CLASS;
    private static final String TESS_DATA = TARGET + "$TessData";
    private static final String VERTEX_DATA = TARGET + "$VertexDataV2";
    private static final String SHIP = "com/fs/starfarer/api/combat/ShipAPI";
    private static final String VECTOR = "org/lwjgl/util/vector/Vector2f";
    private static final String GL11 = "org/lwjgl/opengl/GL11";
    private static final String VECTOR_UTILS = "org/lazywizard/lazylib/VectorUtils";

    @BeforeEach
    void enable() {
        GraphicsLibTessellateArrayRuntime.beginSessionForTest(true);
    }

    @AfterEach
    void reset() {
        GraphicsLibTessellateArrayRuntime.resetForTest();
    }

    @Test
    void replacesReviewedCachedImmediateReplayWithOneArrayDraw() throws Exception {
        byte[] original = fixture(TARGET, true, 1);
        byte[] transformed = GraphicsLibTessellateArrayPlan.transform(
                ClassSignature.parse(original), original);
        assertNotNull(transformed);

        ClassNode owner = read(transformed);
        MethodNode render = method(owner, GraphicsLibTessellateArrayPlan.RENDER_METHOD);
        MethodNode helper = method(owner, "preflight$drawCachedTessellation");
        assertEquals(0, calls(render, GL11, "glBegin"));
        assertEquals(0, calls(render, GL11, "glVertex2f"));
        assertEquals(0, calls(render, GL11, "glEnd"));
        assertEquals(1, calls(render, TARGET, "preflight$drawCachedTessellation"));
        assertEquals(1, calls(helper, GL11, "glDrawArrays"));
        assertEquals(1, calls(helper, GL11, "glVertexPointer"));
        assertEquals(1, calls(helper, GL11, "glPushClientAttrib"));
        assertEquals(1, calls(helper, GL11, "glPopClientAttrib"));
        assertEquals(0, calls(helper, VECTOR_UTILS, "rotate"));
        assertEquals(1, calls(helper, SHIP, "getFacing"));
        assertEquals(1, calls(helper, SHIP, "getLocation"));
        assertTrue(hasField(owner, "preflight$cachedVertexArray"));
        assertEquals(true, GraphicsLibTessellateArrayRuntime.telemetry().get("installed"));
    }

    @Test
    void declinesDisabledChangedDuplicateWrongClassAndSecondTransform() throws Exception {
        byte[] original = fixture(TARGET, true, 1);
        GraphicsLibTessellateArrayRuntime.beginSessionForTest(false);
        assertNull(GraphicsLibTessellateArrayPlan.transform(ClassSignature.parse(original), original));

        GraphicsLibTessellateArrayRuntime.beginSessionForTest(true);
        byte[] changed = fixture(TARGET, false, 1);
        assertNull(GraphicsLibTessellateArrayPlan.transform(ClassSignature.parse(changed), changed));
        byte[] duplicate = fixture(TARGET, true, 2);
        assertNull(GraphicsLibTessellateArrayPlan.transform(ClassSignature.parse(duplicate), duplicate));
        byte[] wrong = fixture("example/Other", true, 1);
        assertNull(GraphicsLibTessellateArrayPlan.transform(ClassSignature.parse(wrong), wrong));

        byte[] transformed = GraphicsLibTessellateArrayPlan.transform(
                ClassSignature.parse(original), original);
        assertNotNull(transformed);
        assertNull(GraphicsLibTessellateArrayPlan.transform(
                ClassSignature.parse(transformed), transformed));
    }

    @Test
    void frameTimeDispatcherCarriesExactExternalGraphicsLibTarget() throws Exception {
        byte[] original = fixture(TARGET, true, 1);
        byte[] transformed = FrameTimePlan.transform(ClassSignature.parse(original), original);
        assertNotNull(transformed);
        ClassNode owner = read(transformed);
        assertEquals(1, calls(
                method(owner, GraphicsLibTessellateArrayPlan.RENDER_METHOD),
                TARGET,
                "preflight$drawCachedTessellation"));
    }

    private static byte[] fixture(String className, boolean includeRotate, int blocks) {
        ClassWriter writer = new ClassWriter(ClassWriter.COMPUTE_MAXS);
        writer.visit(Opcodes.V17, Opcodes.ACC_PUBLIC, className, null, "java/lang/Object", null);
        MethodVisitor render = writer.visitMethod(
                Opcodes.ACC_PUBLIC | Opcodes.ACC_STATIC,
                GraphicsLibTessellateArrayPlan.RENDER_METHOD,
                GraphicsLibTessellateArrayPlan.RENDER_DESCRIPTOR,
                null,
                null);
        render.visitCode();
        render.visitInsn(Opcodes.ACONST_NULL);
        render.visitVarInsn(Opcodes.ASTORE, 5);
        render.visitInsn(Opcodes.ACONST_NULL);
        render.visitVarInsn(Opcodes.ASTORE, 6);
        for (int i = 0; i < blocks; i++) {
            render.visitVarInsn(Opcodes.ALOAD, 5);
            render.visitFieldInsn(Opcodes.GETFIELD, TESS_DATA, "glType", "I");
            render.visitMethodInsn(Opcodes.INVOKESTATIC, GL11, "glBegin", "(I)V", false);
            render.visitInsn(Opcodes.FCONST_1);
            render.visitInsn(Opcodes.FCONST_1);
            render.visitInsn(Opcodes.FCONST_1);
            render.visitMethodInsn(Opcodes.INVOKESTATIC, GL11, "glColor3f", "(FFF)V", false);
            render.visitVarInsn(Opcodes.ALOAD, 5);
            render.visitFieldInsn(
                    Opcodes.GETFIELD, TESS_DATA, "vertices", "Ljava/util/List;");
            render.visitInsn(Opcodes.POP);
            render.visitVarInsn(Opcodes.ALOAD, 6);
            render.visitFieldInsn(Opcodes.GETFIELD, VERTEX_DATA, "data", "[D");
            render.visitInsn(Opcodes.POP);
            render.visitVarInsn(Opcodes.ALOAD, 6);
            render.visitFieldInsn(Opcodes.GETFIELD, VERTEX_DATA, "data", "[D");
            render.visitInsn(Opcodes.POP);
            if (includeRotate) {
                render.visitInsn(Opcodes.ACONST_NULL);
                render.visitVarInsn(Opcodes.ALOAD, 4);
                render.visitMethodInsn(Opcodes.INVOKEINTERFACE, SHIP, "getFacing", "()F", true);
                render.visitMethodInsn(
                        Opcodes.INVOKESTATIC,
                        VECTOR_UTILS,
                        "rotate",
                        "(L" + VECTOR + ";F)L" + VECTOR + ";",
                        false);
                render.visitInsn(Opcodes.POP);
            }
            render.visitInsn(Opcodes.ACONST_NULL);
            render.visitVarInsn(Opcodes.ALOAD, 4);
            render.visitMethodInsn(
                    Opcodes.INVOKEINTERFACE,
                    SHIP,
                    "getLocation",
                    "()L" + VECTOR + ";",
                    true);
            render.visitInsn(Opcodes.ACONST_NULL);
            render.visitMethodInsn(
                    Opcodes.INVOKESTATIC,
                    VECTOR,
                    "add",
                    "(L" + VECTOR + ";L" + VECTOR + ";L" + VECTOR + ";)L" + VECTOR + ";",
                    false);
            render.visitInsn(Opcodes.POP);
            render.visitInsn(Opcodes.FCONST_0);
            render.visitInsn(Opcodes.FCONST_0);
            render.visitMethodInsn(Opcodes.INVOKESTATIC, GL11, "glVertex2f", "(FF)V", false);
            render.visitMethodInsn(Opcodes.INVOKESTATIC, GL11, "glEnd", "()V", false);
        }
        render.visitInsn(Opcodes.RETURN);
        render.visitMaxs(0, 0);
        render.visitEnd();
        writer.visitEnd();
        return writer.toByteArray();
    }

    private static ClassNode read(byte[] bytes) {
        ClassNode node = new ClassNode(Opcodes.ASM9);
        new ClassReader(bytes).accept(node, ClassReader.EXPAND_FRAMES);
        return node;
    }

    private static MethodNode method(ClassNode owner, String name) {
        return owner.methods.stream().filter(candidate -> name.equals(candidate.name)).findFirst().orElseThrow();
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

    private static boolean hasField(ClassNode owner, String name) {
        for (FieldNode field : owner.fields) {
            if (name.equals(field.name)) return true;
        }
        return false;
    }
}
