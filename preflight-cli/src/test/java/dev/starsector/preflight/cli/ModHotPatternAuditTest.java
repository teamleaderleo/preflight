package dev.starsector.preflight.cli;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.jar.JarEntry;
import java.util.jar.JarOutputStream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.Label;
import org.objectweb.asm.MethodVisitor;
import org.objectweb.asm.Opcodes;

class ModHotPatternAuditTest {
    @TempDir
    Path temporaryDirectory;

    @Test
    void ranksLoopLocalAllocationsClockPollsAndGlQueriesByOwningMod() throws Exception {
        Path install = temporaryDirectory.resolve("Starsector");
        Path mod = install.resolve("mods/Suspect Mod");
        Files.createDirectories(mod.resolve("jars"));
        Files.writeString(mod.resolve("mod_info.json"), """
                {
                  "id":"suspect_mod",
                  "jars":["jars/suspect.jar"]
                }
                """);
        Files.writeString(install.resolve("mods/enabled_mods.json"),
                "{\"enabledMods\":[\"suspect_mod\"]}");
        writeJar(mod.resolve("jars/suspect.jar"), hotLoopClass());

        ModHotPatternAudit.Result result = ModHotPatternAudit.scan(install, 100);
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> findings =
                (List<Map<String, Object>>) result.values().get("findings");
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> modScores =
                (List<Map<String, Object>>) result.values().get("modScores");

        assertEquals("suspect_mod", modScores.get(0).get("modId"));
        assertTrue(((Number) modScores.get(0).get("score")).intValue() > 0);
        assertFinding(findings, "TEMP_COLLECTION");
        assertFinding(findings, "CLOCK_POLL");
        assertFinding(findings, "GL_QUERY_OR_SYNC");
        assertTrue(result.toJson().contains("offline static lead generator"));
    }

    private static void assertFinding(List<Map<String, Object>> findings, String pattern) {
        Map<String, Object> finding = findings.stream()
                .filter(value -> pattern.equals(value.get("pattern")))
                .findFirst()
                .orElseThrow();
        assertEquals("suspect_mod", finding.get("modId"));
        assertEquals(Boolean.TRUE, finding.get("hotSurface"));
        assertTrue(((Number) finding.get("inLoopCount")).intValue() > 0);
    }

    private static byte[] hotLoopClass() {
        ClassWriter writer = new ClassWriter(ClassWriter.COMPUTE_FRAMES | ClassWriter.COMPUTE_MAXS);
        writer.visit(
                Opcodes.V17,
                Opcodes.ACC_PUBLIC,
                "suspect/HotLoop",
                null,
                "java/lang/Object",
                new String[] {"com/fs/starfarer/api/EveryFrameScript"});

        MethodVisitor constructor = writer.visitMethod(
                Opcodes.ACC_PUBLIC, "<init>", "()V", null, null);
        constructor.visitCode();
        constructor.visitVarInsn(Opcodes.ALOAD, 0);
        constructor.visitMethodInsn(
                Opcodes.INVOKESPECIAL, "java/lang/Object", "<init>", "()V", false);
        constructor.visitInsn(Opcodes.RETURN);
        constructor.visitMaxs(0, 0);
        constructor.visitEnd();

        MethodVisitor method = writer.visitMethod(
                Opcodes.ACC_PUBLIC, "advance", "(F)V", null, null);
        method.visitCode();
        method.visitInsn(Opcodes.ICONST_0);
        method.visitVarInsn(Opcodes.ISTORE, 2);
        Label loop = new Label();
        method.visitLabel(loop);
        method.visitTypeInsn(Opcodes.NEW, "java/util/ArrayList");
        method.visitInsn(Opcodes.DUP);
        method.visitMethodInsn(
                Opcodes.INVOKESPECIAL, "java/util/ArrayList", "<init>", "()V", false);
        method.visitInsn(Opcodes.POP);
        method.visitMethodInsn(
                Opcodes.INVOKESTATIC, "java/lang/System", "nanoTime", "()J", false);
        method.visitInsn(Opcodes.POP2);
        method.visitMethodInsn(
                Opcodes.INVOKESTATIC, "org/lwjgl/opengl/GL11", "glGetError", "()I", false);
        method.visitInsn(Opcodes.POP);
        method.visitIincInsn(2, 1);
        method.visitVarInsn(Opcodes.ILOAD, 2);
        method.visitIntInsn(Opcodes.BIPUSH, 10);
        method.visitJumpInsn(Opcodes.IF_ICMPLT, loop);
        method.visitInsn(Opcodes.RETURN);
        method.visitMaxs(0, 0);
        method.visitEnd();
        writer.visitEnd();
        return writer.toByteArray();
    }

    private static void writeJar(Path path, byte[] classBytes) throws Exception {
        try (JarOutputStream output = new JarOutputStream(Files.newOutputStream(path))) {
            JarEntry entry = new JarEntry("suspect/HotLoop.class");
            entry.setTime(0L);
            output.putNextEntry(entry);
            output.write(classBytes);
            output.closeEntry();
        }
    }
}
