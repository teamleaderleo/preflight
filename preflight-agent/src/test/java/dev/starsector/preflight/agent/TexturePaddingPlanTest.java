package dev.starsector.preflight.agent;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.lang.reflect.Method;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.Label;
import org.objectweb.asm.MethodVisitor;
import org.objectweb.asm.Opcodes;

class TexturePaddingPlanTest {
    @BeforeEach
    @AfterEach
    void clearGate() {
        System.clearProperty(TexturePaddingRuntime.UNPADDED_PROPERTY);
        TexturePaddingRuntime.beginSession();
        TexturePaddingRuntime.reset();
    }

    @Test
    void foldsExactlyAsShippedWhileTheGateIsOff() throws Exception {
        Method fold = transformedFold(get2Fold());

        // The default has to be the shipped behaviour, because a host that has never observed
        // non-power-of-two support on its own context must not have padding taken away from it.
        assertEquals(2, fold.invoke(instance(fold), 1));
        assertEquals(4, fold.invoke(instance(fold), 3));
        assertEquals(512, fold.invoke(instance(fold), 288));
        assertTrue(TexturePaddingRuntime.report().get("dimensionsFolded").equals(3L),
                TexturePaddingRuntime.report().toString());
    }

    @Test
    void propertyAndTransformDoNotBypassAStandaloneFold() throws Exception {
        Method fold = transformedFold(get2Fold());
        System.setProperty(TexturePaddingRuntime.UNPADDED_PROPERTY, "true");

        // The property used to affect every allocation in the loader. Now it is only permission
        // for a verified prepared upload to claim the exact pair of folds that follows its buffer.
        // A standalone invocation models a miss and remains bit-for-bit vanilla.
        assertEquals(2, fold.invoke(instance(fold), 1));
        assertEquals(4, fold.invoke(instance(fold), 3));
        assertEquals(512, fold.invoke(instance(fold), 288));
        assertEquals(0L, TexturePaddingRuntime.report().get("dimensionsBypassed"));
        assertEquals(3L, TexturePaddingRuntime.report().get("dimensionsFolded"));
    }

    @Test
    void keepsTheOriginalReachableRatherThanRewritingIt() throws Exception {
        byte[] transformed = TexturePaddingPlan.transform(
                ClassSignature.parse(get2Fold()), get2Fold());
        assertNotNull(transformed);

        Class<?> loaded = define(transformed);
        Method original = loaded.getDeclaredMethod("preflight$original$foldDimension", int.class);
        original.setAccessible(true);

        // The shipped bytecode survives verbatim under a new name. That is what makes the fallback
        // structural: there is no state in which the original computation has been lost.
        assertEquals(512, original.invoke(loaded.getDeclaredConstructor().newInstance(), 288));
    }

    @Test
    void refusesToTransformTwice() throws Exception {
        byte[] transformed = TexturePaddingPlan.transform(
                ClassSignature.parse(get2Fold()), get2Fold());
        assertNotNull(transformed);

        assertNull(TexturePaddingPlan.transform(ClassSignature.parse(transformed), transformed));
    }

    @Test
    void refusesAFoldThatTouchesAnythingOutsideItself() throws Exception {
        // A method with a side effect cannot be bypassed by returning its argument, because the
        // effect would be lost silently. The shape gate exists for this case and not for arithmetic.
        byte[] impure = loaderWithFold(visitor -> {
            visitor.visitVarInsn(Opcodes.ALOAD, 0);
            visitor.visitVarInsn(Opcodes.ILOAD, 1);
            visitor.visitFieldInsn(Opcodes.PUTFIELD, TexturePaddingPlan.TARGET_CLASS, "seen", "I");
            visitor.visitVarInsn(Opcodes.ILOAD, 1);
            visitor.visitInsn(Opcodes.IRETURN);
            visitor.visitMaxs(2, 2);
        });

        assertNull(TexturePaddingPlan.transform(ClassSignature.parse(impure), impure));
    }

    @Test
    void refusesAFoldThatCallsOut() throws Exception {
        byte[] calling = loaderWithFold(visitor -> {
            visitor.visitVarInsn(Opcodes.ILOAD, 1);
            visitor.visitMethodInsn(
                    Opcodes.INVOKESTATIC, "java/lang/Integer", "highestOneBit", "(I)I", false);
            visitor.visitInsn(Opcodes.IRETURN);
            visitor.visitMaxs(1, 2);
        });

        assertNull(TexturePaddingPlan.transform(ClassSignature.parse(calling), calling));
    }

    @Test
    void refusesAClassWithoutTheFold() throws Exception {
        byte[] bare = loaderWithFold(null);

        assertNull(TexturePaddingPlan.transform(ClassSignature.parse(bare), bare));
    }

    @Test
    void transformsTheExactLinuxFoldName() throws Exception {
        byte[] original = loaderWithFold(TexturePaddingPlan.LINUX_FOLD_METHOD, get2FoldBody());
        byte[] transformed = TexturePaddingPlan.transform(ClassSignature.parse(original), original);
        assertNotNull(transformed);

        Method fold = define(transformed).getDeclaredMethod(TexturePaddingPlan.LINUX_FOLD_METHOD, int.class);
        fold.setAccessible(true);
        assertEquals(512, fold.invoke(instance(fold), 288));
    }

    private static Method transformedFold(byte[] original) throws Exception {
        byte[] transformed = TexturePaddingPlan.transform(ClassSignature.parse(original), original);
        assertNotNull(transformed);
        Method fold = define(transformed).getDeclaredMethod(TexturePaddingPlan.FOLD_METHOD, int.class);
        fold.setAccessible(true);
        return fold;
    }

    private static Object instance(Method fold) throws Exception {
        return fold.getDeclaringClass().getDeclaredConstructor().newInstance();
    }

    private static Class<?> define(byte[] bytes) {
        // A fresh loader per call, so the transformed class does not collide with another test's.
        return new ClassLoader(TexturePaddingPlanTest.class.getClassLoader()) {
            Class<?> define() {
                return defineClass(TexturePaddingPlan.TARGET_CLASS.replace('/', '.'), bytes, 0, bytes.length);
            }
        }.define();
    }

    /** Slick's get2Fold as the installed loader extracts it: seed at two, double while short. */
    private static byte[] get2Fold() {
        return loaderWithFold(visitor -> {
            Label test = new Label();
            Label body = new Label();
            visitor.visitInsn(Opcodes.ICONST_2);
            visitor.visitVarInsn(Opcodes.ISTORE, 2);
            visitor.visitJumpInsn(Opcodes.GOTO, test);
            visitor.visitLabel(body);
            visitor.visitVarInsn(Opcodes.ILOAD, 2);
            visitor.visitInsn(Opcodes.ICONST_2);
            visitor.visitInsn(Opcodes.IMUL);
            visitor.visitVarInsn(Opcodes.ISTORE, 2);
            visitor.visitLabel(test);
            visitor.visitVarInsn(Opcodes.ILOAD, 2);
            visitor.visitVarInsn(Opcodes.ILOAD, 1);
            visitor.visitJumpInsn(Opcodes.IF_ICMPLT, body);
            visitor.visitVarInsn(Opcodes.ILOAD, 2);
            visitor.visitInsn(Opcodes.IRETURN);
            visitor.visitMaxs(2, 3);
        });
    }

    private static byte[] loaderWithFold(java.util.function.Consumer<MethodVisitor> body) {
        return loaderWithFold(TexturePaddingPlan.FOLD_METHOD, body);
    }

    private static byte[] loaderWithFold(
            String foldName, java.util.function.Consumer<MethodVisitor> body) {
        ClassWriter writer = new ClassWriter(ClassWriter.COMPUTE_FRAMES | ClassWriter.COMPUTE_MAXS);
        writer.visit(Opcodes.V17, Opcodes.ACC_PUBLIC, TexturePaddingPlan.TARGET_CLASS,
                null, "java/lang/Object", null);
        writer.visitField(Opcodes.ACC_PRIVATE, "seen", "I", null, null).visitEnd();

        MethodVisitor constructor =
                writer.visitMethod(Opcodes.ACC_PUBLIC, "<init>", "()V", null, null);
        constructor.visitCode();
        constructor.visitVarInsn(Opcodes.ALOAD, 0);
        constructor.visitMethodInsn(
                Opcodes.INVOKESPECIAL, "java/lang/Object", "<init>", "()V", false);
        constructor.visitInsn(Opcodes.RETURN);
        constructor.visitMaxs(1, 1);
        constructor.visitEnd();

        if (body != null) {
            MethodVisitor fold = writer.visitMethod(
                    Opcodes.ACC_PRIVATE, foldName,
                    TexturePaddingPlan.FOLD_DESCRIPTOR, null, null);
            fold.visitCode();
            body.accept(fold);
            fold.visitEnd();
        }
        writer.visitEnd();
        return writer.toByteArray();
    }

    private static java.util.function.Consumer<MethodVisitor> get2FoldBody() {
        return visitor -> {
            Label test = new Label();
            Label body = new Label();
            visitor.visitInsn(Opcodes.ICONST_2);
            visitor.visitVarInsn(Opcodes.ISTORE, 2);
            visitor.visitJumpInsn(Opcodes.GOTO, test);
            visitor.visitLabel(body);
            visitor.visitVarInsn(Opcodes.ILOAD, 2);
            visitor.visitInsn(Opcodes.ICONST_2);
            visitor.visitInsn(Opcodes.IMUL);
            visitor.visitVarInsn(Opcodes.ISTORE, 2);
            visitor.visitLabel(test);
            visitor.visitVarInsn(Opcodes.ILOAD, 2);
            visitor.visitVarInsn(Opcodes.ILOAD, 1);
            visitor.visitJumpInsn(Opcodes.IF_ICMPLT, body);
            visitor.visitVarInsn(Opcodes.ILOAD, 2);
            visitor.visitInsn(Opcodes.IRETURN);
            visitor.visitMaxs(2, 3);
        };
    }
}
