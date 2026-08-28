package dev.starsector.preflight.agent;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;

import java.util.List;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.MethodVisitor;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.tree.AbstractInsnNode;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.MethodInsnNode;
import org.objectweb.asm.tree.MethodNode;

final class GlMatrixIdentityElisionPlanTest {
    private static final String RUNTIME = GlMatrixIdentityElisionRuntime.class.getName().replace('.', '/');
    private static final List<MethodSpec> METHODS = List.of(
            new MethodSpec("glBegin", "(I)V"),
            new MethodSpec("glEnd", "()V"),
            new MethodSpec("glTranslatef", "(FFF)V"),
            new MethodSpec("glTranslated", "(DDD)V"),
            new MethodSpec("glRotatef", "(FFFF)V"),
            new MethodSpec("glRotated", "(DDDD)V"),
            new MethodSpec("glScalef", "(FFF)V"),
            new MethodSpec("glScaled", "(DDD)V"));

    @BeforeEach
    void enable() {
        System.setProperty(GlMatrixIdentityElisionRuntime.ENABLE_PROPERTY, "true");
        GlMatrixIdentityElisionRuntime.beginSession(true);
    }

    @AfterEach
    void reset() {
        System.clearProperty(GlMatrixIdentityElisionRuntime.ENABLE_PROPERTY);
        AdapterPlanControl.configure(java.util.Set.of());
        GlMatrixIdentityElisionRuntime.reset();
    }

    @Test
    void instrumentsEveryReviewedIdentityWrapperAndRejectsSecondPass() throws Exception {
        byte[] original = fixture(-1, Mode.NORMAL);
        byte[] transformed = GlMatrixIdentityElisionPlan.transform(exactSignature(original), original);

        assertNotNull(transformed);
        assertEquals(METHODS.size(), runtimeCalls(transformed));
        assertEquals(METHODS.size(), GlMatrixIdentityElisionRuntime.telemetry().get("installedMethodCount"));
        assertNull(GlMatrixIdentityElisionPlan.transform(ClassSignature.parse(transformed), transformed));
    }

    @Test
    void declinesDisabledWrongIdentityChangedMethodsAndInvalidAccess() throws Exception {
        byte[] original = fixture(-1, Mode.NORMAL);
        GlMatrixIdentityElisionRuntime.beginSession(false);
        assertNull(GlMatrixIdentityElisionPlan.transform(exactSignature(original), original));

        GlMatrixIdentityElisionRuntime.beginSession(true);
        ClassSignature parsed = ClassSignature.parse(original);
        assertNull(GlMatrixIdentityElisionPlan.transform(new ClassSignature(
                "example/Other", GlMatrixIdentityElisionPlan.TARGET_SHA256,
                parsed.majorVersion(), parsed.access(), parsed.methods()), original));
        assertNull(GlMatrixIdentityElisionPlan.transform(parsed, original));
        assertNull(GlMatrixIdentityElisionPlan.transform(new ClassSignature(
                parsed.internalName(), GlMatrixIdentityElisionPlan.TARGET_SHA256,
                52, parsed.access(), parsed.methods()), original));

        byte[] missing = fixture(2, Mode.OMIT);
        assertNull(GlMatrixIdentityElisionPlan.transform(exactSignature(missing), missing));
        byte[] invalid = fixture(2, Mode.NON_STATIC);
        assertNull(GlMatrixIdentityElisionPlan.transform(exactSignature(invalid), invalid));
    }

    @Test
    void declinesPreinstrumentedAndAmbiguousCompletionMethods() throws Exception {
        byte[] runtimeCall = fixture(2, Mode.RUNTIME_CALL);
        assertNull(GlMatrixIdentityElisionPlan.transform(exactSignature(runtimeCall), runtimeCall));

        byte[] doubleReturn = fixture(0, Mode.DOUBLE_RETURN);
        assertNull(GlMatrixIdentityElisionPlan.transform(exactSignature(doubleReturn), doubleReturn));

        byte[] noReturn = fixture(1, Mode.NO_RETURN);
        assertNull(GlMatrixIdentityElisionPlan.transform(exactSignature(noReturn), noReturn));
    }

    private static byte[] fixture(int specialIndex, Mode mode) {
        ClassWriter writer = new ClassWriter(0);
        writer.visit(Opcodes.V1_5, Opcodes.ACC_PUBLIC, GlMatrixIdentityElisionPlan.TARGET_CLASS,
                null, "java/lang/Object", null);
        for (int index = 0; index < METHODS.size(); index++) {
            if (index == specialIndex && mode == Mode.OMIT) continue;
            MethodSpec spec = METHODS.get(index);
            int access = Opcodes.ACC_PUBLIC | Opcodes.ACC_STATIC;
            if (index == specialIndex && mode == Mode.NON_STATIC) access = Opcodes.ACC_PUBLIC;
            MethodVisitor method = writer.visitMethod(access, spec.name(), spec.descriptor(), null, null);
            method.visitCode();
            if (index == specialIndex && mode == Mode.RUNTIME_CALL) {
                method.visitMethodInsn(Opcodes.INVOKESTATIC, RUNTIME, "beginPrimitive", "()V", false);
            }
            if (!(index == specialIndex && mode == Mode.NO_RETURN)) {
                method.visitInsn(Opcodes.RETURN);
                if (index == specialIndex && mode == Mode.DOUBLE_RETURN) method.visitInsn(Opcodes.RETURN);
            } else {
                method.visitInsn(Opcodes.NOP);
            }
            method.visitMaxs(0, argumentSlots(spec.descriptor()));
            method.visitEnd();
        }
        writer.visitEnd();
        return writer.toByteArray();
    }

    private static int argumentSlots(String descriptor) {
        int slots = 0;
        for (org.objectweb.asm.Type type : org.objectweb.asm.Type.getArgumentTypes(descriptor)) {
            slots += type.getSize();
        }
        return slots;
    }

    private static ClassSignature exactSignature(byte[] bytes) throws Exception {
        ClassSignature parsed = ClassSignature.parse(bytes);
        return new ClassSignature(parsed.internalName(), GlMatrixIdentityElisionPlan.TARGET_SHA256,
                parsed.majorVersion(), parsed.access(), parsed.methods());
    }

    private static int runtimeCalls(byte[] bytes) {
        ClassNode owner = new ClassNode(Opcodes.ASM9);
        new ClassReader(bytes).accept(owner, ClassReader.EXPAND_FRAMES);
        int calls = 0;
        for (MethodNode method : owner.methods) {
            for (AbstractInsnNode instruction : method.instructions) {
                if (instruction instanceof MethodInsnNode call && RUNTIME.equals(call.owner)) calls++;
            }
        }
        return calls;
    }

    private enum Mode {
        NORMAL,
        OMIT,
        NON_STATIC,
        RUNTIME_CALL,
        DOUBLE_RETURN,
        NO_RETURN
    }

    private record MethodSpec(String name, String descriptor) {
    }
}
