package dev.starsector.preflight.agent;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;

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

class WeaponProjectileJsonCacheCompositionTest {
    @TempDir
    Path temporaryDirectory;

    @Test
    void oneSharedClassReceivesBothIndependentCachePlans() throws Exception {
        WeaponJsonCacheRuntime.beginSession();
        ProjectileJsonCacheRuntime.beginSession();
        WeaponJsonCacheRuntime.configure(temporaryDirectory.resolve("a".repeat(64) + ".spwj"));
        ProjectileJsonCacheRuntime.configure(temporaryDirectory.resolve("b".repeat(64) + ".sppj"));
        byte[] original = fixture();

        byte[] rewritten = AdapterTransformationRegistry.transform(
                AdapterTargetRegistry.weaponJsonCacheTarget(),
                ClassSignature.parse(original),
                original);

        assertNotNull(rewritten);
        assertEquals(1, calls(rewritten,
                "dev/starsector/preflight/agent/WeaponJsonCacheRuntime", "cached"));
        assertEquals(1, calls(rewritten,
                "dev/starsector/preflight/agent/ProjectileJsonCacheRuntime", "cached"));
    }

    private static byte[] fixture() {
        ClassWriter writer = new ClassWriter(ClassWriter.COMPUTE_FRAMES | ClassWriter.COMPUTE_MAXS);
        writer.visit(Opcodes.V17, Opcodes.ACC_PUBLIC, WeaponLoaderPhasePlan.TARGET_CLASS,
                null, "java/lang/Object", null);
        loader(writer, WeaponLoaderPhasePlan.LOAD_ALL_METHOD, WeaponLoaderPhasePlan.LOAD_ONE_METHOD,
                "data/weapons/example.wpn");
        loader(writer, ProjectileLoaderPhasePlan.LOAD_ALL_METHOD, ProjectileLoaderPhasePlan.LOAD_ONE_METHOD,
                "data/weapons/proj/example.proj");
        writer.visitEnd();
        return writer.toByteArray();
    }

    private static void loader(ClassWriter writer, String all, String one, String path) {
        MethodVisitor loadAll = writer.visitMethod(Opcodes.ACC_PUBLIC | Opcodes.ACC_STATIC,
                all, "()V", null, null);
        loadAll.visitCode();
        loadAll.visitLdcInsn(path);
        loadAll.visitMethodInsn(Opcodes.INVOKESTATIC, WeaponLoaderPhasePlan.TARGET_CLASS,
                one, "(Ljava/lang/String;)V", false);
        loadAll.visitInsn(Opcodes.RETURN);
        loadAll.visitMaxs(0, 0);
        loadAll.visitEnd();

        MethodVisitor loadOne = writer.visitMethod(Opcodes.ACC_PUBLIC | Opcodes.ACC_STATIC,
                one, "(Ljava/lang/String;)V", null, null);
        loadOne.visitCode();
        loadOne.visitVarInsn(Opcodes.ALOAD, 0);
        loadOne.visitMethodInsn(Opcodes.INVOKESTATIC,
                "com/fs/starfarer/loading/LoadingUtils", "Ó00000",
                "(Ljava/lang/String;)Lorg/json/JSONObject;", false);
        loadOne.visitInsn(Opcodes.POP);
        loadOne.visitInsn(Opcodes.RETURN);
        loadOne.visitMaxs(0, 0);
        loadOne.visitEnd();
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
}
