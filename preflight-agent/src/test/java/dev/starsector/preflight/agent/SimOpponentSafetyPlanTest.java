package dev.starsector.preflight.agent;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.MethodVisitor;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.InsnNode;
import org.objectweb.asm.tree.MethodInsnNode;
import org.objectweb.asm.tree.MethodNode;

public class SimOpponentSafetyPlanTest {
    private static final String HULL_VARIANT =
            "com/fs/starfarer/loading/specs/HullVariantSpec";
    private static final String FIGHTER_WING =
            "com/fs/starfarer/loading/specs/FighterWingSpec";
    private static final String FAKE_STORE =
            "dev/starsector/preflight/agent/FakeSpecStore";

    @BeforeEach
    @AfterEach
    void reset() {
        System.clearProperty(SimOpponentSafetyRuntime.DISABLED_PROPERTY);
        SimOpponentSafetyRuntime.beginSession();
    }

    @Test
    void exactSimulatorFiltersBothOpponentListConsumptionSites() {
        byte[] transformed = SimOpponentSafetyPlan.transform(signature(), fixture(2));
        assertNotNull(transformed);
        MethodNode simulation = method(read(transformed), SimOpponentSafetyPlan.SIMULATION_METHOD);
        assertNotNull(simulation);
        assertEquals(2, calls(simulation,
                SimOpponentSafetyRuntime.class.getName().replace('.', '/'), "filter"));
        assertEquals(4, calls(simulation,
                SimOpponentSafetyRuntime.class.getName().replace('.', '/'), "recordAdded"));
        assertEquals(1, calls(simulation,
                SimOpponentSafetyRuntime.class.getName().replace('.', '/'), "recordMission"));
        MethodNode launch = method(read(transformed), SimOpponentSafetyPlan.LAUNCH_METHOD);
        assertNotNull(launch);
        assertEquals(1, calls(launch,
                SimOpponentSafetyRuntime.class.getName().replace('.', '/'), "recordMission"));
    }

    @Test
    void wrongHashChangedShapeAndSecondRewriteFailClosed() {
        ClassSignature exact = signature();
        assertNull(SimOpponentSafetyPlan.transform(new ClassSignature(
                exact.internalName(), "0".repeat(64), exact.majorVersion(), exact.access(),
                exact.methods()), fixture(2)));
        assertNull(SimOpponentSafetyPlan.transform(exact, fixture(1)));
        byte[] once = SimOpponentSafetyPlan.transform(exact, fixture(2));
        assertNotNull(once);
        assertNull(SimOpponentSafetyPlan.transform(exact, once));
    }

    @Test
    void starsectorRegistryDecidesShipsAndWingsAndOnlyInvalidRowsAreCopiedOut() throws Exception {
        SimOpponentSafetyRuntime.installed();
        Class<?> fakeStore = fakeStore();
        List<String> source = new ArrayList<>(List.of(
                "good_ship", "missing_ship", "good_wing", "missing_wing"));

        List<?> filtered = SimOpponentSafetyRuntime.filter(source, fakeStore);

        assertEquals(List.of("good_ship", "good_wing"), filtered);
        assertEquals(List.of("good_ship", "missing_ship", "good_wing", "missing_wing"), source,
                "the game's shared merged list must not be mutated");
        Map<String, Object> report = SimOpponentSafetyRuntime.telemetry();
        assertEquals(2L, report.get("removed"));
        assertEquals(Map.of("missing_ship", 1L, "missing_wing", 1L),
                report.get("invalidVariantIds"));

        List<String> allValid = List.of("good_ship", "good_wing");
        assertSame(allValid, SimOpponentSafetyRuntime.filter(allValid, fakeStore),
                "an entirely valid list preserves shipped identity and allocation behavior");
    }

    @Test
    void unavailableRegistryFailsOpenToTheExactOriginalList() {
        SimOpponentSafetyRuntime.installed();
        List<String> source = List.of("anything");
        assertSame(source, SimOpponentSafetyRuntime.filter(source, Object.class));
        assertEquals(1L, SimOpponentSafetyRuntime.telemetry().get("failOpen"));
    }

    @Test
    void reflectedFatalErrorsAreNotSwallowed() throws Exception {
        SimOpponentSafetyRuntime.installed();
        assertThrows(VirtualMachineError.class,
                () -> SimOpponentSafetyRuntime.filter(List.of("fatal_ship"), fakeStore()));
        assertEquals(0L, SimOpponentSafetyRuntime.telemetry().get("failOpen"));
    }

    @Test
    void manualKillSwitchIsInert() throws Exception {
        SimOpponentSafetyRuntime.installed();
        System.setProperty(SimOpponentSafetyRuntime.DISABLED_PROPERTY, "true");
        List<String> source = List.of("missing_ship");
        assertSame(source, SimOpponentSafetyRuntime.filter(source, fakeStore()));
        assertEquals(0L, SimOpponentSafetyRuntime.telemetry().get("calls"));
    }

    @Test
    void targetBindsTheExactCoreArchiveAndAppLoader() {
        AdapterTarget target = AdapterTargetRegistry.simOpponentSafetyTarget();
        AdapterSourceIdentity source = new AdapterSourceIdentity(
                "file:/game/Contents/Resources/Java/starfarer_obf.jar",
                "/game/contents/resources/java/starfarer_obf.jar",
                "STARSECTOR_CORE",
                target.sourceSha256(),
                "",
                "jdk/internal/loader/ClassLoaders$AppClassLoader",
                "app");
        assertTrue(target.match(signature(), source).exact());
    }

    public static boolean fakeExists(Class<?> type, String id) {
        if (id.startsWith("fatal")) {
            throw new OutOfMemoryError("synthetic fatal registry failure");
        }
        String expected = id.endsWith("_wing")
                ? FIGHTER_WING.replace('/', '.') : HULL_VARIANT.replace('/', '.');
        return expected.equals(type.getName()) && !id.startsWith("missing");
    }

    private static Class<?> fakeStore() throws Exception {
        FixtureLoader loader = new FixtureLoader(SimOpponentSafetyPlanTest.class.getClassLoader());
        loader.define(HULL_VARIANT.replace('/', '.'), emptyClass(HULL_VARIANT));
        loader.define(FIGHTER_WING.replace('/', '.'), emptyClass(FIGHTER_WING));
        return loader.define(FAKE_STORE.replace('/', '.'), fakeStoreClass());
    }

    private static byte[] emptyClass(String name) {
        ClassWriter writer = new ClassWriter(0);
        writer.visit(Opcodes.V17, Opcodes.ACC_PUBLIC, name, null, "java/lang/Object", null);
        writer.visitEnd();
        return writer.toByteArray();
    }

    private static byte[] fakeStoreClass() {
        ClassWriter writer = new ClassWriter(ClassWriter.COMPUTE_FRAMES | ClassWriter.COMPUTE_MAXS);
        writer.visit(Opcodes.V17, Opcodes.ACC_PUBLIC, FAKE_STORE, null, "java/lang/Object", null);
        MethodVisitor method = writer.visitMethod(
                Opcodes.ACC_PUBLIC | Opcodes.ACC_STATIC,
                "new", "(Ljava/lang/Class;Ljava/lang/String;)Z", null, null);
        method.visitCode();
        method.visitVarInsn(Opcodes.ALOAD, 0);
        method.visitVarInsn(Opcodes.ALOAD, 1);
        method.visitMethodInsn(Opcodes.INVOKESTATIC,
                SimOpponentSafetyPlanTest.class.getName().replace('.', '/'),
                "fakeExists", "(Ljava/lang/Class;Ljava/lang/String;)Z", false);
        method.visitInsn(Opcodes.IRETURN);
        method.visitMaxs(0, 0);
        method.visitEnd();
        writer.visitEnd();
        return writer.toByteArray();
    }

    private static ClassSignature signature() {
        return new ClassSignature(
                SimOpponentSafetyPlan.TARGET_CLASS,
                SimOpponentSafetyPlan.ORIGINAL_SHA256,
                61,
                Opcodes.ACC_PUBLIC,
                List.of(new ClassSignature.Method(
                        SimOpponentSafetyPlan.SIMULATION_METHOD,
                        SimOpponentSafetyPlan.SIMULATION_DESCRIPTOR,
                        Opcodes.ACC_PRIVATE),
                        new ClassSignature.Method(
                                SimOpponentSafetyPlan.LAUNCH_METHOD,
                                SimOpponentSafetyPlan.LAUNCH_DESCRIPTOR,
                                Opcodes.ACC_PRIVATE)));
    }

    private static byte[] fixture(int opponentCalls) {
        ClassWriter writer = new ClassWriter(ClassWriter.COMPUTE_FRAMES | ClassWriter.COMPUTE_MAXS);
        writer.visit(Opcodes.V17, Opcodes.ACC_PUBLIC, SimOpponentSafetyPlan.TARGET_CLASS,
                null, "java/lang/Object", null);
        MethodNode simulation = new MethodNode(Opcodes.ACC_PRIVATE,
                SimOpponentSafetyPlan.SIMULATION_METHOD,
                SimOpponentSafetyPlan.SIMULATION_DESCRIPTOR, null, null);
        for (int i = 0; i < opponentCalls; i++) {
            simulation.instructions.add(new MethodInsnNode(
                    Opcodes.INVOKESTATIC,
                    "com/fs/starfarer/loading/SpecStore",
                    "o00000", "()Ljava/util/List;", false));
            simulation.instructions.add(new InsnNode(Opcodes.POP));
        }
        for (int i = 0; i < 4; i++) {
            simulation.instructions.add(new InsnNode(Opcodes.ACONST_NULL));
            simulation.instructions.add(new InsnNode(Opcodes.ACONST_NULL));
            simulation.instructions.add(new InsnNode(Opcodes.ACONST_NULL));
            simulation.instructions.add(new InsnNode(Opcodes.ACONST_NULL));
            simulation.instructions.add(new InsnNode(Opcodes.ICONST_0));
            simulation.instructions.add(new MethodInsnNode(
                    Opcodes.INVOKEVIRTUAL,
                    "com/fs/starfarer/title/C/OO0O",
                    "addToFleet",
                    "(Lcom/fs/starfarer/api/mission/FleetSide;Ljava/lang/String;"
                            + "Lcom/fs/starfarer/api/fleet/FleetMemberType;Z)"
                            + "Lcom/fs/starfarer/api/fleet/FleetMemberAPI;",
                    false));
            simulation.instructions.add(new InsnNode(Opcodes.POP));
        }
        simulation.instructions.add(new InsnNode(Opcodes.ACONST_NULL));
        simulation.instructions.add(new InsnNode(Opcodes.ARETURN));
        simulation.accept(writer);
        MethodNode launch = new MethodNode(Opcodes.ACC_PRIVATE,
                SimOpponentSafetyPlan.LAUNCH_METHOD,
                SimOpponentSafetyPlan.LAUNCH_DESCRIPTOR, null, null);
        launch.instructions.add(new InsnNode(Opcodes.ACONST_NULL));
        launch.instructions.add(new MethodInsnNode(
                Opcodes.INVOKEVIRTUAL,
                "com/fs/starfarer/title/C/OO0O", "load", "()V", false));
        launch.instructions.add(new InsnNode(Opcodes.RETURN));
        launch.accept(writer);
        writer.visitEnd();
        return writer.toByteArray();
    }

    private static ClassNode read(byte[] bytes) {
        ClassNode node = new ClassNode(Opcodes.ASM9);
        new ClassReader(bytes).accept(node, ClassReader.EXPAND_FRAMES);
        return node;
    }

    private static MethodNode method(ClassNode owner, String name) {
        return owner.methods.stream().filter(value -> name.equals(value.name)).findFirst().orElse(null);
    }

    private static int calls(MethodNode method, String owner, String name) {
        int count = 0;
        for (var instruction : method.instructions) {
            if (instruction instanceof MethodInsnNode call
                    && owner.equals(call.owner) && name.equals(call.name)) {
                count++;
            }
        }
        return count;
    }

    private static final class FixtureLoader extends ClassLoader {
        private FixtureLoader(ClassLoader parent) {
            super(parent);
        }

        private Class<?> define(String name, byte[] bytes) {
            return defineClass(name, bytes, 0, bytes.length);
        }
    }
}
