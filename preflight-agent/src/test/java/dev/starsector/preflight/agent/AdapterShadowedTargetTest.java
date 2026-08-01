package dev.starsector.preflight.agent;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Files;
import java.nio.file.Path;
import java.security.CodeSource;
import java.security.ProtectionDomain;
import java.security.cert.Certificate;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.MethodVisitor;
import org.objectweb.asm.Opcodes;

/**
 * A target whose class arrives from someone else's archive has to say so.
 *
 * <p>Fast Rendering places {@code fr.jar} ahead of {@code starfarer_obf.jar} on the classpath, so
 * its assembled copies of our seams are the bytes the JVM hands to {@code transform()}. The pinned
 * class hash cannot match and the target is declined -- correct, and fail-open -- but the only
 * evidence left behind was "class SHA-256 differs", which reads as a stale cache.
 */
class AdapterShadowedTargetTest {
    @TempDir
    Path temporaryDirectory;

    private static final String CLASS_NAME = "com/fs/graphics/TextureLoader";

    @Test
    void anArchiveNamedFrJarInsideTheGameDirectoryIsNotTheGame() throws Exception {
        // The install path contains "Starsector", and the old classifier tested path substrings
        // before file names -- so Fast Rendering's own archive was reported as STARSECTOR_CORE.
        Path shadowing = temporaryDirectory.resolve("Applications/Starsector.app/fr.jar");
        Path agent = temporaryDirectory.resolve("Applications/Starsector.app/fr.agent.jar");
        Path core = temporaryDirectory.resolve("Applications/Starsector.app/starsector-core/starfarer_obf.jar");
        for (Path path : List.of(shadowing, agent, core)) {
            Files.createDirectories(path.getParent());
            Files.write(path, new byte[] {7});
        }

        assertEquals("FAST_RENDERING", capture(shadowing).sourceKind());
        assertEquals("FAST_RENDERING", capture(agent).sourceKind());
        assertEquals("STARSECTOR_CORE", capture(core).sourceKind());
    }

    @Test
    void aTargetSuppliedByAnotherArchiveIsReportedAsShadowedRatherThanDrifted() throws Exception {
        AdapterTarget target = target();
        ClassSignature signature = signatureFor(CLASS_NAME);

        AdapterSourceIdentity foreign = capture(archive("Applications/Starsector.app/fr.jar"));
        assertTrue(target.shadowed(signature, foreign),
                "a target class delivered from fr.jar is shadowed");
        assertFalse(target.match(signature, foreign).exact(),
                "and it still must not be transformed");
    }

    @Test
    void theSameBytesFromThePinnedArchiveAreNeverCalledShadowed() throws Exception {
        AdapterTarget target = target();
        ClassSignature signature = signatureFor(CLASS_NAME);

        AdapterSourceIdentity pinned = capture(
                archive("Applications/Starsector.app/starsector-core/starfarer_obf.jar"));
        assertFalse(target.shadowed(signature, pinned),
                "a game update that rewrites the class in place is drift, not shadowing");
    }

    @Test
    void anUnrelatedClassFromAnyArchiveIsNeverCalledShadowed() throws Exception {
        AdapterTarget target = target();
        ClassSignature other = signatureFor("com/example/Unrelated");

        assertFalse(target.shadowed(other, capture(archive("Applications/Starsector.app/fr.jar"))),
                "shadowing requires the class name to be ours");
    }

    @Test
    void anUnknownCodeSourceIsNeverCalledShadowed() throws Exception {
        AdapterTarget target = target();
        ClassSignature signature = signatureFor(CLASS_NAME);

        assertFalse(target.shadowed(signature, AdapterSourceIdentity.unknown()),
                "a missing protection domain is not evidence that anybody shadowed anything");
    }

    @Test
    void theReportNamesTheArchiveThatSuppliedTheClass() throws Exception {
        Path destination = temporaryDirectory.resolve("adapter.json");
        AdapterReport report = new AdapterReport(
                AdapterMode.ENABLED, destination, null, List.of("com/fs/"));
        report.shadowed(target(), capture(archive("Applications/Starsector.app/fr.jar")));
        report.write();

        String json = Files.readString(destination);
        assertTrue(json.contains("\"shadowedTargets\":1"), json);
        assertTrue(json.contains("fr.jar"), "the report must name the archive that won: " + json);
        assertTrue(json.contains(CLASS_NAME), json);
        assertTrue(json.contains("re-preparing will not change it"),
                "the diagnosis has to tell the user their cache is fine: " + json);
    }

    private AdapterTarget target() {
        return new AdapterTarget(
                "texture-loader",
                CLASS_NAME,
                "a".repeat(64),
                "texture-compatibility-v2",
                List.of(new AdapterTarget.RequiredMethod("o00000", "()V")),
                "STARSECTOR_CORE",
                "starsector-core/starfarer_obf.jar",
                "",
                "java/net/URLClassLoader",
                "");
    }

    private Path archive(String relative) throws Exception {
        Path path = temporaryDirectory.resolve(relative);
        Files.createDirectories(path.getParent());
        if (!Files.exists(path)) {
            Files.write(path, new byte[] {7});
        }
        return path;
    }

    private AdapterSourceIdentity capture(Path archive) throws Exception {
        return AdapterSourceIdentity.capture(getClass().getClassLoader(), domain(archive), false);
    }

    private static ProtectionDomain domain(Path archive) throws Exception {
        return new ProtectionDomain(
                new CodeSource(archive.toUri().toURL(), (Certificate[]) null), null);
    }

    private static ClassSignature signatureFor(String internalName) throws java.io.IOException {
        ClassWriter writer = new ClassWriter(0);
        writer.visit(Opcodes.V17, Opcodes.ACC_PUBLIC, internalName, null, "java/lang/Object", null);
        MethodVisitor method = writer.visitMethod(
                Opcodes.ACC_PUBLIC | Opcodes.ACC_STATIC, "o00000", "()V", null, null);
        method.visitCode();
        method.visitInsn(Opcodes.RETURN);
        method.visitMaxs(0, 0);
        method.visitEnd();
        writer.visitEnd();
        return ClassSignature.parse(writer.toByteArray());
    }
}
