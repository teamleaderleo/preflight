package dev.starsector.preflight.agent;

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
import org.objectweb.asm.tree.TypeInsnNode;
import org.objectweb.asm.tree.VarInsnNode;

/** Bounds Stellar Networks' remote market refresh to one pass while game time is paused. */
final class StelnetMarketUpdaterPlan {
    static final String TARGET_CLASS = "stelnet/board/query/MarketUpdater";
    static final String ORIGINAL_SHA256 =
            "1f7aafb86365e1d28ae61051d0df896b1283d8ef2a9eb0e29e29567fe5bf14e7";
    static final String ADVANCE_METHOD = "advance";
    static final String ADVANCE_DESCRIPTOR = "(F)V";
    static final String PICK_METHOD = "pickRandomMarket";

    private static final String MARKET = "com/fs/starfarer/api/campaign/econ/MarketAPI";
    private static final String PICK_DESCRIPTOR = "()L" + MARKET + ";";
    private static final String ORIGINAL_PICK = "preflight$original$pickRandomMarket";
    private static final String RUNTIME =
            "dev/starsector/preflight/agent/StelnetMarketUpdaterRuntime";
    private static final String GLOBAL = "com/fs/starfarer/api/Global";
    private static final String SECTOR = "com/fs/starfarer/api/campaign/SectorAPI";
    private static final String ECONOMY = "com/fs/starfarer/api/campaign/econ/EconomyAPI";

    private StelnetMarketUpdaterPlan() {
    }

    static byte[] transform(ClassSignature signature, byte[] originalBytes) {
        if (!TARGET_CLASS.equals(signature.internalName())
                || !ORIGINAL_SHA256.equals(signature.sha256())
                || signature.majorVersion() != 61
                || !signature.hasMethod(ADVANCE_METHOD, ADVANCE_DESCRIPTOR)
                || !signature.hasMethod(PICK_METHOD, PICK_DESCRIPTOR)) {
            return null;
        }
        ClassNode owner = new ClassNode(Opcodes.ASM9);
        new ClassReader(originalBytes).accept(owner, ClassReader.EXPAND_FRAMES);
        MethodNode advance = unique(owner, ADVANCE_METHOD, ADVANCE_DESCRIPTOR);
        MethodNode pick = unique(owner, PICK_METHOD, PICK_DESCRIPTOR);
        MethodNode register = unique(owner, "register", "()V");
        MethodNode closed = unique(owner, "reportPlayerClosedMarket", "(L" + MARKET + ";)V");
        MethodNode transaction = unique(owner, "reportPlayerMarketTransaction",
                "(Lcom/fs/starfarer/api/campaign/PlayerMarketTransaction;)V");
        if (advance == null || pick == null || register == null || closed == null
                || transaction == null
                || hasMethod(owner, ORIGINAL_PICK, PICK_DESCRIPTOR)
                || !reviewAdvance(advance) || !reviewPick(pick)
                || !hasSingleReturn(register) || !hasSingleReturn(closed)
                || !hasSingleReturn(transaction)) {
            return null;
        }

        weaveAdvance(advance);
        int access = pick.access;
        pick.name = ORIGINAL_PICK;
        owner.methods.add(pickWrapper(access));
        invalidateBeforeReturn(register);
        invalidateBeforeReturn(closed);
        invalidateBeforeReturn(transaction);

        ClassWriter writer = new SafeClassWriter(ClassWriter.COMPUTE_FRAMES | ClassWriter.COMPUTE_MAXS);
        owner.accept(writer);
        StelnetMarketUpdaterRuntime.installed();
        return writer.toByteArray();
    }

    private static void weaveAdvance(MethodNode method) {
        MethodInsnNode paused = call(method, SECTOR, "isPaused", "()Z");
        JumpInsnNode pauseJump = nextCode(paused) instanceof JumpInsnNode jump ? jump : null;
        if (pauseJump == null || pauseJump.getOpcode() != Opcodes.IFNE) {
            throw new IllegalStateException("reviewed pause branch changed during weave");
        }
        method.instructions.insert(paused, list(
                new InsnNode(Opcodes.DUP),
                new MethodInsnNode(Opcodes.INVOKESTATIC, RUNTIME, "observePaused", "(Z)V", false)));

        LabelNode pending = new LabelNode();
        method.instructions.insert(pauseJump.label, list(
                new MethodInsnNode(Opcodes.INVOKESTATIC, RUNTIME, "hasPending", "()Z", false),
                new JumpInsnNode(Opcodes.IFNE, pending),
                new InsnNode(Opcodes.RETURN),
                pending));

        MethodInsnNode pick = call(method, TARGET_CLASS, PICK_METHOD, PICK_DESCRIPTOR);
        AbstractInsnNode store = nextCode(pick);
        if (!(store instanceof VarInsnNode variable) || store.getOpcode() != Opcodes.ASTORE) {
            throw new IllegalStateException("reviewed market local changed during weave");
        }
        LabelNode present = new LabelNode();
        method.instructions.insert(store, list(
                new VarInsnNode(Opcodes.ALOAD, variable.var),
                new JumpInsnNode(Opcodes.IFNONNULL, present),
                new InsnNode(Opcodes.RETURN),
                present));
    }

    private static MethodNode pickWrapper(int access) {
        MethodNode method = new MethodNode(
                Opcodes.ASM9, access, PICK_METHOD, PICK_DESCRIPTOR, null, null);
        LabelNode direct = new LabelNode();
        method.instructions.add(new MethodInsnNode(
                Opcodes.INVOKESTATIC, GLOBAL, "getSector", "()L" + SECTOR + ";", false));
        method.instructions.add(new MethodInsnNode(
                Opcodes.INVOKEINTERFACE, SECTOR, "getEconomy", "()L" + ECONOMY + ";", true));
        method.instructions.add(new MethodInsnNode(
                Opcodes.INVOKEINTERFACE, ECONOMY, "getMarketsCopy", "()Ljava/util/List;", true));
        method.instructions.add(new MethodInsnNode(
                Opcodes.INVOKESTATIC, RUNTIME, "nextMarket",
                "(Ljava/util/List;)Ljava/lang/Object;", false));
        method.instructions.add(new VarInsnNode(Opcodes.ASTORE, 1));
        method.instructions.add(new VarInsnNode(Opcodes.ALOAD, 1));
        method.instructions.add(new MethodInsnNode(
                Opcodes.INVOKESTATIC, RUNTIME, "isFallback", "(Ljava/lang/Object;)Z", false));
        method.instructions.add(new JumpInsnNode(Opcodes.IFEQ, direct));
        method.instructions.add(new VarInsnNode(Opcodes.ALOAD, 0));
        method.instructions.add(new MethodInsnNode(
                Opcodes.INVOKEVIRTUAL, TARGET_CLASS, ORIGINAL_PICK, PICK_DESCRIPTOR, false));
        method.instructions.add(new InsnNode(Opcodes.ARETURN));
        method.instructions.add(direct);
        method.instructions.add(new VarInsnNode(Opcodes.ALOAD, 1));
        method.instructions.add(new TypeInsnNode(Opcodes.CHECKCAST, MARKET));
        method.instructions.add(new InsnNode(Opcodes.ARETURN));
        return method;
    }

    private static void invalidateBeforeReturn(MethodNode method) {
        for (AbstractInsnNode instruction : method.instructions.toArray()) {
            if (instruction.getOpcode() == Opcodes.RETURN) {
                method.instructions.insertBefore(instruction, new MethodInsnNode(
                        Opcodes.INVOKESTATIC, RUNTIME, "invalidate", "()V", false));
            }
        }
    }

    private static boolean reviewAdvance(MethodNode method) {
        return calls(method, SECTOR, "isPaused", "()Z") == 1
                && calls(method, TARGET_CLASS, PICK_METHOD, PICK_DESCRIPTOR) == 1
                && calls(method, TARGET_CLASS, "updateMarket", "(L" + MARKET + ";)V") == 1
                && returns(method, Opcodes.RETURN) == 3;
    }

    private static boolean reviewPick(MethodNode method) {
        return calls(method, ECONOMY, "getMarketsCopy", "()Ljava/util/List;") == 1
                && calls(method, "com/fs/starfarer/api/util/WeightedRandomPicker", "addAll",
                        "(Ljava/util/Collection;)V") == 1
                && calls(method, "com/fs/starfarer/api/util/WeightedRandomPicker", "pick",
                        "()Ljava/lang/Object;") == 1
                && returns(method, Opcodes.ARETURN) == 1;
    }

    private static boolean hasSingleReturn(MethodNode method) {
        return returns(method, Opcodes.RETURN) == 1;
    }

    private static int returns(MethodNode method, int opcode) {
        int count = 0;
        for (AbstractInsnNode instruction : method.instructions) {
            if (instruction.getOpcode() == opcode) count++;
        }
        return count;
    }

    private static int calls(MethodNode method, String owner, String name, String descriptor) {
        int count = 0;
        for (AbstractInsnNode instruction : method.instructions) {
            if (instruction instanceof MethodInsnNode call
                    && owner.equals(call.owner) && name.equals(call.name)
                    && descriptor.equals(call.desc)) {
                count++;
            }
        }
        return count;
    }

    private static MethodInsnNode call(
            MethodNode method, String owner, String name, String descriptor) {
        MethodInsnNode result = null;
        for (AbstractInsnNode instruction : method.instructions) {
            if (instruction instanceof MethodInsnNode candidate
                    && owner.equals(candidate.owner) && name.equals(candidate.name)
                    && descriptor.equals(candidate.desc)) {
                if (result != null) return null;
                result = candidate;
            }
        }
        return result;
    }

    private static AbstractInsnNode nextCode(AbstractInsnNode instruction) {
        AbstractInsnNode current = instruction.getNext();
        while (current != null && current.getOpcode() < 0) current = current.getNext();
        return current;
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

    private static boolean hasMethod(ClassNode owner, String name, String descriptor) {
        return owner.methods.stream()
                .anyMatch(method -> name.equals(method.name) && descriptor.equals(method.desc));
    }

    private static InsnList list(AbstractInsnNode... instructions) {
        InsnList result = new InsnList();
        for (AbstractInsnNode instruction : instructions) result.add(instruction);
        return result;
    }
}
