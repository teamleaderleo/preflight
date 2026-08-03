package dev.starsector.preflight.agent;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.Test;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.MethodVisitor;
import org.objectweb.asm.Opcodes;

class MergedReadCachePlanTest {

    @Test
    void weavesBothMergedReadersAndDelegatesMissesToVanilla() throws Exception {
        MergedReadCacheRuntime.beginSession();
        byte[] original = fixture(Opcodes.V17, true, true, false, false);
        byte[] rewritten = MergedReadCachePlan.transform(ClassSignature.parse(original), original);

        assertNotNull(rewritten);
        ClassSignature signature = ClassSignature.parse(rewritten);
        assertTrue(signature.hasMethod(
                MergedReadCachePlan.CSV_VANILLA_METHOD, MergedReadCachePlan.CSV_DESCRIPTOR));
        assertTrue(signature.hasMethod(
                MergedReadCachePlan.JSON_VANILLA_METHOD, MergedReadCachePlan.JSON_DESCRIPTOR));
        assertTrue(signature.hasMethod(
                MergedReadCachePlan.MERGED_METHOD, MergedReadCachePlan.CSV_DESCRIPTOR));
        assertTrue(signature.hasMethod(
                MergedReadCachePlan.MERGED_METHOD, MergedReadCachePlan.JSON_DESCRIPTOR));

        Class<?> loaded = new ByteArrayLoader().define(rewritten);
        assertNull(loaded.getMethod(MergedReadCachePlan.MERGED_METHOD,
                        List.class, String.class, boolean.class, boolean.class)
                .invoke(null, List.of("id"), "data/hulls/ship_data.csv", true, false));
        assertNull(loaded.getMethod(MergedReadCachePlan.MERGED_METHOD, String.class, Set.class)
                .invoke(null, "data/config/settings.json", Set.of()));
        assertEquals(2, loaded.getField("calls").getInt(null));
    }

    @Test
    void declinesChangedShapesSecondWeavesAndOldClassFiles() throws Exception {
        byte[] onlyCsv = fixture(Opcodes.V17, true, false, false, false);
        assertNull(MergedReadCachePlan.transform(ClassSignature.parse(onlyCsv), onlyCsv));

        byte[] original = fixture(Opcodes.V17, true, true, false, false);
        byte[] once = MergedReadCachePlan.transform(ClassSignature.parse(original), original);
        assertNotNull(once);
        assertNull(MergedReadCachePlan.transform(ClassSignature.parse(once), once));

        byte[] java6 = fixture(Opcodes.V1_6, true, true, false, false);
        assertNull(MergedReadCachePlan.transform(ClassSignature.parse(java6), java6));
    }

    @Test
    void declinesAClassAlreadyCarryingTheProbeNames() throws Exception {
        byte[] probed = fixture(Opcodes.V17, true, true, false, true);

        assertNull(MergedReadCachePlan.transform(ClassSignature.parse(probed), probed));
    }

    @Test
    void composesWithTheSingleFileJsonMemoOnTheSameClass() throws Exception {
        byte[] original = fixture(Opcodes.V17, true, true, true, false);
        byte[] cached = MergedReadCachePlan.transform(ClassSignature.parse(original), original);
        assertNotNull(cached);
        byte[] both = LoadJsonMemoPlan.transform(ClassSignature.parse(cached), cached);

        assertNotNull(both);
        ClassSignature signature = ClassSignature.parse(both);
        assertTrue(signature.hasMethod(
                MergedReadCachePlan.CSV_VANILLA_METHOD, MergedReadCachePlan.CSV_DESCRIPTOR));
        assertTrue(signature.hasMethod(
                MergedReadCachePlan.JSON_VANILLA_METHOD, MergedReadCachePlan.JSON_DESCRIPTOR));
        assertTrue(signature.hasMethod(
                LoadJsonMemoPlan.VANILLA_METHOD, LoadJsonMemoPlan.LOAD_DESCRIPTOR));
    }

    /** The two merged readers, optionally beside the single-file loader and a probe marker. */
    private static byte[] fixture(
            int version, boolean csv, boolean json, boolean singleFile, boolean probeMarker) {
        ClassWriter writer = new ClassWriter(ClassWriter.COMPUTE_FRAMES | ClassWriter.COMPUTE_MAXS);
        writer.visit(version, Opcodes.ACC_PUBLIC, MergedReadCachePlan.TARGET_CLASS,
                null, "java/lang/Object", null);
        writer.visitField(Opcodes.ACC_PUBLIC | Opcodes.ACC_STATIC, "calls", "I", null, null)
                .visitEnd();
        if (csv) {
            counting(writer, MergedReadCachePlan.MERGED_METHOD, MergedReadCachePlan.CSV_DESCRIPTOR);
        }
        if (json) {
            counting(writer, MergedReadCachePlan.MERGED_METHOD, MergedReadCachePlan.JSON_DESCRIPTOR);
        }
        if (singleFile) {
            counting(writer, LoadJsonMemoPlan.LOAD_METHOD, LoadJsonMemoPlan.LOAD_DESCRIPTOR);
        }
        if (probeMarker) {
            counting(writer, MergedReadProbePlan.CSV_VANILLA_METHOD,
                    MergedReadCachePlan.CSV_DESCRIPTOR);
        }
        writer.visitEnd();
        return writer.toByteArray();
    }

    private static void counting(ClassWriter writer, String name, String descriptor) {
        MethodVisitor method = writer.visitMethod(
                Opcodes.ACC_PUBLIC | Opcodes.ACC_STATIC, name, descriptor, null, null);
        method.visitCode();
        method.visitFieldInsn(Opcodes.GETSTATIC, MergedReadCachePlan.TARGET_CLASS, "calls", "I");
        method.visitInsn(Opcodes.ICONST_1);
        method.visitInsn(Opcodes.IADD);
        method.visitFieldInsn(Opcodes.PUTSTATIC, MergedReadCachePlan.TARGET_CLASS, "calls", "I");
        method.visitInsn(Opcodes.ACONST_NULL);
        method.visitInsn(Opcodes.ARETURN);
        method.visitMaxs(0, 0);
        method.visitEnd();
    }

    private static final class ByteArrayLoader extends ClassLoader {
        private ByteArrayLoader() {
            super(MergedReadCachePlanTest.class.getClassLoader());
        }

        private Class<?> define(byte[] bytes) {
            return defineClass(null, bytes, 0, bytes.length);
        }
    }
}
