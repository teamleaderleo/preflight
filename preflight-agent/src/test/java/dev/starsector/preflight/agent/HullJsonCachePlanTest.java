package dev.starsector.preflight.agent;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;

import dev.starsector.preflight.core.PreparedHullJsonCacheIO;
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

class HullJsonCachePlanTest {
    private static final String RUNTIME = "dev/starsector/preflight/agent/HullJsonCacheRuntime";

    @TempDir
    Path temporaryDirectory;

    @Test
    void keepsTheOriginalCallAndAddsHitCaptureAndCommitHooks() throws Exception {
        byte[] original = fixture(1);
        byte[] rewritten = HullJsonCachePlan.transform(ClassSignature.parse(original), original);

        assertNotNull(rewritten);
        assertEquals(1, calls(rewritten, "com/fs/starfarer/loading/LoadingUtils", "Ó00000"));
        assertEquals(1, calls(rewritten, RUNTIME, "cached"));
        assertEquals(1, calls(rewritten, RUNTIME, "capture"));
        assertEquals(1, calls(rewritten, RUNTIME, "complete"));
    }

    @Test
    void refusesChangedShapesAndDoubleWeaving() throws Exception {
        assertNull(HullJsonCachePlan.transform(ClassSignature.parse(fixture(2)), fixture(2)));
        byte[] original = fixture(1);
        byte[] once = HullJsonCachePlan.transform(ClassSignature.parse(original), original);
        assertNotNull(once);
        assertNull(HullJsonCachePlan.transform(ClassSignature.parse(once), once));
    }

    @Test
    void executableRewriteLearnsColdAndBypassesVanillaWarm() throws Exception {
        String profile = "f".repeat(64);
        Path artifact = temporaryDirectory.resolve(profile + ".sphj");
        byte[] rewritten = HullJsonCachePlan.transform(ClassSignature.parse(fixture(1)), fixture(1));
        assertNotNull(rewritten);

        com.fs.starfarer.loading.LoadingUtils.reset();
        HullJsonCacheRuntime.beginSession();
        HullJsonCacheRuntime.configure(artifact);
        new ByteArrayLoader().define(rewritten)
                .getMethod(ShipHullLoaderPhasePlan.LOAD_ALL_METHOD).invoke(null);
        assertEquals(1, com.fs.starfarer.loading.LoadingUtils.calls());
        assertEquals(1, PreparedHullJsonCacheIO.read(artifact).entries().size());

        com.fs.starfarer.loading.LoadingUtils.reset();
        HullJsonCacheRuntime.beginSession();
        HullJsonCacheRuntime.configure(artifact);
        new ByteArrayLoader().define(rewritten)
                .getMethod(ShipHullLoaderPhasePlan.LOAD_ALL_METHOD).invoke(null);
        assertEquals(0, com.fs.starfarer.loading.LoadingUtils.calls());
    }

    private static byte[] fixture(int jsonCalls) {
        ClassWriter writer = new ClassWriter(ClassWriter.COMPUTE_FRAMES | ClassWriter.COMPUTE_MAXS);
        writer.visit(Opcodes.V17, Opcodes.ACC_PUBLIC, ShipHullLoaderPhasePlan.TARGET_CLASS,
                null, "java/lang/Object", null);
        MethodVisitor loadAll = writer.visitMethod(Opcodes.ACC_PUBLIC | Opcodes.ACC_STATIC,
                ShipHullLoaderPhasePlan.LOAD_ALL_METHOD,
                ShipHullLoaderPhasePlan.LOAD_ALL_DESCRIPTOR, null, null);
        loadAll.visitCode();
        loadAll.visitLdcInsn("data/hulls/example.ship");
        loadAll.visitMethodInsn(Opcodes.INVOKESTATIC, ShipHullLoaderPhasePlan.TARGET_CLASS,
                ShipHullLoaderPhasePlan.LOAD_ONE_METHOD,
                ShipHullLoaderPhasePlan.LOAD_ONE_DESCRIPTOR, false);
        loadAll.visitInsn(Opcodes.RETURN);
        loadAll.visitMaxs(0, 0);
        loadAll.visitEnd();

        MethodVisitor loadOne = writer.visitMethod(Opcodes.ACC_PUBLIC | Opcodes.ACC_STATIC,
                ShipHullLoaderPhasePlan.LOAD_ONE_METHOD,
                ShipHullLoaderPhasePlan.LOAD_ONE_DESCRIPTOR, null, null);
        loadOne.visitCode();
        for (int index = 0; index < jsonCalls; index++) {
            loadOne.visitVarInsn(Opcodes.ALOAD, 0);
            loadOne.visitMethodInsn(Opcodes.INVOKESTATIC,
                    "com/fs/starfarer/loading/LoadingUtils", "Ó00000",
                    "(Ljava/lang/String;)Lorg/json/JSONObject;", false);
            loadOne.visitInsn(Opcodes.POP);
        }
        loadOne.visitInsn(Opcodes.RETURN);
        loadOne.visitMaxs(0, 0);
        loadOne.visitEnd();
        writer.visitEnd();
        return writer.toByteArray();
    }

    private static int calls(byte[] bytes, String ownerName, String methodName) {
        ClassNode owner = new ClassNode(Opcodes.ASM9);
        new ClassReader(bytes).accept(owner, ClassReader.EXPAND_FRAMES);
        int count = 0;
        for (MethodNode method : owner.methods) {
            for (AbstractInsnNode instruction = method.instructions.getFirst();
                    instruction != null; instruction = instruction.getNext()) {
                if (instruction instanceof MethodInsnNode call
                        && ownerName.equals(call.owner) && methodName.equals(call.name)) {
                    count++;
                }
            }
        }
        return count;
    }

    private static final class ByteArrayLoader extends ClassLoader {
        private ByteArrayLoader() {
            super(HullJsonCachePlanTest.class.getClassLoader());
        }

        private Class<?> define(byte[] bytes) {
            return defineClass(null, bytes, 0, bytes.length);
        }
    }
}
