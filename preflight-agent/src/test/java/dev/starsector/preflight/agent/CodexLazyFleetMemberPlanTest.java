package dev.starsector.preflight.agent;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertSame;

import com.fs.starfarer.api.Global;
import com.fs.starfarer.api.SettingsAPI;
import com.fs.starfarer.api.combat.ShipVariantAPI;
import com.fs.starfarer.api.fleet.FleetMemberAPI;
import com.fs.starfarer.api.fleet.FleetMemberType;
import java.lang.reflect.Method;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.MethodVisitor;
import org.objectweb.asm.Opcodes;

class CodexLazyFleetMemberPlanTest {
    @BeforeEach
    void reset() {
        CodexLazyFleetMemberRuntime.beginSession();
        Global.setSettings(null);
    }

    @Test
    void accessorMaterializesTheExactVariantOnceAndRetainsTheMember() throws Exception {
        byte[] original = entryFixture();
        ClassSignature parsed = ClassSignature.parse(original);
        ClassSignature exact = new ClassSignature(parsed.internalName(),
                CodexLazyFleetMemberPlan.ENTRY_SHA256, parsed.majorVersion(), parsed.access(),
                parsed.methods());
        byte[] transformed = CodexLazyFleetMemberPlan.transform(exact, original);
        assertNotNull(transformed);

        Class<?> entryClass = new ClassLoader(getClass().getClassLoader()) {
            Class<?> define(byte[] bytes) {
                return defineClass(CodexLazyFleetMemberPlan.ENTRY_CLASS.replace('/', '.'),
                        bytes, 0, bytes.length);
            }
        }.define(transformed);
        Object entry = entryClass.getConstructor().newInstance();
        Method setParam2 = entryClass.getMethod("setParam2", Object.class);
        Method getParam2 = entryClass.getMethod("getParam2");

        ShipVariantAPI variant = new ShipVariantAPI() {
            @Override
            public String getVariantFilePath() {
                return "variant";
            }

            @Override
            public com.fs.starfarer.api.combat.ShipHullSpecAPI getHullSpec() {
                return null;
            }
        };
        FleetMemberAPI member = new FleetMemberAPI() { };
        AtomicInteger constructions = new AtomicInteger();
        Global.setSettings(new SettingsAPI() {
            @Override
            public List<String> getAllVariantIds() {
                return List.of();
            }

            @Override
            public ShipVariantAPI getVariant(String id) {
                return null;
            }

            @Override
            public FleetMemberAPI createFleetMember(FleetMemberType type,
                    ShipVariantAPI requested) {
                assertSame(FleetMemberType.SHIP, type);
                assertSame(variant, requested);
                constructions.incrementAndGet();
                return member;
            }
        });

        setParam2.invoke(entry, variant);
        assertSame(member, getParam2.invoke(entry));
        assertSame(member, getParam2.invoke(entry));
        assertEquals(1, constructions.get());
        assertEquals(1L, CodexLazyFleetMemberRuntime.telemetry().get("materialized"));
    }

    private static byte[] entryFixture() {
        ClassWriter writer = new ClassWriter(ClassWriter.COMPUTE_FRAMES | ClassWriter.COMPUTE_MAXS);
        writer.visit(Opcodes.V17, Opcodes.ACC_PUBLIC, CodexLazyFleetMemberPlan.ENTRY_CLASS,
                null, "java/lang/Object", null);
        writer.visitField(Opcodes.ACC_PROTECTED, "param2", "Ljava/lang/Object;", null, null)
                .visitEnd();
        MethodVisitor constructor = writer.visitMethod(Opcodes.ACC_PUBLIC, "<init>", "()V",
                null, null);
        constructor.visitCode();
        constructor.visitVarInsn(Opcodes.ALOAD, 0);
        constructor.visitMethodInsn(Opcodes.INVOKESPECIAL, "java/lang/Object", "<init>",
                "()V", false);
        constructor.visitInsn(Opcodes.RETURN);
        constructor.visitMaxs(0, 0);
        constructor.visitEnd();
        MethodVisitor setter = writer.visitMethod(Opcodes.ACC_PUBLIC, "setParam2",
                "(Ljava/lang/Object;)V", null, null);
        setter.visitCode();
        setter.visitVarInsn(Opcodes.ALOAD, 0);
        setter.visitVarInsn(Opcodes.ALOAD, 1);
        setter.visitFieldInsn(Opcodes.PUTFIELD, CodexLazyFleetMemberPlan.ENTRY_CLASS,
                "param2", "Ljava/lang/Object;");
        setter.visitInsn(Opcodes.RETURN);
        setter.visitMaxs(0, 0);
        setter.visitEnd();
        MethodVisitor getter = writer.visitMethod(Opcodes.ACC_PUBLIC,
                CodexLazyFleetMemberPlan.ACCESSOR_METHOD,
                CodexLazyFleetMemberPlan.ACCESSOR_DESCRIPTOR, null, null);
        getter.visitCode();
        getter.visitVarInsn(Opcodes.ALOAD, 0);
        getter.visitFieldInsn(Opcodes.GETFIELD, CodexLazyFleetMemberPlan.ENTRY_CLASS,
                "param2", "Ljava/lang/Object;");
        getter.visitInsn(Opcodes.ARETURN);
        getter.visitMaxs(0, 0);
        getter.visitEnd();
        writer.visitEnd();
        return writer.toByteArray();
    }
}
