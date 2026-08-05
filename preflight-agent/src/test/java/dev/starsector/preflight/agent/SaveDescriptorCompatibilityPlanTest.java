package dev.starsector.preflight.agent;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.Label;
import org.objectweb.asm.MethodVisitor;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.tree.AbstractInsnNode;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.MethodInsnNode;

class SaveDescriptorCompatibilityPlanTest {
    private static final String RUNTIME =
            SaveDescriptorCompatibilityRuntime.class.getName().replace('.', '/');

    @TempDir
    Path temporaryDirectory;

    @BeforeEach
    void reset() {
        SaveDescriptorCompatibilityRuntime.beginSession();
    }

    @Test
    void exactMethodGetsOneLookupAndTwoResultRecords() {
        byte[] transformed = SaveDescriptorCompatibilityPlan.transform(signature(), fixture(4));
        assertNotNull(transformed);
        ClassNode owner = new ClassNode(Opcodes.ASM9);
        new ClassReader(transformed).accept(owner, ClassReader.EXPAND_FRAMES);
        assertEquals(1, calls(owner, RUNTIME, "lookup"));
        assertEquals(2, calls(owner, RUNTIME, "record"));
        assertTrue((boolean) SaveDescriptorCompatibilityRuntime.telemetry().get("installed"));
    }

    @Test
    void changedShapeWrongHashAndSecondRewriteFailClosed() {
        assertNull(SaveDescriptorCompatibilityPlan.transform(signature(), fixture(3)));
        ClassSignature exact = signature();
        assertNull(SaveDescriptorCompatibilityPlan.transform(new ClassSignature(
                exact.internalName(), "0".repeat(64), exact.majorVersion(), exact.access(), exact.methods()),
                fixture(4)));
        byte[] transformed = SaveDescriptorCompatibilityPlan.transform(exact, fixture(4));
        assertNotNull(transformed);
        assertNull(SaveDescriptorCompatibilityPlan.transform(exact, transformed));
    }

    @Test
    void sessionMemoHitsOnlyForExactCandidateBytes() throws Exception {
        Path save = temporaryDirectory.resolve("save");
        Files.createDirectories(save);
        Path descriptor = save.resolve("descriptor.xml");
        Files.writeString(descriptor, "<saveFileVersion>0.6</saveFileVersion>");

        assertNull(SaveDescriptorCompatibilityRuntime.lookup(save.toFile()));
        assertTrue(SaveDescriptorCompatibilityRuntime.record(true, save.toFile()));
        assertEquals(Boolean.TRUE, SaveDescriptorCompatibilityRuntime.lookup(save.toFile()));

        SaveDescriptorCompatibilityRuntime.beginSession();
        assertNull(SaveDescriptorCompatibilityRuntime.lookup(save.toFile()));
        assertTrue(SaveDescriptorCompatibilityRuntime.record(true, save.toFile()));
        assertEquals(Boolean.TRUE, SaveDescriptorCompatibilityRuntime.lookup(save.toFile()));
        Files.writeString(descriptor, "<saveFileVersion>0.5</saveFileVersion>");
        assertNull(SaveDescriptorCompatibilityRuntime.lookup(save.toFile()));
    }

    @Test
    void targetPinsReviewedCoreArchive() {
        AdapterTarget target = AdapterTargetRegistry.saveDescriptorCompatibilityTarget();
        AdapterSourceIdentity source = new AdapterSourceIdentity(
                "file:/game/contents/resources/java/starfarer_obf.jar",
                "/game/contents/resources/java/starfarer_obf.jar",
                "STARSECTOR_CORE",
                target.sourceSha256(),
                "",
                "jdk/internal/loader/ClassLoaders$AppClassLoader",
                "app");
        assertTrue(target.match(signature(), source).exact());
        assertTrue(AdapterTransformationRegistry.hasPlan(target.planId()));
    }

    private static ClassSignature signature() {
        return new ClassSignature(
                SaveDescriptorCompatibilityPlan.TARGET_CLASS,
                SaveDescriptorCompatibilityPlan.ORIGINAL_SHA256,
                61,
                Opcodes.ACC_PUBLIC,
                List.of(new ClassSignature.Method(
                        SaveDescriptorCompatibilityPlan.METHOD,
                        SaveDescriptorCompatibilityPlan.DESCRIPTOR,
                        Opcodes.ACC_PUBLIC | Opcodes.ACC_STATIC)));
    }

    private static byte[] fixture(int returnCount) {
        ClassWriter writer = new ClassWriter(ClassWriter.COMPUTE_FRAMES | ClassWriter.COMPUTE_MAXS);
        writer.visit(Opcodes.V17, Opcodes.ACC_PUBLIC,
                SaveDescriptorCompatibilityPlan.TARGET_CLASS, null, "java/lang/Object", null);
        MethodVisitor method = writer.visitMethod(
                Opcodes.ACC_PUBLIC | Opcodes.ACC_STATIC,
                SaveDescriptorCompatibilityPlan.METHOD,
                SaveDescriptorCompatibilityPlan.DESCRIPTOR,
                null,
                null);
        method.visitCode();
        Label path = new Label();
        method.visitInsn(Opcodes.ACONST_NULL);
        method.visitVarInsn(Opcodes.ASTORE, 0);
        method.visitVarInsn(Opcodes.ALOAD, 0);
        method.visitJumpInsn(Opcodes.IFNONNULL, path);
        method.visitInsn(Opcodes.ICONST_0);
        method.visitInsn(Opcodes.IRETURN);
        method.visitLabel(path);
        method.visitTypeInsn(Opcodes.NEW, "java/io/File");
        method.visitInsn(Opcodes.DUP);
        method.visitVarInsn(Opcodes.ALOAD, 0);
        method.visitMethodInsn(Opcodes.INVOKESPECIAL, "java/io/File", "<init>",
                "(Ljava/lang/String;)V", false);
        method.visitVarInsn(Opcodes.ASTORE, 1);
        Label exists = new Label();
        method.visitVarInsn(Opcodes.ALOAD, 1);
        method.visitMethodInsn(Opcodes.INVOKEVIRTUAL, "java/io/File", "exists", "()Z", false);
        method.visitJumpInsn(Opcodes.IFNE, exists);
        method.visitInsn(Opcodes.ICONST_0);
        method.visitInsn(Opcodes.IRETURN);
        method.visitLabel(exists);
        Label exhausted = new Label();
        if (returnCount == 4) {
            method.visitInsn(Opcodes.ICONST_0);
            method.visitJumpInsn(Opcodes.IFNE, exhausted);
        }
        method.visitLdcInsn("0.6");
        method.visitLdcInsn("0.6");
        method.visitMethodInsn(Opcodes.INVOKEVIRTUAL, "java/lang/String", "equals",
                "(Ljava/lang/Object;)Z", false);
        method.visitInsn(Opcodes.IRETURN);
        if (returnCount == 4) {
            method.visitLabel(exhausted);
            method.visitInsn(Opcodes.ICONST_0);
            method.visitInsn(Opcodes.IRETURN);
        }
        method.visitMaxs(0, 0);
        method.visitEnd();
        writer.visitEnd();
        return writer.toByteArray();
    }

    private static int calls(ClassNode owner, String callOwner, String name) {
        int result = 0;
        for (org.objectweb.asm.tree.MethodNode method : owner.methods) {
            for (AbstractInsnNode instruction : method.instructions) {
                if (instruction instanceof MethodInsnNode call
                        && callOwner.equals(call.owner) && name.equals(call.name)) result++;
            }
        }
        return result;
    }
}
