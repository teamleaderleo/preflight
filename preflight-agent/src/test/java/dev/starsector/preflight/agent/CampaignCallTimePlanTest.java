package dev.starsector.preflight.agent;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.lang.reflect.InvocationTargetException;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.Label;
import org.objectweb.asm.MethodVisitor;
import org.objectweb.asm.Opcodes;

class CampaignCallTimePlanTest {
    private static final CampaignCallTimePlan.Probe PROBE = CampaignCallTimePlan.probes().get(0);

    @BeforeEach
    void enable() {
        CampaignCallTimeRuntime.beginSession(true);
    }

    @AfterEach
    void reset() {
        CampaignCallTimeRuntime.reset();
    }

    @Test
    void recordsNormalAndExceptionalExitsWithoutChangingBehavior() throws Exception {
        byte[] original = fixture();
        byte[] transformed = CampaignCallTimePlan.transform(exactSignature(original), original);
        assertNotNull(transformed);
        assertNull(CampaignCallTimePlan.transform(ClassSignature.parse(transformed), transformed));

        Class<?> type = new DefiningLoader().define(PROBE.className().replace('/', '.'), transformed);
        Object instance = type.getConstructor().newInstance();
        type.getMethod(PROBE.method(), float.class).invoke(instance, 1f);
        InvocationTargetException thrown = assertThrows(InvocationTargetException.class,
                () -> type.getMethod(PROBE.method(), float.class).invoke(instance, -1f));
        assertEquals(IllegalStateException.class, thrown.getCause().getClass());

        Map<String, Object> seam = seam(PROBE.id());
        assertEquals(true, seam.get("installed"));
        assertEquals(2L, seam.get("calls"));
        assertEquals(2, ((List<?>) seam.get("slowestCalls")).size());
    }

    @Test
    void declinesDisabledAndWrongIdentity() throws Exception {
        byte[] original = fixture();
        CampaignCallTimeRuntime.beginSession(false);
        assertNull(CampaignCallTimePlan.transform(exactSignature(original), original));

        CampaignCallTimeRuntime.beginSession(true);
        assertNull(CampaignCallTimePlan.transform(ClassSignature.parse(original), original));
    }

    private static byte[] fixture() {
        ClassWriter writer = new ClassWriter(ClassWriter.COMPUTE_FRAMES | ClassWriter.COMPUTE_MAXS);
        writer.visit(Opcodes.V1_8, Opcodes.ACC_PUBLIC, PROBE.className(),
                null, "java/lang/Object", null);

        MethodVisitor constructor = writer.visitMethod(
                Opcodes.ACC_PUBLIC, "<init>", "()V", null, null);
        constructor.visitCode();
        constructor.visitVarInsn(Opcodes.ALOAD, 0);
        constructor.visitMethodInsn(Opcodes.INVOKESPECIAL,
                "java/lang/Object", "<init>", "()V", false);
        constructor.visitInsn(Opcodes.RETURN);
        constructor.visitMaxs(0, 0);
        constructor.visitEnd();

        MethodVisitor advance = writer.visitMethod(
                Opcodes.ACC_PUBLIC, PROBE.method(), PROBE.descriptor(), null, null);
        advance.visitCode();
        Label normal = new Label();
        advance.visitVarInsn(Opcodes.FLOAD, 1);
        advance.visitInsn(Opcodes.FCONST_0);
        advance.visitInsn(Opcodes.FCMPG);
        advance.visitJumpInsn(Opcodes.IFGE, normal);
        advance.visitTypeInsn(Opcodes.NEW, "java/lang/IllegalStateException");
        advance.visitInsn(Opcodes.DUP);
        advance.visitMethodInsn(Opcodes.INVOKESPECIAL,
                "java/lang/IllegalStateException", "<init>", "()V", false);
        advance.visitInsn(Opcodes.ATHROW);
        advance.visitLabel(normal);
        advance.visitInsn(Opcodes.RETURN);
        advance.visitMaxs(0, 0);
        advance.visitEnd();
        writer.visitEnd();
        return writer.toByteArray();
    }

    private static ClassSignature exactSignature(byte[] bytes) throws Exception {
        ClassSignature parsed = ClassSignature.parse(bytes);
        return new ClassSignature(parsed.internalName(), PROBE.sha256(), parsed.majorVersion(),
                parsed.access(), parsed.methods());
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> seam(int id) {
        List<Map<String, Object>> seams =
                (List<Map<String, Object>>) CampaignCallTimeRuntime.telemetry().get("seams");
        return seams.get(id);
    }

    private static final class DefiningLoader extends ClassLoader {
        DefiningLoader() {
            super(CampaignCallTimePlanTest.class.getClassLoader());
        }

        Class<?> define(String name, byte[] bytes) {
            return defineClass(name, bytes, 0, bytes.length);
        }
    }
}
