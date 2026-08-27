package dev.starsector.preflight.agent;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.tree.AbstractInsnNode;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.InsnList;
import org.objectweb.asm.tree.IntInsnNode;
import org.objectweb.asm.tree.MethodInsnNode;
import org.objectweb.asm.tree.MethodNode;

/** Exact LWJGL 2 wrapper-entry counters for broad OpenGL command families. */
final class GlCommandCountPlan {
    static final String SOURCE_FILE = "contents/resources/java/lwjgl.jar";
    static final String SOURCE_SHA256 =
            "527d509f60132e5b2653c7fc0f8cf299d6f698f4a8013342bef47705dc57ed3f";
    static final String LOADER = "jdk/internal/loader/ClassLoaders$AppClassLoader";
    static final String LOADER_NAME = "app";
    private static final String RUNTIME =
            "dev/starsector/preflight/agent/GlCommandCountRuntime";

    private static final List<Target> TARGETS = List.of(
            new Target(
                    "gl11",
                    "org/lwjgl/opengl/GL11",
                    "875ff80814db1f6c16dd118fb27df7a7dc97adb4876dde023afd0e4ca0f18ce4",
                    "glBegin", "(I)V",
                    List.of(
                            names(GlCommandCountRuntime.IMMEDIATE_DRAW, 1, "glBegin"),
                            names(GlCommandCountRuntime.ARRAY_DRAW, 10,
                                    "glDrawArrays", "glDrawElements", "glDrawPixels"),
                            names(GlCommandCountRuntime.TEXTURE_BIND, 1, "glBindTexture"),
                            names(GlCommandCountRuntime.TEXTURE_UPLOAD, 14,
                                    "glTexImage2D", "glTexSubImage2D",
                                    "glCopyTexImage2D", "glCopyTexSubImage2D"),
                            names(GlCommandCountRuntime.FIXED_FUNCTION_STATE, 11,
                                    "glEnable", "glDisable", "glEnableClientState",
                                    "glDisableClientState", "glBlendFunc", "glAlphaFunc",
                                    "glDepthFunc", "glDepthMask", "glCullFace", "glScissor",
                                    "glViewport"),
                            names(GlCommandCountRuntime.MATRIX_STATE, 16,
                                    "glMatrixMode", "glLoadIdentity", "glPushMatrix", "glPopMatrix",
                                    "glLoadMatrix", "glMultMatrix", "glTranslatef", "glTranslated",
                                    "glRotatef", "glRotated", "glScalef", "glScaled", "glOrtho",
                                    "glFrustum"),
                            names(GlCommandCountRuntime.SYNCHRONOUS_READBACK, 24,
                                    "glFinish", "glFlush", "glReadPixels", "glGetError",
                                    "glGetInteger", "glGetFloat", "glGetBoolean", "glGetDouble",
                                    "glGetString", "glGetTexImage"))),
            new Target(
                    "gl13",
                    "org/lwjgl/opengl/GL13",
                    "54a7f00a0710058dbd113906d51dbaf4008da1ce07e9b9fd860d49b156ce1a3c",
                    "glActiveTexture", "(I)V",
                    List.of(
                            names(GlCommandCountRuntime.TEXTURE_UNIT_STATE, 2,
                                    "glActiveTexture", "glClientActiveTexture"),
                            names(GlCommandCountRuntime.TEXTURE_UPLOAD, 5,
                                    "glCompressedTexImage2D", "glCompressedTexSubImage2D"))),
            new Target(
                    "gl15",
                    "org/lwjgl/opengl/GL15",
                    "1652dc1b0e9928d7ab3a47ede0a5b4571b934c62e8d6b5acae3c6ffd504b1f49",
                    "glBindBuffer", "(II)V",
                    List.of(
                            names(GlCommandCountRuntime.BUFFER_BIND, 1, "glBindBuffer"),
                            names(GlCommandCountRuntime.BUFFER_UPLOAD, 11,
                                    "glBufferData", "glBufferSubData"))),
            new Target(
                    "gl20",
                    "org/lwjgl/opengl/GL20",
                    "4ce53c6eb13178a9ef2097318d0802f4317f7925baa3b6c27d0f7a96581c5451",
                    "glUseProgram", "(I)V",
                    List.of(
                            names(GlCommandCountRuntime.SHADER_PROGRAM_STATE, 10,
                                    "glUseProgram", "glEnableVertexAttribArray",
                                    "glDisableVertexAttribArray", "glVertexAttribPointer"),
                            prefix(GlCommandCountRuntime.UNIFORM_UPDATE, 19, "glUniform"),
                            names(GlCommandCountRuntime.FRAMEBUFFER_STATE, 2, "glDrawBuffers"))),
            new Target(
                    "ext-framebuffer-object",
                    "org/lwjgl/opengl/EXTFramebufferObject",
                    "69efaf76d2096b3b3d0b2e1f183fb0bc35dc694a11932a54052176b6cfd50c4d",
                    "glBindFramebufferEXT", "(II)V",
                    List.of(names(GlCommandCountRuntime.FRAMEBUFFER_STATE, 1,
                            "glBindFramebufferEXT"))));

    private GlCommandCountPlan() {
    }

    static List<Target> targets() {
        return TARGETS;
    }

    static byte[] transform(ClassSignature signature, byte[] originalBytes) {
        if (!GlCommandCountRuntime.planEnabled()) return null;
        Target target = target(signature.internalName());
        if (target == null
                || !target.sha256().equals(signature.sha256())
                || signature.majorVersion() != 49
                || !signature.hasMethod(target.requiredMethod(), target.requiredDescriptor())) {
            return null;
        }

        ClassNode owner = new ClassNode(Opcodes.ASM9);
        new ClassReader(originalBytes).accept(owner, ClassReader.EXPAND_FRAMES);
        int[] observed = new int[target.rules().size()];
        List<InstrumentedMethod> methods = new ArrayList<>();
        for (MethodNode method : owner.methods) {
            int ruleIndex = matchingRule(target.rules(), method.name);
            if (ruleIndex == -2) return null;
            if (ruleIndex < 0) continue;
            if ((method.access & (Opcodes.ACC_PUBLIC | Opcodes.ACC_STATIC))
                    != (Opcodes.ACC_PUBLIC | Opcodes.ACC_STATIC)
                    || (method.access & (Opcodes.ACC_ABSTRACT | Opcodes.ACC_NATIVE)) != 0
                    || calls(method, RUNTIME, "record") != 0) {
                return null;
            }
            observed[ruleIndex]++;
            methods.add(new InstrumentedMethod(method, target.rules().get(ruleIndex).category()));
        }
        for (int index = 0; index < observed.length; index++) {
            if (observed[index] != target.rules().get(index).expectedMethods()) return null;
        }

        for (InstrumentedMethod method : methods) {
            InsnList hook = new InsnList();
            hook.add(new IntInsnNode(Opcodes.BIPUSH, method.category()));
            hook.add(new MethodInsnNode(
                    Opcodes.INVOKESTATIC, RUNTIME, "record", "(I)V", false));
            method.method().instructions.insert(hook);
        }
        ClassWriter writer = new SafeClassWriter(ClassWriter.COMPUTE_FRAMES | ClassWriter.COMPUTE_MAXS);
        owner.accept(writer);
        GlCommandCountRuntime.installed(target.internalName(), methods.size());
        return writer.toByteArray();
    }

    private static Target target(String internalName) {
        for (Target target : TARGETS) {
            if (target.internalName().equals(internalName)) return target;
        }
        return null;
    }

    private static int matchingRule(List<Rule> rules, String methodName) {
        int result = -1;
        for (int index = 0; index < rules.size(); index++) {
            if (!rules.get(index).matches(methodName)) continue;
            if (result >= 0) return -2;
            result = index;
        }
        return result;
    }

    private static int calls(MethodNode method, String owner, String name) {
        int result = 0;
        for (AbstractInsnNode instruction : method.instructions) {
            if (instruction instanceof MethodInsnNode call
                    && owner.equals(call.owner) && name.equals(call.name)) result++;
        }
        return result;
    }

    private static Rule names(int category, int expected, String... names) {
        return new Rule(category, expected, Set.copyOf(new LinkedHashSet<>(List.of(names))), null);
    }

    private static Rule prefix(int category, int expected, String prefix) {
        return new Rule(category, expected, Set.of(), prefix);
    }

    record Target(
            String idSuffix,
            String internalName,
            String sha256,
            String requiredMethod,
            String requiredDescriptor,
            List<Rule> rules) {
        Target {
            rules = List.copyOf(rules);
        }

        int expectedMethods() {
            return rules.stream().mapToInt(Rule::expectedMethods).sum();
        }
    }

    record Rule(int category, int expectedMethods, Set<String> names, String prefix) {
        Rule {
            names = Set.copyOf(names);
        }

        boolean matches(String methodName) {
            return names.contains(methodName) || (prefix != null && methodName.startsWith(prefix));
        }
    }

    private record InstrumentedMethod(MethodNode method, int category) {
    }
}
