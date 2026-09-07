package dev.starsector.preflight.agent;

import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.tree.ClassNode;

class HullLoaderFramesTest {
    @Test
    void cachePreservesUnchangedApplicationHierarchy() throws Exception {
        byte[] original = fixture();
        byte[] changed = HullJsonCachePlan.transform(ClassSignature.parse(original), original);
        assertNotNull(changed);
        WeaponJsonCacheFramesTest.verifyHierarchy(changed);
    }

    @Test
    void timingPreservesUnchangedApplicationHierarchy() throws Exception {
        byte[] original = fixture();
        byte[] changed = ShipHullLoaderPhasePlan.transform(ClassSignature.parse(original), original);
        assertNotNull(changed);
        WeaponJsonCacheFramesTest.verifyHierarchy(changed);
    }

    @Test
    void compositionPreservesUnchangedApplicationHierarchy() throws Exception {
        byte[] original = fixture();
        byte[] timed = ShipHullLoaderPhasePlan.transform(ClassSignature.parse(original), original);
        assertNotNull(timed);
        byte[] cached = HullJsonCachePlan.transform(ClassSignature.parse(timed), timed);
        assertNotNull(cached);
        WeaponJsonCacheFramesTest.verifyHierarchy(cached);

        ClassNode owner = new ClassNode(Opcodes.ASM9);
        new ClassReader(original).accept(owner, ClassReader.EXPAND_FRAMES);
        ClassSignature signature = ClassSignature.parse(original);
        assertTrue(ShipHullLoaderPhasePlan.apply(signature, owner));
        assertTrue(HullJsonCachePlan.apply(signature, owner));
        WeaponJsonCacheFramesTest.verifyHierarchy(ShipHullLoaderPhasePlan.write(owner));
    }

    private static byte[] fixture() throws Exception {
        ClassNode owner = new ClassNode(Opcodes.ASM9);
        new ClassReader(ShipHullLoaderPhasePlanTest.fixture(value -> value))
                .accept(owner, ClassReader.EXPAND_FRAMES);
        byte[] original = WeaponJsonCacheFramesTest.withHierarchy(owner);
        WeaponJsonCacheFramesTest.verifyHierarchy(original);
        return original;
    }
}
