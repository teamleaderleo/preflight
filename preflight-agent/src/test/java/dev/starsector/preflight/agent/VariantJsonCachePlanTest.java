package dev.starsector.preflight.agent;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;

import dev.starsector.preflight.core.PreparedVariantJsonCacheIO;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.MethodVisitor;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.tree.AbstractInsnNode;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.MethodInsnNode;
import org.objectweb.asm.tree.MethodNode;

class VariantJsonCachePlanTest {
    private static final String RUNTIME = "dev/starsector/preflight/agent/VariantJsonCacheRuntime";

    @TempDir
    Path temporaryDirectory;

    @Test
    void keepsTheOriginalCallAsTheMissPathAndAddsHitCaptureAndCommitHooks() throws Exception {
        byte[] original = fixture(1);
        byte[] rewritten = VariantJsonCachePlan.transform(ClassSignature.parse(original), original);

        assertNotNull(rewritten);
        MethodNode method = method(rewritten);
        assertEquals(1, calls(method, "com/fs/starfarer/loading/LoadingUtils", "Ó00000"));
        assertEquals(1, calls(method, RUNTIME, "cached"));
        assertEquals(1, calls(method, RUNTIME, "capture"));
        assertEquals(1, calls(method, RUNTIME, "complete"));
    }

    @Test
    void refusesChangedShapesAndDoubleWeaving() throws Exception {
        assertNull(VariantJsonCachePlan.transform(ClassSignature.parse(fixture(2)), fixture(2)));
        byte[] original = fixture(1);
        byte[] once = VariantJsonCachePlan.transform(ClassSignature.parse(original), original);
        assertNotNull(once);
        assertNull(VariantJsonCachePlan.transform(ClassSignature.parse(once), once));
    }

    @Test
    void executableRewriteLearnsColdAndBypassesVanillaWarm() throws Exception {
        String profile = "f".repeat(64);
        Path artifact = temporaryDirectory.resolve(profile + ".spvj");
        byte[] original = fixture(1);
        byte[] rewritten = VariantJsonCachePlan.transform(ClassSignature.parse(original), original);
        assertNotNull(rewritten);

        com.fs.starfarer.loading.LoadingUtils.reset();
        VariantJsonCacheRuntime.beginSession();
        VariantJsonCacheRuntime.configure(artifact);
        new ByteArrayLoader().define(rewritten).getMethod(VariantLoaderPhasePlan.METHOD).invoke(null);
        assertEquals(1, com.fs.starfarer.loading.LoadingUtils.calls());
        assertEquals(1, PreparedVariantJsonCacheIO.read(artifact).entries().size());

        com.fs.starfarer.loading.LoadingUtils.reset();
        VariantJsonCacheRuntime.beginSession();
        VariantJsonCacheRuntime.configure(artifact);
        new ByteArrayLoader().define(rewritten).getMethod(VariantLoaderPhasePlan.METHOD).invoke(null);
        assertEquals(0, com.fs.starfarer.loading.LoadingUtils.calls());
    }

    private static byte[] fixture(int jsonCalls) {
        ClassWriter writer = new ClassWriter(ClassWriter.COMPUTE_FRAMES | ClassWriter.COMPUTE_MAXS);
        writer.visit(Opcodes.V17, Opcodes.ACC_PUBLIC, SpecStorePhasePlan.TARGET_CLASS,
                null, "java/lang/Object", null);
        MethodVisitor method = writer.visitMethod(Opcodes.ACC_PUBLIC | Opcodes.ACC_STATIC,
                VariantLoaderPhasePlan.METHOD, VariantLoaderPhasePlan.DESCRIPTOR, null, null);
        method.visitCode();
        for (int index = 0; index < jsonCalls; index++) {
            method.visitLdcInsn("data/variants/example" + index + ".variant");
            method.visitMethodInsn(Opcodes.INVOKESTATIC, "com/fs/starfarer/loading/LoadingUtils", "Ó00000",
                    "(Ljava/lang/String;)Lorg/json/JSONObject;", false);
            method.visitInsn(Opcodes.POP);
        }
        method.visitInsn(Opcodes.RETURN);
        method.visitMaxs(0, 0);
        method.visitEnd();
        writer.visitEnd();
        return writer.toByteArray();
    }

    private static MethodNode method(byte[] bytes) {
        ClassNode owner = new ClassNode(Opcodes.ASM9);
        new ClassReader(bytes).accept(owner, ClassReader.EXPAND_FRAMES);
        return owner.methods.stream()
                .filter(method -> VariantLoaderPhasePlan.METHOD.equals(method.name))
                .findFirst().orElseThrow();
    }

    private static int calls(MethodNode method, String owner, String name) {
        int count = 0;
        for (AbstractInsnNode instruction = method.instructions.getFirst();
                instruction != null; instruction = instruction.getNext()) {
            if (instruction instanceof MethodInsnNode call
                    && owner.equals(call.owner) && name.equals(call.name)) {
                count++;
            }
        }
        return count;
    }

    private static final class ByteArrayLoader extends ClassLoader {
        private ByteArrayLoader() {
            super(VariantJsonCachePlanTest.class.getClassLoader());
        }

        private Class<?> define(byte[] bytes) {
            return defineClass(null, bytes, 0, bytes.length);
        }
    }
}
