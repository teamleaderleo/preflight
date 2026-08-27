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
import org.objectweb.asm.tree.AbstractInsnNode;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.MethodInsnNode;
import org.objectweb.asm.tree.analysis.Analyzer;
import org.objectweb.asm.tree.analysis.BasicInterpreter;

/** Opt-in structural transform of exact installed AI Tweaks bytes; never starts Starsector. */
class AiTweaksWeaponLocationSnapshotInstalledAdapterIT {
    private static final String RUNTIME =
            AiTweaksWeaponLocationSnapshotRuntime.class.getName().replace('.', '/');

    @AfterEach
    void clearProperty() {
        System.clearProperty(AiTweaksWeaponLocationSnapshotPlan.ENABLED_PROPERTY);
    }

    @Test
    void installedTargetsBracketOneSelectionAndRetainTheOriginalGetterFallback() throws Exception {
        String configured = System.getProperty("preflight.aitweaks.jar", "").trim();
        String configuredLiveClass = System.getProperty(
                "preflight.aitweaks.weaponHandleLiveClass", "").trim();
        Assumptions.assumeTrue(!configured.isEmpty(),
                "set -Dpreflight.aitweaks.jar=<aitweaks-core.jar>");
        Assumptions.assumeTrue(!configuredLiveClass.isEmpty(),
                "set -Dpreflight.aitweaks.weaponHandleLiveClass=<loader-produced class>");
        Path archive = Path.of(configured).toAbsolutePath().normalize();
        Path liveClass = Path.of(configuredLiveClass).toAbsolutePath().normalize();
        Assumptions.assumeTrue(Files.isRegularFile(archive));
        Assumptions.assumeTrue(Files.isRegularFile(liveClass));
        System.setProperty(AiTweaksWeaponLocationSnapshotPlan.ENABLED_PROPERTY, "true");

        try (JarFile jar = new JarFile(archive.toFile())) {
            for (var target : AiTweaksWeaponLocationSnapshotPlan.targets()) {
                var entry = jar.getJarEntry(target.internalName() + ".class");
                assertNotNull(entry);
                byte[] archiveEntry;
                try (var input = jar.getInputStream(entry)) {
                    archiveEntry = input.readAllBytes();
                }
                byte[] original = archiveEntry;
                if (AiTweaksWeaponLocationSnapshotPlan.WEAPON_HANDLE_CLASS.equals(
                        target.internalName())) {
                    assertEquals(
                            AiTweaksWeaponLocationSnapshotPlan.WEAPON_HANDLE_ARCHIVE_ENTRY_SHA256,
                            ClassSignature.parse(archiveEntry).sha256());
                    original = Files.readAllBytes(liveClass);
                }
                ClassSignature signature = ClassSignature.parse(original);
                assertEquals(target.internalName(), signature.internalName());
                assertEquals(target.sha256(), signature.sha256());
                assertEquals(61, signature.majorVersion());
                byte[] transformed = AiTweaksWeaponLocationSnapshotPlan.transform(
                        signature, original);
                assertNotNull(transformed, target.internalName());

                ClassNode owner = parse(transformed);
                if (AiTweaksWeaponLocationSnapshotPlan.AUTOFIRE_CLASS.equals(owner.name)) {
                    assertEquals(1, calls(owner, RUNTIME, "begin"));
                    assertEquals(2, calls(owner, RUNTIME, "end"));
                } else {
                    assertEquals(1, calls(owner, RUNTIME, "cachedLocation"));
                    assertEquals(1, calls(owner, RUNTIME, "rememberLocation"));
                    assertEquals(1, calls(owner,
                            "com/fs/starfarer/api/combat/WeaponAPI", "getLocation"));
                }
                for (var method : owner.methods) {
                    new Analyzer<>(new BasicInterpreter()).analyze(owner.name, method);
                }
            }
        }
    }

    private static ClassNode parse(byte[] bytes) {
        ClassNode owner = new ClassNode(Opcodes.ASM9);
        new ClassReader(bytes).accept(owner, ClassReader.EXPAND_FRAMES);
        return owner;
    }

    private static int calls(ClassNode owner, String targetOwner, String name) {
        int count = 0;
        for (var method : owner.methods) {
            for (AbstractInsnNode instruction : method.instructions) {
                if (instruction instanceof MethodInsnNode call
                        && targetOwner.equals(call.owner) && name.equals(call.name)) count++;
            }
        }
        return count;
    }
}
