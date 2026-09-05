package dev.starsector.preflight.agent;

import static org.junit.jupiter.api.Assertions.*;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.jar.JarFile;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.tree.*;
import org.objectweb.asm.tree.analysis.Analyzer;
import org.objectweb.asm.tree.analysis.BasicVerifier;

class TextureProgressLogPlanTest {
    @AfterEach void reset() { System.clearProperty(AssetProgressLogRuntime.PROPERTY); }

    @Test void installedRewriteRetainsCleanupGlCountersAndExceptionBoundaries() throws Exception {
        byte[] bytes = installed();
        ClassNode owner = read(bytes);
        List<String> effects = effects(owner);
        List<TryCatchBlockNode> handlers = owner.methods.stream()
                .flatMap(method -> method.tryCatchBlocks.stream()).toList();
        assertEquals(3, progress(owner));
        assertTrue(TextureProgressLogPlan.apply(ClassSignature.parse(bytes), owner));
        assertEquals(0, progress(owner));
        assertEquals(effects, effects(owner));
        assertEquals(handlers, owner.methods.stream().flatMap(method -> method.tryCatchBlocks.stream()).toList());
        for (MethodNode method : owner.methods) {
            new Analyzer<>(new BasicVerifier()).analyze(owner.name, method);
        }
    }

    @Test void installedPreparedCompositionHonorsTheProgressControl() throws Exception {
        byte[] bytes = installed();
        ClassSignature signature = ClassSignature.parse(bytes);
        System.setProperty(AssetProgressLogRuntime.PROPERTY, "on");
        byte[] verbose = TexturePreparedPixelPlan.transform(signature, bytes);
        assertNotNull(verbose);
        assertEquals(3, progress(read(verbose)));
        System.setProperty(AssetProgressLogRuntime.PROPERTY, "off");
        byte[] quiet = TexturePreparedPixelPlan.transform(signature, bytes);
        assertNotNull(quiet);
        assertEquals(0, progress(read(quiet)));
    }

    @Test void suffixDriftDeclinesWithoutPartialMutation() throws Exception {
        byte[] bytes = installed();
        ClassNode owner = read(bytes);
        for (MethodNode method : owner.methods) {
            for (AbstractInsnNode instruction : method.instructions) {
                if (instruction instanceof LdcInsnNode text && TextureProgressLogPlan.CLEANED.equals(text.cst)) {
                    text.cst = "Changed cleanup message";
                }
            }
        }
        byte[] before = write(owner);
        assertFalse(TextureProgressLogPlan.apply(ClassSignature.parse(bytes), owner));
        assertArrayEquals(before, write(owner));
        assertFalse(TextureProgressLogPlan.apply(ClassSignature.parse(before), owner));
    }

    @Test void installedRulesRewriteRetainsLookupsAndAllNonLoggingEffects() throws Exception {
        String path = System.getProperty("preflight.starsector.core.jar");
        Assumptions.assumeTrue(path != null);
        byte[] bytes;
        try (JarFile jar = new JarFile(path)) {
            bytes = jar.getInputStream(jar.getJarEntry(RulesLoaderPhasePlan.TARGET_CLASS + ".class")).readAllBytes();
        }
        ClassNode before = read(bytes);
        byte[] quiet = AssetProgressLogPlan.windowsRules(ClassSignature.parse(bytes), bytes);
        assertNotNull(quiet);
        ClassNode after = read(quiet);
        // Exclude only pure StringBuilder formatting; JSON lookups, rule construction and writes stay.
        assertEquals(effects(before).stream().filter(v -> !v.contains("java/lang/StringBuilder")).toList(),
                effects(after).stream().filter(v -> !v.contains("java/lang/StringBuilder")).toList());
        assertEquals(before.methods.stream().map(m -> m.tryCatchBlocks.size()).toList(),
                after.methods.stream().map(m -> m.tryCatchBlocks.size()).toList());
        for (MethodNode method : after.methods) new Analyzer<>(new BasicVerifier()).analyze(after.name, method);
        assertNull(AssetProgressLogPlan.windowsRules(ClassSignature.parse(quiet), quiet));
    }

    private static List<String> effects(ClassNode owner) {
        List<String> result = new ArrayList<>();
        for (MethodNode method : owner.methods) {
            for (AbstractInsnNode instruction : method.instructions) {
                if (instruction instanceof MethodInsnNode call) {
                    if (call.owner.equals("java/lang/String") && call.name.equals("format")) continue;
                    if (call.owner.equals("java/lang/Float") && call.name.equals("valueOf")) continue;
                    if (call.owner.equals("org/apache/log4j/Logger")
                            && (call.name.equals("info") || call.name.equals("debug"))) continue;
                    result.add(method.name + method.desc + ":" + call.owner + "." + call.name + call.desc);
                } else if (instruction instanceof FieldInsnNode field
                        && (field.getOpcode() == Opcodes.PUTSTATIC || field.getOpcode() == Opcodes.PUTFIELD)) {
                    result.add(method.name + method.desc + ":" + field.getOpcode() + ":" + field.owner + field.name + field.desc);
                }
            }
        }
        return result;
    }

    private static int progress(ClassNode owner) {
        int result = 0;
        for (MethodNode method : owner.methods) for (AbstractInsnNode instruction : method.instructions) {
            if (instruction instanceof LdcInsnNode text
                    && (TextureProgressLogPlan.LOADED.equals(text.cst) || TextureProgressLogPlan.CLEANED.equals(text.cst))) result++;
        }
        return result;
    }
    private static ClassNode read(byte[] bytes) {
        ClassNode owner = new ClassNode(); new ClassReader(bytes).accept(owner, ClassReader.EXPAND_FRAMES); return owner;
    }
    private static byte[] write(ClassNode owner) {
        ClassWriter writer = new ClassWriter(0); owner.accept(writer); return writer.toByteArray();
    }
    private static byte[] installed() throws Exception {
        String path = System.getProperty("preflight.starsector.common.jar");
        Assumptions.assumeTrue(path != null);
        try (JarFile jar = new JarFile(Path.of(path).toFile())) {
            return jar.getInputStream(jar.getJarEntry(TexturePreparedPixelPlan.TARGET_CLASS + ".class")).readAllBytes();
        }
    }
}
