package dev.starsector.preflight.agent;

import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.FieldNode;
import org.objectweb.asm.tree.InsnList;
import org.objectweb.asm.tree.MethodInsnNode;
import org.objectweb.asm.tree.TypeInsnNode;
import org.objectweb.asm.tree.VarInsnNode;

/** Leaves Janino discovery, output assembly and class definition in their original methods. */
final class JaninoUnitMemoPlan {
    static final String FIELD = "preflightUnitMemo";
    static final String UNIT = "org/codehaus/janino/UnitCompiler";
    static final String ARRAY = "[Lorg/codehaus/janino/util/ClassFile;";

    private JaninoUnitMemoPlan() { }

    static byte[] transform(ClassSignature signature, byte[] bytes) {
        if (!AdapterTargetRegistry.janinoBytecodeCacheTarget().sha256().equals(signature.sha256())) {
            return null;
        }
        ClassNode owner = new ClassNode();
        new ClassReader(bytes).accept(owner, 0);
        if (owner.fields.stream().anyMatch(field -> FIELD.equals(field.name))) return null;
        int replaced = 0;
        for (var method : owner.methods) {
            if (!method.name.equals(JaninoBytecodeCachePlan.GENERATE_METHOD)
                    || !method.desc.equals(JaninoBytecodeCachePlan.GENERATE_DESCRIPTOR)) continue;
            for (var instruction : method.instructions.toArray()) {
                if (!(instruction instanceof MethodInsnNode call)
                        || !call.owner.equals(UNIT) || !call.name.equals("compileUnit")
                        || !call.desc.equals("(ZZZ)" + ARRAY)
                        || call.getOpcode() != Opcodes.INVOKEVIRTUAL) continue;
                InsnList replacement = new InsnList();
                replacement.add(new VarInsnNode(Opcodes.ALOAD, 0));
                replacement.add(new MethodInsnNode(Opcodes.INVOKESTATIC,
                        "dev/starsector/preflight/agent/JaninoUnitMemoRuntime", "compile",
                        "(Ljava/lang/Object;ZZZLjava/lang/Object;)Ljava/lang/Object;", false));
                replacement.add(new TypeInsnNode(Opcodes.CHECKCAST, ARRAY));
                method.instructions.insertBefore(call, replacement);
                method.instructions.remove(call);
                replaced++;
            }
        }
        if (replaced != 1) return null;
        owner.fields.add(new FieldNode(Opcodes.ACC_PRIVATE | Opcodes.ACC_SYNTHETIC,
                FIELD, "Ljava/lang/Object;", null, null));
        ClassWriter writer = new SafeClassWriter(ClassWriter.COMPUTE_MAXS);
        owner.accept(writer);
        return writer.toByteArray();
    }
}
