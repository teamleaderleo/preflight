package dev.starsector.preflight.agent;

import java.util.ArrayList;
import java.util.List;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.tree.AbstractInsnNode;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.InsnList;
import org.objectweb.asm.tree.InsnNode;
import org.objectweb.asm.tree.JumpInsnNode;
import org.objectweb.asm.tree.LdcInsnNode;
import org.objectweb.asm.tree.MethodInsnNode;
import org.objectweb.asm.tree.MethodNode;
import org.objectweb.asm.tree.TypeInsnNode;
import org.objectweb.asm.tree.VarInsnNode;

/** Removes fixed-table and per-character StringBuilder allocation from vanilla font wrapping. */
final class FontWrapAllocationPlan {
    static final String TARGET_CLASS = "com/fs/graphics/A/C";
    static final String ORIGINAL_SHA256 =
            "01638a6e83c4a66eec57db511a903e2a361bb3f3e9b3679224b50b6d500903ea";
    static final String METHOD = "return";
    static final String DESCRIPTOR = "(FF)V";

    private static final String STRING = "java/lang/String";
    private static final String STRING_BUILDER = "java/lang/StringBuilder";
    private static final String[] END_PUNCTUATION = {
        "。", "，", "！", "？", "；", "：", "）", "］", "}", ",.?!)]}"
    };
    private static final String[] OPEN_PUNCTUATION = {"[", "{", "(", "[{("};
    private static final String[] CLOSE_PUNCTUATION = {"）", "］", "}", ")]}"};

    private FontWrapAllocationPlan() {
    }

    static byte[] transform(ClassSignature signature, byte[] originalBytes) {
        if (signature.majorVersion() != 61
                || !TARGET_CLASS.equals(signature.internalName())
                || !ORIGINAL_SHA256.equals(signature.sha256())
                || !signature.hasMethod(METHOD, DESCRIPTOR)) {
            return null;
        }
        ClassNode owner = read(originalBytes);
        MethodNode method = unique(owner, METHOD, DESCRIPTOR);
        if (method == null) return null;

        Chain end = findUniqueChain(method, 8, END_PUNCTUATION);
        Chain open = findUniqueChain(method, 9, OPEN_PUNCTUATION);
        Chain close = findUniqueChain(method, 10, CLOSE_PUNCTUATION);
        List<ContainsSite> contains = containsSites(method);
        if (end == null || open == null || close == null || contains == null) return null;

        rewriteChain(method, end, String.join("", END_PUNCTUATION));
        rewriteChain(method, open, String.join("", OPEN_PUNCTUATION));
        rewriteChain(method, close, String.join("", CLOSE_PUNCTUATION));
        for (ContainsSite site : contains) rewriteContains(method, site);

        FontWrapAllocationRuntime.installed();
        return write(owner);
    }

    private static Chain findUniqueChain(MethodNode method, int local, String[] parts) {
        Chain result = null;
        for (AbstractInsnNode instruction : method.instructions) {
            if (!(instruction instanceof LdcInsnNode literal) || !"".equals(literal.cst)) continue;
            AbstractInsnNode store = nextCode(instruction);
            if (!(store instanceof VarInsnNode variable)
                    || variable.getOpcode() != Opcodes.ASTORE || variable.var != local) {
                continue;
            }
            AbstractInsnNode current = store;
            boolean matches = true;
            for (String part : parts) {
                AbstractInsnNode allocation = nextCode(current);
                AbstractInsnNode duplicate = nextCode(allocation);
                AbstractInsnNode load = nextCode(duplicate);
                AbstractInsnNode valueOf = nextCode(load);
                AbstractInsnNode constructor = nextCode(valueOf);
                AbstractInsnNode appendedLiteral = nextCode(constructor);
                AbstractInsnNode append = nextCode(appendedLiteral);
                AbstractInsnNode toString = nextCode(append);
                AbstractInsnNode nextStore = nextCode(toString);
                if (!(allocation instanceof TypeInsnNode type)
                        || allocation.getOpcode() != Opcodes.NEW
                        || !STRING_BUILDER.equals(type.desc)
                        || duplicate.getOpcode() != Opcodes.DUP
                        || !(load instanceof VarInsnNode localLoad)
                        || load.getOpcode() != Opcodes.ALOAD || localLoad.var != local
                        || !call(valueOf, Opcodes.INVOKESTATIC, STRING, "valueOf",
                                "(Ljava/lang/Object;)Ljava/lang/String;")
                        || !call(constructor, Opcodes.INVOKESPECIAL, STRING_BUILDER, "<init>",
                                "(Ljava/lang/String;)V")
                        || !(appendedLiteral instanceof LdcInsnNode partLiteral)
                        || !part.equals(partLiteral.cst)
                        || !call(append, Opcodes.INVOKEVIRTUAL, STRING_BUILDER, "append",
                                "(Ljava/lang/String;)Ljava/lang/StringBuilder;")
                        || !call(toString, Opcodes.INVOKEVIRTUAL, STRING_BUILDER, "toString",
                                "()Ljava/lang/String;")
                        || !(nextStore instanceof VarInsnNode localStore)
                        || nextStore.getOpcode() != Opcodes.ASTORE || localStore.var != local) {
                    matches = false;
                    break;
                }
                current = nextStore;
            }
            if (matches) {
                if (result != null) return null;
                result = new Chain(instruction, current);
            }
        }
        return result;
    }

    private static List<ContainsSite> containsSites(MethodNode method) {
        List<ContainsSite> result = new ArrayList<>();
        for (AbstractInsnNode instruction : method.instructions) {
            if (!call(instruction, Opcodes.INVOKEVIRTUAL, STRING, "contains",
                    "(Ljava/lang/CharSequence;)Z")) {
                continue;
            }
            MethodInsnNode contains = (MethodInsnNode) instruction;
            AbstractInsnNode toString = previousCode(contains);
            AbstractInsnNode append = previousCode(toString);
            AbstractInsnNode toChar = previousCode(append);
            AbstractInsnNode character = previousCode(toChar);
            AbstractInsnNode constructor = previousCode(character);
            AbstractInsnNode duplicate = previousCode(constructor);
            AbstractInsnNode allocation = previousCode(duplicate);
            AbstractInsnNode branch = nextCode(contains);
            if (!(allocation instanceof TypeInsnNode type)
                    || allocation.getOpcode() != Opcodes.NEW
                    || !STRING_BUILDER.equals(type.desc)
                    || duplicate.getOpcode() != Opcodes.DUP
                    || !call(constructor, Opcodes.INVOKESPECIAL, STRING_BUILDER, "<init>", "()V")
                    || !(character instanceof VarInsnNode load)
                    || character.getOpcode() != Opcodes.ILOAD
                    || toChar.getOpcode() != Opcodes.I2C
                    || !call(append, Opcodes.INVOKEVIRTUAL, STRING_BUILDER, "append",
                            "(C)Ljava/lang/StringBuilder;")
                    || !call(toString, Opcodes.INVOKEVIRTUAL, STRING_BUILDER, "toString",
                            "()Ljava/lang/String;")
                    || !(branch instanceof JumpInsnNode jump)
                    || jump.getOpcode() != Opcodes.IFEQ) {
                return null;
            }
            result.add(new ContainsSite(
                    allocation, contains, load.var, jump));
        }
        return result.size() == 3 ? result : null;
    }

    private static void rewriteChain(MethodNode method, Chain chain, String value) {
        InsnList replacement = new InsnList();
        replacement.add(new LdcInsnNode(value));
        int local = ((VarInsnNode) nextCode(chain.start)).var;
        replacement.add(new VarInsnNode(Opcodes.ASTORE, local));
        method.instructions.insertBefore(chain.start, replacement);
        removeRange(method, chain.start, chain.end);
    }

    private static void rewriteContains(MethodNode method, ContainsSite site) {
        InsnList replacement = new InsnList();
        replacement.add(new VarInsnNode(Opcodes.ILOAD, site.characterLocal));
        replacement.add(new MethodInsnNode(
                Opcodes.INVOKEVIRTUAL, STRING, "indexOf", "(I)I", false));
        method.instructions.insertBefore(site.start, replacement);
        removeRange(method, site.start, site.end);
        site.branch.setOpcode(Opcodes.IFLT);
    }

    private static void removeRange(
            MethodNode method, AbstractInsnNode start, AbstractInsnNode end) {
        AbstractInsnNode current = start;
        while (true) {
            AbstractInsnNode next = current.getNext();
            method.instructions.remove(current);
            if (current == end) return;
            current = next;
        }
    }

    private static boolean call(
            AbstractInsnNode instruction, int opcode, String owner, String name, String descriptor) {
        return instruction instanceof MethodInsnNode call
                && call.getOpcode() == opcode
                && owner.equals(call.owner)
                && name.equals(call.name)
                && descriptor.equals(call.desc);
    }

    private static ClassNode read(byte[] bytes) {
        ClassNode owner = new ClassNode(Opcodes.ASM9);
        new ClassReader(bytes).accept(owner, ClassReader.EXPAND_FRAMES);
        return owner;
    }

    private static byte[] write(ClassNode owner) {
        ClassWriter writer =
                new SafeClassWriter(ClassWriter.COMPUTE_FRAMES | ClassWriter.COMPUTE_MAXS);
        owner.accept(writer);
        return writer.toByteArray();
    }

    private static MethodNode unique(ClassNode owner, String name, String descriptor) {
        MethodNode result = null;
        for (MethodNode method : owner.methods) {
            if (name.equals(method.name) && descriptor.equals(method.desc)) {
                if (result != null) return null;
                result = method;
            }
        }
        return result;
    }

    private static AbstractInsnNode previousCode(AbstractInsnNode instruction) {
        AbstractInsnNode current = instruction == null ? null : instruction.getPrevious();
        while (current != null && current.getOpcode() < 0) current = current.getPrevious();
        return current;
    }

    private static AbstractInsnNode nextCode(AbstractInsnNode instruction) {
        AbstractInsnNode current = instruction == null ? null : instruction.getNext();
        while (current != null && current.getOpcode() < 0) current = current.getNext();
        return current;
    }

    private record Chain(AbstractInsnNode start, AbstractInsnNode end) {
    }

    private record ContainsSite(
            AbstractInsnNode start, AbstractInsnNode end, int characterLocal, JumpInsnNode branch) {
    }
}
