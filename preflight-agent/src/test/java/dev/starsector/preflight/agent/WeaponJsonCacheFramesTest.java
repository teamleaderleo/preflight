package dev.starsector.preflight.agent;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;

import org.junit.jupiter.api.Test;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.Label;
import org.objectweb.asm.MethodVisitor;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.Type;
import org.objectweb.asm.tree.ClassNode;

public class WeaponJsonCacheFramesTest {
    @Test
    void preservesApplicationHierarchyWithEitherCacheAndTheirComposition() throws Exception {
        byte[] original = fixture();
        verifyHierarchy(original);
        byte[] weapon = WeaponJsonCachePlan.transform(ClassSignature.parse(original), original);
        byte[] projectile = ProjectileJsonCachePlan.transform(ClassSignature.parse(original), original);
        assertNotNull(weapon);
        assertNotNull(projectile);
        verifyHierarchy(weapon);
        verifyHierarchy(projectile);
        byte[] both = ProjectileJsonCachePlan.transform(ClassSignature.parse(weapon), weapon);
        assertNotNull(both);
        verifyHierarchy(both);

        ClassNode owner = new ClassNode(Opcodes.ASM9);
        new ClassReader(original).accept(owner, ClassReader.EXPAND_FRAMES);
        ClassSignature signature = ClassSignature.parse(original);
        org.junit.jupiter.api.Assertions.assertTrue(WeaponJsonCachePlan.apply(signature, owner));
        org.junit.jupiter.api.Assertions.assertTrue(ProjectileJsonCachePlan.apply(signature, owner));
        verifyHierarchy(WeaponJsonCachePlan.write(owner));
    }

    private static void verifyHierarchy(byte[] bytes) throws Exception {
        Class<?> type = new ClassLoader(WeaponJsonCacheFramesTest.class.getClassLoader()) {
            Class<?> define() {
                return defineClass(null, bytes, 0, bytes.length);
            }
        }.define();
        assertEquals(7, type.getMethod("hierarchy", boolean.class).invoke(null, true));
        assertEquals(7, type.getMethod("hierarchy", boolean.class).invoke(null, false));
    }

    private static byte[] fixture() {
        ClassNode owner = new ClassNode(Opcodes.ASM9);
        new ClassReader(WeaponJsonCachePlanTest.fixture(1)).accept(owner, ClassReader.EXPAND_FRAMES);
        ClassNode projectile = new ClassNode(Opcodes.ASM9);
        new ClassReader(ProjectileJsonCachePlanTest.fixture(1)).accept(projectile, ClassReader.EXPAND_FRAMES);
        owner.methods.addAll(projectile.methods);
        ClassWriter writer = new ClassWriter(ClassWriter.COMPUTE_FRAMES | ClassWriter.COMPUTE_MAXS);
        owner.accept(writer);
        MethodVisitor method = writer.visitMethod(Opcodes.ACC_PUBLIC | Opcodes.ACC_STATIC,
                "hierarchy", "(Z)I", null, null);
        method.visitCode();
        Label child = new Label();
        Label join = new Label();
        method.visitVarInsn(Opcodes.ILOAD, 0);
        method.visitJumpInsn(Opcodes.IFEQ, child);
        construct(method, Base.class);
        method.visitJumpInsn(Opcodes.GOTO, join);
        method.visitLabel(child);
        construct(method, Child.class);
        method.visitLabel(join);
        method.visitMethodInsn(Opcodes.INVOKEVIRTUAL, Type.getInternalName(Base.class), "value", "()I", false);
        method.visitInsn(Opcodes.IRETURN);
        method.visitMaxs(0, 0);
        method.visitEnd();
        return writer.toByteArray();
    }

    private static void construct(MethodVisitor method, Class<?> type) {
        method.visitTypeInsn(Opcodes.NEW, Type.getInternalName(type));
        method.visitInsn(Opcodes.DUP);
        method.visitMethodInsn(Opcodes.INVOKESPECIAL, Type.getInternalName(type), "<init>", "()V", false);
    }

    public static class Base {
        public int value() {
            return 7;
        }
    }

    public static final class Child extends Base {
    }
}
