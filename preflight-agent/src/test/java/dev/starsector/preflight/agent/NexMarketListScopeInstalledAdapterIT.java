package dev.starsector.preflight.agent;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.jar.JarFile;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.tree.AbstractInsnNode;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.MethodInsnNode;
import org.objectweb.asm.tree.MethodNode;

/** Verifies both exact installed halves without launching or redistributing the game. */
class NexMarketListScopeInstalledAdapterIT {
    @BeforeEach
    void enable() {
        System.setProperty(NexMarketListScopeRuntime.ENABLED_PROPERTY, "true");
        NexMarketListScopeRuntime.beginSession();
    }

    @AfterEach
    void reset() {
        System.clearProperty(NexMarketListScopeRuntime.ENABLED_PROPERTY);
        NexMarketListScopeRuntime.reset();
    }

    @Test
    void installedNexAndCoreMatchReviewedWrappers() throws Exception {
        Path nex = configured("preflight.nexerelin.jar");
        Path core = configured("preflight.starsector.core.jar");

        byte[] nexOriginal = entry(nex, NexMarketListScopePlan.NEX_CLASS);
        ClassSignature nexSignature = ClassSignature.parse(nexOriginal);
        assertEquals(NexMarketListScopePlan.NEX_SHA256, nexSignature.sha256());
        byte[] nexTransformed = NexMarketListScopePlan.transform(nexSignature, nexOriginal);
        assertNotNull(nexTransformed);
        ClassNode nexOwner = read(nexTransformed);
        assertNotNull(method(nexOwner, NexMarketListScopePlan.NEX_METHOD,
                NexMarketListScopePlan.NEX_DESCRIPTOR));
        assertNotNull(method(nexOwner, "preflight$original$collectEconomicData",
                NexMarketListScopePlan.NEX_DESCRIPTOR));
        assertEquals(1, runtimeCalls(nexOwner, "beginScope"));
        assertEquals(2, runtimeCalls(nexOwner, "endScope"));
        assertEquals(1, method(nexOwner, NexMarketListScopePlan.NEX_METHOD,
                NexMarketListScopePlan.NEX_DESCRIPTOR).tryCatchBlocks.size());
        assertNull(NexMarketListScopePlan.transform(nexSignature, nexTransformed));

        byte[] coreOriginal = entry(core, NexMarketListScopePlan.CORE_CLASS);
        ClassSignature coreSignature = ClassSignature.parse(coreOriginal);
        assertEquals(NexMarketListScopePlan.CORE_SHA256, coreSignature.sha256());
        byte[] coreTransformed = NexMarketListScopePlan.transform(coreSignature, coreOriginal);
        assertNotNull(coreTransformed);
        ClassNode coreOwner = read(coreTransformed);
        assertNotNull(method(coreOwner, NexMarketListScopePlan.CORE_METHOD,
                NexMarketListScopePlan.CORE_DESCRIPTOR));
        assertNotNull(method(coreOwner, "preflight$original$getMarkets",
                NexMarketListScopePlan.CORE_DESCRIPTOR));
        assertEquals(2, runtimeCalls(coreOwner, "inScope"));
        assertEquals(1, runtimeCalls(coreOwner, "reuse"));
        assertEquals(1, runtimeCalls(coreOwner, "observe"));
        assertNull(NexMarketListScopePlan.transform(coreSignature, coreTransformed));

        assertEquals(Boolean.TRUE,
                NexMarketListScopeRuntime.telemetry().get("nexInstalled"));
        assertEquals(Boolean.TRUE,
                NexMarketListScopeRuntime.telemetry().get("coreInstalled"));
    }

    private static Path configured(String property) {
        String value = System.getProperty(property, "").trim();
        Assumptions.assumeTrue(!value.isEmpty(), "set " + property);
        Path archive = Path.of(value).toAbsolutePath().normalize();
        Assumptions.assumeTrue(Files.isRegularFile(archive));
        return archive;
    }

    private static byte[] entry(Path archive, String name) throws Exception {
        try (JarFile jar = new JarFile(archive.toFile())) {
            var entry = jar.getJarEntry(name + ".class");
            assertNotNull(entry);
            try (var input = jar.getInputStream(entry)) {
                return input.readAllBytes();
            }
        }
    }

    private static ClassNode read(byte[] bytes) {
        ClassNode owner = new ClassNode(Opcodes.ASM9);
        new ClassReader(bytes).accept(owner, ClassReader.EXPAND_FRAMES);
        return owner;
    }

    private static MethodNode method(ClassNode owner, String name, String descriptor) {
        return owner.methods.stream()
                .filter(candidate -> name.equals(candidate.name)
                        && descriptor.equals(candidate.desc))
                .findFirst().orElse(null);
    }

    private static int runtimeCalls(ClassNode owner, String name) {
        int result = 0;
        String runtime = NexMarketListScopeRuntime.class.getName().replace('.', '/');
        for (MethodNode method : owner.methods) {
            for (AbstractInsnNode instruction : method.instructions) {
                if (instruction instanceof MethodInsnNode call
                        && runtime.equals(call.owner) && name.equals(call.name)) result++;
            }
        }
        return result;
    }

}
