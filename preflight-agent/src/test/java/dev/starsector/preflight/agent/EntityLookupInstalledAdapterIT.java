package dev.starsector.preflight.agent;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.jar.JarFile;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.MethodInsnNode;
import org.objectweb.asm.tree.MethodNode;

/** Opt-in exact installed-class check for all three mutation-tracked entity-index seams. */
class EntityLookupInstalledAdapterIT {
    private static final String RUNTIME = EntityLookupRuntime.class.getName().replace('.', '/');

    @AfterEach
    void reset() {
        EntityLookupRuntime.beginSession();
    }

    @Test
    void installedEntityIndexTracksListsAndIds() throws Exception {
        String configured = System.getProperty("preflight.starsector.core.jar", "").trim();
        Assumptions.assumeTrue(!configured.isEmpty(),
                "set -Dpreflight.starsector.core.jar=<starfarer_obf.jar>");
        Path core = Path.of(configured).toAbsolutePath().normalize();
        Path common = core.resolveSibling("fs.common_obf.jar");
        Assumptions.assumeTrue(Files.isRegularFile(core) && Files.isRegularFile(common));

        byte[] location = classBytes(core, EntityLookupPlan.TARGET_CLASS);
        byte[] repository = classBytes(common, EntityRepositoryListPlan.TARGET_CLASS);
        byte[] entity = classBytes(core, EntityIdMutationPlan.TARGET_CLASS);

        byte[] transformedLocation =
                EntityLookupPlan.transform(ClassSignature.parse(location), location);
        byte[] transformedRepository =
                EntityRepositoryListPlan.transform(ClassSignature.parse(repository), repository);
        byte[] transformedEntity =
                EntityIdMutationPlan.transform(ClassSignature.parse(entity), entity);
        assertNotNull(transformedLocation);
        assertNotNull(transformedRepository);
        assertNotNull(transformedEntity);

        ClassNode locationOwner = read(transformedLocation);
        assertEquals(1, calls(method(locationOwner, EntityLookupPlan.LOOKUP_METHOD,
                EntityLookupPlan.LOOKUP_DESCRIPTOR), RUNTIME, "lookup"));

        ClassNode repositoryOwner = read(transformedRepository);
        assertEquals(1, calls(method(repositoryOwner, EntityRepositoryListPlan.GET_LIST_METHOD,
                EntityRepositoryListPlan.GET_LIST_DESCRIPTOR), RUNTIME, "newRepositoryList"));
        assertEquals(1, calls(method(repositoryOwner, EntityRepositoryListPlan.ADD_METHOD,
                EntityRepositoryListPlan.ADD_DESCRIPTOR), RUNTIME, "newRepositoryList"));

        ClassNode entityOwner = read(transformedEntity);
        assertEquals(1, calls(method(entityOwner, EntityIdMutationPlan.SET_ID_METHOD,
                EntityIdMutationPlan.SET_ID_DESCRIPTOR), RUNTIME, "entityIdChanging"));
        assertEquals(true, EntityLookupRuntime.counters().get("installed"));
    }

    private static byte[] classBytes(Path archive, String internalName) throws Exception {
        try (JarFile jar = new JarFile(archive.toFile())) {
            var entry = jar.getJarEntry(internalName + ".class");
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
                .filter(method -> name.equals(method.name) && descriptor.equals(method.desc))
                .findFirst().orElseThrow();
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
}
