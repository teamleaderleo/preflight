package dev.starsector.preflight.agent;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;

import java.util.List;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.MethodVisitor;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.MethodInsnNode;

class MagicLibPaintjobLoadPlanTest {
    @AfterEach
    void reset() {
        MagicLibPaintjobLoadRuntime.reset();
    }

    @Test
    void routesTheOneReviewedRestrictedLoadThroughFailOpenHelper() {
        byte[] transformed = MagicLibPaintjobLoadPlan.transform(signature(), fixture(3));
        assertNotNull(transformed);
        ClassNode owner = read(transformed);
        assertNotNull(owner.methods.stream()
                .filter(method -> "preflight$optionalPaintjobJson".equals(method.name))
                .findFirst().orElse(null));
        assertEquals(1, owner.methods.stream()
                .flatMap(method -> java.util.stream.StreamSupport.stream(
                        java.util.Spliterators.spliteratorUnknownSize(
                                method.instructions.iterator(), 0), false))
                .filter(instruction -> instruction instanceof MethodInsnNode call
                        && MagicLibPaintjobLoadPlan.TARGET_CLASS.equals(call.owner)
                        && "preflight$optionalPaintjobJson".equals(call.name))
                .count());
        assertEquals(true, MagicLibPaintjobLoadRuntime.telemetry().get("installed"));
    }

    @Test
    void wrongIdentityWrongLocalAndSecondRewriteFailClosed() {
        byte[] original = fixture(3);
        ClassSignature exact = signature();
        assertNull(MagicLibPaintjobLoadPlan.transform(new ClassSignature(
                exact.internalName(), "0".repeat(64), exact.majorVersion(), exact.access(),
                exact.methods()), original));
        assertNull(MagicLibPaintjobLoadPlan.transform(exact, fixture(2)));
        byte[] once = MagicLibPaintjobLoadPlan.transform(exact, original);
        assertNotNull(once);
        assertNull(MagicLibPaintjobLoadPlan.transform(exact, once));
    }

    private static byte[] fixture(int modLocal) {
        String target = MagicLibPaintjobLoadPlan.TARGET_CLASS;
        ClassWriter writer = new ClassWriter(ClassWriter.COMPUTE_FRAMES | ClassWriter.COMPUTE_MAXS);
        writer.visit(Opcodes.V17, Opcodes.ACC_PUBLIC | Opcodes.ACC_FINAL,
                target, null, "java/lang/Object", null);
        MethodVisitor method = writer.visitMethod(Opcodes.ACC_PUBLIC | Opcodes.ACC_STATIC,
                MagicLibPaintjobLoadPlan.LOAD_METHOD,
                MagicLibPaintjobLoadPlan.LOAD_DESCRIPTOR, null, null);
        method.visitCode();
        method.visitInsn(Opcodes.ACONST_NULL);
        method.visitLdcInsn("data/config/example.paintjob");
        method.visitVarInsn(Opcodes.ALOAD, modLocal);
        method.visitMethodInsn(Opcodes.INVOKEINTERFACE,
                "com/fs/starfarer/api/ModSpecAPI", "getId", "()Ljava/lang/String;", true);
        method.visitMethodInsn(Opcodes.INVOKEINTERFACE,
                "com/fs/starfarer/api/SettingsAPI", "loadJSON",
                "(Ljava/lang/String;Ljava/lang/String;)Lorg/json/JSONObject;", true);
        method.visitInsn(Opcodes.POP);
        method.visitInsn(Opcodes.ACONST_NULL);
        method.visitInsn(Opcodes.ARETURN);
        method.visitMaxs(0, 4);
        method.visitEnd();
        writer.visitEnd();
        return writer.toByteArray();
    }

    private static ClassSignature signature() {
        return new ClassSignature(
                MagicLibPaintjobLoadPlan.TARGET_CLASS,
                MagicLibPaintjobLoadPlan.ORIGINAL_SHA256,
                61,
                Opcodes.ACC_PUBLIC,
                List.of(new ClassSignature.Method(
                        MagicLibPaintjobLoadPlan.LOAD_METHOD,
                        MagicLibPaintjobLoadPlan.LOAD_DESCRIPTOR,
                        Opcodes.ACC_PUBLIC | Opcodes.ACC_STATIC)));
    }

    private static ClassNode read(byte[] bytes) {
        ClassNode owner = new ClassNode(Opcodes.ASM9);
        new ClassReader(bytes).accept(owner, ClassReader.EXPAND_FRAMES);
        return owner;
    }
}
