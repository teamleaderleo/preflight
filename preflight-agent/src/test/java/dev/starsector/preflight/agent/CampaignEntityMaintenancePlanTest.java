package dev.starsector.preflight.agent;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;

import java.lang.reflect.Proxy;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.MethodVisitor;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.tree.AbstractInsnNode;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.MethodInsnNode;
import org.objectweb.asm.tree.MethodNode;

class CampaignEntityMaintenancePlanTest {
    @BeforeEach
    void enable() {
        CampaignEntityMaintenanceRuntime.beginSession();
    }

    @AfterEach
    void reset() {
        System.clearProperty(CampaignEntityMaintenanceRuntime.DISABLED_PROPERTY);
        CampaignEntityMaintenanceRuntime.beginSession();
    }

    @Test
    void entityWrapperSkipsEmptyListsAndRunsTheUnchangedNonEmptyPath() throws Exception {
        byte[] original = entityFixture();
        byte[] transformed = CampaignEntityMaintenancePlan.transform(
                exact(original, CampaignEntityMaintenancePlan.ENTITY_SHA256), original);
        assertNotNull(transformed);
        assertNotNull(method(transformed, CampaignEntityMaintenancePlan.SCRIPT_METHOD));
        assertNotNull(method(transformed, "preflight$original$runScripts"));
        assertNull(CampaignEntityMaintenancePlan.transform(
                ClassSignature.parse(transformed), transformed));

        ByteArrayLoader loader = new ByteArrayLoader(Map.of(
                CampaignEntityMaintenancePlan.ENTITY_CLASS.replace('/', '.'), transformed,
                "com.fs.starfarer.api.EveryFrameScript", scriptFixture()));
        Class<?> scriptType = loader.loadClass("com.fs.starfarer.api.EveryFrameScript");
        Class<?> entityType = loader.loadClass(
                CampaignEntityMaintenancePlan.ENTITY_CLASS.replace('/', '.'));
        Object entity = entityType.getConstructor().newInstance();
        var advance = entityType.getDeclaredMethod(
                CampaignEntityMaintenancePlan.SCRIPT_METHOD, float.class);
        advance.setAccessible(true);

        advance.invoke(entity, 1f);
        AtomicInteger calls = new AtomicInteger();
        Object script = Proxy.newProxyInstance(loader, new Class<?>[] {scriptType},
                (proxy, method, arguments) -> {
                    if ("advance".equals(method.getName())) calls.incrementAndGet();
                    return null;
                });
        var scripts = entityType.getDeclaredField("scripts");
        scripts.setAccessible(true);
        @SuppressWarnings("unchecked")
        ArrayList<Object> values = (ArrayList<Object>) scripts.get(entity);
        values.add(script);
        advance.invoke(entity, 1f);

        assertEquals(1, calls.get());
        assertEquals(1L, CampaignEntityMaintenanceRuntime.telemetry().get("emptyScriptLists"));
        assertEquals(1L, CampaignEntityMaintenanceRuntime.telemetry().get("nonEmptyScriptLists"));
    }

    @Test
    void fleetViewReusesFirstSnapshotAndDisabledRuntimeDeclines() throws Exception {
        byte[] original = fleetViewFixture();
        byte[] transformed = CampaignEntityMaintenancePlan.transform(
                exact(original, CampaignEntityMaintenancePlan.FLEET_VIEW_SHA256), original);
        assertNotNull(transformed);
        assertEquals(1, calls(method(transformed, CampaignEntityMaintenancePlan.ADVANCE_METHOD),
                "com/fs/starfarer/campaign/fleet/CampaignFleet", "getSortedMembers"));

        System.setProperty(CampaignEntityMaintenanceRuntime.DISABLED_PROPERTY, "true");
        CampaignEntityMaintenanceRuntime.beginSession();
        assertNull(CampaignEntityMaintenancePlan.transform(
                exact(original, CampaignEntityMaintenancePlan.FLEET_VIEW_SHA256), original));
    }

    @Test
    void marketUsesCompactStableSnapshotsForBothPluginLists() throws Exception {
        byte[] original = marketFixture();
        byte[] transformed = CampaignEntityMaintenancePlan.transform(
                exact(original, CampaignEntityMaintenancePlan.MARKET_SHA256), original);
        assertNotNull(transformed);
        MethodNode advance = method(transformed, CampaignEntityMaintenancePlan.ADVANCE_METHOD);
        assertEquals(2, calls(advance,
                CampaignEntityMaintenanceRuntime.class.getName().replace('.', '/'),
                "marketSnapshotIterator"));
        assertEquals(0, calls(advance, "java/util/ArrayList", "<init>"));
        assertNull(CampaignEntityMaintenancePlan.transform(
                ClassSignature.parse(transformed), transformed));

        ByteArrayLoader loader = new ByteArrayLoader(Map.of(
                CampaignEntityMaintenancePlan.MARKET_CLASS.replace('/', '.'), transformed));
        Class<?> marketType = loader.loadClass(
                CampaignEntityMaintenancePlan.MARKET_CLASS.replace('/', '.'));
        Object market = marketType.getConstructor().newInstance();
        var method = marketType.getMethod(CampaignEntityMaintenancePlan.ADVANCE_METHOD, float.class);
        method.invoke(market, 1f);
        addToList(marketType, market, "conditions", new Object());
        addToList(marketType, market, "industries", new Object());
        method.invoke(market, 1f);

        Map<String, Object> telemetry = CampaignEntityMaintenanceRuntime.telemetry();
        assertEquals(1L, telemetry.get("emptyMarketConditions"));
        assertEquals(1L, telemetry.get("nonEmptyMarketConditions"));
        assertEquals(1L, telemetry.get("emptyMarketIndustries"));
        assertEquals(1L, telemetry.get("nonEmptyMarketIndustries"));
    }

    @Test
    void nonEmptyMarketIteratorRetainsVanillaSnapshotSemantics() {
        List<String> source = new ArrayList<>(List.of("first"));
        Iterator<?> snapshot = CampaignEntityMaintenanceRuntime.marketSnapshotIterator(
                source, CampaignEntityMaintenanceRuntime.MARKET_CONDITIONS);
        source.add("second");
        assertEquals("first", snapshot.next());
        assertFalse(snapshot.hasNext());

        Iterator<?> empty = CampaignEntityMaintenanceRuntime.marketSnapshotIterator(
                List.of(), CampaignEntityMaintenanceRuntime.MARKET_INDUSTRIES);
        assertFalse(empty.hasNext());

        Iterator<?> paused = CampaignEntityMaintenanceRuntime.marketSnapshotIterator(
                List.of("condition"), CampaignEntityMaintenanceRuntime.PAUSED_MARKET_CONDITIONS);
        assertEquals("condition", paused.next());
        assertEquals(1L, CampaignEntityMaintenanceRuntime.telemetry()
                .get("nonEmptyPausedMarketConditions"));
    }

    @Test
    void pausedEconomyConditionsUseCompactStableSnapshots() throws Exception {
        byte[] original = economyFixture();
        byte[] transformed = CampaignEntityMaintenancePlan.transform(
                exact(original, CampaignEntityMaintenancePlan.ECONOMY_SHA256), original);
        assertNotNull(transformed);
        MethodNode method = method(
                transformed, CampaignEntityMaintenancePlan.PAUSED_CONDITIONS_METHOD);
        assertEquals(1, calls(method,
                CampaignEntityMaintenanceRuntime.class.getName().replace('.', '/'),
                "marketSnapshotIterator"));
        assertEquals(0, calls(method, "java/util/ArrayList", "<init>"));
        assertNull(CampaignEntityMaintenancePlan.transform(
                ClassSignature.parse(transformed), transformed));
    }

    @Test
    void locationSnapshotsRetainPassStructureWithoutArrayListWrappers() throws Exception {
        byte[] original = locationFixture();
        byte[] transformed = CampaignEntityMaintenancePlan.transform(
                exact(original, CampaignEntityMaintenancePlan.LOCATION_SHA256), original);
        assertNotNull(transformed);
        MethodNode method = method(
                transformed, CampaignEntityMaintenancePlan.PAUSED_LOCATION_METHOD);
        String runtime = CampaignEntityMaintenanceRuntime.class.getName().replace('.', '/');
        assertEquals(2, calls(method, runtime, "locationSnapshot"));
        assertEquals(3, calls(method, runtime, "locationSnapshotIterator"));
        assertEquals(0, calls(method, "java/util/ArrayList", "<init>"));
        MethodNode active = method(
                transformed, CampaignEntityMaintenancePlan.ACTIVE_LOCATION_METHOD);
        assertEquals(3, calls(active, runtime, "locationSnapshot"));
        assertEquals(3, calls(active, runtime, "locationSnapshotIterator"));
        assertEquals(0, calls(active, "java/util/ArrayList", "<init>"));
        assertNull(CampaignEntityMaintenancePlan.transform(
                ClassSignature.parse(transformed), transformed));

        List<String> source = new ArrayList<>(List.of("first"));
        Object[] snapshot = CampaignEntityMaintenanceRuntime.locationSnapshot(
                source, CampaignEntityMaintenanceRuntime.PAUSED_LOCATION_ENTITIES);
        source.add("second");
        Iterator<?> firstPass = CampaignEntityMaintenanceRuntime.locationSnapshotIterator(snapshot);
        Iterator<?> secondPass = CampaignEntityMaintenanceRuntime.locationSnapshotIterator(snapshot);
        assertEquals("first", firstPass.next());
        assertEquals("first", secondPass.next());
        assertFalse(firstPass.hasNext());
        assertFalse(secondPass.hasNext());

        Object[] empty = CampaignEntityMaintenanceRuntime.locationSnapshot(
                List.of(), CampaignEntityMaintenanceRuntime.PAUSED_LOCATION_SCRIPTS);
        assertFalse(CampaignEntityMaintenanceRuntime.locationSnapshotIterator(empty).hasNext());
        Map<String, Object> telemetry = CampaignEntityMaintenanceRuntime.telemetry();
        assertEquals(true, telemetry.get("pausedLocationSnapshotsInstalled"));
        assertEquals(true, telemetry.get("activeLocationSnapshotsInstalled"));
        assertEquals(1L, telemetry.get("nonEmptyPausedLocationEntities"));
        assertEquals(1L, telemetry.get("emptyPausedLocationScripts"));
    }

    @Test
    void memorySkipsEmptyIteratorsAndRetainsNonEmptyLoops() throws Exception {
        byte[] original = memoryFixture();
        byte[] transformed = CampaignEntityMaintenancePlan.transform(
                exact(original, CampaignEntityMaintenancePlan.MEMORY_SHA256), original);
        assertNotNull(transformed);
        MethodNode advance = method(transformed, CampaignEntityMaintenancePlan.ADVANCE_METHOD);
        String runtime = CampaignEntityMaintenanceRuntime.class.getName().replace('.', '/');
        assertEquals(1, calls(advance, runtime, "memoryExpirationsPresent"));
        assertEquals(1, calls(advance, runtime, "memoryRequirementsPresent"));
        assertNull(CampaignEntityMaintenancePlan.transform(
                ClassSignature.parse(transformed), transformed));

        ByteArrayLoader loader = new ByteArrayLoader(Map.of(
                CampaignEntityMaintenancePlan.MEMORY_CLASS.replace('/', '.'), transformed));
        Class<?> memoryType = loader.loadClass(
                CampaignEntityMaintenancePlan.MEMORY_CLASS.replace('/', '.'));
        Object memory = memoryType.getConstructor().newInstance();
        var method = memoryType.getMethod(CampaignEntityMaintenancePlan.ADVANCE_METHOD, float.class);
        method.invoke(memory, 1f);
        addToList(memoryType, memory, "expire", new Object());
        addToMap(memoryType, memory, "require", "key", new Object());
        method.invoke(memory, 1f);

        Map<String, Object> telemetry = CampaignEntityMaintenanceRuntime.telemetry();
        assertEquals(true, telemetry.get("memoryInstalled"));
        assertEquals(1L, telemetry.get("emptyMemoryExpirations"));
        assertEquals(1L, telemetry.get("nonEmptyMemoryExpirations"));
        assertEquals(1L, telemetry.get("emptyMemoryRequirements"));
        assertEquals(1L, telemetry.get("nonEmptyMemoryRequirements"));
    }

    private static byte[] entityFixture() {
        ClassWriter writer = new ClassWriter(0);
        writer.visit(Opcodes.V17, Opcodes.ACC_PUBLIC,
                CampaignEntityMaintenancePlan.ENTITY_CLASS, null, "java/lang/Object", null);
        writer.visitField(Opcodes.ACC_PRIVATE, "scripts", "Ljava/util/List;", null, null).visitEnd();
        MethodVisitor constructor = writer.visitMethod(
                Opcodes.ACC_PUBLIC, "<init>", "()V", null, null);
        constructor.visitCode();
        constructor.visitVarInsn(Opcodes.ALOAD, 0);
        constructor.visitMethodInsn(Opcodes.INVOKESPECIAL,
                "java/lang/Object", "<init>", "()V", false);
        constructor.visitVarInsn(Opcodes.ALOAD, 0);
        constructor.visitTypeInsn(Opcodes.NEW, "java/util/ArrayList");
        constructor.visitInsn(Opcodes.DUP);
        constructor.visitMethodInsn(Opcodes.INVOKESPECIAL,
                "java/util/ArrayList", "<init>", "()V", false);
        constructor.visitFieldInsn(Opcodes.PUTFIELD, CampaignEntityMaintenancePlan.ENTITY_CLASS,
                "scripts", "Ljava/util/List;");
        constructor.visitInsn(Opcodes.RETURN);
        constructor.visitMaxs(3, 1);
        constructor.visitEnd();
        MethodVisitor method = writer.visitMethod(Opcodes.ACC_PRIVATE,
                CampaignEntityMaintenancePlan.SCRIPT_METHOD,
                CampaignEntityMaintenancePlan.SCRIPT_DESCRIPTOR, null, null);
        method.visitCode();
        method.visitTypeInsn(Opcodes.NEW, "java/util/ArrayList");
        method.visitInsn(Opcodes.DUP);
        method.visitVarInsn(Opcodes.ALOAD, 0);
        method.visitFieldInsn(Opcodes.GETFIELD, CampaignEntityMaintenancePlan.ENTITY_CLASS,
                "scripts", "Ljava/util/List;");
        method.visitMethodInsn(Opcodes.INVOKESPECIAL, "java/util/ArrayList", "<init>",
                "(Ljava/util/Collection;)V", false);
        method.visitInsn(Opcodes.POP);
        method.visitVarInsn(Opcodes.ALOAD, 0);
        method.visitFieldInsn(Opcodes.GETFIELD, CampaignEntityMaintenancePlan.ENTITY_CLASS,
                "scripts", "Ljava/util/List;");
        method.visitInsn(Opcodes.POP);
        method.visitVarInsn(Opcodes.ALOAD, 0);
        method.visitFieldInsn(Opcodes.GETFIELD, CampaignEntityMaintenancePlan.ENTITY_CLASS,
                "scripts", "Ljava/util/List;");
        method.visitInsn(Opcodes.ICONST_0);
        method.visitMethodInsn(Opcodes.INVOKEINTERFACE,
                "java/util/List", "get", "(I)Ljava/lang/Object;", true);
        method.visitTypeInsn(Opcodes.CHECKCAST, "com/fs/starfarer/api/EveryFrameScript");
        method.visitVarInsn(Opcodes.FLOAD, 1);
        method.visitMethodInsn(Opcodes.INVOKEINTERFACE,
                "com/fs/starfarer/api/EveryFrameScript", "advance", "(F)V", true);
        method.visitInsn(Opcodes.RETURN);
        method.visitMaxs(4, 2);
        method.visitEnd();
        writer.visitEnd();
        return writer.toByteArray();
    }

    private static byte[] scriptFixture() {
        ClassWriter writer = new ClassWriter(0);
        writer.visit(Opcodes.V17, Opcodes.ACC_PUBLIC | Opcodes.ACC_ABSTRACT | Opcodes.ACC_INTERFACE,
                "com/fs/starfarer/api/EveryFrameScript", null, "java/lang/Object", null);
        writer.visitMethod(Opcodes.ACC_PUBLIC | Opcodes.ACC_ABSTRACT,
                "advance", "(F)V", null, null).visitEnd();
        writer.visitEnd();
        return writer.toByteArray();
    }

    private static byte[] fleetViewFixture() {
        ClassWriter writer = new ClassWriter(0);
        writer.visit(Opcodes.V17, Opcodes.ACC_PUBLIC,
                CampaignEntityMaintenancePlan.FLEET_VIEW_CLASS, null, "java/lang/Object", null);
        writer.visitField(Opcodes.ACC_PRIVATE, "fleet",
                "Lcom/fs/starfarer/campaign/fleet/CampaignFleet;", null, null).visitEnd();
        MethodVisitor method = writer.visitMethod(Opcodes.ACC_PUBLIC,
                CampaignEntityMaintenancePlan.ADVANCE_METHOD,
                CampaignEntityMaintenancePlan.ADVANCE_DESCRIPTOR, null, null);
        method.visitCode();
        method.visitVarInsn(Opcodes.ALOAD, 0);
        method.visitFieldInsn(Opcodes.GETFIELD, CampaignEntityMaintenancePlan.FLEET_VIEW_CLASS,
                "fleet", "Lcom/fs/starfarer/campaign/fleet/CampaignFleet;");
        method.visitMethodInsn(Opcodes.INVOKEVIRTUAL,
                "com/fs/starfarer/campaign/fleet/CampaignFleet", "getSortedMembers",
                "()Ljava/util/List;", false);
        method.visitVarInsn(Opcodes.ASTORE, 3);
        method.visitVarInsn(Opcodes.ALOAD, 0);
        method.visitFieldInsn(Opcodes.GETFIELD, CampaignEntityMaintenancePlan.FLEET_VIEW_CLASS,
                "fleet", "Lcom/fs/starfarer/campaign/fleet/CampaignFleet;");
        method.visitMethodInsn(Opcodes.INVOKEVIRTUAL,
                "com/fs/starfarer/campaign/fleet/CampaignFleet", "getSortedMembers",
                "()Ljava/util/List;", false);
        method.visitMethodInsn(Opcodes.INVOKEINTERFACE,
                "java/util/List", "size", "()I", true);
        method.visitInsn(Opcodes.POP);
        method.visitInsn(Opcodes.RETURN);
        method.visitMaxs(2, 4);
        method.visitEnd();
        writer.visitEnd();
        return writer.toByteArray();
    }

    private static byte[] marketFixture() {
        ClassWriter writer = new ClassWriter(0);
        writer.visit(Opcodes.V17, Opcodes.ACC_PUBLIC,
                CampaignEntityMaintenancePlan.MARKET_CLASS, null, "java/lang/Object", null);
        writer.visitField(Opcodes.ACC_PRIVATE, "conditions", "Ljava/util/List;", null, null).visitEnd();
        writer.visitField(Opcodes.ACC_PRIVATE, "industries", "Ljava/util/List;", null, null).visitEnd();
        MethodVisitor constructor = writer.visitMethod(
                Opcodes.ACC_PUBLIC, "<init>", "()V", null, null);
        constructor.visitCode();
        constructor.visitVarInsn(Opcodes.ALOAD, 0);
        constructor.visitMethodInsn(Opcodes.INVOKESPECIAL,
                "java/lang/Object", "<init>", "()V", false);
        initializeList(constructor, "conditions");
        initializeList(constructor, "industries");
        constructor.visitInsn(Opcodes.RETURN);
        constructor.visitMaxs(3, 1);
        constructor.visitEnd();

        MethodVisitor getConditions = writer.visitMethod(Opcodes.ACC_PUBLIC,
                "getConditions", "()Ljava/util/List;", null, null);
        getConditions.visitCode();
        getConditions.visitVarInsn(Opcodes.ALOAD, 0);
        getConditions.visitFieldInsn(Opcodes.GETFIELD,
                CampaignEntityMaintenancePlan.MARKET_CLASS, "conditions", "Ljava/util/List;");
        getConditions.visitInsn(Opcodes.ARETURN);
        getConditions.visitMaxs(1, 1);
        getConditions.visitEnd();

        MethodVisitor advance = writer.visitMethod(Opcodes.ACC_PUBLIC,
                CampaignEntityMaintenancePlan.ADVANCE_METHOD,
                CampaignEntityMaintenancePlan.ADVANCE_DESCRIPTOR, null, null);
        advance.visitCode();
        advance.visitTypeInsn(Opcodes.NEW, "java/util/ArrayList");
        advance.visitInsn(Opcodes.DUP);
        advance.visitVarInsn(Opcodes.ALOAD, 0);
        advance.visitMethodInsn(Opcodes.INVOKEVIRTUAL,
                CampaignEntityMaintenancePlan.MARKET_CLASS,
                "getConditions", "()Ljava/util/List;", false);
        advance.visitMethodInsn(Opcodes.INVOKESPECIAL, "java/util/ArrayList", "<init>",
                "(Ljava/util/Collection;)V", false);
        advance.visitMethodInsn(Opcodes.INVOKEVIRTUAL, "java/util/ArrayList", "iterator",
                "()Ljava/util/Iterator;", false);
        advance.visitInsn(Opcodes.POP);
        advance.visitTypeInsn(Opcodes.NEW, "java/util/ArrayList");
        advance.visitInsn(Opcodes.DUP);
        advance.visitVarInsn(Opcodes.ALOAD, 0);
        advance.visitFieldInsn(Opcodes.GETFIELD,
                CampaignEntityMaintenancePlan.MARKET_CLASS, "industries", "Ljava/util/List;");
        advance.visitMethodInsn(Opcodes.INVOKESPECIAL, "java/util/ArrayList", "<init>",
                "(Ljava/util/Collection;)V", false);
        advance.visitMethodInsn(Opcodes.INVOKEVIRTUAL, "java/util/ArrayList", "iterator",
                "()Ljava/util/Iterator;", false);
        advance.visitInsn(Opcodes.POP);
        advance.visitInsn(Opcodes.RETURN);
        advance.visitMaxs(4, 2);
        advance.visitEnd();
        writer.visitEnd();
        return writer.toByteArray();
    }

    private static byte[] memoryFixture() {
        ClassWriter writer = new ClassWriter(0);
        writer.visit(Opcodes.V17, Opcodes.ACC_PUBLIC,
                CampaignEntityMaintenancePlan.MEMORY_CLASS, null, "java/lang/Object", null);
        writer.visitField(Opcodes.ACC_PRIVATE, "expire", "Ljava/util/List;", null, null).visitEnd();
        writer.visitField(Opcodes.ACC_PRIVATE, "require", "Ljava/util/LinkedHashMap;", null, null)
                .visitEnd();
        MethodVisitor constructor = writer.visitMethod(
                Opcodes.ACC_PUBLIC, "<init>", "()V", null, null);
        constructor.visitCode();
        constructor.visitVarInsn(Opcodes.ALOAD, 0);
        constructor.visitMethodInsn(Opcodes.INVOKESPECIAL,
                "java/lang/Object", "<init>", "()V", false);
        constructor.visitVarInsn(Opcodes.ALOAD, 0);
        constructor.visitTypeInsn(Opcodes.NEW, "java/util/ArrayList");
        constructor.visitInsn(Opcodes.DUP);
        constructor.visitMethodInsn(Opcodes.INVOKESPECIAL,
                "java/util/ArrayList", "<init>", "()V", false);
        constructor.visitFieldInsn(Opcodes.PUTFIELD,
                CampaignEntityMaintenancePlan.MEMORY_CLASS, "expire", "Ljava/util/List;");
        constructor.visitVarInsn(Opcodes.ALOAD, 0);
        constructor.visitTypeInsn(Opcodes.NEW, "java/util/LinkedHashMap");
        constructor.visitInsn(Opcodes.DUP);
        constructor.visitMethodInsn(Opcodes.INVOKESPECIAL,
                "java/util/LinkedHashMap", "<init>", "()V", false);
        constructor.visitFieldInsn(Opcodes.PUTFIELD,
                CampaignEntityMaintenancePlan.MEMORY_CLASS,
                "require", "Ljava/util/LinkedHashMap;");
        constructor.visitInsn(Opcodes.RETURN);
        constructor.visitMaxs(3, 1);
        constructor.visitEnd();

        MethodVisitor advance = writer.visitMethod(Opcodes.ACC_PUBLIC,
                CampaignEntityMaintenancePlan.ADVANCE_METHOD,
                CampaignEntityMaintenancePlan.ADVANCE_DESCRIPTOR, null, null);
        advance.visitCode();
        advance.visitVarInsn(Opcodes.ALOAD, 0);
        advance.visitFieldInsn(Opcodes.GETFIELD,
                CampaignEntityMaintenancePlan.MEMORY_CLASS, "expire", "Ljava/util/List;");
        advance.visitMethodInsn(Opcodes.INVOKEINTERFACE,
                "java/util/List", "iterator", "()Ljava/util/Iterator;", true);
        advance.visitVarInsn(Opcodes.ASTORE, 2);
        org.objectweb.asm.Label expireCheck = new org.objectweb.asm.Label();
        org.objectweb.asm.Label requireStart = new org.objectweb.asm.Label();
        advance.visitLabel(expireCheck);
        advance.visitVarInsn(Opcodes.ALOAD, 2);
        advance.visitMethodInsn(Opcodes.INVOKEINTERFACE,
                "java/util/Iterator", "hasNext", "()Z", true);
        advance.visitJumpInsn(Opcodes.IFEQ, requireStart);
        advance.visitVarInsn(Opcodes.ALOAD, 2);
        advance.visitMethodInsn(Opcodes.INVOKEINTERFACE,
                "java/util/Iterator", "next", "()Ljava/lang/Object;", true);
        advance.visitInsn(Opcodes.POP);
        advance.visitJumpInsn(Opcodes.GOTO, expireCheck);
        advance.visitLabel(requireStart);
        advance.visitVarInsn(Opcodes.ALOAD, 0);
        advance.visitFieldInsn(Opcodes.GETFIELD,
                CampaignEntityMaintenancePlan.MEMORY_CLASS,
                "require", "Ljava/util/LinkedHashMap;");
        advance.visitMethodInsn(Opcodes.INVOKEVIRTUAL,
                "java/util/LinkedHashMap", "values", "()Ljava/util/Collection;", false);
        advance.visitMethodInsn(Opcodes.INVOKEINTERFACE,
                "java/util/Collection", "iterator", "()Ljava/util/Iterator;", true);
        advance.visitVarInsn(Opcodes.ASTORE, 3);
        org.objectweb.asm.Label requireCheck = new org.objectweb.asm.Label();
        org.objectweb.asm.Label done = new org.objectweb.asm.Label();
        advance.visitLabel(requireCheck);
        advance.visitVarInsn(Opcodes.ALOAD, 3);
        advance.visitMethodInsn(Opcodes.INVOKEINTERFACE,
                "java/util/Iterator", "hasNext", "()Z", true);
        advance.visitJumpInsn(Opcodes.IFEQ, done);
        advance.visitVarInsn(Opcodes.ALOAD, 3);
        advance.visitMethodInsn(Opcodes.INVOKEINTERFACE,
                "java/util/Iterator", "next", "()Ljava/lang/Object;", true);
        advance.visitInsn(Opcodes.POP);
        advance.visitJumpInsn(Opcodes.GOTO, requireCheck);
        advance.visitLabel(done);
        advance.visitInsn(Opcodes.RETURN);
        advance.visitMaxs(1, 4);
        advance.visitEnd();
        writer.visitEnd();
        return writer.toByteArray();
    }

    private static byte[] economyFixture() {
        ClassWriter writer = new ClassWriter(0);
        writer.visit(Opcodes.V17, Opcodes.ACC_PUBLIC,
                CampaignEntityMaintenancePlan.ECONOMY_CLASS, null, "java/lang/Object", null);
        MethodVisitor method = writer.visitMethod(Opcodes.ACC_PUBLIC,
                CampaignEntityMaintenancePlan.PAUSED_CONDITIONS_METHOD,
                CampaignEntityMaintenancePlan.ADVANCE_DESCRIPTOR, null, null);
        method.visitCode();
        method.visitInsn(Opcodes.ACONST_NULL);
        method.visitVarInsn(Opcodes.ASTORE, 2);
        method.visitTypeInsn(Opcodes.NEW, "java/util/ArrayList");
        method.visitInsn(Opcodes.DUP);
        method.visitVarInsn(Opcodes.ALOAD, 2);
        method.visitMethodInsn(Opcodes.INVOKEINTERFACE,
                "com/fs/starfarer/api/campaign/econ/MarketAPI",
                "getConditions", "()Ljava/util/List;", true);
        method.visitMethodInsn(Opcodes.INVOKESPECIAL,
                "java/util/ArrayList", "<init>", "(Ljava/util/Collection;)V", false);
        method.visitMethodInsn(Opcodes.INVOKEVIRTUAL,
                "java/util/ArrayList", "iterator", "()Ljava/util/Iterator;", false);
        method.visitInsn(Opcodes.POP);
        method.visitInsn(Opcodes.RETURN);
        method.visitMaxs(4, 3);
        method.visitEnd();
        writer.visitEnd();
        return writer.toByteArray();
    }

    private static byte[] locationFixture() {
        ClassWriter writer = new ClassWriter(0);
        writer.visit(Opcodes.V17, Opcodes.ACC_PUBLIC,
                CampaignEntityMaintenancePlan.LOCATION_CLASS, null, "java/lang/Object", null);
        writer.visitField(Opcodes.ACC_PRIVATE, "objects",
                "Lcom/fs/util/container/repo/ObjectRepository;", null, null).visitEnd();
        writer.visitField(Opcodes.ACC_PRIVATE, "scripts", "Ljava/util/List;", null, null).visitEnd();
        MethodVisitor active = writer.visitMethod(Opcodes.ACC_PUBLIC,
                CampaignEntityMaintenancePlan.ACTIVE_LOCATION_METHOD,
                CampaignEntityMaintenancePlan.LOCATION_DESCRIPTOR, null, null);
        active.visitCode();
        emitRepositorySnapshot(active, "com/fs/starfarer/campaign/CampaignEntity", 4);
        emitRepositorySnapshot(active,
                CampaignEntityMaintenancePlan.LOCATION_CLASS + "$LocationToken", 5);
        active.visitVarInsn(Opcodes.ALOAD, 0);
        active.visitFieldInsn(Opcodes.GETFIELD, CampaignEntityMaintenancePlan.LOCATION_CLASS,
                "objects", "Lcom/fs/util/container/repo/ObjectRepository;");
        active.visitLdcInsn(org.objectweb.asm.Type.getObjectType(
                "com/fs/starfarer/campaign/BaseCampaignEntity"));
        active.visitMethodInsn(Opcodes.INVOKEVIRTUAL,
                "com/fs/util/container/repo/ObjectRepository", "getList",
                "(Ljava/lang/Class;)Ljava/util/List;", false);
        active.visitVarInsn(Opcodes.ASTORE, 10);
        active.visitTypeInsn(Opcodes.NEW, "java/util/ArrayList");
        active.visitInsn(Opcodes.DUP);
        active.visitVarInsn(Opcodes.ALOAD, 10);
        active.visitMethodInsn(Opcodes.INVOKESPECIAL, "java/util/ArrayList", "<init>",
                "(Ljava/util/Collection;)V", false);
        active.visitVarInsn(Opcodes.ASTORE, 10);
        active.visitVarInsn(Opcodes.ALOAD, 10);
        active.visitMethodInsn(Opcodes.INVOKEINTERFACE,
                "java/util/List", "iterator", "()Ljava/util/Iterator;", true);
        active.visitInsn(Opcodes.POP);
        active.visitInsn(Opcodes.RETURN);
        active.visitMaxs(4, 11);
        active.visitEnd();
        MethodVisitor method = writer.visitMethod(Opcodes.ACC_PUBLIC,
                CampaignEntityMaintenancePlan.PAUSED_LOCATION_METHOD,
                CampaignEntityMaintenancePlan.LOCATION_DESCRIPTOR, null, null);
        method.visitCode();
        method.visitTypeInsn(Opcodes.NEW, "java/util/ArrayList");
        method.visitInsn(Opcodes.DUP);
        method.visitVarInsn(Opcodes.ALOAD, 0);
        method.visitFieldInsn(Opcodes.GETFIELD, CampaignEntityMaintenancePlan.LOCATION_CLASS,
                "objects", "Lcom/fs/util/container/repo/ObjectRepository;");
        method.visitLdcInsn(org.objectweb.asm.Type.getObjectType(
                "com/fs/starfarer/campaign/CampaignEntity"));
        method.visitMethodInsn(Opcodes.INVOKEVIRTUAL,
                "com/fs/util/container/repo/ObjectRepository", "getList",
                "(Ljava/lang/Class;)Ljava/util/List;", false);
        method.visitMethodInsn(Opcodes.INVOKESPECIAL, "java/util/ArrayList", "<init>",
                "(Ljava/util/Collection;)V", false);
        method.visitVarInsn(Opcodes.ASTORE, 3);
        method.visitVarInsn(Opcodes.ALOAD, 3);
        method.visitMethodInsn(Opcodes.INVOKEINTERFACE,
                "java/util/List", "iterator", "()Ljava/util/Iterator;", true);
        method.visitInsn(Opcodes.POP);
        method.visitVarInsn(Opcodes.ALOAD, 3);
        method.visitMethodInsn(Opcodes.INVOKEINTERFACE,
                "java/util/List", "iterator", "()Ljava/util/Iterator;", true);
        method.visitInsn(Opcodes.POP);
        method.visitTypeInsn(Opcodes.NEW, "java/util/ArrayList");
        method.visitInsn(Opcodes.DUP);
        method.visitVarInsn(Opcodes.ALOAD, 0);
        method.visitFieldInsn(Opcodes.GETFIELD, CampaignEntityMaintenancePlan.LOCATION_CLASS,
                "scripts", "Ljava/util/List;");
        method.visitMethodInsn(Opcodes.INVOKESPECIAL, "java/util/ArrayList", "<init>",
                "(Ljava/util/Collection;)V", false);
        method.visitVarInsn(Opcodes.ASTORE, 4);
        method.visitVarInsn(Opcodes.ALOAD, 4);
        method.visitMethodInsn(Opcodes.INVOKEINTERFACE,
                "java/util/List", "iterator", "()Ljava/util/Iterator;", true);
        method.visitInsn(Opcodes.POP);
        method.visitInsn(Opcodes.RETURN);
        method.visitMaxs(4, 5);
        method.visitEnd();
        writer.visitEnd();
        return writer.toByteArray();
    }

    private static void emitRepositorySnapshot(
            MethodVisitor method, String requestedType, int local) {
        method.visitTypeInsn(Opcodes.NEW, "java/util/ArrayList");
        method.visitInsn(Opcodes.DUP);
        method.visitVarInsn(Opcodes.ALOAD, 0);
        method.visitFieldInsn(Opcodes.GETFIELD, CampaignEntityMaintenancePlan.LOCATION_CLASS,
                "objects", "Lcom/fs/util/container/repo/ObjectRepository;");
        method.visitLdcInsn(org.objectweb.asm.Type.getObjectType(requestedType));
        method.visitMethodInsn(Opcodes.INVOKEVIRTUAL,
                "com/fs/util/container/repo/ObjectRepository", "getList",
                "(Ljava/lang/Class;)Ljava/util/List;", false);
        method.visitMethodInsn(Opcodes.INVOKESPECIAL, "java/util/ArrayList", "<init>",
                "(Ljava/util/Collection;)V", false);
        method.visitVarInsn(Opcodes.ASTORE, local);
        method.visitVarInsn(Opcodes.ALOAD, local);
        method.visitMethodInsn(Opcodes.INVOKEINTERFACE,
                "java/util/List", "iterator", "()Ljava/util/Iterator;", true);
        method.visitInsn(Opcodes.POP);
    }

    private static void initializeList(MethodVisitor constructor, String field) {
        constructor.visitVarInsn(Opcodes.ALOAD, 0);
        constructor.visitTypeInsn(Opcodes.NEW, "java/util/ArrayList");
        constructor.visitInsn(Opcodes.DUP);
        constructor.visitMethodInsn(Opcodes.INVOKESPECIAL,
                "java/util/ArrayList", "<init>", "()V", false);
        constructor.visitFieldInsn(Opcodes.PUTFIELD,
                CampaignEntityMaintenancePlan.MARKET_CLASS, field, "Ljava/util/List;");
    }

    @SuppressWarnings("unchecked")
    private static void addToList(Class<?> owner, Object instance, String name, Object value)
            throws Exception {
        var field = owner.getDeclaredField(name);
        field.setAccessible(true);
        ((List<Object>) field.get(instance)).add(value);
    }

    @SuppressWarnings("unchecked")
    private static void addToMap(
            Class<?> owner, Object instance, String name, Object key, Object value) throws Exception {
        var field = owner.getDeclaredField(name);
        field.setAccessible(true);
        ((LinkedHashMap<Object, Object>) field.get(instance)).put(key, value);
    }

    private static ClassSignature exact(byte[] bytes, String hash) throws Exception {
        ClassSignature parsed = ClassSignature.parse(bytes);
        return new ClassSignature(parsed.internalName(), hash, parsed.majorVersion(),
                parsed.access(), parsed.methods());
    }

    private static MethodNode method(byte[] bytes, String name) {
        ClassNode owner = new ClassNode(Opcodes.ASM9);
        new ClassReader(bytes).accept(owner, ClassReader.EXPAND_FRAMES);
        return owner.methods.stream().filter(candidate -> name.equals(candidate.name))
                .findFirst().orElseThrow();
    }

    private static int calls(MethodNode method, String owner, String name) {
        int result = 0;
        for (AbstractInsnNode instruction : method.instructions) {
            if (instruction instanceof MethodInsnNode call
                    && owner.equals(call.owner) && name.equals(call.name)) result++;
        }
        return result;
    }

    private static final class ByteArrayLoader extends ClassLoader {
        private final Map<String, byte[]> classes;

        private ByteArrayLoader(Map<String, byte[]> classes) {
            super(CampaignEntityMaintenancePlanTest.class.getClassLoader());
            this.classes = Map.copyOf(classes);
        }

        @Override
        protected synchronized Class<?> loadClass(String name, boolean resolve)
                throws ClassNotFoundException {
            Class<?> loaded = findLoadedClass(name);
            if (loaded == null && classes.containsKey(name)) {
                byte[] bytes = classes.get(name);
                loaded = defineClass(name, bytes, 0, bytes.length);
            }
            if (loaded == null) loaded = super.loadClass(name, false);
            if (resolve) resolveClass(loaded);
            return loaded;
        }
    }
}
