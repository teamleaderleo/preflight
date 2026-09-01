package dev.starsector.preflight.agent;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.lang.reflect.Method;
import java.util.Set;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.Label;
import org.objectweb.asm.MethodVisitor;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.tree.ClassNode;

/**
 * The prepared-pixel rewrite and the padded-dimension fold bypass have to land on the same class.
 *
 * <p>Both rewrite {@code com/fs/graphics/TextureLoader}, and the transformation registry dispatches
 * one plan per class, so nothing ever installed the fold bypass and {@code --prepared-unpadded}
 * silently behaved like {@code --prepared-npot}. Until they compose, the 1.39 GB of padding the last
 * campaign measured stays uploaded.
 */
class TextureLoaderPlanCompositionTest {
    private static final String ORIGINAL_FOLD = "preflight$original$foldDimension";
    private static final String ORIGINAL_CONVERT = "preflight$original$convertPixels";

    @BeforeEach
    @AfterEach
    void clearGate() {
        System.clearProperty(TexturePaddingRuntime.UNPADDED_PROPERTY);
        TexturePaddingRuntime.beginSession();
        AdapterPlanControl.configure(Set.of());
    }

    @Test
    void bothRewritesLandOnOneLoader() throws Exception {
        byte[] composed = compose();

        ClassNode node = read(composed);
        assertTrue(hasMethod(node, ORIGINAL_CONVERT),
                "the prepared-pixel rewrite must survive the fold weave");
        assertTrue(hasMethod(node, ORIGINAL_FOLD),
                "the fold bypass must be installed on the already-rewritten loader");
        assertTrue(TexturePaddingRuntime.foldBypassReady(),
                "and the runtime latch must be set, because it is what unlocks the gate");
    }

    @Test
    void theFoldStillFoldsWhileTheGateIsOff() throws Exception {
        Class<?> loader = define(compose());
        Method fold = loader.getDeclaredMethod(TexturePaddingPlan.FOLD_METHOD, int.class);
        fold.setAccessible(true);
        Object instance = loader.getDeclaredConstructor().newInstance();

        assertFalse(TexturePaddingRuntime.enabled(), "composition alone must not switch it on");
        assertEquals(1024, fold.invoke(instance, 597), "597 folds to 1024 when the gate is shut");
        assertEquals(512, fold.invoke(instance, 373));
    }

    @Test
    void theFoldStillFoldsWithoutAnInFlightPreparedUpload() throws Exception {
        Class<?> loader = define(compose());
        Method fold = loader.getDeclaredMethod(TexturePaddingPlan.FOLD_METHOD, int.class);
        fold.setAccessible(true);
        Object instance = loader.getDeclaredConstructor().newInstance();

        System.setProperty(TexturePaddingRuntime.UNPADDED_PROPERTY, "true");
        assertTrue(TexturePaddingRuntime.enabled());
        // The capability and property only make true-size uploads available. This call has no
        // verified prepared buffer in flight, so it models a cache miss and must retain vanilla
        // allocation semantics.
        assertEquals(1024, fold.invoke(instance, 597));
        assertEquals(512, fold.invoke(instance, 373));
    }

    @Test
    void aLoaderWithNoFoldKeepsThePreparedPixelRewriteAndLeavesTheGateShut() throws Exception {
        byte[] withoutFold = TexturePreparedPixelPlanTest.textureLoader(3, true, true);
        byte[] rewritten =
                TexturePreparedPixelPlan.transform(ClassSignature.parse(withoutFold), withoutFold);
        byte[] result = AdapterTransformationRegistry.withFoldBypass(rewritten);

        assertNotNull(result, "the prepared-pixel rewrite must not be lost when no fold is present");
        assertTrue(hasMethod(read(result), ORIGINAL_CONVERT));
        assertFalse(hasMethod(read(result), ORIGINAL_FOLD));
        assertFalse(TexturePaddingRuntime.foldBypassReady(),
                "no fold woven means the allocation stays padded, so the gate must stay shut");
    }

    @Test
    void cachedPreparedRewriteWithoutFoldDoesNotReplayThePaddingLatch() throws Exception {
        byte[] withoutFold = TexturePreparedPixelPlanTest.textureLoader(3, true, true);
        ClassSignature signature = ClassSignature.parse(withoutFold);
        byte[] rewritten = TexturePreparedPixelPlan.transform(signature, withoutFold);
        assertNotNull(rewritten);

        AdapterInstallationEffects.replay(
                AdapterTargetRegistry.texturePreparedPixelTarget(), signature, rewritten);

        assertFalse(TexturePaddingRuntime.foldBypassReady(),
                "calling TexturePaddingRuntime.enabled() is not evidence that the fold was woven");
    }

    @Test
    void foldPlanFilterKeepsThePreparedPixelRewriteAlone() throws Exception {
        byte[] loader = withFold(TexturePreparedPixelPlanTest.textureLoader(3, true, true));
        byte[] rewritten = TexturePreparedPixelPlan.transform(ClassSignature.parse(loader), loader);
        AdapterPlanControl.configure(Set.of(TexturePaddingRuntime.PLAN_ID));

        byte[] result = AdapterTransformationRegistry.withFoldBypass(rewritten);

        assertNotNull(result);
        assertTrue(hasMethod(read(result), ORIGINAL_CONVERT));
        assertFalse(hasMethod(read(result), ORIGINAL_FOLD));
        assertFalse(TexturePaddingRuntime.foldBypassReady());
    }

    /**
     * The registry's own composition step, over a loader carrying both the prepared-pixel methods
     * and a fold. The dispatch around it is gated on a configured cache, which is a separate
     * concern; what is under test is that the fold bypass survives being applied to already
     * rewritten bytes.
     */
    private static byte[] compose() throws Exception {
        byte[] loader = withFold(TexturePreparedPixelPlanTest.textureLoader(3, true, true));
        byte[] rewritten = TexturePreparedPixelPlan.transform(ClassSignature.parse(loader), loader);
        assertNotNull(rewritten, "the prepared-pixel plan must rewrite this loader");
        byte[] composed = AdapterTransformationRegistry.withFoldBypass(rewritten);
        assertNotNull(composed, "composition must not discard the rewrite");
        return composed;
    }

    /** Appends Slick's {@code get2Fold} — seed at two, double while short — to an existing loader. */
    private static byte[] withFold(byte[] loader) {
        ClassReader reader = new ClassReader(loader);
        ClassWriter writer = new ClassWriter(ClassWriter.COMPUTE_FRAMES | ClassWriter.COMPUTE_MAXS);
        reader.accept(writer, ClassReader.EXPAND_FRAMES);

        MethodVisitor fold = writer.visitMethod(
                Opcodes.ACC_PRIVATE, TexturePaddingPlan.FOLD_METHOD,
                TexturePaddingPlan.FOLD_DESCRIPTOR, null, null);
        fold.visitCode();
        Label test = new Label();
        Label body = new Label();
        fold.visitInsn(Opcodes.ICONST_2);
        fold.visitVarInsn(Opcodes.ISTORE, 2);
        fold.visitJumpInsn(Opcodes.GOTO, test);
        fold.visitLabel(body);
        fold.visitVarInsn(Opcodes.ILOAD, 2);
        fold.visitInsn(Opcodes.ICONST_2);
        fold.visitInsn(Opcodes.IMUL);
        fold.visitVarInsn(Opcodes.ISTORE, 2);
        fold.visitLabel(test);
        fold.visitVarInsn(Opcodes.ILOAD, 2);
        fold.visitVarInsn(Opcodes.ILOAD, 1);
        fold.visitJumpInsn(Opcodes.IF_ICMPLT, body);
        fold.visitVarInsn(Opcodes.ILOAD, 2);
        fold.visitInsn(Opcodes.IRETURN);
        fold.visitMaxs(2, 3);
        fold.visitEnd();
        return writer.toByteArray();
    }

    private static ClassNode read(byte[] bytes) {
        ClassNode node = new ClassNode(Opcodes.ASM9);
        new ClassReader(bytes).accept(node, ClassReader.EXPAND_FRAMES);
        return node;
    }

    private static boolean hasMethod(ClassNode node, String name) {
        return node.methods.stream().anyMatch(method -> name.equals(method.name));
    }

    /**
     * Defines the composed loader, synthesising the game classes it refers to.
     *
     * <p>The prepared-pixel rewrite names {@code com/fs/graphics/Object} and friends, which exist
     * only in the installed game. Verification resolves them at definition time, so an empty stub
     * per name is enough to get the class loaded and the fold invoked.
     */
    private static Class<?> define(byte[] bytes) {
        return new ClassLoader(TextureLoaderPlanCompositionTest.class.getClassLoader()) {
            @Override
            protected Class<?> findClass(String name) throws ClassNotFoundException {
                if (!name.startsWith("com.fs.")) {
                    throw new ClassNotFoundException(name);
                }
                byte[] stub = stub(name.replace('.', '/'));
                return defineClass(name, stub, 0, stub.length);
            }

            Class<?> define() {
                return defineClass(
                        TexturePaddingPlan.TARGET_CLASS.replace('/', '.'), bytes, 0, bytes.length);
            }
        }.define();
    }

    private static byte[] stub(String internalName) {
        ClassWriter writer = new ClassWriter(ClassWriter.COMPUTE_FRAMES | ClassWriter.COMPUTE_MAXS);
        writer.visit(Opcodes.V17, Opcodes.ACC_PUBLIC, internalName, null, "java/lang/Object", null);
        MethodVisitor constructor =
                writer.visitMethod(Opcodes.ACC_PUBLIC, "<init>", "()V", null, null);
        constructor.visitCode();
        constructor.visitVarInsn(Opcodes.ALOAD, 0);
        constructor.visitMethodInsn(
                Opcodes.INVOKESPECIAL, "java/lang/Object", "<init>", "()V", false);
        constructor.visitInsn(Opcodes.RETURN);
        constructor.visitMaxs(1, 1);
        constructor.visitEnd();
        writer.visitEnd();
        return writer.toByteArray();
    }
}
