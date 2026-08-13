package dev.starsector.preflight.agent;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;

import java.util.List;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.InsnNode;
import org.objectweb.asm.tree.MethodInsnNode;
import org.objectweb.asm.tree.MethodNode;

class SimOpponentDialogProbePlanTest {
    @AfterEach
    void reset() {
        SimOpponentSafetyRuntime.beginSession();
    }

    @Test
    void observesTheCompletedReserveGridAndConstructor() {
        byte[] transformed = SimOpponentDialogProbePlan.transform(signature(), fixture(1, 1, 1, 1));
        assertNotNull(transformed);
        ClassNode owner = read(transformed);
        assertEquals(1, recordCalls(method(owner, SimOpponentDialogProbePlan.GRID_METHOD)));
        assertEquals(1, recordCalls(method(owner, SimOpponentDialogProbePlan.LAYOUT_METHOD)));
        assertEquals(1, recordCalls(method(owner, SimOpponentDialogProbePlan.ADVANCE_METHOD)));
        assertEquals(1, updateRecordCalls(method(owner, SimOpponentDialogProbePlan.UPDATE_METHOD)));
    }

    @Test
    void wrongHashChangedShapeAndSecondRewriteFailClosed() {
        ClassSignature exact = signature();
        assertNull(SimOpponentDialogProbePlan.transform(new ClassSignature(
                exact.internalName(), "0".repeat(64), exact.majorVersion(), exact.access(),
                exact.methods()), fixture(1, 1, 1, 1)));
        assertNull(SimOpponentDialogProbePlan.transform(exact, fixture(2, 1, 1, 1)));
        assertNull(SimOpponentDialogProbePlan.transform(exact, fixture(1, 2, 1, 1)));
        assertNull(SimOpponentDialogProbePlan.transform(exact, fixture(1, 1, 2, 1)));
        byte[] once = SimOpponentDialogProbePlan.transform(exact, fixture(1, 1, 1, 1));
        assertNotNull(once);
        assertNull(SimOpponentDialogProbePlan.transform(exact, once));
    }

    @Test
    void nullDialogObservationIsContainedAndReported() {
        SimOpponentSafetyRuntime.recordDialog(null, 0);
        var telemetry = SimOpponentSafetyRuntime.telemetry();
        assertEquals(1L, telemetry.get("dialogObservations"));
        assertEquals(1L, telemetry.get("dialogGridBuilds"));
        assertEquals(1L, telemetry.get("dialogInspectionFailures"));
    }

    private static ClassSignature signature() {
        return new ClassSignature(
                SimOpponentDialogProbePlan.TARGET_CLASS,
                SimOpponentDialogProbePlan.ORIGINAL_SHA256,
                61,
                Opcodes.ACC_PUBLIC,
                List.of(new ClassSignature.Method(
                        SimOpponentDialogProbePlan.GRID_METHOD,
                        SimOpponentDialogProbePlan.GRID_DESCRIPTOR,
                        Opcodes.ACC_PRIVATE),
                        new ClassSignature.Method(
                                SimOpponentDialogProbePlan.LAYOUT_METHOD,
                                SimOpponentDialogProbePlan.LAYOUT_DESCRIPTOR,
                                Opcodes.ACC_PRIVATE),
                        new ClassSignature.Method(
                                SimOpponentDialogProbePlan.ADVANCE_METHOD,
                                SimOpponentDialogProbePlan.ADVANCE_DESCRIPTOR,
                                Opcodes.ACC_PUBLIC),
                        new ClassSignature.Method(
                                SimOpponentDialogProbePlan.UPDATE_METHOD,
                                SimOpponentDialogProbePlan.UPDATE_DESCRIPTOR,
                                Opcodes.ACC_PUBLIC)));
    }

    private static byte[] fixture(
            int gridReturns, int layoutReturns, int advanceReturns, int updateReturns) {
        ClassWriter writer = new ClassWriter(0);
        writer.visit(Opcodes.V17, Opcodes.ACC_PUBLIC,
                SimOpponentDialogProbePlan.TARGET_CLASS, null, "java/lang/Object", null);
        MethodNode grid = new MethodNode(Opcodes.ACC_PRIVATE,
                SimOpponentDialogProbePlan.GRID_METHOD,
                SimOpponentDialogProbePlan.GRID_DESCRIPTOR, null, null);
        for (int index = 0; index < gridReturns; index++) {
            grid.instructions.add(new InsnNode(Opcodes.RETURN));
        }
        grid.accept(writer);
        MethodNode layout = new MethodNode(Opcodes.ACC_PRIVATE,
                SimOpponentDialogProbePlan.LAYOUT_METHOD,
                SimOpponentDialogProbePlan.LAYOUT_DESCRIPTOR, null, null);
        for (int index = 0; index < layoutReturns; index++) {
            layout.instructions.add(new InsnNode(Opcodes.RETURN));
        }
        layout.accept(writer);
        MethodNode advance = new MethodNode(Opcodes.ACC_PUBLIC,
                SimOpponentDialogProbePlan.ADVANCE_METHOD,
                SimOpponentDialogProbePlan.ADVANCE_DESCRIPTOR, null, null);
        for (int index = 0; index < advanceReturns; index++) {
            advance.instructions.add(new InsnNode(Opcodes.RETURN));
        }
        advance.accept(writer);
        MethodNode update = new MethodNode(Opcodes.ACC_PUBLIC,
                SimOpponentDialogProbePlan.UPDATE_METHOD,
                SimOpponentDialogProbePlan.UPDATE_DESCRIPTOR, null, null);
        for (int index = 0; index < updateReturns; index++) {
            update.instructions.add(new InsnNode(Opcodes.RETURN));
        }
        update.accept(writer);
        writer.visitEnd();
        return writer.toByteArray();
    }

    private static ClassNode read(byte[] bytes) {
        ClassNode owner = new ClassNode(Opcodes.ASM9);
        new ClassReader(bytes).accept(owner, ClassReader.EXPAND_FRAMES);
        return owner;
    }

    private static MethodNode method(ClassNode owner, String name) {
        return owner.methods.stream().filter(value -> name.equals(value.name)).findFirst().orElseThrow();
    }

    private static int recordCalls(MethodNode method) {
        int count = 0;
        for (var instruction : method.instructions) {
            if (instruction instanceof MethodInsnNode call
                    && SimOpponentSafetyRuntime.class.getName().replace('.', '/').equals(call.owner)
                    && "recordDialog".equals(call.name)) {
                count++;
            }
        }
        return count;
    }

    private static int updateRecordCalls(MethodNode method) {
        int count = 0;
        for (var instruction : method.instructions) {
            if (instruction instanceof MethodInsnNode call
                    && SimOpponentSafetyRuntime.class.getName().replace('.', '/').equals(call.owner)
                    && "recordCategoryUpdate".equals(call.name)) {
                count++;
            }
        }
        return count;
    }
}
