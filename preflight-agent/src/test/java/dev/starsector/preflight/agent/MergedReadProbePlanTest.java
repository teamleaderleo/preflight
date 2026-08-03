package dev.starsector.preflight.agent;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.Test;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.MethodVisitor;
import org.objectweb.asm.Opcodes;

class MergedReadProbePlanTest {

    @Test
    void timesBothMergedReadersAndStillReturnsWhatVanillaReturned() throws Exception {
        Class<?> loaded = installed();
        StartupPhaseRuntime.beginSession(null);
        StartupPhaseRuntime.enableMergedReadProbe(true);

        Object csv = loaded.getMethod(MergedReadProbePlan.MERGED_METHOD,
                        List.class, String.class, boolean.class, boolean.class)
                .invoke(null, List.of("core"), "data/hulls/afflictor.ship", true, false);
        Object json = loaded.getMethod(MergedReadProbePlan.MERGED_METHOD, String.class, Set.class)
                .invoke(null, "data/config/sounds.json", Set.of());

        assertNull(csv, "the delegator must hand back exactly what the original produced");
        assertNull(json);
        assertEquals(2, loaded.getField("calls").getInt(null), "vanilla must still run, once each");

        Map<String, Object> rows = groups();
        assertTrue(rows.containsKey("csv data/hulls/*"),
                "thousands of spec files belong to their directory: " + rows.keySet());
        assertTrue(rows.containsKey("json data/config/sounds.json"),
                "a named config file keeps its own row: " + rows.keySet());
    }

    @Test
    void countsRepeatsUnderOneRowAndKeepsSpreadsheetsApart() throws Exception {
        Class<?> loaded = installed();
        StartupPhaseRuntime.beginSession(null);
        StartupPhaseRuntime.enableMergedReadProbe(true);

        var merged = loaded.getMethod(MergedReadProbePlan.MERGED_METHOD,
                List.class, String.class, boolean.class, boolean.class);
        merged.invoke(null, List.of(), "data/hulls/a.ship", true, false);
        merged.invoke(null, List.of(), "data/hulls/b.ship", true, false);
        merged.invoke(null, List.of(), "data/hulls/ship_data.csv", true, false);

        Map<String, Object> rows = groups();
        assertEquals(2L, calls(rows.get("csv data/hulls/*")));
        assertEquals(1L, calls(rows.get("csv data/hulls/ship_data.csv")));
    }

    @Test
    void recordsNothingWhileTheProbeIsOff() throws Exception {
        Class<?> loaded = installed();
        StartupPhaseRuntime.beginSession(null);
        StartupPhaseRuntime.enableMergedReadProbe(false);

        loaded.getMethod(MergedReadProbePlan.MERGED_METHOD, String.class, Set.class)
                .invoke(null, "data/campaign/econ/economy.json", Set.of());

        assertEquals(1, loaded.getField("calls").getInt(null), "the read still has to happen");
        assertTrue(groups().isEmpty(), "an installed probe that is off costs one branch and no rows");
    }

    @Test
    void refusesChangedShapesAndDoubleWeaving() throws Exception {
        byte[] onlyCsv = fixture(true, false, false);
        assertNull(MergedReadProbePlan.transform(ClassSignature.parse(onlyCsv), onlyCsv),
                "half the pair is not the reviewed shape");

        byte[] original = fixture(true, true, false);
        byte[] once = MergedReadProbePlan.transform(ClassSignature.parse(original), original);
        assertNotNull(once);
        assertNull(MergedReadProbePlan.transform(ClassSignature.parse(once), once));
    }

    @Test
    void declinesAClassTooOldToCarryAMethodHandleConstant() throws Exception {
        byte[] java6 = fixture(Opcodes.V1_6, true, true, false);
        assertNull(MergedReadProbePlan.transform(ClassSignature.parse(java6), java6),
                "raising the class version would change how it is verified");
    }

    @Test
    void composesWithTheSingleFileMemoOnTheSameClass() throws Exception {
        byte[] original = fixture(true, true, true);
        byte[] probed = MergedReadProbePlan.transform(ClassSignature.parse(original), original);
        assertNotNull(probed);
        byte[] both = LoadJsonMemoPlan.transform(ClassSignature.parse(probed), probed);

        assertNotNull(both, "the memo must still find loadJSON inside a probed LoadingUtils");
        ClassSignature signature = ClassSignature.parse(both);
        assertTrue(signature.hasMethod(
                MergedReadProbePlan.CSV_VANILLA_METHOD, MergedReadProbePlan.CSV_DESCRIPTOR));
        assertTrue(signature.hasMethod(
                MergedReadProbePlan.JSON_VANILLA_METHOD, MergedReadProbePlan.JSON_DESCRIPTOR));
        assertTrue(signature.hasMethod(
                LoadJsonMemoPlan.VANILLA_METHOD, LoadJsonMemoPlan.LOAD_DESCRIPTOR));
    }

    @Test
    void namesTheRowAPathIsCountedUnder() {
        assertEquals("data/campaign/rules.csv",
                StartupPhaseRuntime.mergedReadGroup("data/campaign/rules.csv"));
        assertEquals("data/config/settings.json",
                StartupPhaseRuntime.mergedReadGroup("data/config/settings.json"));
        assertEquals("data/hulls/skins/*",
                StartupPhaseRuntime.mergedReadGroup("data/hulls/skins/wolf_d.skin"));
        assertEquals("*", StartupPhaseRuntime.mergedReadGroup("loose.wpn"));
        assertEquals("<null>", StartupPhaseRuntime.mergedReadGroup(null));
    }

    // -----------------------------------------------------------------------------------------

    private static Class<?> installed() throws Exception {
        byte[] original = fixture(true, true, false);
        byte[] rewritten = MergedReadProbePlan.transform(ClassSignature.parse(original), original);
        assertNotNull(rewritten);
        return new ByteArrayLoader().define(rewritten);
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> groups() {
        List<Map<String, Object>> rows =
                (List<Map<String, Object>>) StartupPhaseRuntime.telemetry().get("mergedReads");
        return rows.stream().collect(java.util.stream.Collectors.toMap(
                row -> row.get("kind") + " " + row.get("group"), row -> row));
    }

    @SuppressWarnings("unchecked")
    private static long calls(Object row) {
        assertNotNull(row, "expected a row for this group");
        return (Long) ((Map<String, Object>) row).get("calls");
    }

    private static byte[] fixture(boolean csv, boolean json, boolean singleFile) {
        return fixture(Opcodes.V17, csv, json, singleFile);
    }

    /** The two merged readers, optionally beside the single-file loader the memo targets. */
    private static byte[] fixture(int version, boolean csv, boolean json, boolean singleFile) {
        ClassWriter writer = new ClassWriter(ClassWriter.COMPUTE_FRAMES | ClassWriter.COMPUTE_MAXS);
        writer.visit(version, Opcodes.ACC_PUBLIC, MergedReadProbePlan.TARGET_CLASS,
                null, "java/lang/Object", null);
        writer.visitField(Opcodes.ACC_PUBLIC | Opcodes.ACC_STATIC, "calls", "I", null, null)
                .visitEnd();
        if (csv) {
            counting(writer, MergedReadProbePlan.MERGED_METHOD, MergedReadProbePlan.CSV_DESCRIPTOR);
        }
        if (json) {
            counting(writer, MergedReadProbePlan.MERGED_METHOD, MergedReadProbePlan.JSON_DESCRIPTOR);
        }
        if (singleFile) {
            counting(writer, LoadJsonMemoPlan.LOAD_METHOD, LoadJsonMemoPlan.LOAD_DESCRIPTOR);
        }
        writer.visitEnd();
        return writer.toByteArray();
    }

    /** A stand-in read: it counts itself so a test can tell vanilla ran, and returns null. */
    private static void counting(ClassWriter writer, String name, String descriptor) {
        MethodVisitor method = writer.visitMethod(
                Opcodes.ACC_PUBLIC | Opcodes.ACC_STATIC, name, descriptor, null, null);
        method.visitCode();
        method.visitFieldInsn(Opcodes.GETSTATIC, MergedReadProbePlan.TARGET_CLASS, "calls", "I");
        method.visitInsn(Opcodes.ICONST_1);
        method.visitInsn(Opcodes.IADD);
        method.visitFieldInsn(Opcodes.PUTSTATIC, MergedReadProbePlan.TARGET_CLASS, "calls", "I");
        method.visitInsn(Opcodes.ACONST_NULL);
        method.visitInsn(Opcodes.ARETURN);
        method.visitMaxs(0, 0);
        method.visitEnd();
    }

    private static final class ByteArrayLoader extends ClassLoader {
        private ByteArrayLoader() {
            super(MergedReadProbePlanTest.class.getClassLoader());
        }

        private Class<?> define(byte[] bytes) {
            return defineClass(null, bytes, 0, bytes.length);
        }
    }
}
