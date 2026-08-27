package dev.starsector.preflight.agent;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.tree.AbstractInsnNode;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.InsnList;
import org.objectweb.asm.tree.InsnNode;
import org.objectweb.asm.tree.JumpInsnNode;
import org.objectweb.asm.tree.LabelNode;
import org.objectweb.asm.tree.MethodInsnNode;
import org.objectweb.asm.tree.MethodNode;
import org.objectweb.asm.tree.VarInsnNode;

/** Exact LWJGL wrappers for one opt-in, fail-open GL texture-binding experiment. */
final class GlTextureBindDedupPlan {
    static final String SOURCE_FILE = GlCommandCountPlan.SOURCE_FILE;
    static final String SOURCE_SHA256 = GlCommandCountPlan.SOURCE_SHA256;
    static final String LOADER = GlCommandCountPlan.LOADER;
    static final String LOADER_NAME = GlCommandCountPlan.LOADER_NAME;
    private static final String RUNTIME =
            "dev/starsector/preflight/agent/GlTextureBindDedupRuntime";

    private static final List<Target> TARGETS = List.of(
            target("gl11", "org/lwjgl/opengl/GL11",
                    "875ff80814db1f6c16dd118fb27df7a7dc97adb4876dde023afd0e4ca0f18ce4",
                    Map.ofEntries(
                            bind("glBindTexture(II)V"),
                            invalidate("glCallList(I)V"),
                            invalidate("glCallLists(Ljava/nio/ByteBuffer;)V"),
                            invalidate("glCallLists(Ljava/nio/IntBuffer;)V"),
                            invalidate("glCallLists(Ljava/nio/ShortBuffer;)V"),
                            invalidate("glPopAttrib()V"),
                            invalidate("glDeleteTextures(I)V"),
                            invalidate("glDeleteTextures(Ljava/nio/IntBuffer;)V"),
                            hook("glNewList(II)V", "beginDisplayList"),
                            hook("glEndList()V", "endDisplayList"))),
            target("gl13", "org/lwjgl/opengl/GL13",
                    "54a7f00a0710058dbd113906d51dbaf4008da1ce07e9b9fd860d49b156ce1a3c",
                    Map.ofEntries(invalidate("glActiveTexture(I)V"))),
            target("arb-multitexture", "org/lwjgl/opengl/ARBMultitexture",
                    "f73b524a62e1dbf50816c17a464da9e3c439b3c6effbd818599c75867db28f72",
                    Map.ofEntries(invalidate("glActiveTextureARB(I)V"))),
            target("arb-direct-state-access", "org/lwjgl/opengl/ARBDirectStateAccess",
                    "4f3e62bb23cafa2b817294987941372466682de43a87761f5659c33c02d02f5e",
                    Map.ofEntries(invalidate("glBindTextureUnit(II)V"))),
            target("arb-multi-bind", "org/lwjgl/opengl/ARBMultiBind",
                    "5d8e6d3b206741346fb9c27d6439dbb9f837a868f777fc08402a1131dd944a09",
                    Map.ofEntries(invalidate("glBindTextures(IILjava/nio/IntBuffer;)V"))),
            target("gl44", "org/lwjgl/opengl/GL44",
                    "872c941ec9da8538fcac3fafaad4f296cdfbe05df48f3078700a882692296b04",
                    Map.ofEntries(invalidate("glBindTextures(IILjava/nio/IntBuffer;)V"))),
            target("gl45", "org/lwjgl/opengl/GL45",
                    "80bef8374e7beb939f52ae0c1478b15dd123f762a1bff624cbc6786e46bac479",
                    Map.ofEntries(invalidate("glBindTextureUnit(II)V"))),
            target("ext-direct-state-access", "org/lwjgl/opengl/EXTDirectStateAccess",
                    "2c04a2e7a2c9c9dbacca9715a5b5841f03224300e8a6f06f54e8aa08ae245cfd",
                    Map.ofEntries(invalidate("glBindMultiTextureEXT(III)V"))),
            target("gl-context", "org/lwjgl/opengl/GLContext",
                    "9226ffc4217c71679c5f4e44a4030288dfa8ee687f11a5181cca01ea1713c1cc",
                    Map.ofEntries(
                            invalidate("useContext(Ljava/lang/Object;)V"),
                            invalidate("useContext(Ljava/lang/Object;Z)V"))));

    private GlTextureBindDedupPlan() {
    }

    static List<Target> targets() {
        return TARGETS;
    }

    static int expectedMethods(String internalName) {
        Target target = target(internalName);
        return target == null ? 0 : target.hooks().size();
    }

    static byte[] transform(ClassSignature signature, byte[] originalBytes) {
        if (!GlTextureBindDedupRuntime.planEnabled()) return null;
        Target target = target(signature.internalName());
        if (target == null
                || !target.sha256().equals(signature.sha256())
                || signature.majorVersion() != 49
                || !signature.hasMethod(target.requiredMethod(), target.requiredDescriptor())) {
            return null;
        }
        ClassNode owner = new ClassNode(Opcodes.ASM9);
        new ClassReader(originalBytes).accept(owner, ClassReader.EXPAND_FRAMES);
        Map<String, MethodNode> selected = new LinkedHashMap<>();
        for (MethodNode method : owner.methods) {
            String key = method.name + method.desc;
            if (!target.hooks().containsKey(key)) continue;
            if ((method.access & (Opcodes.ACC_PUBLIC | Opcodes.ACC_STATIC))
                    != (Opcodes.ACC_PUBLIC | Opcodes.ACC_STATIC)
                    || (method.access & (Opcodes.ACC_ABSTRACT | Opcodes.ACC_NATIVE)) != 0
                    || selected.put(key, method) != null
                    || callsRuntime(method)) {
                return null;
            }
        }
        if (selected.size() != target.hooks().size()) return null;
        for (Map.Entry<String, MethodNode> entry : selected.entrySet()) {
            Hook hook = target.hooks().get(entry.getKey());
            if (hook.kind() == Kind.BIND) {
                if (!instrumentBind(entry.getValue())) return null;
            } else {
                entry.getValue().instructions.insert(call(hook.runtimeMethod()));
            }
        }
        ClassWriter writer = new SafeClassWriter(ClassWriter.COMPUTE_FRAMES | ClassWriter.COMPUTE_MAXS);
        owner.accept(writer);
        GlTextureBindDedupRuntime.installed(owner.name, selected.size());
        return writer.toByteArray();
    }

    private static boolean instrumentBind(MethodNode method) {
        AbstractInsnNode onlyReturn = null;
        for (AbstractInsnNode instruction : method.instructions) {
            if (instruction.getOpcode() != Opcodes.RETURN) continue;
            if (onlyReturn != null) return false;
            onlyReturn = instruction;
        }
        if (onlyReturn == null) return false;
        LabelNode original = new LabelNode();
        InsnList guard = new InsnList();
        guard.add(new VarInsnNode(Opcodes.ILOAD, 0));
        guard.add(new VarInsnNode(Opcodes.ILOAD, 1));
        guard.add(new MethodInsnNode(
                Opcodes.INVOKESTATIC, RUNTIME, "shouldSkip", "(II)Z", false));
        guard.add(new JumpInsnNode(Opcodes.IFEQ, original));
        guard.add(new InsnNode(Opcodes.RETURN));
        guard.add(original);
        method.instructions.insert(guard);

        InsnList completed = new InsnList();
        completed.add(new VarInsnNode(Opcodes.ILOAD, 0));
        completed.add(new VarInsnNode(Opcodes.ILOAD, 1));
        completed.add(new MethodInsnNode(
                Opcodes.INVOKESTATIC, RUNTIME, "originalBindCompleted", "(II)V", false));
        method.instructions.insertBefore(onlyReturn, completed);
        return true;
    }

    private static InsnList call(String name) {
        InsnList instructions = new InsnList();
        instructions.add(new MethodInsnNode(Opcodes.INVOKESTATIC, RUNTIME, name, "()V", false));
        return instructions;
    }

    private static boolean callsRuntime(MethodNode method) {
        for (AbstractInsnNode instruction : method.instructions) {
            if (instruction instanceof MethodInsnNode call && RUNTIME.equals(call.owner)) return true;
        }
        return false;
    }

    private static Target target(String name) {
        for (Target target : TARGETS) if (target.internalName().equals(name)) return target;
        return null;
    }

    private static Target target(
            String id, String className, String sha256, Map<String, Hook> hooks) {
        String required = hooks.keySet().stream().sorted().findFirst().orElseThrow();
        int descriptor = required.indexOf('(');
        return new Target(id, className, sha256,
                required.substring(0, descriptor), required.substring(descriptor), Map.copyOf(hooks));
    }

    private static Map.Entry<String, Hook> bind(String signature) {
        return Map.entry(signature, new Hook(Kind.BIND, null));
    }

    private static Map.Entry<String, Hook> invalidate(String signature) {
        return hook(signature, "invalidate");
    }

    private static Map.Entry<String, Hook> hook(String signature, String runtimeMethod) {
        return Map.entry(signature, new Hook(Kind.HOOK, runtimeMethod));
    }

    record Target(
            String idSuffix,
            String internalName,
            String sha256,
            String requiredMethod,
            String requiredDescriptor,
            Map<String, Hook> hooks) {
    }

    private enum Kind { BIND, HOOK }

    private record Hook(Kind kind, String runtimeMethod) {
    }
}
