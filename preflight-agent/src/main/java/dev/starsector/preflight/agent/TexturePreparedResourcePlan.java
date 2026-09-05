package dev.starsector.preflight.agent;

import org.objectweb.asm.Opcodes;
import org.objectweb.asm.Type;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.tree.AbstractInsnNode;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.FieldInsnNode;
import org.objectweb.asm.tree.InsnList;
import org.objectweb.asm.tree.InsnNode;
import org.objectweb.asm.tree.JumpInsnNode;
import org.objectweb.asm.tree.LabelNode;
import org.objectweb.asm.tree.LdcInsnNode;
import org.objectweb.asm.tree.MethodInsnNode;
import org.objectweb.asm.tree.MethodNode;
import org.objectweb.asm.tree.TableSwitchInsnNode;
import org.objectweb.asm.tree.TryCatchBlockNode;
import org.objectweb.asm.tree.VarInsnNode;

/** Joins only the reviewed TEXTURE branch to the unchanged Windows producer and repository call. */
final class TexturePreparedResourcePlan {
    static final String RUNTIME = "dev/starsector/preflight/agent/TexturePreparedResourceRuntime";
    static final String WORKER = "com/fs/graphics/L$1";
    static final String WORKER_SHA256 = "ac01b004ecbb323ee81cc2cd969b30fe9803db6b8c2622de4b87800e11ad465f";
    private static final String WRAPPER = "preflight$commitPreparedResource";
    private static final String REGISTER = "(Ljava/lang/String;Ljava/lang/String;)V";

    private TexturePreparedResourcePlan() { }

    static byte[] transform(ClassSignature signature, byte[] bytes) {
        ClassNode owner = new ClassNode(Opcodes.ASM9);
        new ClassReader(bytes).accept(owner, ClassReader.EXPAND_FRAMES);
        return apply(signature, owner) ? write(owner) : null;
    }

    static byte[] write(ClassNode owner) {
        ClassWriter writer = new SafeClassWriter(ClassWriter.COMPUTE_FRAMES | ClassWriter.COMPUTE_MAXS, true);
        owner.accept(writer);
        return writer.toByteArray();
    }

    static byte[] transformWorker(ClassSignature signature, byte[] bytes) {
        if (!TexturePreparedResourceRuntime.requested()
                || !(Boolean.getBoolean(TexturePreparedResourceRuntime.CLAIM_PROPERTY)
                    || Boolean.getBoolean(TexturePreparedResourceRuntime.BARRIER_PROPERTY))
                || !WORKER.equals(signature.internalName()) || !WORKER_SHA256.equals(signature.sha256()))
            return null;
        ClassNode owner = new ClassNode(Opcodes.ASM9);
        new ClassReader(bytes).accept(owner, ClassReader.EXPAND_FRAMES);
        MethodNode run = method(owner, "run", "()V");
        if (run == null) return null;
        MethodInsnNode decode = null;
        for (AbstractInsnNode instruction : run.instructions) {
            if (instruction instanceof MethodInsnNode call) {
                if (RUNTIME.equals(call.owner)) return null;
                if ("com/fs/graphics/L".equals(call.owner) && "o00000".equals(call.name)
                        && TexturePreparedPrefetchPlan.DECODE_DESCRIPTOR.equals(call.desc)) {
                    if (decode != null) return null;
                    decode = call;
                }
            }
        }
        if (decode == null) return null;
        // The pinned byte loop ends at IFEQ back to its body (BCI 109). Fallthrough is
        // the sole entry into the image phase, including when the image queue is empty.
        AbstractInsnNode byteBoundary = null;
        for (AbstractInsnNode instruction : run.instructions) {
            if (instruction instanceof FieldInsnNode field
                    && field.getOpcode() == Opcodes.GETSTATIC
                    && "com/fs/graphics/L".equals(field.owner) && "õ00000".equals(field.name)
                    && "Ljava/util/List;".equals(field.desc)) {
                AbstractInsnNode next = nextOpcode(field);
                if (next instanceof MethodInsnNode call && "java/util/List".equals(call.owner)
                        && "isEmpty".equals(call.name) && "()Z".equals(call.desc)
                        && nextOpcode(call) instanceof JumpInsnNode jump
                        && jump.getOpcode() == Opcodes.IFEQ) {
                    if (byteBoundary != null) return null;
                    byteBoundary = jump;
                }
            }
        }
        if (byteBoundary == null) return null;
        AbstractInsnNode store = nextOpcode(decode);
        AbstractInsnNode map = nextOpcode(store);
        AbstractInsnNode path = nextOpcode(map);
        AbstractInsnNode image = nextOpcode(path);
        AbstractInsnNode put = nextOpcode(image);
        AbstractInsnNode pop = nextOpcode(put);
        if (!(store instanceof VarInsnNode s) || s.getOpcode() != Opcodes.ASTORE || s.var != 2
                || !(map instanceof FieldInsnNode f) || f.getOpcode() != Opcodes.GETSTATIC
                || !"com/fs/graphics/L".equals(f.owner) || !"void".equals(f.name)
                || !"Ljava/util/Map;".equals(f.desc)
                || !(path instanceof VarInsnNode p) || p.getOpcode() != Opcodes.ALOAD || p.var != 1
                || !(image instanceof VarInsnNode i) || i.getOpcode() != Opcodes.ALOAD || i.var != 2
                || !(put instanceof MethodInsnNode m) || m.getOpcode() != Opcodes.INVOKEINTERFACE
                || !"java/util/Map".equals(m.owner) || !"put".equals(m.name)
                || !"(Ljava/lang/Object;Ljava/lang/Object;)Ljava/lang/Object;".equals(m.desc)
                || pop == null || pop.getOpcode() != Opcodes.POP) return null;
        InsnList signal = new InsnList();
        signal.add(new VarInsnNode(Opcodes.ALOAD, 1));
        signal.add(new VarInsnNode(Opcodes.ALOAD, 2));
        signal.add(new MethodInsnNode(Opcodes.INVOKESTATIC, RUNTIME, "resultReady",
                "(Ljava/lang/String;Ljava/awt/image/BufferedImage;)V", false));
        run.instructions.insert(pop, signal);
        if (Boolean.getBoolean(TexturePreparedResourceRuntime.BARRIER_PROPERTY)) {
            run.instructions.insert(byteBoundary, new MethodInsnNode(Opcodes.INVOKESTATIC,
                    RUNTIME, "bytePhaseComplete", "()V", false));
        }
        return write(owner);
    }

    private static AbstractInsnNode nextOpcode(AbstractInsnNode node) {
        if (node == null) return null;
        do { node = node.getNext(); } while (node != null && node.getOpcode() < 0);
        return node;
    }

    static boolean apply(ClassSignature signature, ClassNode owner) {
        if (!TexturePreparedResourceRuntime.requested()
                || !ResourcePriorityPlan.TARGET_CLASS.equals(signature.internalName())
                || !FrameTimeStartupCompletionPlan.WINDOWS_ORIGINAL_SHA256.equals(signature.sha256())) return false;
        MethodNode init = method(owner, "init", "(Ljava/util/Map;)V");
        if (init == null || method(owner, WRAPPER, REGISTER) != null) return false;
        MethodInsnNode start = null;
        MethodInsnNode registration = null;
        for (AbstractInsnNode instruction : init.instructions) {
            if (instruction instanceof MethodInsnNode call && call.owner.equals("com/fs/graphics/L")
                    && call.name.equals("o00000") && call.desc.equals("()V")) {
                if (start != null) return false;
                start = call;
            }
            if (!(instruction instanceof TableSwitchInsnNode table) || table.min != 1 || table.max != 5)
                continue;
            for (AbstractInsnNode cursor = table.labels.get(0); cursor != null; cursor = cursor.getNext()) {
                if (cursor instanceof JumpInsnNode) break;
                if (cursor instanceof MethodInsnNode call && call.owner.equals("com/fs/graphics/oOoO")
                        && call.name.equals("super") && call.desc.equals(REGISTER)) {
                    if (registration != null) return false;
                    registration = call;
                }
            }
        }
        if (start == null || registration == null) return false;
        InsnList begin = new InsnList();
        begin.add(new VarInsnNode(Opcodes.ALOAD, 0));
        begin.add(new FieldInsnNode(Opcodes.GETFIELD, owner.name, "resources", "Ljava/util/List;"));
        begin.add(new LdcInsnNode(Type.getObjectType(owner.name)));
        begin.add(new MethodInsnNode(Opcodes.INVOKESTATIC, RUNTIME, "begin",
                "(Ljava/util/List;Ljava/lang/Class;)V", false));
        init.instructions.insertBefore(start, begin);
        registration.owner = owner.name;
        registration.name = WRAPPER;
        owner.methods.add(wrapper());
        // Resource init can fail before the ordinary worker stop; never retain prototype state then.
        LabelNode from = new LabelNode();
        LabelNode to = new LabelNode();
        LabelNode failure = new LabelNode();
        init.instructions.insert(from);
        for (AbstractInsnNode instruction : init.instructions.toArray()) {
            if (instruction.getOpcode() == Opcodes.RETURN)
                init.instructions.insertBefore(instruction, end());
        }
        init.instructions.add(to);
        init.instructions.add(failure);
        init.instructions.add(end());
        init.instructions.add(new InsnNode(Opcodes.ATHROW));
        init.tryCatchBlocks.add(new TryCatchBlockNode(from, to, failure, null));
        return true;
    }

    static boolean applyPrefetch(ClassSignature signature, ClassNode owner) {
        if (!TexturePreparedResourceRuntime.requested()
                || !"9e339c5a0edadebdd81b088e0882f5a00b4696b9f5e862a9beec3ff03c439f3e".equals(signature.sha256()))
            return false;
        for (MethodNode method : owner.methods) {
            for (AbstractInsnNode instruction : method.instructions) {
                if (instruction instanceof MethodInsnNode call && RUNTIME.equals(call.owner)) return false;
            }
        }
        MethodNode decode = method(owner, "o00000", TexturePreparedPrefetchPlan.DECODE_DESCRIPTOR);
        MethodNode getter = method(owner, "Õ00000", TexturePreparedPrefetchPlan.DECODE_DESCRIPTOR);
        MethodNode stop = method(owner, "Ò00000", "()V");
        MethodNode start = method(owner, "o00000", "()V");
        if (decode == null || getter == null || stop == null || start == null) return false;
        MethodInsnNode threadStart = null;
        for (AbstractInsnNode instruction : start.instructions) {
            if (instruction instanceof MethodInsnNode call && call.owner.equals("java/lang/Thread")
                    && call.name.equals("start") && call.desc.equals("()V")) {
                if (threadStart != null) return false;
                threadStart = call;
            }
        }
        if (threadStart == null) return false;
        InsnList bind = new InsnList();
        bind.add(new InsnNode(Opcodes.DUP));
        bind.add(new MethodInsnNode(Opcodes.INVOKESTATIC, RUNTIME, "worker", "(Ljava/lang/Thread;)V", false));
        start.instructions.insertBefore(threadStart, bind);
        start.maxStack = Math.max(start.maxStack, 2);
        for (AbstractInsnNode instruction : decode.instructions.toArray()) {
            if (instruction.getOpcode() != Opcodes.ARETURN) continue;
            InsnList publish = new InsnList();
            publish.add(new VarInsnNode(Opcodes.ALOAD, 0));
            publish.add(new InsnNode(Opcodes.SWAP));
            publish.add(new MethodInsnNode(Opcodes.INVOKESTATIC, RUNTIME, "publish",
                    "(Ljava/lang/String;Ljava/awt/image/BufferedImage;)Ljava/awt/image/BufferedImage;", false));
            decode.instructions.insertBefore(instruction, publish);
        }
        for (AbstractInsnNode instruction : getter.instructions.toArray()) {
            if (instruction.getOpcode() != Opcodes.ARETURN) continue;
            InsnList retire = new InsnList();
            retire.add(new InsnNode(Opcodes.DUP));
            retire.add(new VarInsnNode(Opcodes.ALOAD, 0));
            retire.add(new InsnNode(Opcodes.SWAP));
            retire.add(new MethodInsnNode(Opcodes.INVOKESTATIC, RUNTIME, "originalConsumed",
                    "(Ljava/lang/String;Ljava/awt/image/BufferedImage;)V", false));
            getter.instructions.insertBefore(instruction, retire);
        }
        stop.instructions.insert(new MethodInsnNode(Opcodes.INVOKESTATIC, RUNTIME,
                "finishWorker", "()V", false));
        decode.maxStack = Math.max(decode.maxStack, 3);
        getter.maxStack = Math.max(getter.maxStack, 3);
        return true;
    }

    private static MethodNode wrapper() {
        MethodNode method = new MethodNode(Opcodes.ASM9, Opcodes.ACC_PRIVATE | Opcodes.ACC_STATIC,
                WRAPPER, REGISTER, null, new String[] {"java/lang/Exception"});
        method.instructions.add(new VarInsnNode(Opcodes.ALOAD, 0));
        method.instructions.add(new VarInsnNode(Opcodes.ALOAD, 1));
        method.instructions.add(new MethodInsnNode(Opcodes.INVOKESTATIC, RUNTIME, "enter", REGISTER, false));
        LabelNode from = new LabelNode();
        LabelNode to = new LabelNode();
        LabelNode failure = new LabelNode();
        method.instructions.add(from);
        method.instructions.add(new VarInsnNode(Opcodes.ALOAD, 0));
        method.instructions.add(new VarInsnNode(Opcodes.ALOAD, 1));
        method.instructions.add(new MethodInsnNode(Opcodes.INVOKESTATIC, "com/fs/graphics/oOoO", "super", REGISTER, false));
        method.instructions.add(to);
        method.instructions.add(new InsnNode(Opcodes.ICONST_1));
        method.instructions.add(new MethodInsnNode(Opcodes.INVOKESTATIC, RUNTIME, "exit", "(Z)V", false));
        method.instructions.add(new InsnNode(Opcodes.RETURN));
        method.instructions.add(failure);
        method.instructions.add(new InsnNode(Opcodes.ICONST_0));
        method.instructions.add(new MethodInsnNode(Opcodes.INVOKESTATIC, RUNTIME, "exit", "(Z)V", false));
        method.instructions.add(new InsnNode(Opcodes.ATHROW));
        method.tryCatchBlocks.add(new TryCatchBlockNode(from, to, failure, null));
        return method;
    }

    private static MethodInsnNode end() {
        return new MethodInsnNode(Opcodes.INVOKESTATIC, RUNTIME, "end", "()V", false);
    }

    private static MethodNode method(ClassNode owner, String name, String descriptor) {
        return owner.methods.stream().filter(m -> m.name.equals(name) && m.desc.equals(descriptor))
                .findFirst().orElse(null);
    }
}
